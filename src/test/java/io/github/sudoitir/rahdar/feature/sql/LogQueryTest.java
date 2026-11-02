package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.platform.broker.BodyDecoder.Compression;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService;
import io.github.sudoitir.rahdar.platform.broker.LogBounds.Bounds;
import io.github.sudoitir.rahdar.platform.broker.LogPage;
import io.github.sudoitir.rahdar.platform.broker.LogPage.LogRecord;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A cluster read as one (Kafka) has no selector, so the console reads the log and evaluates the whole WHERE in
 * Rahdar, bounded by the scan cap. A queue RabbitMQ can only consume is refused with its reason.
 */
class LogQueryTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final ResourceId TOPIC = new ResourceId(KindId.of("kafka.topic"), ResourcePath.of("orders"));

    private final SqlQueryParser parser = new SqlQueryParser();
    private final SelectorRenderer renderer = new SelectorRenderer();
    private final PredicateSplitter splitter = new PredicateSplitter(renderer);
    private final LogTargets logs = mock(LogTargets.class);
    private final ClusterDirectory nodes = mock(ClusterDirectory.class);
    private final BrokerMessages messages = mock(BrokerMessages.class);
    private BrokerNodeEntity node;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        var constructor = BrokerNodeEntity.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        node = constructor.newInstance();
        set("id", UUID.randomUUID());
        set("name", "kafka-1");
        set("clusterId", CLUSTER);
        when(nodes.nodes(CLUSTER)).thenReturn(List.of(node));
        when(logs.applies(CLUSTER)).thenReturn(true);
        when(logs.node(CLUSTER)).thenReturn(node);
        when(logs.partitioned(CLUSTER)).thenReturn(true);
    }

    private void set(String field, Object value) throws ReflectiveOperationException {
        Field f = BrokerNodeEntity.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(node, value);
    }

    private SqlProperties props(long scanCap) {
        return new SqlProperties(
                50,
                scanCap,
                2_000,
                Long.MAX_VALUE,
                Duration.ofSeconds(30),
                2,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1));
    }

    private QueryPlanner planner(SqlProperties properties) {
        return new QueryPlanner(
                mock(QueueSnapshots.class),
                mock(QueueLocator.class),
                nodes,
                splitter,
                renderer,
                mock(ClockOffsetService.class),
                properties,
                mock(MessageIndexCoverage.class),
                logs,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static LogRecord record(long offset, String body) {
        return new LogRecord(
                new BrowsedMessage(
                        offset,
                        3,
                        true,
                        4,
                        NOW.toEpochMilli(),
                        0,
                        10,
                        null,
                        null,
                        null,
                        null,
                        body,
                        BodyEncoding.TEXT,
                        Compression.NONE,
                        null,
                        false,
                        null,
                        Map.of("tenant", "acme"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of()),
                false,
                null,
                null);
    }

    private void givenTopic() {
        when(logs.resolve(any(), any(), anyList())).thenReturn(List.of(new LogTargets.Log(TOPIC, "orders", 6L)));
        TreeMap<Integer, Bounds> partitions = new TreeMap<>();
        partitions.put(0, new Bounds(0, 3));
        partitions.put(1, new Bounds(0, 3));
        when(logs.partitions(CLUSTER, TOPIC)).thenReturn(partitions);
        when(logs.page(eq(CLUSTER), eq(TOPIC), eq(0), anyLong(), anyInt()))
                .thenReturn(new LogPage(List.of(record(0, "paid 0"), record(1, "paid 1"), record(2, "late 2")), null));
        when(logs.page(eq(CLUSTER), eq(TOPIC), eq(1), anyLong(), anyInt()))
                .thenReturn(new LogPage(List.of(record(0, "paid 3"), record(1, "late 4"), record(2, "paid 5")), null));
    }

    @Test
    void aTopicIsReadFromItsLogAndTheWholePredicateIsEvaluatedInRahdar() {
        givenTopic();
        SqlProperties properties = props(50_000);
        QueryPlanner planner = planner(properties);
        BrokerQueryExecutor executor = new BrokerQueryExecutor(
                nodes,
                new MessagePredicate(),
                planner,
                properties,
                BrokerQueryExecutorTest.clearGovernance(),
                messages,
                logs);
        QueryPlan plan = planner.plan(
                CLUSTER, parser.parse("SELECT * FROM orders WHERE props.tenant = 'acme' AND body LIKE 'paid%'"));

        QueryResult result = executor.execute(CLUSTER, plan, new BrokerQueryExecutorTest.CountingSink());

        // No selector is sent: the broker cannot evaluate one, so everything is scanned here.
        assertThat(plan.selector()).isNull();
        assertThat(plan.requiresScan()).isTrue();
        assertThat(plan.targets().getFirst().isLog()).isTrue();
        assertThat(result.rows()).extracting(Row::body).containsExactly("paid 0", "paid 1", "paid 3", "paid 5");
        assertThat(result.nodes().getFirst().examined()).isEqualTo(6);
        verify(messages, never()).browse(any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void thePartitionIsAColumnToFilterOn() {
        givenTopic();
        SqlProperties properties = props(50_000);
        QueryPlanner planner = planner(properties);
        BrokerQueryExecutor executor = new BrokerQueryExecutor(
                nodes,
                new MessagePredicate(),
                planner,
                properties,
                BrokerQueryExecutorTest.clearGovernance(),
                messages,
                logs);
        QueryPlan plan = planner.plan(CLUSTER, parser.parse("SELECT * FROM orders WHERE props.partition = 1"));

        QueryResult result = executor.execute(CLUSTER, plan, new BrokerQueryExecutorTest.CountingSink());

        assertThat(result.rows()).extracting(Row::body).containsExactly("paid 3", "late 4", "paid 5");
    }

    @Test
    void theScanCapStopsAReadOfALogAndIsReported() {
        givenTopic();
        SqlProperties properties = props(3);
        QueryPlanner planner = planner(properties);
        BrokerQueryExecutor executor = new BrokerQueryExecutor(
                nodes,
                new MessagePredicate(),
                planner,
                properties,
                BrokerQueryExecutorTest.clearGovernance(),
                messages,
                logs);
        QueryPlan plan = planner.plan(CLUSTER, parser.parse("SELECT * FROM orders WHERE body LIKE 'none%'"));

        QueryResult result = executor.execute(CLUSTER, plan, new BrokerQueryExecutorTest.CountingSink());

        assertThat(result.boundsReached())
                .extracting(QueryResult.Bound::kind)
                .contains(QueryResult.Bound.Kind.SCAN_CAP);
    }

    @Test
    void aQueueThatCanOnlyBeConsumedRefusesThePlanWithItsReason() {
        when(logs.resolve(any(), any(), anyList()))
                .thenThrow(new ConflictException(
                        "not-browsable",
                        "RabbitMQ can browse only streams; classic and quorum queues can only be"
                                + " consumed, which would take the messages."));
        QueryPlanner planner = planner(props(50_000));

        assertThatThrownBy(() -> planner.plan(CLUSTER, parser.parse("SELECT * FROM jobs")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("can browse only streams");
    }

    @Test
    void theIndexIsRefusedOnAClusterReadAsOneNamingTheSystem() {
        ClusterKinds kinds = mock(ClusterKinds.class);
        when(kinds.readAsOne(CLUSTER)).thenReturn(true);
        when(kinds.systemName(CLUSTER)).thenReturn("Apache Kafka");
        LogTargets real = new LogTargets(kinds, nodes, null, messages, mock(SystemExtensions.class));

        assertThatThrownBy(() -> real.requireIndexable(CLUSTER))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("captures Artemis queues")
                .hasMessageContaining("Apache Kafka")
                .hasMessageContaining("Query the topic directly");
    }
}
