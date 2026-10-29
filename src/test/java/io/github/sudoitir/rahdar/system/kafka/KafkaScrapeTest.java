package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The scrape keeps a Kafka cluster's stored brokers true: one that joined is added, one that left is marked. */
class KafkaScrapeTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final ClusterDirectory clusters = mock(ClusterDirectory.class);
    private final NodeStateRecorder recorder = mock(NodeStateRecorder.class);
    private final KafkaPartitions partitions = mock(KafkaPartitions.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AuditService audit = mock(AuditService.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private final KafkaHealthStore healthStore = mock(KafkaHealthStore.class);
    private final MutableClock clock = new MutableClock();
    private final KafkaScrape scrape =
            new KafkaScrape(gateway, clusters, recorder, partitions, meters, audit, actors, clock, healthStore);

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-29T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

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
    }

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

    private static NodeDescription brokerOf(String id, String clusterId) {
        return new NodeDescription(
                NodeId.of(id),
                id,
                new Membership(Optional.empty(), Designation.NONE, Serving.ACTIVE, Optional.empty()),
                Set.of(NodeFunction.BROKER),
                List.of(Endpoint.of(Plane.DATA, KafkaOptions.PROTOCOL, "k" + id + ":9092")),
                Optional.empty(),
                List.of(),
                Attributes.builder().putRaw("kafka.cluster_id", clusterId).build(),
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

    @Test
    void aBrokerTheClusterStopsListingIsRetiredAfterTheGracePeriodAndItsLeavingIsAudited() {
        ClusterNode one = stored("1");
        ClusterNode three = stored("3");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(one, three));
        clusterReports(broker("1"));
        AuditEvent event = mock(AuditEvent.class);
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(event);

        scrape.nodes(CLUSTER, 1);
        clock.advance(KafkaScrape.RETIRE_AFTER.minusSeconds(1));
        scrape.nodes(CLUSTER, 2);
        verify(recorder, never()).retire(any());

        clock.advance(Duration.ofSeconds(1));
        scrape.nodes(CLUSTER, 3);

        verify(recorder).retire(three.getId());
        verify(recorder, never()).retire(one.getId());
        verify(audit)
                .begin(
                        any(),
                        eq("NODE_LEFT_CLUSTER"),
                        eq("NODE"),
                        eq("broker-3"),
                        eq(CLUSTER),
                        any(),
                        any(),
                        eq(false));
        verify(audit).succeed(event, 1);
    }

    @Test
    void aBrokerThatIsListedAgainBeforeTheGracePeriodEndsStartsOverAndAnEmptyAnswerRetiresNothing() {
        ClusterNode one = stored("1");
        ClusterNode three = stored("3");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(one, three));
        clusterReports(broker("1"));
        scrape.nodes(CLUSTER, 1);
        clock.advance(Duration.ofMinutes(9));
        clusterReports(broker("1"), broker("3"));
        scrape.nodes(CLUSTER, 2);
        clock.advance(Duration.ofMinutes(9));
        clusterReports(broker("1"));
        scrape.nodes(CLUSTER, 3);
        clock.advance(Duration.ofMinutes(20));
        clusterReports();
        scrape.nodes(CLUSTER, 4);

        verify(recorder, never()).retire(any());
    }

    @Test
    void aCycleIsTwoDriverReadsOfTheWholeClusterWhateverItsSize() {
        clusterReports(broker("1"), broker("2"), broker("3"));
        List<ClusterNode> brokers = List.of(stored("1"), stored("2"), stored("3"));
        when(clusters.nodes(CLUSTER)).thenReturn(brokers);
        when(partitions.due(CLUSTER)).thenReturn(true);

        scrape.nodes(CLUSTER, 1);
        scrape.nodes(CLUSTER, 2);

        assertThat(meters.get("rahdar.kafka.scrape.rounds")
                        .tag("cluster", CLUSTER.toString())
                        .tag("call", "nodes")
                        .counter()
                        .count())
                .isEqualTo(2);
        assertThat(meters.get("rahdar.kafka.scrape.rounds")
                        .tag("call", "partitions")
                        .counter()
                        .count())
                .isEqualTo(2);
    }

    @Test
    void theHealthVerdictIsWrittenWhenItChangesAndOtherwiseOnlyOnTheHeartbeat() {
        ClusterNode one = stored("1");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(one));
        clusterReports(broker("1"));

        scrape.nodes(CLUSTER, 1);
        scrape.nodes(CLUSTER, 2);
        verify(healthStore, times(1)).write(any(), any());

        clock.advance(KafkaScrape.HEALTH_HEARTBEAT);
        scrape.nodes(CLUSTER, 3);
        verify(healthStore, times(2)).write(any(), any());

        clusterReports(broker("1"), brokerOf("2", "x"));
        scrape.nodes(CLUSTER, 4);
        verify(healthStore, times(3)).write(any(), any());
    }

    @Test
    void thePartitionsAreReadOnlyWhenTheyAreDue() {
        clusterReports(broker("1"));
        ClusterNode one = stored("1");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(one));
        when(partitions.due(CLUSTER)).thenReturn(false);

        scrape.nodes(CLUSTER, 1);

        verify(partitions, never()).refresh(any());
    }

    @Test
    void aClusterReProvisionedAtTheSameAddressIsNotReadFurtherUntilItIsTheRegisteredOneAgain() {
        ClusterNode one = stored("1");
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(one));
        when(clusters.registeredIdentity(CLUSTER)).thenReturn(Optional.of("registered-id"));
        clusterReports(brokerOf("1", "new-id"), brokerOf("2", "new-id"));

        scrape.nodes(CLUSTER, 1);

        verify(recorder, never()).applyTierA(any(), any(), anyLong());
        verify(recorder, never()).adopt(any(), any(), any(), any());
        verify(partitions, never()).refresh(any());
        ArgumentCaptor<String> words = ArgumentCaptor.forClass(String.class);
        verify(recorder)
                .recordNodeError(eq(one.getId()), words.capture(), eq(BrokerConnectionException.Kind.UNREACHABLE));
        assertThat(words.getValue())
                .isEqualTo("The cluster at this address changed: it reports cluster id new-id, registered as"
                        + " registered-id. Re-register it or restore the original cluster.");

        // Another replica reads the same words from the stored broker, and both health and the sweep stop on them.
        when(one.getLastError()).thenReturn(words.getValue());
        assertThat(KafkaClusterIdentity.recorded(List.of(one))).hasValue(words.getValue());
        assertThatThrownBy(() -> scrape.subscriptions(CLUSTER)).isInstanceOf(IllegalStateException.class);

        when(one.getLastError()).thenReturn(null);
        clusterReports(brokerOf("1", "registered-id"));
        scrape.nodes(CLUSTER, 2);

        verify(recorder).applyTierA(eq(one.getId()), any(), eq(2L));
        assertThat(KafkaClusterIdentity.recorded(List.of(one))).isEmpty();
    }
}
