package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.platform.broker.BodyDecoder.Compression;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrowsePage;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetRegistry.ClockOffset;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.system.artemis.QueueLocator;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The tail is a sample, and the two things that make it a usable one are that it
 * does not re-deliver what it has already shown and that it stops when its client
 * does. Both are asserted here; the honesty of the gap figure is the third.
 */
class SqlTailPollerTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    private final SqlQueryParser parser = new SqlQueryParser();
    private final SelectorRenderer renderer = new SelectorRenderer();
    private final PredicateSplitter splitter = new PredicateSplitter(renderer);
    private final MessagePredicate residuals = new MessagePredicate();

    private QueueSnapshots snapshots;
    private ClusterDirectory nodes;
    private ClockOffsetService clocks;
    private MessageIndexCoverage coverage;
    private IndexQueryExecutor indexExecutor;
    private BrokerNodeEntity node;
    private final AtomicLong messagesAdded = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        snapshots = mock(QueueSnapshots.class);
        nodes = mock(ClusterDirectory.class);
        clocks = mock(ClockOffsetService.class);
        coverage = mock(MessageIndexCoverage.class);
        indexExecutor = mock(IndexQueryExecutor.class);
        when(clocks.offsetFor(any())).thenReturn(Optional.of(new ClockOffset(0, 5, 10, 3, NOW)));
        when(coverage.isCaptured(any(), any())).thenReturn(false);
        when(coverage.check(any(), any(), any())).thenReturn(List.of());
        node = node();
        when(nodes.nodes(CLUSTER)).thenReturn(List.of(node));
        // Re-read on every call, so a test can move the counter between polls.
        when(snapshots.forCluster(CLUSTER))
                .thenAnswer(invocation -> List.of(snapshot("ORDER.IN", 5, messagesAdded.get())));
    }

    // ---- tests ----------------------------------------------------------

    @Test
    void thePollPushesTheHighWaterMarkIntoTheSelectorRatherThanRereadingTheQueue() {
        RecordingTransport transport = new RecordingTransport(3);
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(CLUSTER, plan("SELECT * FROM \"ORDER.IN\""), transport, listener);

        poller.tick();
        await(() -> !listener.statuses.isEmpty());

        assertThat(transport.filters).isNotEmpty();
        assertThat(transport.filters.getFirst()).contains("AMQTimestamp >= " + NOW.toEpochMilli());
    }

    @Test
    void aMessageAlreadyShownIsNotShownAgainOnTheNextPoll() {
        RecordingTransport transport = new RecordingTransport(3);
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(CLUSTER, plan("SELECT * FROM \"ORDER.IN\""), transport, listener);

        poller.tick();
        await(() -> listener.statuses.size() == 1);
        assertThat(listener.rows).hasSize(3);

        // The broker still returns them: the mark is a `>=`, so the messages stamped
        // in the same millisecond come back and are filtered by id here.
        poller.tick();
        await(() -> listener.statuses.size() == 2);
        assertThat(listener.rows).hasSize(3);
        assertThat(listener.statuses.getLast().shown()).isEqualTo(3);
    }

    /**
     * The plan resolved onto the index because capture covers the queue. Re-reading the queue
     * would miss exactly the messages capture exists to keep: the ones already consumed.
     */
    @Test
    void aTailOverACapturedQueueReadsTheIndexAndNotTheBroker() {
        when(coverage.isCaptured(any(), any())).thenReturn(true);
        when(indexExecutor.execute(any(), any(), any(), any()))
                .thenReturn(new QueryResult(List.of(), List.of(), List.of(), List.of()));
        RecordingTransport transport = new RecordingTransport(3);
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\"");
        assertThat(plan.resolvedSource()).isEqualTo(QueryAst.Source.INDEX);
        poller.start(CLUSTER, plan, transport, listener);

        poller.tick();
        await(() -> !listener.statuses.isEmpty());

        assertThat(transport.filters).as("no broker read").isEmpty();
        Mockito.verify(indexExecutor).execute(eq(CLUSTER), eq(plan), any(), any(Instant.class));
    }

    /**
     * The window the next poll asks for follows what the query returned, not what survived
     * dedup. An index holding a full page of rows inside the overlap window — already delivered,
     * or older than where the tail started — otherwise fills every page with them forever, and a
     * row written after them is never reached: the tail goes quiet at the rate the page fills,
     * which is the failure capture exists to prevent.
     */
    @Test
    void anIndexTailReachesWhatWasWrittenBehindAFullPageOfRowsItWillNotDeliver() {
        when(coverage.isCaptured(any(), any())).thenReturn(true);
        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\"");
        int limit = plan.effectiveLimit();
        Instant base = Instant.now().minusSeconds(5);

        List<Row> index = new ArrayList<>();
        // A whole page of rows this tail will not deliver: they predate its mark.
        for (int i = 0; i < limit; i++) {
            index.add(indexRow(i, NOW.minusSeconds(60), base.plusMillis(i)));
        }
        Row awaited = indexRow(9_999, NOW.plusSeconds(1), base.plusMillis(limit));
        index.add(awaited);
        when(indexExecutor.execute(any(), any(), any(), any(Instant.class)))
                .thenAnswer(call -> indexPage(index, call.getArgument(2), call.getArgument(3), limit));

        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(CLUSTER, plan, new RecordingTransport(0), listener);

        poller.tick();
        await(() -> listener.statuses.size() == 1);
        poller.tick();
        await(() -> listener.statuses.size() == 2);

        assertThat(listener.rows).extracting(Row::messageId).containsExactly(awaited.messageId());
    }

    /** The index as a tail sees it: at or after {@code since}, oldest first, cut at the limit. */
    private static QueryResult indexPage(List<Row> index, BrokerQueryExecutor.Sink sink, Instant since, int limit) {
        List<Row> matched = index.stream()
                .filter(row -> !row.observedAt().isBefore(since))
                .sorted(Comparator.comparing(Row::observedAt))
                .toList();
        List<QueryResult.Bound> bounds = List.of();
        if (matched.size() > limit) {
            matched = matched.subList(0, limit);
            bounds = List.of(new QueryResult.Bound(QueryResult.Bound.Kind.ROW_LIMIT, limit));
        }
        matched.forEach(sink::row);
        return new QueryResult(matched, List.of(), bounds, List.of());
    }

    private Row indexRow(long messageId, Instant timestamp, Instant observedAt) {
        return new Row(
                node.getId(),
                node.getName(),
                "ORDER.IN",
                "ORDER.IN",
                messageId,
                3,
                true,
                4,
                timestamp.toEpochMilli(),
                0,
                100,
                null,
                null,
                null,
                null,
                null,
                "body-" + messageId,
                false,
                false,
                null,
                Map.of(),
                QueryAst.Source.INDEX,
                observedAt,
                observedAt,
                "CAPTURED",
                null);
    }

    @Test
    void aClientThatHasGoneAwayStopsTheTailWithoutAnotherBrokerRead() {
        RecordingTransport transport = new RecordingTransport(3);
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(CLUSTER, plan("SELECT * FROM \"ORDER.IN\""), transport, listener);

        listener.cancelled = true;
        poller.tick();

        assertThat(poller.activeTails()).isZero();
        assertThat(transport.filters).isEmpty();
    }

    @Test
    void stoppingATailRemovesItEvenWhileItsListenerIsStillAlive() {
        RecordingTransport transport = new RecordingTransport(3);
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        SqlTailPoller.Tail tail = poller.start(CLUSTER, plan("SELECT * FROM \"ORDER.IN\""), transport, listener);

        tail.stop();
        poller.tick();

        assertThat(poller.activeTails()).isZero();
        assertThat(transport.filters).isEmpty();
    }

    @Test
    void theObservedGapIsReportedFromTheMessagesAddedDeltaRatherThanImplied() {
        RecordingTransport transport = new RecordingTransport(3);
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(CLUSTER, plan("SELECT * FROM \"ORDER.IN\""), transport, listener);

        // Thirty arrived; the poll saw three of them still on the queue. The rest
        // were consumed between reads and are exactly what a sample cannot show.
        messagesAdded.set(130);
        poller.tick();
        await(() -> !listener.statuses.isEmpty());

        SqlTailPoller.TailStatus status = listener.statuses.getLast();
        assertThat(status.enqueued()).isEqualTo(30);
        assertThat(status.shown()).isEqualTo(3);
        // No predicate, so the subtraction is a fact rather than an upper bound.
        assertThat(status.everyMessageMatches()).isTrue();
    }

    @Test
    void aQueryWithAPredicateSaysItsGapFigureCannotBeSeparatedFromNonMatches() {
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(
                CLUSTER, plan("SELECT * FROM \"ORDER.IN\" WHERE priority > 4"), new RecordingTransport(0), listener);

        poller.tick();
        await(() -> !listener.statuses.isEmpty());

        assertThat(listener.statuses.getLast().everyMessageMatches()).isFalse();
    }

    @Test
    void aDeepBacklogIsWalkedABoundedNumberOfPagesPerTickAndSaysItIsStillGoing() {
        DeepTransport transport = new DeepTransport();
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(CLUSTER, plan("SELECT * FROM \"ORDER.IN\""), transport, listener, Duration.ZERO, true);

        poller.tick();
        await(() -> !listener.statuses.isEmpty());

        assertThat(transport.pagesRead.get()).isEqualTo(SqlTailPoller.BACKLOG_PAGES_PER_TICK);
        assertThat(listener.statuses.getLast().backlogInProgress()).isTrue();
    }

    @Test
    void aShallowBacklogIsFinishedInOneTick() {
        CollectingListener listener = new CollectingListener();
        SqlTailPoller poller = poller();
        poller.start(
                CLUSTER, plan("SELECT * FROM \"ORDER.IN\""), new RecordingTransport(3), listener, Duration.ZERO, true);

        poller.tick();
        await(() -> !listener.statuses.isEmpty());

        assertThat(listener.statuses.getLast().backlogInProgress()).isFalse();
    }

    // ---- harness --------------------------------------------------------

    /** A queue deeper than any tick may read: every page is full, and every message is distinct. */
    private static final class DeepTransport implements MessageTransport {
        final AtomicInteger pagesRead = new AtomicInteger();

        @Override
        public Channel channel() {
            return Channel.JOLOKIA;
        }

        @Override
        public BrowseResult browse(TransportTarget target, int page, int size, String filter) {
            pagesRead.incrementAndGet();
            List<BrowsedMessage> messages = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                messages.add(message((page - 1) * size + i));
            }
            return new BrowseResult(new BrowsePage(messages, 1_000_000L), Channel.JOLOKIA);
        }

        @Override
        public void send(TransportTarget target, SendSpec spec) {
            throw new UnsupportedOperationException();
        }
    }

    private SqlTailPoller poller() {
        SqlProperties properties = new SqlProperties(
                50,
                50_000,
                2_000,
                Long.MAX_VALUE,
                Duration.ofSeconds(30),
                2,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1));
        QueryPlanner planner = new QueryPlanner(
                snapshots,
                Mockito.mock(QueueLocator.class),
                nodes,
                splitter,
                renderer,
                clocks,
                properties,
                coverage,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new SqlTailPoller(
                new BrokerQueryExecutor(
                        nodes, residuals, planner, properties, BrokerQueryExecutorTest.clearGovernance()),
                indexExecutor,
                snapshots);
    }

    private QueryPlan plan(String sql) {
        SqlProperties properties = new SqlProperties(
                50,
                50_000,
                2_000,
                Long.MAX_VALUE,
                Duration.ofSeconds(30),
                2,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1));
        QueryPlanner planner = new QueryPlanner(
                snapshots,
                Mockito.mock(QueueLocator.class),
                nodes,
                splitter,
                renderer,
                clocks,
                properties,
                coverage,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return planner.plan(CLUSTER, parser.parse(sql));
    }

    /** A poll runs on its own thread, so the assertions wait for it rather than assume it. */
    private static void await(BooleanSupplier condition) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(10))
                .until(condition::getAsBoolean);
    }

    private static final class CollectingListener implements SqlTailPoller.Listener {
        final List<Row> rows = new CopyOnWriteArrayList<>();
        final List<NodeOutcome> outcomes = new CopyOnWriteArrayList<>();
        final List<SqlTailPoller.TailStatus> statuses = new CopyOnWriteArrayList<>();
        volatile boolean cancelled;

        @Override
        public void row(Row row) {
            rows.add(row);
        }

        @Override
        public void nodeFinished(NodeOutcome outcome) {
            outcomes.add(outcome);
        }

        @Override
        public void status(SqlTailPoller.TailStatus status) {
            statuses.add(status);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** Serves a fixed set of messages and records the selector it was asked to apply. */
    private static final class RecordingTransport implements MessageTransport {
        private final int depth;
        final List<String> filters = Collections.synchronizedList(new ArrayList<>());

        private RecordingTransport(int depth) {
            this.depth = depth;
        }

        @Override
        public Channel channel() {
            return Channel.JOLOKIA;
        }

        @Override
        public BrowseResult browse(TransportTarget target, int page, int size, String filter) {
            filters.add(filter == null ? "" : filter);
            List<BrowsedMessage> messages = new ArrayList<>();
            if (page == 1) {
                for (int i = 0; i < depth; i++) {
                    messages.add(message(i));
                }
            }
            return new BrowseResult(new BrowsePage(messages, depth), Channel.JOLOKIA);
        }

        @Override
        public void send(TransportTarget target, SendSpec spec) {
            throw new UnsupportedOperationException();
        }
    }

    private static BrowsedMessage message(int i) {
        return new BrowsedMessage(
                i,
                3,
                true,
                4,
                NOW.toEpochMilli(),
                0,
                100,
                null,
                null,
                null,
                null,
                "body-" + i,
                BodyEncoding.TEXT,
                Compression.NONE,
                null,
                false,
                null,
                Map.of("tenant", "acme"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }

    private BrokerNodeEntity node() {
        BrokerNodeEntity entity = instantiate(BrokerNodeEntity.class);
        set(entity, "id", UUID.randomUUID());
        set(entity, "name", "broker-1");
        set(entity, "artemisNodeId", "node-a");
        set(entity, "clusterId", CLUSTER);
        set(entity, "jolokiaUrl", "http://broker:8161/console/jolokia");
        return entity;
    }

    private QueueSnapshot snapshot(String queue, long depth, long added) {
        return new QueueSnapshot(
                CLUSTER, node.getId(), queue, queue, "ANYCAST", false, false, null, depth, 0, 0, 0, added, 0, 0);
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("fixture could not build " + type.getSimpleName(), e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("fixture could not set " + field, e);
        }
    }
}
