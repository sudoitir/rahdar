package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.NodeOutcome;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.kafka.KafkaOptions;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A Kafka cluster's verdict from its brokers' conditions, on the estate's shared scale (ADR-0215). */
class KafkaHealthTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private static Condition condition(String type, ConditionStatus status, String reason) {
        return new Condition(type, status, reason, reason, Optional.empty());
    }

    private static NodeDescription broker(String id, boolean voter, boolean leader, Condition... conditions) {
        Attributes.Builder attributes = Attributes.builder();
        if (voter) {
            attributes.putRaw("kafka.quorum_voter", true).putRaw("kafka.quorum_leader", leader);
        }
        return new NodeDescription(
                NodeId.of(id),
                id,
                new Membership(Optional.empty(), Designation.NONE, Serving.ACTIVE, Optional.empty()),
                Set.of(NodeFunction.BROKER),
                List.of(Endpoint.of(Plane.DATA, KafkaOptions.PROTOCOL, "k" + id + ":9092")),
                Optional.empty(),
                List.of(conditions),
                attributes.build(),
                Optional.empty());
    }

    private static Condition serving(boolean yes) {
        return condition(
                ConditionTypes.SERVING, yes ? ConditionStatus.TRUE : ConditionStatus.FALSE, yes ? "" : "FENCED");
    }

    private static Condition replicated(boolean behind) {
        return condition(
                ConditionTypes.UNDER_REPLICATED,
                behind ? ConditionStatus.TRUE : ConditionStatus.FALSE,
                behind ? "OUT_OF_SYNC_REPLICAS" : "");
    }

    private static ClusterHealth judge(NodeDescription... nodes) {
        return KafkaHealth.judge(
                CLUSTER,
                Partial.complete(
                        List.of(nodes),
                        java.util.Arrays.stream(nodes)
                                .map(NodeDescription::node)
                                .toList()),
                OptionalLong.of(0));
    }

    @Test
    void healthyBrokersAndAQuorumLeaderAreOk() {
        ClusterHealth health = judge(
                broker("1", true, true, serving(true), replicated(false)), broker("2", true, false, serving(true)));

        assertThat(health.level()).isEqualTo(Level.OK);
        assertThat(health.liveEndpointNames()).containsExactly("broker-1", "broker-2");
        assertThat(health.notes()).isEmpty();
    }

    @Test
    void aZooKeeperClusterWithEveryBrokerServingIsOkWithANoteAboutItsQuorum() {
        ClusterHealth health = judge(broker("1", false, false, serving(true)));

        assertThat(health.level()).isEqualTo(Level.OK);
        assertThat(health.notes())
                .singleElement()
                .asString()
                .contains("quorum cannot be read")
                .contains("not counted as healthy");
    }

    @Test
    void aFencedBrokerDegradesTheClusterAndIsNotLive() {
        ClusterHealth health = judge(broker("1", true, true, serving(true)), broker("2", true, false, serving(false)));

        assertThat(health.level()).isEqualTo(Level.DEGRADED);
        assertThat(health.liveEndpointNames()).containsExactly("broker-1");
        assertThat(health.notes()).anyMatch(n -> n.contains("broker-2 is not serving"));
    }

    @Test
    void underReplicatedPartitionsDegradeAndMarkReplicationBehind() {
        ClusterHealth health = judge(broker("1", true, true, serving(true), replicated(true)));

        assertThat(health.level()).isEqualTo(Level.DEGRADED);
        assertThat(health.replicationBehind()).isTrue();
    }

    @Test
    void aLaggingVoterDegrades() {
        ClusterHealth health = judge(
                broker("1", true, true, serving(true)),
                broker(
                        "2",
                        true,
                        false,
                        serving(true),
                        condition(ConditionTypes.OUT_OF_SYNC, ConditionStatus.TRUE, "VOTER_BEHIND")));

        assertThat(health.level()).isEqualTo(Level.DEGRADED);
        assertThat(health.notes()).anyMatch(n -> n.contains("lags the quorum leader"));
    }

    @Test
    void anUnreachableNodeDegradesAndIsNamed() {
        NodeDescription one = broker("1", true, true, serving(true));
        BrokerException down = new BrokerException(
                Status.UNAVAILABLE,
                new ErrorInfo(ErrorReasons.UNREACHABLE, ErrorReasons.DOMAIN, Map.of()),
                Dispatch.NOT_SENT,
                null,
                "refused");
        ClusterHealth health = KafkaHealth.judge(
                CLUSTER,
                new Partial<>(
                        List.of(one),
                        Map.of(one.node(), NodeOutcome.APPLIED, NodeId.of("2"), NodeOutcome.FAILED),
                        Map.of(NodeId.of("2"), down)),
                OptionalLong.of(0));

        assertThat(health.level()).isEqualTo(Level.DEGRADED);
        assertThat(health.notes()).anyMatch(n -> n.contains("Node 2 is unreachable"));
    }

    @Test
    void aQuorumWithNoLeaderIsCritical() {
        ClusterHealth health = judge(broker("1", true, false, serving(true)), broker("2", true, false, serving(true)));

        assertThat(health.level()).isEqualTo(Level.CRITICAL);
        assertThat(health.notes()).anyMatch(n -> n.contains("no leader"));
    }

    @Test
    void partitionsWithoutALeaderAreCritical() {
        ClusterHealth health = KafkaHealth.judge(
                CLUSTER,
                Partial.complete(List.of(broker("1", true, true, serving(true))), List.of(NodeId.of("1"))),
                OptionalLong.of(3));

        assertThat(health.level()).isEqualTo(Level.CRITICAL);
        assertThat(health.notes()).anyMatch(n -> n.contains("3 partitions have no leader"));
    }

    @Test
    void partitionsWhoseLeadersCouldNotBeReadAreNotCountedAsFine() {
        ClusterHealth health = KafkaHealth.judge(
                CLUSTER,
                Partial.complete(List.of(broker("1", true, true, serving(true))), List.of(NodeId.of("1"))),
                OptionalLong.empty());

        assertThat(health.level()).isEqualTo(Level.OK);
        assertThat(health.notes()).anyMatch(n -> n.contains("offline partitions are not ruled out"));
    }

    @Test
    void noBrokerAtAllIsCritical() {
        ClusterHealth health = KafkaHealth.judge(
                CLUSTER, new Partial<>(new ArrayList<NodeDescription>(), Map.of(), Map.of()), OptionalLong.of(0));

        assertThat(health.level()).isEqualTo(Level.CRITICAL);
        assertThat(health.liveEndpointNames()).isEmpty();
    }

    private final KafkaHealthStore store = mock(KafkaHealthStore.class);
    private final Instant[] now = {Instant.parse("2026-10-29T10:00:00Z")};
    private final KafkaHealth health = new KafkaHealth(store, new Clock() {
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
            return now[0];
        }
    });

    @Test
    void healthIsWhatTheScrapeWroteAndNeverACallToABroker() {
        ClusterHealth written =
                KafkaHealth.verdict(CLUSTER, Level.DEGRADED, List.of("broker-1"), List.of("note"), true);
        when(store.read(CLUSTER)).thenReturn(Optional.of(new KafkaHealthStore.Stored(written, now[0])));

        assertThat(health.evaluate(CLUSTER, List.of(), Set.of())).isEqualTo(written);
        // It holds no way to reach a broker, so a blocked or slow driver cannot delay a health request.
        assertThat(KafkaHealth.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getName().startsWith("io.github.sudoitir.rahdar.platform.broker"))
                .noneMatch(field -> field.getType().getName().startsWith("io.github.sudoitir.openbroker"));
    }

    @Test
    void aClusterTheScrapeHasNotReadOrHasStoppedReadingIsUnknownWithTheReason() {
        assertThat(health.evaluate(CLUSTER, List.of(), Set.of()).level()).isEqualTo(Level.UNKNOWN);

        ClusterHealth written = KafkaHealth.verdict(CLUSTER, Level.OK, List.of("broker-1"), List.of(), false);
        when(store.read(CLUSTER)).thenReturn(Optional.of(new KafkaHealthStore.Stored(written, now[0])));
        now[0] = now[0].plus(KafkaHealth.STALE).plusSeconds(1);

        ClusterHealth stale = health.evaluate(CLUSTER, List.of(), Set.of());
        assertThat(stale.level()).isEqualTo(Level.UNKNOWN);
        assertThat(stale.notes()).anyMatch(n -> n.contains("last read this cluster at"));
    }
}
