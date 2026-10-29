package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Notice;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Target;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageCaptureNodeEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageCaptureNodeRepository;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The index's failure mode is a quiet one: a short list that reads as the answer.
 * Every case here is a way the index cannot speak for what was asked, and the
 * assertion is that it says so.
 */
class MessageIndexCoverageTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final SqlQueryParser parser = new SqlQueryParser();
    private MessageIndexSubscriptionRepository subscriptions;
    private MessageCaptureNodeRepository captureNodes;
    private QueueSnapshots snapshots;
    private MessageIndexCoverage coverage;

    @BeforeEach
    void setUp() {
        subscriptions = mock(MessageIndexSubscriptionRepository.class);
        captureNodes = mock(MessageCaptureNodeRepository.class);
        snapshots = mock(QueueSnapshots.class);
        coverage = new MessageIndexCoverage(subscriptions, captureNodes, snapshots);
    }

    private MessageIndexSubscriptionEntity subscription(String pattern, Instant captureFrom, int retentionDays) {
        MessageIndexSubscriptionEntity entity = new MessageIndexSubscriptionEntity();
        entity.setId(UUID.randomUUID());
        entity.setClusterId(CLUSTER);
        entity.setSubscriptionPattern(pattern);
        entity.setCaptureFrom(captureFrom);
        entity.setRetentionDays(retentionDays);
        entity.setIntervalMs(5000);
        entity.setCreatedAt(Instant.now());
        entity.setEnabled(true);
        return entity;
    }

    private Target target(String queueName) {
        return new Target(UUID.randomUUID(), "primary", queueName, queueName, "ANYCAST", 10L, Instant.now(), true);
    }

    private QueryAst ast(String sql) {
        return parser.parse(sql);
    }

    @Test
    void namesTheQueueTheIndexCannotAnswerFor() {
        when(subscriptions.findByClusterId(CLUSTER))
                .thenReturn(List.of(subscription("ORDER.IN", Instant.now().minus(Duration.ofDays(3)), 7)));

        List<Notice> notices = coverage.check(
                CLUSTER, ast("SELECT * FROM index.\"ORDER.*\""), List.of(target("ORDER.IN"), target("ORDER.RETRY")));

        // Not "no results for ORDER.RETRY": the queue is named, because an operator
        // reading a wildcard result has no other way to learn one target was silent.
        assertThat(notices)
                .anySatisfy(notice -> assertThat(notice.detail()).contains("ORDER.RETRY"))
                .noneSatisfy(notice -> assertThat(notice.detail()).contains("ORDER.IN —"));
    }

    @Test
    void warnsWhenTheWindowReachesBeforeCaptureBegan() {
        when(subscriptions.findByClusterId(CLUSTER))
                .thenReturn(List.of(subscription("ORDER.IN", Instant.now().minus(Duration.ofHours(2)), 30)));

        List<Notice> notices = coverage.check(
                CLUSTER,
                ast("SELECT * FROM index.\"ORDER.IN\" WHERE timestamp > now() - interval '2 days'"),
                List.of(target("ORDER.IN")));

        assertThat(notices)
                .singleElement()
                .satisfies(notice -> assertThat(notice.kind()).isEqualTo(Notice.Kind.INDEX_COVERAGE_GAP));
    }

    @Test
    void warnsWhenTheWindowReachesPastRetention() {
        // Capturing for a year, but only keeping two days of it — the gap is retention,
        // not capture, and the operator is told either way.
        when(subscriptions.findByClusterId(CLUSTER))
                .thenReturn(List.of(subscription("ORDER.IN", Instant.now().minus(Duration.ofDays(365)), 2)));

        List<Notice> notices = coverage.check(
                CLUSTER,
                ast("SELECT * FROM index.\"ORDER.IN\" WHERE timestamp > now() - interval '10 days'"),
                List.of(target("ORDER.IN")));

        assertThat(notices).hasSize(1);
    }

    @Test
    void saysNothingWhenTheWindowIsFullyCovered() {
        when(subscriptions.findByClusterId(CLUSTER))
                .thenReturn(List.of(subscription("ORDER.#", Instant.now().minus(Duration.ofDays(30)), 30)));

        List<Notice> notices = coverage.check(
                CLUSTER,
                ast("SELECT * FROM index.\"ORDER.IN\" WHERE timestamp > now() - interval '2 hours'"),
                List.of(target("ORDER.IN")));

        assertThat(notices).isEmpty();
    }

    @Test
    void aDisabledSubscriptionCoversNothing() {
        MessageIndexSubscriptionEntity disabled =
                subscription("ORDER.IN", Instant.now().minus(Duration.ofDays(3)), 7);
        disabled.setEnabled(false);
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of(disabled));

        assertThat(coverage.isCaptured(CLUSTER, List.of(target("ORDER.IN")))).isFalse();
        assertThat(coverage.check(CLUSTER, ast("SELECT * FROM index.\"ORDER.IN\""), List.of(target("ORDER.IN"))))
                .isNotEmpty();
    }

    /**
     * Capture never backfills: it copies what is routed from {@code captureFrom} on. Measured
     * on the dev stack, a queue held 3 messages, a CAPTURE subscription was created, and the
     * plain query answered 0 rows from the index with nothing said. A query with no time window
     * asks for everything, so it is told where the index begins.
     */
    @Test
    void aQueryWithNoWindowIsToldTheIndexHoldsNothingBeforeCaptureBegan() {
        Instant began = Instant.now().minus(Duration.ofMinutes(5));
        MessageIndexSubscriptionEntity captured = subscription("ORDER.IN", began, 7);
        captured.setMode(CaptureMode.CAPTURE);
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of(captured));

        List<Notice> notices = coverage.check(CLUSTER, ast("SELECT * FROM \"ORDER.IN\""), List.of(target("ORDER.IN")));

        assertThat(notices).anySatisfy(notice -> {
            assertThat(notice.kind()).isEqualTo(Notice.Kind.INDEX_COVERAGE_GAP);
            assertThat(notice.detail()).contains(began.toString()).contains("already on the queue");
        });
    }

    /**
     * A CAPTURE subscription is not capture until its tap is active on the node: one that is
     * pending or refused copies nothing, so it does not cover the queue there.
     */
    @Test
    void aCaptureSubscriptionCoversATargetOnlyWhereItsTapIsActive() {
        MessageIndexSubscriptionEntity sampled = subscription("ORDER.IN", Instant.now(), 7);
        sampled.setMode(CaptureMode.SAMPLE);
        MessageIndexSubscriptionEntity captured = subscription("PAY.IN", Instant.now(), 7);
        captured.setMode(CaptureMode.CAPTURE);
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of(sampled, captured));
        Target pay = target("PAY.IN");

        when(captureNodes.findBySubscriptionIdAndNodeId(captured.getId(), pay.nodeId()))
                .thenReturn(Optional.of(node(captured, pay, CaptureState.PENDING)));
        assertThat(coverage.isCaptured(CLUSTER, List.of(pay))).isFalse();

        when(captureNodes.findBySubscriptionIdAndNodeId(captured.getId(), pay.nodeId()))
                .thenReturn(Optional.of(node(captured, pay, CaptureState.ACTIVE)));
        assertThat(coverage.isCaptured(CLUSTER, List.of(pay))).isTrue();
        assertThat(coverage.isCaptured(CLUSTER, List.of(target("ORDER.IN")))).isFalse();
    }

    private static QueueSnapshot snapshot(String queue, String address) {
        return new QueueSnapshot(
                CLUSTER,
                UUID.randomUUID(),
                queue,
                address,
                "MULTICAST",
                true,
                false,
                Instant.now(),
                0L,
                0,
                0,
                0,
                0,
                0,
                0,
                "artemis.queue",
                Map.of());
    }

    private MessageIndexSubscriptionEntity capturing(String pattern) {
        MessageIndexSubscriptionEntity entity =
                subscription(pattern, Instant.now().minus(Duration.ofDays(1)), 7);
        entity.setMode(CaptureMode.CAPTURE);
        return entity;
    }

    private static Target onAddress(String queue, String address, String nodeName) {
        return new Target(UUID.randomUUID(), nodeName, queue, address, "MULTICAST", 10L, Instant.now(), true);
    }

    @Test
    void noTargetsIsNeverCaptured() {
        assertThat(coverage.isCaptured(CLUSTER, List.of())).isFalse();
    }

    @Test
    void aMissingCaptureNodeRowMeansNotCaptured() {
        MessageIndexSubscriptionEntity captured = capturing("ORDER.IN");
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of(captured));
        when(captureNodes.findBySubscriptionIdAndNodeId(any(), any())).thenReturn(Optional.empty());

        assertThat(coverage.isCaptured(CLUSTER, List.of(target("ORDER.IN")))).isFalse();
    }

    @Test
    void pluralUncoveredQueuesAreAllNamed() {
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of(subscription("OTHER", Instant.now(), 7)));

        List<Notice> notices = coverage.check(
                CLUSTER,
                ast("SELECT * FROM index.\"A.*\""),
                List.of(target("A.ONE"), target("A.TWO"), target("A.ONE")));

        assertThat(notices)
                .anySatisfy(notice ->
                        assertThat(notice.detail()).contains("A.ONE, A.TWO").contains("captures them."));
    }

    @Test
    void noSubscriptionAtAllOnlyReportsTheUncoveredQueue() {
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of());

        List<Notice> notices = coverage.check(CLUSTER, ast("SELECT * FROM \"Q\""), List.of(target("Q")));

        assertThat(notices)
                .singleElement()
                .satisfies(notice ->
                        assertThat(notice.detail()).contains("nothing for Q").endsWith("captures it."));
    }

    @Test
    void aNodeWhereCaptureIsNotActiveIsNamed() {
        MessageIndexSubscriptionEntity captured = capturing("ORDER.IN");
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of(captured));
        Target active = onAddress("ORDER.IN", "ORDER.IN", "node-a");
        Target refused = onAddress("ORDER.IN", "ORDER.IN", "node-b");
        Target refusedAgain = onAddress("ORDER.IN", "ORDER.IN", "node-b");
        when(captureNodes.findBySubscriptionIdAndNodeId(captured.getId(), active.nodeId()))
                .thenReturn(Optional.of(node(captured, active, CaptureState.ACTIVE)));
        when(captureNodes.findBySubscriptionIdAndNodeId(captured.getId(), refused.nodeId()))
                .thenReturn(Optional.of(node(captured, refused, CaptureState.PENDING)));
        when(captureNodes.findBySubscriptionIdAndNodeId(captured.getId(), refusedAgain.nodeId()))
                .thenReturn(Optional.empty());

        List<Notice> notices =
                coverage.check(CLUSTER, ast("SELECT * FROM \"ORDER.IN\""), List.of(active, refused, refusedAgain));

        assertThat(notices).anySatisfy(notice -> {
            assertThat(notice.kind()).isEqualTo(Notice.Kind.CAPTURE_NODE_GAP);
            assertThat(notice.detail()).contains("Not capturing on node-b").doesNotContain("node-a");
        });
    }

    @Test
    void anAddressWithSeveralBoundQueuesIsReportedAsScoped() {
        MessageIndexSubscriptionEntity captured = capturing("SUB.*");
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of(captured));
        Target one = onAddress("SUB.ONE", "topic.a", "node-a");
        Target two = onAddress("SUB.TWO", "topic.b", "node-a");
        Target lone = onAddress("SUB.LONE", "topic.c", "node-a");
        Target noAddress = onAddress("SUB.NONE", null, "node-a");
        for (Target t : List.of(one, two, lone, noAddress)) {
            when(captureNodes.findBySubscriptionIdAndNodeId(captured.getId(), t.nodeId()))
                    .thenReturn(Optional.of(node(captured, t, CaptureState.ACTIVE)));
        }
        when(snapshots.forCluster(CLUSTER))
                .thenReturn(List.of(
                        snapshot("SUB.ONE", "topic.a"),
                        snapshot("SUB.ONE.B", "topic.a"),
                        snapshot("SUB.TWO", "topic.b"),
                        snapshot("SUB.TWO.B", "topic.b"),
                        snapshot("SUB.TWO.B", "topic.b"),
                        snapshot("SUB.LONE", "topic.c")));

        List<Notice> notices =
                coverage.check(CLUSTER, ast("SELECT * FROM \"SUB.*\""), List.of(one, two, lone, noAddress));

        assertThat(notices)
                .filteredOn(n -> n.kind() == Notice.Kind.DESTINATION_SCOPED_CAPTURE)
                .singleElement()
                .satisfies(notice ->
                        assertThat(notice.detail()).isEqualTo("topic.a, topic.b have more than one queue bound."));
        assertThat(notices).noneMatch(n -> n.kind() == Notice.Kind.CAPTURE_NODE_GAP);
    }

    @Test
    void oneFannedOutAddressReadsInTheSingular() {
        MessageIndexSubscriptionEntity captured = capturing("SUB.*");
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of(captured));
        Target one = onAddress("SUB.ONE", "topic.a", "node-a");
        when(captureNodes.findBySubscriptionIdAndNodeId(captured.getId(), one.nodeId()))
                .thenReturn(Optional.of(node(captured, one, CaptureState.ACTIVE)));
        when(snapshots.forCluster(CLUSTER))
                .thenReturn(List.of(snapshot("SUB.ONE", "topic.a"), snapshot("SUB.ONE.B", "topic.a")));

        assertThat(coverage.check(CLUSTER, ast("SELECT * FROM \"SUB.*\""), List.of(one)))
                .filteredOn(n -> n.kind() == Notice.Kind.DESTINATION_SCOPED_CAPTURE)
                .singleElement()
                .satisfies(notice -> assertThat(notice.detail()).isEqualTo("topic.a has more than one queue bound."));
    }

    /** The widest relative window anywhere in the predicate decides how far back the query reaches. */
    @Test
    void theWidestTimeWindowInAnyPredicateShapeIsWhatIsChecked() {
        when(subscriptions.findByClusterId(CLUSTER))
                .thenReturn(List.of(subscription("Q", Instant.now().minus(Duration.ofDays(1)), 30)));
        List<Target> targets = List.of(target("Q"));

        // Each is a window of 2 days, past the 1 day capture began: a gap, however the window is spelled.
        for (String where : List.of(
                "timestamp > now() - interval '2 days'",
                "priority > 1 AND timestamp > now() - interval '2 days'",
                "priority > 1 OR timestamp > now() - interval '2 days'",
                "NOT timestamp < now() - interval '2 days'",
                "timestamp BETWEEN now() - interval '2 days' AND now()")) {
            assertThat(coverage.check(CLUSTER, ast("SELECT * FROM index.\"Q\" WHERE " + where), targets))
                    .as(where)
                    .singleElement()
                    .satisfies(n -> assertThat(n.detail()).contains("reaches further back"));
        }
    }

    @Test
    void predicatesWithoutARelativeTimeAreAWindowlessQuery() {
        when(subscriptions.findByClusterId(CLUSTER))
                .thenReturn(List.of(subscription("Q", Instant.now().minus(Duration.ofDays(1)), 30)));
        List<Target> targets = List.of(target("Q"));

        for (String where : List.of(
                "priority > 1",
                "priority IN (1, 2)",
                "correlationId IS NULL",
                "correlationId LIKE 'a%'",
                "MATCH (body) AGAINST ('word')",
                "priority BETWEEN 1 AND 2")) {
            List<Notice> notices = coverage.check(CLUSTER, ast("SELECT * FROM index.\"Q\" WHERE " + where), targets);
            assertThat(notices)
                    .as(where)
                    .singleElement()
                    .satisfies(n -> assertThat(n.detail()).contains("holds nothing routed before"));
        }
    }

    private static MessageCaptureNodeEntity node(
            MessageIndexSubscriptionEntity subscription, Target target, CaptureState state) {
        MessageCaptureNodeEntity node = new MessageCaptureNodeEntity();
        node.setSubscriptionId(subscription.getId());
        node.setNodeId(target.nodeId());
        node.setCaptureState(state);
        return node;
    }
}
