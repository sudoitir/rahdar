package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.NodeOutcome;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.Designation;
import io.github.sudoitir.openbroker.model.Membership;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Serving;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A RabbitMQ cluster's verdict from its nodes' conditions and its queues, on the estate's shared scale (ADR-0215). */
class RabbitMqHealthTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Optional<String> REACHABLE = Optional.empty();

    private static Condition condition(String type, ConditionStatus status, String reason, String message) {
        return new Condition(type, status, reason, message, Optional.empty());
    }

    private static NodeDescription node(String name, Condition... conditions) {
        List<Condition> all = new ArrayList<>(List.of(
                condition(ConditionTypes.SERVING, ConditionStatus.TRUE, "", ""),
                condition(ConditionTypes.FULL, ConditionStatus.FALSE, "", ""),
                condition(ConditionTypes.OUT_OF_SYNC, ConditionStatus.FALSE, "", "")));
        List<String> replaced =
                List.of(conditions).stream().map(Condition::type).toList();
        all.removeIf(c -> replaced.contains(c.type()));
        all.addAll(List.of(conditions));
        return new NodeDescription(
                NodeId.of(name),
                name,
                new Membership(Optional.of("rabbit"), Designation.NONE, Serving.ACTIVE, Optional.empty()),
                Set.of(NodeFunction.BROKER),
                List.of(),
                Optional.empty(),
                all,
                Attributes.empty(),
                Optional.empty());
    }

    private static Partial<List<NodeDescription>> described(NodeDescription... nodes) {
        return Partial.complete(
                List.of(nodes),
                List.of(nodes).stream().map(NodeDescription::node).toList());
    }

    private static RabbitMqHealth.Queues queues(
            List<String> behind, List<String> lost, List<String> leaderless, int mirrors) {
        return new RabbitMqHealth.Queues(behind, lost, leaderless, mirrors);
    }

    private static final RabbitMqHealth.Queues CLEAR = queues(List.of(), List.of(), List.of(), 0);

    @Test
    void healthyNodesAndQueuesAreOkAndEveryRunningNodeServes() {
        ClusterHealth verdict =
                RabbitMqHealth.judge(CLUSTER, described(node("rabbit@a"), node("rabbit@b")), CLEAR, REACHABLE);

        assertThat(verdict.level()).isEqualTo(Level.OK);
        assertThat(verdict.liveEndpointNames()).containsExactly("rabbit@a", "rabbit@b");
        assertThat(verdict.notes()).isEmpty();
        assertThat(verdict.replicationBehind()).isFalse();
    }

    @Test
    void aNodeThatIsNotRunningDegradesTheClusterAndDoesNotServe() {
        ClusterHealth verdict = RabbitMqHealth.judge(
                CLUSTER,
                described(
                        node("rabbit@a"),
                        node(
                                "rabbit@b",
                                condition(ConditionTypes.SERVING, ConditionStatus.FALSE, "not_running", "stopped"))),
                CLEAR,
                REACHABLE);

        assertThat(verdict.level()).isEqualTo(Level.DEGRADED);
        assertThat(verdict.liveEndpointNames()).containsExactly("rabbit@a");
        assertThat(verdict.notes()).containsExactly("Node rabbit@b is not running.");
    }

    @Test
    void aNetworkPartitionIsCritical() {
        ClusterHealth verdict = RabbitMqHealth.judge(
                CLUSTER,
                described(node(
                        "rabbit@a",
                        condition(
                                ConditionTypes.OUT_OF_SYNC,
                                ConditionStatus.TRUE,
                                "network_partition",
                                "The node is partitioned from rabbit@b."))),
                CLEAR,
                REACHABLE);

        assertThat(verdict.level()).isEqualTo(Level.CRITICAL);
        assertThat(verdict.notes()).anyMatch(n -> n.contains("network partition") && n.contains("rabbit@b"));
    }

    @Test
    void aMemoryOrDiskAlarmIsCriticalAndNamesWhichOne() {
        ClusterHealth memory = RabbitMqHealth.judge(
                CLUSTER,
                described(node("rabbit@a", condition(ConditionTypes.FULL, ConditionStatus.TRUE, "mem_alarm", "m"))),
                CLEAR,
                REACHABLE);
        ClusterHealth disk = RabbitMqHealth.judge(
                CLUSTER,
                described(
                        node("rabbit@a", condition(ConditionTypes.FULL, ConditionStatus.TRUE, "disk_free_alarm", "d"))),
                CLEAR,
                REACHABLE);

        assertThat(memory.level()).isEqualTo(Level.CRITICAL);
        assertThat(memory.notes()).anyMatch(n -> n.contains("memory alarm") && n.contains("blocked"));
        assertThat(disk.level()).isEqualTo(Level.CRITICAL);
        assertThat(disk.notes()).anyMatch(n -> n.contains("disk space alarm"));
    }

    @Test
    void queuesThatLostTheirMajorityAreCriticalAndNameAtMostFive() {
        List<String> lost = List.of("/a", "/b", "/c", "/d", "/e", "/f", "/g");

        ClusterHealth verdict = RabbitMqHealth.judge(
                CLUSTER, described(node("rabbit@a")), queues(List.of(), lost, List.of(), 0), REACHABLE);
        ClusterHealth one = RabbitMqHealth.judge(
                CLUSTER, described(node("rabbit@a")), queues(List.of(), List.of("/q"), List.of(), 0), REACHABLE);

        assertThat(verdict.level()).isEqualTo(Level.CRITICAL);
        assertThat(verdict.notes())
                .containsExactly("7 queues (/a, /b, /c, /d, /e and 2 more) have lost the majority of their members,"
                        + " so they can neither be read nor written.");
        assertThat(one.notes())
                .containsExactly(
                        "Queue /q has lost the majority of its members, so it can neither be read nor" + " written.");
    }

    @Test
    void underReplicatedLeaderlessAndUnsynchronisedQueuesDegradeAndMarkReplicationBehind() {
        ClusterHealth behind = RabbitMqHealth.judge(
                CLUSTER, described(node("rabbit@a")), queues(List.of("/q"), List.of(), List.of(), 0), REACHABLE);
        ClusterHealth leaderless = RabbitMqHealth.judge(
                CLUSTER, described(node("rabbit@a")), queues(List.of(), List.of(), List.of("/p", "/q"), 0), REACHABLE);
        ClusterHealth mirrors = RabbitMqHealth.judge(
                CLUSTER, described(node("rabbit@a")), queues(List.of(), List.of(), List.of(), 3), REACHABLE);

        assertThat(behind.level()).isEqualTo(Level.DEGRADED);
        assertThat(behind.replicationBehind()).isTrue();
        assertThat(behind.notes()).containsExactly("Queue /q is under-replicated: a member is offline.");
        assertThat(leaderless.level()).isEqualTo(Level.DEGRADED);
        assertThat(leaderless.notes()).anyMatch(n -> n.startsWith("2 queues (/p, /q) have no leader online"));
        assertThat(mirrors.level()).isEqualTo(Level.DEGRADED);
        assertThat(mirrors.replicationBehind()).isTrue();
        assertThat(mirrors.notes()).containsExactly("3 classic queues have mirrors that are not synchronised.");
    }

    @Test
    void manyUnderReplicatedQueuesAreCountedTogether() {
        ClusterHealth verdict = RabbitMqHealth.judge(
                CLUSTER, described(node("rabbit@a")), queues(List.of("/a", "/b"), List.of(), List.of(), 0), REACHABLE);

        assertThat(verdict.notes())
                .containsExactly("2 queues (/a, /b) are under-replicated: a member of each is offline.");
    }

    @Test
    void queuesNotReadYetAreANoteAndNeverOk() {
        ClusterHealth verdict = RabbitMqHealth.judge(CLUSTER, described(node("rabbit@a")), null, REACHABLE);

        assertThat(verdict.level()).isEqualTo(Level.OK);
        assertThat(verdict.notes())
                .containsExactly("The queues have not been read yet, so lost replicas are not" + " ruled out.");
    }

    @Test
    void anUnreachableDataPlaneWhileManagementAnswersIsAWarningWithTheReason() {
        ClusterHealth verdict =
                RabbitMqHealth.judge(CLUSTER, described(node("rabbit@a")), CLEAR, Optional.of("mq:5672 REFUSED"));

        assertThat(verdict.level()).isEqualTo(Level.DEGRADED);
        assertThat(verdict.notes())
                .containsExactly("The management API answers but no AMQP endpoint accepts a connection (mq:5672"
                        + " REFUSED), so clients cannot publish or consume.");
    }

    @Test
    void aNodeThatDidNotAnswerDegradesAndNoNodeAtAllIsCritical() {
        BrokerException failure = mock(BrokerException.class);
        when(failure.getMessage()).thenReturn("timed out");
        NodeDescription a = node("rabbit@a");
        Partial<List<NodeDescription>> partial = new Partial<>(
                List.of(a),
                Map.of(NodeId.of("rabbit@a"), NodeOutcome.APPLIED, NodeId.of("rabbit@b"), NodeOutcome.FAILED),
                Map.of(NodeId.of("rabbit@b"), failure));

        ClusterHealth some = RabbitMqHealth.judge(CLUSTER, partial, CLEAR, REACHABLE);
        ClusterHealth none = RabbitMqHealth.judge(CLUSTER, described(), CLEAR, REACHABLE);

        assertThat(some.level()).isEqualTo(Level.DEGRADED);
        assertThat(some.notes()).containsExactly("Node rabbit@b is unreachable: timed out");
        assertThat(none.level()).isEqualTo(Level.CRITICAL);
        assertThat(none.notes()).contains("No node answered.");
    }

    @Test
    void anUnreachableOrRenamedClusterIsCritical() {
        assertThat(RabbitMqHealth.unreachable(CLUSTER, "refused").level()).isEqualTo(Level.CRITICAL);
        assertThat(RabbitMqHealth.unreachable(CLUSTER, "refused").notes())
                .containsExactly("No node is reachable: refused");
        assertThat(RabbitMqHealth.replaced(CLUSTER, "renamed").notes()).containsExactly("renamed");
    }

    // ---- what a request reads --------------------------------------------------------------------------------

    private final RabbitMqHealthStore store = mock(RabbitMqHealthStore.class);
    private Instant now = NOW;
    private final RabbitMqHealth health = new RabbitMqHealth(store, new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    @Test
    void aRequestReadsWhatTheScrapeWroteAndNeverANode() {
        ClusterHealth written =
                RabbitMqHealth.verdict(CLUSTER, Level.DEGRADED, List.of("rabbit@a"), List.of("note"), true);
        when(store.read(CLUSTER)).thenReturn(Optional.of(new RabbitMqHealthStore.Stored(written, NOW)));

        assertThat(health.evaluate(CLUSTER, List.of(), Set.of())).isEqualTo(written);
        assertThat(health.system()).isEqualTo("rabbitmq");
    }

    @Test
    void noVerdictYetAndAStaleOneAreUnknownWithTheReason() {
        when(store.read(CLUSTER)).thenReturn(Optional.empty());
        assertThat(health.evaluate(CLUSTER, List.of(), Set.of()).level()).isEqualTo(Level.UNKNOWN);

        ClusterHealth written = RabbitMqHealth.verdict(CLUSTER, Level.OK, List.of("rabbit@a"), List.of(), false);
        when(store.read(CLUSTER)).thenReturn(Optional.of(new RabbitMqHealthStore.Stored(written, NOW)));
        now = NOW.plus(RabbitMqHealth.STALE).plus(Duration.ofSeconds(1));

        ClusterHealth stale = health.evaluate(CLUSTER, List.of(), Set.of());
        assertThat(stale.level()).isEqualTo(Level.UNKNOWN);
        assertThat(stale.notes()).anyMatch(n -> n.contains("last read this cluster at"));
    }
}
