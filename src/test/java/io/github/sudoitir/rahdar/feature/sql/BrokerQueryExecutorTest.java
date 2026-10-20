package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Notice;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Bound;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.platform.broker.BodyDecoder.Compression;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.BrowsePage;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetRegistry.ClockOffset;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The executor's job is to stay inside its bounds and to be honest about the ones it
 * hits. A bounded result that does not say it is bounded is the failure that matters,
 * so most of these assert the report rather than the rows.
 */
class BrokerQueryExecutorTest {

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
    private BrokerNodeEntity node;

    @BeforeEach
    void setUp() {
        snapshots = mock(QueueSnapshots.class);
        nodes = mock(ClusterDirectory.class);
        clocks = mock(ClockOffsetService.class);
        coverage = mock(MessageIndexCoverage.class);
        when(clocks.offsetFor(any())).thenReturn(Optional.of(new ClockOffset(0, 5, 10, 3, NOW)));
        when(coverage.isCaptured(any(), any())).thenReturn(false);
        when(coverage.check(any(), any(), any())).thenReturn(List.of());
        node = node("broker-1", "node-a");
    }

    // ---- harness --------------------------------------------------------

    /** A caller with clear access, so these tests see the broker's content exactly as they did before governance. */
    static SqlGovernance clearGovernance() {
        SqlGovernance governance = mock(SqlGovernance.class);
        when(governance.clearAccess(any(), any())).thenReturn(true);
        return governance;
    }

    private SqlProperties props(long scanCap, int maxRows, Duration timeout) {
        return new SqlProperties(
                50, scanCap, maxRows, Long.MAX_VALUE, timeout, 2, Duration.ofSeconds(5), Duration.ofSeconds(1));
    }

    private QueryPlanner planner(SqlProperties properties) {
        return new QueryPlanner(
                snapshots,
                mock(QueueLocator.class),
                nodes,
                splitter,
                renderer,
                clocks,
                properties,
                coverage,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private QueryResult run(String sql, BrokerMessages transport, SqlProperties properties) {
        return run(sql, transport, properties, new CountingSink());
    }

    private QueryResult run(
            String sql, BrokerMessages transport, SqlProperties properties, BrokerQueryExecutor.Sink sink) {
        QueryPlanner planner = planner(properties);
        BrokerQueryExecutor executor =
                new BrokerQueryExecutor(nodes, residuals, planner, properties, clearGovernance(), transport);
        QueryPlan plan = planner.plan(CLUSTER, parser.parse(sql));
        return executor.execute(CLUSTER, plan, sink);
    }

    static class CountingSink implements BrokerQueryExecutor.Sink {
        final List<Row> rows = new ArrayList<>();
        final List<NodeOutcome> outcomes = new ArrayList<>();
        volatile boolean cancelled;

        @Override
        public synchronized void row(Row row) {
            rows.add(row);
        }

        @Override
        public synchronized void nodeFinished(NodeOutcome outcome) {
            outcomes.add(outcome);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** Messages that serve a fixed queue depth. */
    private static BrokerMessages transportServing(int totalMessages, boolean truncated) {
        return transport((queue, page, size, filter) -> {
            int from = (page - 1) * size;
            List<BrowsedMessage> messages = new ArrayList<>();
            for (int i = from; i < Math.min(from + size, totalMessages); i++) {
                messages.add(message(i, truncated));
            }
            return new BrowsePage(messages, (long) totalMessages);
        });
    }

    private static BrowsedMessage message(int i, boolean truncated) {
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
                "body-" + i + "-needle",
                BodyEncoding.TEXT,
                Compression.NONE,
                null,
                truncated,
                truncated ? 256 : null,
                Map.of("tenant", "acme"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }

    private BrokerNodeEntity node(String name, String artemisNodeId) {
        BrokerNodeEntity entity = instantiate(BrokerNodeEntity.class);
        set(entity, "id", UUID.randomUUID());
        set(entity, "name", name);
        set(entity, "artemisNodeId", artemisNodeId);
        set(entity, "clusterId", CLUSTER);
        set(entity, "jolokiaUrl", "http://broker:8161/console/jolokia");
        return entity;
    }

    private QueueSnapshot snapshot(BrokerNodeEntity on, String queue, long depth) {
        return new QueueSnapshot(
                CLUSTER, on.getId(), queue, queue, "ANYCAST", false, false, null, depth, 0, 0, 0, 0, 0, 0);
    }

    private void given(List<BrokerNodeEntity> nodeList, List<QueueSnapshot> snapshotList) {
        when(nodes.nodes(CLUSTER)).thenReturn(new ArrayList<>(nodeList));
        when(snapshots.forCluster(CLUSTER)).thenReturn(new ArrayList<>(snapshotList));
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

    // ---- tests ----------------------------------------------------------

    @Test
    void aMatchingQueryReturnsRowsAttributedToTheirNodeAndQueue() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 5)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%needle%'",
                transportServing(5, false), props(50_000, 2_000, Duration.ofSeconds(30)));

        assertThat(result.rows()).hasSize(5);
        assertThat(result.rows()).allSatisfy(row -> {
            assertThat(row.nodeName()).isEqualTo("broker-1");
            assertThat(row.queueName()).isEqualTo("ORDER.IN");
            assertThat(row.source()).isEqualTo(QueryAst.Source.BROKER);
        });
        assertThat(result.isPartial()).isFalse();
    }

    @Test
    void aCallerWithoutClearAccessSearchesTheMaskedBodyNotTheRawOne() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 5)));
        SqlGovernance masked = mock(SqlGovernance.class);
        when(masked.clearAccess(any(), any())).thenReturn(false);
        when(masked.forEvaluation(any(), any(), any())).thenAnswer(call -> {
            BrowsedMessage m = call.getArgument(2);
            return new BrowsedMessage(
                    m.messageId(),
                    m.type(),
                    m.durable(),
                    m.priority(),
                    m.timestamp(),
                    m.expiration(),
                    m.size(),
                    m.groupId(),
                    m.correlationId(),
                    m.replyTo(),
                    m.userId(),
                    "[redacted personal data]",
                    m.bodyEncoding(),
                    m.bodyCompression(),
                    m.contentType(),
                    m.bodyTruncated(),
                    m.observedLimitBytes(),
                    m.stringProperties(),
                    m.intProperties(),
                    m.longProperties(),
                    m.doubleProperties(),
                    m.booleanProperties());
        });
        SqlProperties properties = props(50_000, 2_000, Duration.ofSeconds(30));
        QueryPlanner planner = planner(properties);
        BrokerQueryExecutor executor =
                new BrokerQueryExecutor(nodes, residuals, planner, properties, masked, transportServing(5, false));

        QueryResult result = executor.execute(
                CLUSTER,
                planner.plan(CLUSTER, parser.parse("SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%needle%'")),
                new CountingSink());

        // Every raw body contains the needle; none of the bodies the caller may see does.
        assertThat(result.rows()).isEmpty();
        assertThat(result.nodes().getFirst().examined()).isEqualTo(5);
    }

    @Test
    void aResidualPredicateThatMatchesNothingReturnsNoRowsButStillAnswers() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 5)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%haystack%'",
                transportServing(5, false), props(50_000, 2_000, Duration.ofSeconds(30)));

        assertThat(result.rows()).isEmpty();
        assertThat(result.nodes()).extracting(NodeOutcome::status).containsExactly(NodeOutcome.Status.ANSWERED);
        assertThat(result.nodes().getFirst().examined()).isEqualTo(5);
    }

    @Test
    void theScanCapStopsTheQueryAndTheResultSaysSo() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 1_000)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%haystack%'",
                transportServing(1_000, false), props(200, 2_000, Duration.ofSeconds(30)));

        assertThat(result.boundsReached()).extracting(Bound::kind).contains(Bound.Kind.SCAN_CAP);
        assertThat(result.isPartial()).isTrue();
    }

    @Test
    void theRowLimitIsExactRatherThanOvershotByAPage() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 1_000)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%needle%'",
                transportServing(1_000, false), props(50_000, 10, Duration.ofSeconds(30)));

        assertThat(result.rows()).hasSize(10);
        assertThat(result.boundsReached()).extracting(Bound::kind).contains(Bound.Kind.ROW_LIMIT);
    }

    @Test
    void aTimeoutIsReportedRatherThanReturningAQuietShortResult() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 1_000)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%needle%'",
                transportServing(1_000, false), props(50_000, 2_000, Duration.ZERO));

        assertThat(result.boundsReached()).extracting(Bound::kind).contains(Bound.Kind.TIMEOUT);
        assertThat(result.isPartial()).isTrue();
    }

    @Test
    void aFailedNodeIsReportedWithItsReasonAndTheOthersStillAnswer() {
        BrokerNodeEntity healthy = node;
        BrokerNodeEntity broken = node("broker-2", "node-b");
        given(List.of(healthy, broken), List.of(snapshot(healthy, "ORDER.IN", 3), snapshot(broken, "ORDER.OUT", 3)));

        BrokerMessages flaky = transport((queue, page, size, filter) -> {
            if ("ORDER.OUT".equals(queue)) {
                throw new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "connection refused");
            }
            List<BrowsedMessage> messages = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                messages.add(message(i, false));
            }
            return new BrowsePage(messages, 3L);
        });

        QueryResult result = run("SELECT * FROM \"ORDER.#\"", flaky, props(50_000, 2_000, Duration.ofSeconds(30)));

        assertThat(result.rows()).hasSize(3);
        assertThat(result.isPartial()).isTrue();
        NodeOutcome failed = result.nodes().stream()
                .filter(n -> n.status() == NodeOutcome.Status.FAILED)
                .findFirst()
                .orElseThrow();
        assertThat(failed.nodeName()).isEqualTo("broker-2");
        assertThat(failed.detail()).contains("connection refused");
    }

    @Test
    void abandoningTheQueryStopsFurtherBrokerReads() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 1_000)));
        AtomicInteger browses = new AtomicInteger();
        BrokerMessages counting = transport((queue, page, size, filter) -> {
            browses.incrementAndGet();
            List<BrowsedMessage> messages = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                messages.add(message(i, false));
            }
            return new BrowsePage(messages, 1_000L);
        });
        CountingSink sink = new CountingSink();
        sink.cancelled = true;

        QueryResult result =
                run("SELECT * FROM \"ORDER.IN\"", counting, props(50_000, 2_000, Duration.ofSeconds(30)), sink);

        assertThat(browses.get()).isZero();
        assertThat(result.rows()).isEmpty();
    }

    @Test
    void aTruncatedBodyUnderABodyPredicateMakesTheResultSayItMayBeIncomplete() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 3)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%needle%'",
                transportServing(3, true), props(50_000, 2_000, Duration.ofSeconds(30)));

        assertThat(result.notices()).extracting(Notice::kind).contains(Notice.Kind.BODY_TRUNCATED);
        assertThat(result.notices())
                .anySatisfy(n -> assertThat(n.detail()).contains("management-message-attribute-size-limit"));
    }

    @Test
    void aTruncatedBodyWithNoBodyPredicateDoesNotWarn() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 3)));

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\" WHERE priority > 1",
                transportServing(3, true),
                props(50_000, 2_000, Duration.ofSeconds(30)));

        assertThat(result.notices()).isEmpty();
    }

    // ---- edges ----------------------------------------------------------

    /** What a browse does, so an edge case can be written as one lambda. */
    @FunctionalInterface
    private interface Browse {
        BrowsePage apply(String queue, int page, int size, String filter);
    }

    private static BrokerMessages transport(Browse browse) {
        BrokerMessages messages = mock(BrokerMessages.class);
        when(messages.browse(any(), any(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(i -> browse.apply(i.getArgument(2), i.getArgument(3), i.getArgument(4), i.getArgument(5)));
        return messages;
    }

    private static BrowsePage fullPage(int size) {
        List<BrowsedMessage> messages = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            messages.add(message(i, false));
        }
        return new BrowsePage(messages, 100_000L);
    }

    @Test
    void anUnexpectedBrowseFailureIsReportedWithItsMessageOrItsClassName() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 3)));
        SqlProperties properties = props(50_000, 2_000, Duration.ofSeconds(30));

        QueryResult withMessage = run(
                "SELECT * FROM \"ORDER.IN\"",
                transport((queue, page, size, filter) -> {
                    throw new IllegalStateException("boom");
                }),
                properties);
        QueryResult withoutMessage = run(
                "SELECT * FROM \"ORDER.IN\"",
                transport((queue, page, size, filter) -> {
                    throw new IllegalStateException();
                }),
                properties);

        assertThat(withMessage.nodes().getFirst().status()).isEqualTo(NodeOutcome.Status.FAILED);
        assertThat(withMessage.nodes().getFirst().detail()).isEqualTo("boom");
        assertThat(withoutMessage.nodes().getFirst().detail()).isEqualTo("IllegalStateException");
    }

    @Test
    void abandoningTheQueryBetweenPagesEndsTheTargetAsNotRead() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 100_000)));
        CountingSink sink = new CountingSink();
        AtomicInteger browses = new AtomicInteger();

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\"",
                transport((queue, page, size, filter) -> {
                    browses.incrementAndGet();
                    sink.cancelled = true;
                    return fullPage(size);
                }),
                props(50_000, 2_000, Duration.ofSeconds(30)),
                sink);

        assertThat(browses.get()).isEqualTo(1);
        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes().getFirst().status()).isEqualTo(NodeOutcome.Status.NOT_READ);
        assertThat(result.nodes().getFirst().detail()).isEqualTo("The query was abandoned.");
    }

    @Test
    void aTailsExtraSelectorIsAndedIntoThePushedDownOneOrStandsAlone() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 3)));
        SqlProperties properties = props(50_000, 2_000, Duration.ofSeconds(30));
        QueryPlanner planner = planner(properties);
        List<String> filters = new ArrayList<>();
        BrokerMessages capturing = transport((queue, page, size, filter) -> {
            filters.add(filter);
            return fullPage(1);
        });
        BrokerQueryExecutor executor =
                new BrokerQueryExecutor(nodes, residuals, planner, properties, clearGovernance(), capturing);

        executor.execute(
                CLUSTER,
                planner.plan(CLUSTER, parser.parse("SELECT * FROM \"ORDER.IN\" WHERE priority = 4")),
                new CountingSink(),
                target -> "AMQTimestamp > 5");
        executor.execute(
                CLUSTER,
                planner.plan(CLUSTER, parser.parse("SELECT * FROM \"ORDER.IN\"")),
                new CountingSink(),
                target -> "AMQTimestamp > 5");
        executor.execute(
                CLUSTER,
                planner.plan(CLUSTER, parser.parse("SELECT * FROM \"ORDER.IN\" WHERE priority = 4")),
                new CountingSink(),
                target -> " ");

        assertThat(filters.get(0))
                .startsWith("(")
                .endsWith(") AND (AMQTimestamp > 5)")
                .contains("JMSPriority");
        assertThat(filters.get(1)).isEqualTo("AMQTimestamp > 5");
        assertThat(filters.get(2)).contains("JMSPriority").doesNotContain("AMQTimestamp");
    }

    @Test
    void maxPagesPerTargetBoundsHowMuchOfOneQueueACallReads() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 100_000)));
        SqlProperties properties = props(500_000, 2_000, Duration.ofSeconds(30));
        QueryPlanner planner = planner(properties);
        AtomicInteger browses = new AtomicInteger();
        BrokerQueryExecutor executor = new BrokerQueryExecutor(
                nodes, residuals, planner, properties, clearGovernance(), transport((queue, page, size, filter) -> {
                    browses.incrementAndGet();
                    return fullPage(size);
                }));

        QueryResult result = executor.execute(
                CLUSTER,
                planner.plan(CLUSTER, parser.parse("SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%haystack%'")),
                new CountingSink(),
                target -> null,
                2);

        assertThat(browses.get()).isEqualTo(2);
        assertThat(result.nodes().getFirst().examined()).isEqualTo(2L * BrokerQueryExecutor.PAGE_SIZE);
    }

    @Test
    void aQueueSkippedBecauseABoundWasReachedSaysWhichBound() {
        given(List.of(node), List.of(snapshot(node, "ORDER.A", 1_000), snapshot(node, "ORDER.B", 1_000)));

        QueryResult capped = run(
                "SELECT * FROM \"ORDER.#\" WHERE body LIKE '%haystack%'",
                transportServing(1_000, false), props(200, 2_000, Duration.ofSeconds(30)));
        QueryResult timedOut = run(
                "SELECT * FROM \"ORDER.#\" WHERE body LIKE '%needle%'",
                transportServing(1_000, false), props(50_000, 2_000, Duration.ZERO));

        assertThat(capped.nodes())
                .filteredOn(n -> n.status() == NodeOutcome.Status.NOT_READ)
                .isNotEmpty()
                .allSatisfy(n -> assertThat(n.detail()).contains("the scan cap of 200 messages was reached"));
        assertThat(timedOut.nodes())
                .filteredOn(n -> n.status() == NodeOutcome.Status.NOT_READ)
                .isNotEmpty()
                .allSatisfy(n -> assertThat(n.detail()).contains("timed out"));
    }

    @Test
    void aSinkThatFailsEndsItsNodeButNotTheQuery() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 3)));
        CountingSink failing = new CountingSink() {
            @Override
            public synchronized void row(Row row) {
                throw new IllegalStateException("caller went away");
            }
        };

        QueryResult result = run(
                "SELECT * FROM \"ORDER.IN\"",
                transportServing(3, false),
                props(50_000, 2_000, Duration.ofSeconds(30)),
                failing);

        assertThat(result.nodes()).isEmpty();
    }

    @Test
    void theCollectingSinkKeepsRowsAndReportsCancellation() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 3)));
        BrokerQueryExecutor.CollectingSink sink = new BrokerQueryExecutor.CollectingSink();

        assertThat(sink.first()).isEmpty();
        assertThat(sink.isCancelled()).isFalse();
        assertThat(sink.clearServed()).isEmpty();

        run(
                "SELECT * FROM \"ORDER.IN\"",
                transportServing(3, false),
                props(50_000, 2_000, Duration.ofSeconds(30)),
                sink);

        assertThat(sink.first()).isPresent();
        assertThat(sink.first().orElseThrow().queueName()).isEqualTo("ORDER.IN");
        sink.cancel();
        assertThat(sink.isCancelled()).isTrue();
    }

    @Test
    void theExecutorWithoutAnExtraSelectorMatchesTheOneWithANullSelector() {
        given(List.of(node), List.of(snapshot(node, "ORDER.IN", 2)));
        SqlProperties properties = props(50_000, 2_000, Duration.ofSeconds(30));
        QueryPlanner planner = planner(properties);
        BrokerQueryExecutor executor = new BrokerQueryExecutor(
                nodes, residuals, planner, properties, clearGovernance(), transportServing(2, false));
        QueryPlan plan = planner.plan(CLUSTER, parser.parse("SELECT * FROM \"ORDER.IN\""));

        QueryResult result = executor.execute(CLUSTER, plan, new CountingSink(), t -> null);

        assertThat(result.rows()).hasSize(2);
    }
}
