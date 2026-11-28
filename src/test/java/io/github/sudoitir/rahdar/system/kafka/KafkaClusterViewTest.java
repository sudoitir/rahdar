package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.kafka.KafkaOptions;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.Designation;
import io.github.sudoitir.openbroker.model.Membership;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Nodes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Serving;
import io.github.sudoitir.rahdar.kernel.core.HeavyRequests;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterBroker;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterView;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.CoReplication;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One cluster read. The graph and the words come from the same snapshot. */
class KafkaClusterViewTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final Resources resources = mock(Resources.class);
    private final Nodes nodes = mock(Nodes.class);
    private final ClusterConnection connection = mock(ClusterConnection.class);
    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final HeavyRequests heavy = mock(HeavyRequests.class);
    private final KafkaClusterView view;

    KafkaClusterViewTest() {
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        when(connection.nodes()).thenReturn(nodes);
        ActorResolver actors = mock(ActorResolver.class);
        when(actors.resolve()).thenReturn(new Actor("test-admin", null, null, null));
        when(heavy.run(any(), any(), any())).thenAnswer(invocation -> {
            Function<HeavyRequests.Run, ?> work = invocation.getArgument(2);
            return work.apply(null);
        });
        view = new KafkaClusterView(gateway, access, actors, heavy);
    }

    @BeforeEach
    void noCluster() {
        when(nodes.describe(Scope.whole())).thenReturn(Partial.complete(List.of(), List.of()));
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(List.of(), Optional.empty()), List.of()));
    }

    @Test
    void threeBrokersShareTwoPairsAndTheWordsMatchTheEdges() {
        describe(kraft("1", true), kraft("2", false), kraft("3", false));
        partitions(
                partition("orders", 0, 1, List.of(1, 2), List.of(1, 2)),
                partition("orders", 1, 2, List.of(2, 3), List.of(2, 3)));

        ClusterView cluster = view.read(CLUSTER);

        assertThat(cluster.mode()).isEqualTo("KRaft");
        assertThat(cluster.complete()).isTrue();
        assertThat(cluster.brokers()).extracting(ClusterBroker::id).containsExactly("1", "2", "3");
        assertThat(cluster.brokers()).allMatch(ClusterBroker::reachable);
        assertThat(cluster.edges())
                .extracting(CoReplication::a, CoReplication::b)
                .containsExactly(tuple("1", "2"), tuple("2", "3"));
        assertThat(cluster.edges()).allMatch(edge -> edge.outOfSyncReplicas() == 0);
        assertThat(cluster.brokers().getFirst().leaders()).isEqualTo(1L);
        assertThat(cluster.brokers().getFirst().replicasHeld()).isEqualTo(1L);
        assertThat(cluster.totals().topics()).isEqualTo(1);
        assertThat(cluster.totals().partitions()).isEqualTo(2);
        assertThat(cluster.totals().brokersReached()).isEqualTo(3);
        assertThat(cluster.totals().atLeast()).isFalse();
        assertThat(cluster.quorum().available()).isTrue();
        assertThat(cluster.quorum().majority()).isTrue();
        assertThat(cluster.quorum().leader()).isEqualTo("1");
        assertThat(cluster.brokers().getFirst().quorumLeader()).isTrue();
    }

    @Test
    void anUnreachableBrokerKeepsItsCountersAndTheQuorumStillHasAMajority() {
        NodeDescription down = serving(kraft("3", false), false, "The controller has fenced this broker.");
        describe(kraft("1", true), kraft("2", false), down);
        partitions(partition("orders", 0, 1, List.of(1, 2, 3), List.of(1, 2)));

        ClusterView cluster = view.read(CLUSTER);

        ClusterBroker third = cluster.brokers().get(2);
        assertThat(third.reachable()).isFalse();
        assertThat(third.reason()).contains("fenced");
        assertThat(third.replicasHeld()).isEqualTo(1L);
        assertThat(third.outOfSyncReplicas()).isEqualTo(1L);
        assertThat(third.offlineReplicas()).isZero();
        assertThat(cluster.totals().underReplicated()).isEqualTo(1);
        assertThat(cluster.quorum().majority()).isTrue();
        assertThat(cluster.quorum().available()).isTrue();
        assertThat(cluster.edges())
                .filteredOn(edge -> edge.a().equals("1") && edge.b().equals("3"))
                .allMatch(edge -> edge.outOfSyncReplicas() > 0);
        assertThat(cluster.edges())
                .filteredOn(edge -> edge.b().equals("2"))
                .allMatch(edge -> edge.outOfSyncReplicas() == 0);
    }

    @Test
    void aZooKeeperClusterDoesNotCountItsQuorumHealthy() {
        describe(broker("1", "zookeeper", false, false));

        ClusterView cluster = view.read(CLUSTER);

        assertThat(cluster.mode()).isEqualTo("ZooKeeper");
        assertThat(cluster.quorum().available()).isFalse();
        assertThat(cluster.quorum().reason()).isEqualTo(KafkaClusterView.ZOOKEEPER_QUORUM);
        assertThat(cluster.quorum().majority()).isFalse();
        assertThat(cluster.notes()).contains(KafkaClusterView.ZOOKEEPER_QUORUM);
    }

    @Test
    void aControllerOnlyNodeIsNotABrokerAndHasNoEdge() {
        describe(kraft("1", true), controller("100"));
        partitions(partition("orders", 0, 1, List.of(1), List.of(1)));

        ClusterView cluster = view.read(CLUSTER);

        assertThat(cluster.brokers()).extracting(ClusterBroker::id).containsExactly("1");
        assertThat(cluster.controllers()).singleElement().satisfies(controller -> {
            assertThat(controller.id()).isEqualTo("100");
            assertThat(controller.lag()).isEqualTo(4L);
            assertThat(controller.caughtUp()).isFalse();
            assertThat(controller.quorumLeader()).isFalse();
        });
        assertThat(cluster.totals().controllers()).isEqualTo(1);
        assertThat(cluster.totals().brokers()).isEqualTo(1);
        assertThat(cluster.edges()).isEmpty();
        assertThat(cluster.quorum().voters()).extracting("id").containsExactly("1", "100");
    }

    @Test
    void aReadThatReachesTheTopicCapIsAFloorNotATotal() {
        describe(kraft("1", true));
        List<Resource> pages = new ArrayList<>();
        for (int i = 0; i < KafkaClusterView.TOPIC_CAP; i++) {
            pages.add(partition("topic-" + i, 0, 1, List.of(1), List.of(1)));
        }
        partitions(pages);

        ClusterView cluster = view.read(CLUSTER);

        assertThat(cluster.complete()).isFalse();
        assertThat(cluster.totals().atLeast()).isTrue();
        assertThat(cluster.totals().topics()).isEqualTo((long) KafkaClusterView.TOPIC_CAP);
        assertThat(cluster.totals().partitions()).isEqualTo((long) KafkaClusterView.TOPIC_CAP);
        assertThat(cluster.reason()).isEqualTo(KafkaClusterView.TOPIC_CAP_REASON);
        assertThat(cluster.notes()).contains(KafkaClusterView.TOPIC_CAP_REASON);
    }

    @Test
    void topicNamesNeedDestinationReadAndTheCountsStay() {
        describe(kraft("1", false));
        partitions(partition("orders", 0, 2, List.of(1, 2), List.of(1, 2)));
        when(access.holds(CLUSTER, Permissions.DESTINATION_READ)).thenReturn(false);

        ClusterView withheld = view.read(CLUSTER);

        assertThat(withheld.problems().preferredLeaderMismatches()).isEqualTo(1);
        assertThat(withheld.problems().topics()).isEmpty();
        assertThat(withheld.problems().topicsWithheld()).isEqualTo(KafkaClusterView.TOPICS_WITHHELD);

        when(access.holds(CLUSTER, Permissions.DESTINATION_READ)).thenReturn(true);

        ClusterView shown = view.read(CLUSTER);

        assertThat(shown.problems().topics()).containsExactly("orders");
        assertThat(shown.problems().topicsWithheld()).isNull();
        assertThat(shown.problems().topicsOmitted()).isZero();
        assertThat(withheld.problems().topicsOmitted()).isEqualTo(1);
    }

    @Test
    void aPlacementThatCannotBeReadIsUnavailableNotZero() {
        describe(kraft("1", true));
        when(resources.read(any(ResourceQuery.class))).thenThrow(new IllegalStateException("Read timed out"));

        ClusterView cluster = view.read(CLUSTER);

        assertThat(cluster.complete()).isFalse();
        assertThat(cluster.reason()).contains(KafkaClusterView.PLACEMENT_UNREAD);
        assertThat(cluster.totals().topics()).isNull();
        assertThat(cluster.totals().partitions()).isNull();
        assertThat(cluster.totals().atLeast()).isFalse();
        assertThat(cluster.problems().topicsWithheld()).isNull();
        assertThat(cluster.problems().topicsOmitted()).isZero();
        assertThat(cluster.brokers()).singleElement().satisfies(broker -> {
            assertThat(broker.leaders()).isNull();
            assertThat(broker.replicasHeld()).isNull();
            assertThat(broker.outOfSyncReplicas()).isNull();
            assertThat(broker.offlineReplicas()).isNull();
        });
    }

    @Test
    void aHealthyClusterWithoutDestinationReadWithholdsNoNames() {
        describe(kraft("1", false));
        partitions(partition("orders", 0, 1, List.of(1), List.of(1)));
        when(access.holds(CLUSTER, Permissions.DESTINATION_READ)).thenReturn(false);

        ClusterView cluster = view.read(CLUSTER);

        assertThat(cluster.problems().topics()).isEmpty();
        assertThat(cluster.problems().topicsWithheld()).isNull();
        assertThat(cluster.problems().topicsOmitted()).isZero();
    }

    @Test
    void topicNamesPastTheCapSayHowManyWereLeftOut() {
        describe(kraft("1", false));
        when(access.holds(CLUSTER, Permissions.DESTINATION_READ)).thenReturn(true);
        List<Resource> sick = new ArrayList<>();
        for (int i = 0; i < KafkaClusterView.TOP + 5; i++) {
            sick.add(partition("t" + i, 0, 2, List.of(1, 2), List.of(1, 2)));
        }
        partitions(sick);

        ClusterView cluster = view.read(CLUSTER);

        assertThat(cluster.problems().topics()).hasSize(KafkaClusterView.TOP);
        assertThat(cluster.problems().topicsOmitted()).isEqualTo(5);
        assertThat(cluster.problems().topicsWithheld()).isNull();
    }

    @Test
    void aKraftClusterBefore4DoesNotReadAMissingBrokerAsHealthy() {
        describe(kraft("1", true, "4.0"));
        assertThat(view.read(CLUSTER).notes()).doesNotContain(KafkaClusterView.FENCE_GAP);

        describe(kraft("1", true, "3.9"));
        ClusterView cluster = view.read(CLUSTER);

        assertThat(cluster.notes()).contains(KafkaClusterView.FENCE_GAP);
        assertThat(cluster.quorum().majority()).isTrue();
        verify(connection, never()).access();
    }

    @Test
    void aControllerWithoutAVersionDoesNotHideTheBrokerRelease() {
        describe(controller("100"), kraft("1", true, "3.9"));

        assertThat(view.read(CLUSTER).notes()).contains(KafkaClusterView.FENCE_GAP);
    }

    @Test
    void aClusterThatReportsNoVersionDoesNotGuessTheFenceGap() {
        describe(controller("100"), kraft("1", true));

        assertThat(view.read(CLUSTER).notes()).doesNotContain(KafkaClusterView.FENCE_GAP);
    }

    @Test
    void aBrokerIdThatIsNotANumberKeepsThatIdAndNoCounters() {
        when(nodes.describe(Scope.whole()))
                .thenReturn(new Partial<>(
                        List.of(),
                        Map.of(),
                        Map.of(
                                NodeId.of("broker-a"),
                                new BrokerException(
                                        Status.UNAVAILABLE,
                                        ErrorInfo.of("UNAVAILABLE"),
                                        Dispatch.NOT_SENT,
                                        NodeId.of("broker-a"),
                                        "did not answer"))));

        ClusterBroker broker = view.read(CLUSTER).brokers().getFirst();

        assertThat(broker.id()).isEqualTo("broker-a");
        assertThat(broker.leaders()).isNull();
        assertThat(broker.replicasHeld()).isNull();
        assertThat(broker.outOfSyncReplicas()).isNull();
        assertThat(broker.offlineReplicas()).isNull();
    }

    @Test
    void aCallerWithoutClusterReadIsRefusedBeforeTheBrokerIsCalled() {
        doThrow(new NotFoundException("permission")).when(access).requireCluster(CLUSTER, Permissions.CLUSTER_READ);

        assertThatThrownBy(() -> view.read(CLUSTER)).isInstanceOf(NotFoundException.class);
        verify(gateway, never()).cluster(any());
        verify(heavy, never()).run(any(), any(), any());
    }

    private void describe(NodeDescription... described) {
        when(nodes.describe(Scope.whole())).thenReturn(Partial.complete(List.of(described), List.of()));
    }

    private void partitions(Resource... items) {
        partitions(List.of(items));
    }

    private void partitions(List<Resource> items) {
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(items, Optional.empty()), List.of()));
    }

    private static NodeDescription kraft(String id, boolean leader) {
        return kraft(id, leader, null);
    }

    private static NodeDescription kraft(String id, boolean leader, String version) {
        return broker(id, "kraft", true, leader, version);
    }

    private static NodeDescription broker(String id, String mode, boolean voter, boolean leader) {
        return broker(id, mode, voter, leader, null);
    }

    private static NodeDescription broker(String id, String mode, boolean voter, boolean leader, String version) {
        Attributes.Builder attributes = Attributes.builder().putRaw("kafka.cluster_mode", mode);
        if (version != null) {
            attributes.putRaw("kafka.cluster_version", version);
        }
        if (voter) {
            attributes.putRaw("kafka.quorum_voter", true).putRaw("kafka.quorum_lag", 0L);
        }
        if (leader) {
            attributes.putRaw("kafka.quorum_leader", true);
        }
        return node(id, Set.of(NodeFunction.BROKER), attributes.build(), List.of());
    }

    private static NodeDescription controller(String id) {
        Attributes attributes = Attributes.builder()
                .putRaw("kafka.cluster_mode", "kraft")
                .putRaw("kafka.quorum_voter", true)
                .putRaw("kafka.quorum_lag", 4L)
                .build();
        return node(
                id,
                Set.of(NodeFunction.CONTROLLER),
                attributes,
                List.of(new Condition(
                        ConditionTypes.OUT_OF_SYNC, ConditionStatus.TRUE, "BEHIND", "behind", Optional.empty())));
    }

    private static NodeDescription serving(NodeDescription node, boolean serving, String reason) {
        List<Condition> conditions = new ArrayList<>(node.conditions());
        conditions.add(new Condition(
                ConditionTypes.SERVING,
                serving ? ConditionStatus.TRUE : ConditionStatus.FALSE,
                serving ? "" : "FENCED",
                serving ? "" : reason,
                Optional.empty()));
        return new NodeDescription(
                node.node(),
                node.nativeId(),
                node.membership(),
                node.functions(),
                node.endpoints(),
                node.version(),
                conditions,
                node.attributes(),
                node.serverTime());
    }

    private static NodeDescription node(
            String id, Set<NodeFunction> functions, Attributes attributes, List<Condition> conditions) {
        return new NodeDescription(
                NodeId.of(id),
                id,
                new Membership(Optional.empty(), Designation.NONE, Serving.ACTIVE, Optional.empty()),
                functions,
                List.of(Endpoint.of(Plane.DATA, KafkaOptions.PROTOCOL, "k" + id + ":9092")),
                Optional.empty(),
                conditions,
                attributes,
                Optional.empty());
    }

    private static Resource partition(
            String topic, int number, Integer leader, List<Integer> replicas, List<Integer> inSync) {
        Attributes.Builder attributes =
                Attributes.builder().putRaw(KafkaKinds.REPLICAS, replicas).putRaw(KafkaKinds.IN_SYNC_REPLICAS, inSync);
        if (leader != null) {
            attributes.putRaw(KafkaKinds.LEADER, leader.longValue());
        }
        return new Resource(
                ResourceId.of("kafka.partition", topic, Integer.toString(number)),
                attributes.build(),
                List.of(),
                List.of(),
                Instant.EPOCH);
    }
}
