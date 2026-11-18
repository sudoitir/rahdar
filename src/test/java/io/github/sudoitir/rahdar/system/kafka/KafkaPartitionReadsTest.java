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
import io.github.sudoitir.openbroker.kafka.KafkaOptions;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Designation;
import io.github.sudoitir.openbroker.model.Membership;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Nodes;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.Placement;
import io.github.sudoitir.openbroker.model.PlacementRole;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Serving;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.PartitionPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.PartitionRow;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReplicaSuggestion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Placement reads and the rack suggestion. A suggestion never asks to move a replica. */
class KafkaPartitionReadsTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final Resources resources = mock(Resources.class);
    private final Nodes nodes = mock(Nodes.class);
    private final ClusterConnection connection = mock(ClusterConnection.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final KafkaPartitionReads reads;

    KafkaPartitionReadsTest() {
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        when(connection.nodes()).thenReturn(nodes);
        reads = new KafkaPartitionReads(gateway, access);
    }

    @BeforeEach
    void noNodes() {
        when(nodes.describe(Scope.whole())).thenReturn(Partial.complete(List.of(), List.of()));
    }

    @Test
    void aTopicPageKeepsReplicaOrderWhenTheLeaderIsNotFirst() {
        listing(partition("orders", 0, 2, List.of(1, 2), List.of(1, 2), null));

        PartitionPage page = reads.partitions(CLUSTER, "orders", null, null);

        PartitionRow row = page.items().getFirst();
        assertThat(row.replicas()).containsExactly(1, 2);
        assertThat(row.leader()).isEqualTo(2);
        assertThat(row.preferredLeader()).isFalse();
        assertThat(row.replicasOnBroker()).extracting("broker").containsExactly(1, 2);
        verify(connection, never()).operations();
    }

    @Test
    void replicationListsOnlyPartitionsThatNeedAttentionAndKeepsLogDirMisses() {
        listing(
                partition("orders", 0, 1, List.of(1, 2), List.of(1, 2), null),
                partition("orders", 1, 2, List.of(1, 2), List.of(1, 2), "broker 3: timed out"),
                partition("billing", 0, null, List.of(1, 2), List.of(), null));

        PartitionPage page = reads.replication(CLUSTER, null, null, null);

        assertThat(page.items())
                .extracting(PartitionRow::topic, PartitionRow::partition)
                .containsExactly(tuple("billing", 0), tuple("orders", 1));
        PartitionRow misled = page.items().get(1);
        assertThat(misled.replicas()).containsExactly(1, 2);
        assertThat(misled.preferredLeader()).isFalse();
        assertThat(misled.logDirsUnavailable()).isEqualTo("broker 3: timed out");
        assertThat(page.items().getFirst().offlineReplicas()).containsExactly(1, 2);
        verify(connection, never()).operations();
    }

    @Test
    void aSuggestionKeepsTheCurrentReplicasAndDoesNotReassign() {
        listing(partition("orders", 0, 1, List.of(1, 2), List.of(1, 2), null));
        when(nodes.describe(Scope.whole()))
                .thenReturn(Partial.complete(
                        List.of(broker("1", "a"), broker("2", "a"), broker("3", "b"), broker("4", null)), List.of()));

        ReplicaSuggestion suggestion = reads.suggest(CLUSTER, "orders", 0);

        assertThat(suggestion.label()).isEqualTo("suggestion");
        assertThat(suggestion.currentReplicas()).containsExactly(1, 2);
        assertThat(suggestion.replicas()).containsExactly(1, 2, 3);
        assertThat(suggestion.note()).contains("suggestion");
        verify(connection, never()).operations();
    }

    @Test
    void aReadWithoutPermissionDoesNotListPartitions() {
        doThrow(new NotFoundException("permission"))
                .when(access)
                .requireResource(CLUSTER, ResourceRef.destination("orders"), Permissions.DESTINATION_READ);

        assertThatThrownBy(() -> reads.partitions(CLUSTER, "orders", null, null))
                .isInstanceOf(NotFoundException.class);
        verify(resources, never()).read(any(ResourceQuery.class));
    }

    private void listing(Resource... items) {
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(List.of(items), Optional.empty()), List.of()));
    }

    private static Resource partition(
            String topic, int number, Integer leader, List<Integer> replicas, List<Integer> inSync, String logDirs) {
        Attributes.Builder attributes =
                Attributes.builder().putRaw(KafkaKinds.REPLICAS, replicas).putRaw(KafkaKinds.IN_SYNC_REPLICAS, inSync);
        if (leader != null) {
            attributes.putRaw(KafkaKinds.LEADER, leader.longValue());
        }
        if (logDirs != null) {
            attributes.putRaw(KafkaKinds.LOG_DIRS_UNAVAILABLE, logDirs);
        }
        List<Placement> placements = replicas.stream()
                .map(broker -> new Placement(
                        NodeId.of(Integer.toString(broker)),
                        Optional.of(PartitionId.of(Integer.toString(number))),
                        leader != null && leader.equals(broker) ? PlacementRole.LEADER : PlacementRole.FOLLOWER,
                        Optional.of(inSync.contains(broker)),
                        Attributes.builder()
                                .putRaw(KafkaKinds.REPLICA_SIZE_BYTES, 80L)
                                .putRaw(KafkaKinds.OFFSET_LAG, 0L)
                                .build()))
                .toList();
        return new Resource(
                ResourceId.of("kafka.partition", topic, Integer.toString(number)),
                attributes.build(),
                List.of(),
                placements,
                Instant.EPOCH);
    }

    private static NodeDescription broker(String id, String rack) {
        Attributes attributes = rack == null
                ? Attributes.empty()
                : Attributes.builder().putRaw("kafka.rack", rack).build();
        return new NodeDescription(
                NodeId.of(id),
                id,
                new Membership(Optional.empty(), Designation.NONE, Serving.ACTIVE, Optional.empty()),
                Set.of(NodeFunction.BROKER),
                List.of(Endpoint.of(Plane.DATA, KafkaOptions.PROTOCOL, "k" + id + ":9092")),
                Optional.empty(),
                List.of(),
                attributes,
                Optional.empty());
    }
}
