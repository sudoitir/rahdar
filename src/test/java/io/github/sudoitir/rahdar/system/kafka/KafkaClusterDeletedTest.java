package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a replica keeps for a Kafka cluster goes when the cluster is deleted. */
class KafkaClusterDeletedTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final KafkaPartitions partitions = new KafkaPartitions(mock(BrokerGateway.class), Clock.systemUTC());
    private final KafkaIdentityWatch identity = new KafkaIdentityWatch();
    private final KafkaHealth health = new KafkaHealth(mock(BrokerGateway.class), partitions, identity);
    private final KafkaScrape scrape = new KafkaScrape(
            mock(BrokerGateway.class),
            mock(ClusterDirectory.class),
            mock(NodeStateRecorder.class),
            partitions,
            meters,
            identity,
            null,
            null,
            Clock.systemUTC());
    private final KafkaClusterDeleted deleted = new KafkaClusterDeleted(health, partitions, identity, scrape);

    @Test
    void theMetersReadingsAndIdentityChangeOfTheDeletedClusterGoAndOthersStay() {
        UUID gone = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        for (UUID cluster : List.of(gone, kept)) {
            meters.counter("rahdar.kafka.scrape.rounds", "cluster", cluster.toString(), "call", "nodes");
            identity.check(cluster, "new", "old");
        }

        deleted.onClusterDeleted(new ReplicaSignal("cluster-deleted", gone.toString()));

        assertThat(meters.find("rahdar.kafka.scrape.rounds")
                        .tag("cluster", gone.toString())
                        .meters())
                .isEmpty();
        assertThat(meters.find("rahdar.kafka.scrape.rounds")
                        .tag("cluster", kept.toString())
                        .meters())
                .hasSize(1);
        assertThat(identity.change(gone)).isEmpty();
        assertThat(identity.change(kept)).isPresent();
        assertThat(partitions.offline(gone)).isEmpty();
    }
}
