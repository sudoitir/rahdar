package io.github.sudoitir.rahdar.system.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Designation;
import io.github.sudoitir.openbroker.model.Membership;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Nodes;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Serving;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The scrape keeps a Kafka cluster's stored brokers true: one that joined is added, one that left is marked. */
class KafkaScrapeTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final ClusterDirectory clusters = mock(ClusterDirectory.class);
    private final NodeStateRecorder recorder = mock(NodeStateRecorder.class);
    private final KafkaPartitions partitions = mock(KafkaPartitions.class);
    private final KafkaScrape scrape = new KafkaScrape(gateway, clusters, recorder, partitions);

    private static ClusterNode stored(String brokerId) {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(UUID.randomUUID());
        when(node.getNativeNodeId()).thenReturn(brokerId);
        when(node.getName()).thenReturn("broker-" + brokerId);
        return node;
    }

    private static NodeDescription broker(String id) {
        return new NodeDescription(
                NodeId.of(id),
                id,
                new Membership(Optional.empty(), Designation.NONE, Serving.ACTIVE, Optional.empty()),
                Set.of(NodeFunction.BROKER),
                List.of(Endpoint.of(Plane.DATA, KafkaOptions.PROTOCOL, "k" + id + ":9092")),
                Optional.empty(),
                List.of(),
                Attributes.empty(),
                Optional.empty());
    }

    private void clusterReports(NodeDescription... brokers) {
        ClusterConnection connection = mock(ClusterConnection.class);
        Nodes nodes = mock(Nodes.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.nodes()).thenReturn(nodes);
        when(nodes.describe(Scope.whole()))
                .thenReturn(Partial.complete(
                        List.of(brokers),
                        java.util.Arrays.stream(brokers)
                                .map(NodeDescription::node)
                                .toList()));
    }

    @Test
    void aBrokerThatJoinedIsAddedWithTheNameAndAddressTheClusterGivesIt() {
        ClusterNode one = stored("1");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(one));
        clusterReports(broker("1"), broker("2"));

        scrape.nodes(CLUSTER, 7);

        verify(recorder).applyTierA(eq(one.getId()), any(), eq(7L));
        verify(recorder)
                .adopt(CLUSTER, "broker-2", "2", List.of(Endpoint.of(Plane.DATA, KafkaOptions.PROTOCOL, "k2:9092")));
    }

    @Test
    void aBrokerTheClusterNoLongerListsIsMarkedAndKeepsItsLastState() {
        ClusterNode one = stored("1");
        ClusterNode three = stored("3");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(one, three));
        clusterReports(broker("1"));

        scrape.nodes(CLUSTER, 8);

        verify(recorder).recordNodeError(eq(three.getId()), any(), eq(BrokerConnectionException.Kind.UNREACHABLE));
        verify(recorder, never()).applyTierA(eq(three.getId()), any(), anyLong());
        verify(recorder, never()).adopt(any(), any(), any(), any());
    }
}
