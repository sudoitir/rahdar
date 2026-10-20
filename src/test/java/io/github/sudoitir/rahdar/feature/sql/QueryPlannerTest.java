package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Source;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Notice;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Target;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetRegistry.ClockOffset;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.system.artemis.QueueLocator;
import io.github.sudoitir.rahdar.system.artemis.QueueLocator.QueueLocation;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The planner is where a query stops being text and becomes broker load, so the
 * cases that matter are the ones that decide how much load: which targets, which
 * backend, and how expensive the plan says it will be.
 */
class QueryPlannerTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    private final SqlQueryParser parser = new SqlQueryParser();
    private final SelectorRenderer renderer = new SelectorRenderer();
    private final PredicateSplitter splitter = new PredicateSplitter(renderer);

    private QueueSnapshots snapshots;
    private ClusterDirectory nodes;
    private ClockOffsetService clocks;
    private MessageIndexCoverage coverage;
    private QueueLocator locator;
    private QueryPlanner planner;

    @BeforeEach
    void setUp() {
        snapshots = mock(QueueSnapshots.class);
        nodes = mock(ClusterDirectory.class);
        clocks = mock(ClockOffsetService.class);
        coverage = mock(MessageIndexCoverage.class);
        locator = mock(QueueLocator.class);
        when(clocks.offsetFor(any())).thenReturn(Optional.of(new ClockOffset(0, 5, 10, 3, NOW)));
        when(coverage.isCaptured(any(), any())).thenReturn(false);
        when(coverage.check(any(), any(), any())).thenReturn(List.of());
        planner = newPlanner(defaults());
    }

    private QueryPlanner newPlanner(SqlProperties sql) {
        SqlProperties properties = sql;
        return new QueryPlanner(
                snapshots,
                locator,
                nodes,
                splitter,
                renderer,
                clocks,
                properties,
                coverage,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SqlProperties defaults() {
        return new SqlProperties(
                50, 50_000L, 2_000, 250_000L, Duration.ofSeconds(30), 2, Duration.ofSeconds(5), Duration.ofSeconds(1));
    }

    private QueryPlan plan(String sql) {
        return planner.plan(CLUSTER, parser.parse(sql));
    }

    // ---- fixtures -------------------------------------------------------

    /** A node whose Artemis node id decides which endpoints are one logical node. */
    private BrokerNodeEntity node(String name, String artemisNodeId) {
        BrokerNodeEntity entity = instantiate(BrokerNodeEntity.class);
        set(entity, "id", UUID.randomUUID());
        set(entity, "name", name);
        set(entity, "artemisNodeId", artemisNodeId);
        set(entity, "clusterId", CLUSTER);
        return entity;
    }

    private QueueSnapshot snapshot(BrokerNodeEntity node, String queue, long depth) {
        return new QueueSnapshot(
                CLUSTER, node.getId(), queue, queue, "ANYCAST", false, false, null, depth, 0, 0, 0, 0, 0, 0);
    }

    /** Both entities keep a protected no-arg constructor for JPA; tests use the same one. */
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

    private void given(List<BrokerNodeEntity> nodeList, List<QueueSnapshot> snapshotList) {
        when(nodes.nodes(CLUSTER)).thenReturn(new ArrayList<>(nodeList));
        when(snapshots.forCluster(CLUSTER)).thenReturn(new ArrayList<>(snapshotList));
    }

    // ---- targets --------------------------------------------------------

    @Test
    void aWildcardResolvesToEveryMatchingQueue() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(
                List.of(a),
                List.of(snapshot(a, "ORDER.IN", 10), snapshot(a, "ORDER.OUT", 20), snapshot(a, "AUDIT.IN", 5)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.*\"");

        assertThat(plan.targets()).extracting(Target::queueName).containsExactly("ORDER.IN", "ORDER.OUT");
    }

    @Test
    void anArtemisHashWildcardSpansLevels() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.EU.IN", 1), snapshot(a, "ORDER.IN", 1)));

        assertThat(plan("SELECT * FROM \"ORDER.#\"").targets()).hasSize(2);
        // '*' is one level, so it does not reach ORDER.EU.IN.
        assertThat(plan("SELECT * FROM \"ORDER.*\"").targets())
                .extracting(Target::queueName)
                .containsExactly("ORDER.IN");
    }

    @Test
    void aPairedPrimaryAndBackupCountOnce() {
        // Both endpoints carry the same Artemis node id, so they are one logical node.
        // Without de-duplication every matching message would be returned twice.
        BrokerNodeEntity primary = node("broker-1", "node-a");
        BrokerNodeEntity backup = node("broker-1-backup", "node-a");
        given(List.of(primary, backup), List.of(snapshot(primary, "ORDER.IN", 10), snapshot(backup, "ORDER.IN", 10)));

        assertThat(plan("SELECT * FROM \"ORDER.IN\"").targets()).hasSize(1);
    }

    @Test
    void aPatternMatchingNothingSaysSoRatherThanReturningAnEmptyResult() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));

        QueryPlan plan = plan("SELECT * FROM \"NOTHING.*\"");

        assertThat(plan.targets()).isEmpty();
        assertThat(plan.notices()).extracting(Notice::kind).contains(Notice.Kind.NO_QUEUE_MATCHED);
    }

    /**
     * A queue created a moment ago is on the broker before the scrape reaches it. Named
     * outright, it is looked up live, rather than reported as a queue that does not exist.
     */
    @Test
    void aNamedQueueTheScrapeHasNotReachedIsLookedUpLive() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));
        when(locator.locate(CLUSTER, "FRESH"))
                .thenReturn(List.of(new QueueLocation(a.getId(), "FRESH", "FRESH", "ANYCAST", 3)));

        QueryPlan plan = plan("SELECT * FROM \"FRESH\"");

        assertThat(plan.targets()).singleElement().satisfies(t -> {
            assertThat(t.queueName()).isEqualTo("FRESH");
            assertThat(t.nodeId()).isEqualTo(a.getId());
            assertThat(t.messageCount()).isEqualTo(3);
        });
        assertThat(plan.notices()).extracting(Notice::kind).doesNotContain(Notice.Kind.NO_QUEUE_MATCHED);
    }

    @Test
    void aNodePredicateFiltersTargetsSoTheOtherNodeIsNeverAsked() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        BrokerNodeEntity b = node("broker-2", "node-b");
        given(List.of(a, b), List.of(snapshot(a, "ORDER.IN", 10), snapshot(b, "ORDER.IN", 10)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE node = 'broker-2'");

        assertThat(plan.targets()).extracting(Target::nodeName).containsExactly("broker-2");
    }

    @Test
    void theTargetCapIsApplied() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        List<QueueSnapshot> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(snapshot(a, "Q." + i, 1));
        }
        given(List.of(a), many);
        planner = newPlanner(new SqlProperties(
                3, 50_000L, 2_000, 250_000L, Duration.ofSeconds(30), 2, Duration.ofSeconds(5), Duration.ofSeconds(1)));

        assertThat(plan("SELECT * FROM \"Q.#\"").targets()).hasSize(3);
    }

    // ---- source ---------------------------------------------------------

    /**
     * The plain query reads the index only when every target is captured on the node it sits
     * on (ADR-0086). A CAPTURE subscription whose tap is not active on a node is not capture.
     */
    @Test
    void anUnqualifiedQueryPrefersTheIndexOnlyWhenEveryTargetIsCapturedOnItsNode() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10), snapshot(a, "ORDER.OUT", 10)));

        when(coverage.isCaptured(eq(CLUSTER), any())).thenReturn(false);
        assertThat(plan("SELECT * FROM \"ORDER.*\"").resolvedSource()).isEqualTo(Source.BROKER);

        when(coverage.isCaptured(eq(CLUSTER), any())).thenReturn(true);
        assertThat(plan("SELECT * FROM \"ORDER.*\"").resolvedSource()).isEqualTo(Source.INDEX);
    }

    @Test
    void theQualifierOverridesTheDefault() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));
        when(coverage.isCaptured(any(), any())).thenReturn(true);

        assertThat(plan("SELECT * FROM broker.\"ORDER.IN\"").resolvedSource()).isEqualTo(Source.BROKER);
    }

    @Test
    void anIndexOnlyColumnAgainstALiveBrokerIsRefusedRatherThanReturningNothing() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));

        assertThatThrownBy(() -> plan("SELECT * FROM broker.\"ORDER.IN\" WHERE observedAt > now() - interval '1 day'"))
                .isInstanceOf(SqlSyntaxException.class)
                .hasMessageContaining("no meaning against a live broker");
    }

    // ---- cost -----------------------------------------------------------

    @Test
    void aPushdownOnlyQueryIsEstimatedAtTheLimitNotTheQueueDepth() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 1_000_000)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE priority > 4 LIMIT 100");

        assertThat(plan.requiresScan()).isFalse();
        assertThat(plan.estimatedMessagesExamined()).isEqualTo(100);
        assertThat(plan.pushedDown()).containsExactly("priority > 4");
        assertThat(plan.scanned()).isEmpty();
    }

    @Test
    void aBodyPredicateIsEstimatedAtTheWholeQueueDepth() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 12_000)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%timeout%' LIMIT 100");

        assertThat(plan.requiresScan()).isTrue();
        assertThat(plan.estimatedMessagesExamined()).isEqualTo(12_000);
        assertThat(plan.scanned()).containsExactly("body LIKE '%timeout%'");
    }

    @Test
    void anOverBudgetQueryIsRefusedWithTheEstimateAndAHint() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 1_000_000)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%x%'");

        assertThatThrownBy(() -> planner.enforceCostCeiling(plan))
                .isInstanceOf(CostRefusedException.class)
                .hasMessageContaining("250000")
                .hasMessageContaining("narrower time window");
    }

    @Test
    void aWithinBudgetQueryIsNotRefused() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 500)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%x%'");

        assertThatNoException().isThrownBy(() -> planner.enforceCostCeiling(plan));
    }

    // ---- clock ----------------------------------------------------------

    @Test
    void aRelativeWindowIsRenderedInTheNodesOwnTime() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));
        // The broker is 60s ahead of Rahdar, so its "an hour ago" is 60s later too.
        when(clocks.offsetFor(a.getId())).thenReturn(Optional.of(new ClockOffset(60_000, 5, 10, 3, NOW)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE timestamp > now() - interval '1 hour'");

        long expected = NOW.plusSeconds(60).minus(Duration.ofHours(1)).toEpochMilli();
        assertThat(plan.selector()).isEqualTo("AMQTimestamp > " + expected);
    }

    @Test
    void anUnmeasuredClockIsDisclosedRatherThanAssumedZero() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));
        when(clocks.offsetFor(a.getId())).thenReturn(Optional.empty());

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE timestamp > now() - interval '1 hour'");

        assertThat(plan.notices()).extracting(Notice::kind).contains(Notice.Kind.CLOCK_OFFSET_UNKNOWN);
    }

    @Test
    void aQueryWithNoRelativeWindowDoesNotWarnAboutClocks() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 10)));
        when(clocks.offsetFor(a.getId())).thenReturn(Optional.empty());

        assertThat(plan("SELECT * FROM \"ORDER.IN\" WHERE priority > 4").notices())
                .extracting(Notice::kind)
                .doesNotContain(Notice.Kind.CLOCK_OFFSET_UNKNOWN);
    }

    // ---- fixtures for hand-built queries ---------------------------------

    private static QueryAst astOf(Source source, List<Column> projection, QueryAst.Predicate where, Integer limit) {
        return new QueryAst(source, "ORDER.IN", projection, where, List.of(), limit, "SELECT ...");
    }

    private static QueryAst.Predicate compare(QueryAst.Term term, QueryAst.Literal value) {
        return new QueryAst.Predicate.Compare(term, QueryAst.Operator.EQ, value);
    }

    private static QueryAst.Term column(Column column) {
        return new QueryAst.Term.ColumnTerm(column);
    }

    private QueryPlan planOf(Source source, QueryAst.Predicate where) {
        return planner.plan(CLUSTER, astOf(source, List.of(), where, null));
    }

    private BrokerNodeEntity oneQueue(long depth) {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", depth)));
        return a;
    }

    // ---- narrowing hints --------------------------------------------------

    private SqlProperties ceiling(long costCeiling) {
        return new SqlProperties(
                50,
                50_000L,
                2_000,
                costCeiling,
                Duration.ofSeconds(30),
                2,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1));
    }

    @Test
    void aScanAcrossSeveralQueuesTellsTheOperatorToNameOne() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        given(List.of(a), List.of(snapshot(a, "ORDER.IN", 1_000_000), snapshot(a, "ORDER.OUT", 1_000_000)));

        QueryPlan plan = plan("SELECT * FROM \"ORDER.*\" WHERE body LIKE '%x%'");

        assertThatThrownBy(() -> planner.enforceCostCeiling(plan))
                .isInstanceOf(CostRefusedException.class)
                .hasMessageContaining("Name one queue instead of a wildcard");
    }

    @Test
    void aPushdownOnlyQueryOverTheCeilingTellsTheOperatorToNarrowTheFrom() {
        planner = newPlanner(ceiling(10));
        oneQueue(1_000_000);

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE priority > 4");

        assertThat(plan.requiresScan()).isFalse();
        assertThatThrownBy(() -> planner.enforceCostCeiling(plan))
                .isInstanceOf(CostRefusedException.class)
                .hasMessageContaining("Narrow the FROM pattern, or add a LIMIT.");
    }

    @Test
    void theCostCeilingDoesNotApplyToTheIndex() {
        planner = newPlanner(ceiling(10));
        oneQueue(1_000_000);

        QueryPlan plan = plan("SELECT * FROM index.\"ORDER.IN\" WHERE body LIKE '%x%'");

        assertThat(plan.estimatedMessagesExamined()).isGreaterThan(10);
        planner.enforceCostCeiling(plan);
    }

    // ---- index plans -------------------------------------------------------

    @Test
    void anIndexPlanCarriesCoverageNoticesAndWhetherEveryTargetIsCaptured() {
        BrokerNodeEntity a = oneQueue(10);
        Notice gap = new Notice(Notice.Kind.INDEX_COVERAGE_GAP, "gap");
        when(coverage.check(any(), any(), any())).thenReturn(List.of(gap));
        when(coverage.isCaptured(any(), any())).thenReturn(true);

        QueryPlan index = plan("SELECT * FROM index.\"ORDER.IN\"");

        assertThat(index.notices()).contains(gap);
        assertThat(index.captured()).isTrue();
        assertThat(index.targets()).extracting(Target::nodeName).containsExactly("broker-1");

        QueryPlan broker = plan("SELECT * FROM broker.\"ORDER.IN\"");
        assertThat(broker.notices()).doesNotContain(gap);
        assertThat(broker.captured()).as("only an index plan can claim capture").isFalse();
        assertThat(a.getId()).isNotNull();
    }

    // ---- indexed observations against a live broker -------------------------

    @Test
    void everyPredicateShapeNamingAnIndexOnlyColumnIsRefusedAgainstABroker() {
        oneQueue(10);
        QueryAst.Term observed = column(Column.OBSERVED_AT);
        QueryAst.Literal text = new QueryAst.Literal.Str("x");

        List<QueryAst.Predicate> shapes = List.of(
                compare(observed, text),
                new QueryAst.Predicate.In(observed, List.of(text), false),
                new QueryAst.Predicate.IsNull(observed, false),
                new QueryAst.Predicate.Like(observed, "x%", null, false, false),
                new QueryAst.Predicate.Between(observed, text, text, false),
                new QueryAst.Predicate.Not(compare(observed, text)),
                new QueryAst.Predicate.And(List.of(compare(column(Column.QUEUE), text), compare(observed, text))),
                new QueryAst.Predicate.Or(List.of(compare(observed, text))),
                compare(new QueryAst.Term.CaseFold(observed, true), text));

        for (QueryAst.Predicate shape : shapes) {
            assertThatThrownBy(() -> planOf(Source.BROKER, shape))
                    .as(shape.toString())
                    .isInstanceOf(SqlSyntaxException.class)
                    .hasMessageContaining("'" + Column.OBSERVED_AT.sqlName() + "' describes an indexed observation");
        }
    }

    @Test
    void fullTextSearchAndMatchRankAreIndexOnlyAgainstABroker() {
        oneQueue(10);

        var match = new QueryAst.Predicate.Match("acme");
        var rank = compare(new QueryAst.Term.MatchRank(), new QueryAst.Literal.Num(1, true));

        assertThatThrownBy(() -> planOf(Source.BROKER, match))
                .isInstanceOf(SqlSyntaxException.class)
                .hasMessageContaining("MATCH(body, ...)");
        assertThatThrownBy(() -> planOf(Source.BROKER, rank))
                .isInstanceOf(SqlSyntaxException.class)
                .hasMessageContaining("match_rank");
    }

    @Test
    void anIndexOnlyProjectionColumnIsRefusedAgainstABroker() {
        oneQueue(10);

        var ast = astOf(Source.BROKER, List.of(Column.LAST_SEEN_AT), null, null);

        assertThatThrownBy(() -> planner.plan(CLUSTER, ast))
                .isInstanceOf(SqlSyntaxException.class)
                .hasMessageContaining(Column.LAST_SEEN_AT.sqlName());
    }

    @Test
    void columnsAndPropertiesTheBrokerCanReadPassTheIndexOnlyCheck() {
        oneQueue(10);
        QueryAst.Literal text = new QueryAst.Literal.Str("x");

        QueryPlan plan = planner.plan(
                CLUSTER,
                astOf(
                        Source.BROKER,
                        List.of(Column.QUEUE),
                        new QueryAst.Predicate.And(List.of(
                                compare(new QueryAst.Term.PropertyTerm("region"), text),
                                compare(new QueryAst.Term.JsonTerm("a.b"), text),
                                compare(column(Column.PRIORITY), new QueryAst.Literal.Num(4, true)))),
                        null));

        assertThat(plan.resolvedSource()).isEqualTo(Source.BROKER);
    }

    // ---- relative windows ----------------------------------------------------

    @Test
    void aRelativeWindowInAnyPredicateShapeIsNotedAgainstAnUnmeasuredClock() {
        BrokerNodeEntity a = oneQueue(10);
        when(clocks.offsetFor(a.getId())).thenReturn(Optional.empty());
        QueryAst.Literal now = new QueryAst.Literal.RelativeTime(Duration.ofHours(1));
        QueryAst.Literal text = new QueryAst.Literal.Str("x");
        QueryAst.Term timestamp = column(Column.TIMESTAMP);

        List<QueryAst.Predicate> windows = List.of(
                compare(timestamp, now),
                new QueryAst.Predicate.In(timestamp, List.of(text, now), false),
                new QueryAst.Predicate.Between(timestamp, now, text, false),
                new QueryAst.Predicate.Between(timestamp, text, now, false),
                new QueryAst.Predicate.Not(compare(timestamp, now)),
                new QueryAst.Predicate.And(List.of(
                        compare(column(Column.PRIORITY), new QueryAst.Literal.Num(4, true)), compare(timestamp, now))),
                new QueryAst.Predicate.Or(List.of(compare(timestamp, now))));
        for (QueryAst.Predicate window : windows) {
            assertThat(planOf(Source.BROKER, window).notices())
                    .as(window.toString())
                    .extracting(Notice::kind)
                    .contains(Notice.Kind.CLOCK_OFFSET_UNKNOWN);
        }

        List<QueryAst.Predicate> none = List.of(
                new QueryAst.Predicate.IsNull(timestamp, false),
                new QueryAst.Predicate.Like(column(Column.QUEUE), "a%", null, false, false),
                compare(timestamp, new QueryAst.Literal.Num(1, true)),
                new QueryAst.Predicate.In(timestamp, List.of(text), false),
                new QueryAst.Predicate.Between(timestamp, text, text, false));
        for (QueryAst.Predicate predicate : none) {
            assertThat(planOf(Source.BROKER, predicate).notices())
                    .as(predicate.toString())
                    .extracting(Notice::kind)
                    .doesNotContain(Notice.Kind.CLOCK_OFFSET_UNKNOWN);
        }
    }

    @Test
    void theUnmeasuredNodesAreNamedOnceEach() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        BrokerNodeEntity b = node("broker-2", "node-b");
        given(List.of(a, b), List.of(snapshot(a, "ORDER.IN", 1), snapshot(b, "ORDER.IN", 1)));
        when(clocks.offsetFor(any())).thenReturn(Optional.empty());

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE timestamp > now() - interval '1 hour'");

        assertThat(plan.notices())
                .filteredOn(n -> n.kind() == Notice.Kind.CLOCK_OFFSET_UNKNOWN)
                .singleElement()
                .satisfies(n -> assertThat(n.detail()).contains("broker-1, broker-2"));
        assertThat(plan.targets())
                .allSatisfy(t -> assertThat(t.clockOffsetKnown()).isFalse());
        assertThat(plan.targets().getFirst().brokerNow()).isEqualTo(NOW);
    }

    // ---- targets, limits and selectors ----------------------------------------

    @Test
    void aCappedTargetListSaysHowManyMatchedAndHowManyWereRead() {
        BrokerNodeEntity a = node("broker-1", "node-a");
        List<QueueSnapshot> many = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            many.add(snapshot(a, "Q." + i, 1));
        }
        given(List.of(a), many);
        planner = newPlanner(new SqlProperties(
                2, 50_000L, 2_000, 250_000L, Duration.ofSeconds(30), 2, Duration.ofSeconds(5), Duration.ofSeconds(1)));

        QueryPlan plan = plan("SELECT * FROM \"Q.#\"");

        assertThat(plan.targets()).extracting(Target::queueName).containsExactly("Q.0", "Q.1");
        assertThat(plan.notices()).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(Notice.Kind.TARGET_CAPPED);
            assertThat(n.detail()).contains("5 targets").contains("first 2");
        });
    }

    @Test
    void aSnapshotOnANodeTheDirectoryDoesNotKnowIsNotATarget() {
        BrokerNodeEntity known = node("broker-1", "node-a");
        BrokerNodeEntity unknown = node("ghost", "node-z");
        given(List.of(known), List.of(snapshot(known, "ORDER.IN", 1), snapshot(unknown, "ORDER.IN", 1)));

        assertThat(plan("SELECT * FROM \"ORDER.IN\"").targets())
                .extracting(Target::nodeName)
                .containsExactly("broker-1");
    }

    @Test
    void nodesWithoutAnArtemisNodeIdAreNeverMergedAsAPair() {
        BrokerNodeEntity a = node("broker-1", null);
        BrokerNodeEntity b = node("broker-2", null);
        given(List.of(a, b), List.of(snapshot(a, "ORDER.IN", 1), snapshot(b, "ORDER.IN", 1)));

        assertThat(plan("SELECT * FROM \"ORDER.IN\"").targets()).hasSize(2);
    }

    @Test
    void theEffectiveLimitIsTheQueriesLimitCappedAtMaxRows() {
        oneQueue(10);

        assertThat(planner.plan(CLUSTER, astOf(Source.BROKER, List.of(), null, 10))
                        .effectiveLimit())
                .isEqualTo(10);
        assertThat(planner.plan(CLUSTER, astOf(Source.BROKER, List.of(), null, 9_999))
                        .effectiveLimit())
                .isEqualTo(2_000);
        assertThat(planner.plan(CLUSTER, astOf(Source.BROKER, List.of(), null, null))
                        .effectiveLimit())
                .isEqualTo(2_000);
    }

    @Test
    void aPlanWithNoTargetsStillRendersItsSelectorAgainstRahdarsClock() {
        given(List.of(), List.of());
        when(locator.locate(any(), any())).thenReturn(List.of());

        QueryPlan plan = plan("SELECT * FROM \"NOPE\" WHERE priority > 4");

        assertThat(plan.targets()).isEmpty();
        assertThat(plan.selector()).isEqualTo("JMSPriority > 4");
        assertThat(plan.estimatedMessagesExamined()).isZero();
        assertThat(plan.pushedDown()).containsExactly("priority > 4");
        assertThat(plan.scanned()).isEmpty();
    }

    @Test
    void aWhereClauseThatIsAConjunctionIsDescribedPartByPart() {
        oneQueue(10);

        QueryPlan plan = plan("SELECT * FROM \"ORDER.IN\" WHERE priority > 4 AND durable = true AND body LIKE '%x%'");

        assertThat(plan.pushedDown()).containsExactly("priority > 4", "durable = true");
        assertThat(plan.scanned()).containsExactly("body LIKE '%x%'");
    }

    @Test
    void theExecutorHelpersExposeTheSameSplitAndPerNodeSelectors() {
        QueryAst ast = parser.parse("SELECT * FROM \"ORDER.IN\" WHERE priority > 4 AND body LIKE '%x%'");

        PredicateSplitter.Split split = planner.splitOf(ast);

        assertThat(split.hasPushdown()).isTrue();
        assertThat(split.requiresScan()).isTrue();
        assertThat(planner.selectorFor(split.pushdown(), NOW)).isEqualTo("JMSPriority > 4");
        assertThat(planner.selectorFor(null, NOW)).isNull();
        assertThat(QueryPlanner.lower("AbC")).isEqualTo("abc");
        assertThat(QueryPlanner.lower(null)).isNull();
    }

    @Test
    void theInjectablePlannerUsesTheSystemClock() {
        QueryPlanner injected =
                new QueryPlanner(snapshots, locator, nodes, splitter, renderer, clocks, defaults(), coverage);
        BrokerNodeEntity a = oneQueue(10);
        Instant before = Instant.now();

        QueryPlan plan = injected.plan(CLUSTER, parser.parse("SELECT * FROM \"ORDER.IN\""));

        assertThat(plan.targets()).singleElement().satisfies(t -> {
            assertThat(t.nodeId()).isEqualTo(a.getId());
            assertThat(t.brokerNow()).isAfterOrEqualTo(before.minusSeconds(1));
        });
    }
}
