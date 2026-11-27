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

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.access.Access;
import io.github.sudoitir.openbroker.access.Assessment;
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
    private final Access brokerAccess = mock(Access.class);
    private final ClusterConnection connection = mock(ClusterConnection.class);
    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final HeavyRequests heavy = mock(HeavyRequests.class);
    private final KafkaClusterView view;

    KafkaClusterViewTest() {
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        when(connection.nodes()).thenReturn(nodes);
        when(connection.access()).thenReturn(brokerAccess);
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
        when(brokerAccess.check(any(), any())).thenReturn(Assessment.available("fenced brokers are listed"));
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
        assertThat(cluster.brokers().getFirst().leaders()).isEqualTo(1);
        assertThat(cluster.brokers().getFirst().replicasHeld()).isEqualTo(1);
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
        assertThat(third.replicasHeld()).isEqualTo(1);
        assertThat(third.outOfSyncReplicas()).isEqualTo(1);
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
        assertThat(cluster.quorum().reason()).isEqualTo(KafkaBrokers.ZOOKEEPER_QUORUM);
        assertThat(cluster.quorum().majority()).isFalse();
        assertThat(cluster.notes()).contains(KafkaBrokers.ZOOKEEPER_QUORUM);
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
        assertThat(cluster.totals().topics()).isNull();
        assertThat(cluster.totals().partitions()).isNull();
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
    }

    @Test
    void aKraftClusterBefore4DoesNotReadAMissingBrokerAsHealthy() {
        describe(kraft("1", true));
        when(brokerAccess.check(any(), any()))
                .thenReturn(Assessment.unavailable("Kafka before 4.0 does not list fenced brokers.", Optional.empty()));

        ClusterView cluster = view.read(CLUSTER);

        assertThat(cluster.notes()).contains("Kafka before 4.0 does not list fenced brokers.");
        assertThat(cluster.quorum().majority()).isTrue();
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
        return broker(id, "kraft", true, leader);
    }

    private static NodeDescription broker(String id, String mode, boolean voter, boolean leader) {
        Attributes.Builder attributes = Attributes.builder().putRaw("kafka.cluster_mode", mode);
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
