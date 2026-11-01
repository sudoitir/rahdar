package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence.RabbitMqConnectionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a replica keeps for a RabbitMQ cluster goes when the cluster is deleted. */
class RabbitMqClusterDeletedTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RabbitMqScrape scrape = new RabbitMqScrape(
            mock(BrokerGateway.class),
            mock(ClusterDirectory.class),
            mock(NodeStateRecorder.class),
            meters,
            null,
            null,
            Clock.systemUTC(),
            mock(RabbitMqHealthStore.class),
            mock(RabbitMqConnectionRepository.class),
            mock(AmqpProbe.class));
    private final RabbitMqClusterDeleted deleted = new RabbitMqClusterDeleted(scrape);

    @Test
    void theMetersOfTheDeletedClusterGoAndOthersStay() {
        UUID gone = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        for (UUID cluster : List.of(gone, kept)) {
            meters.counter("rahdar.rabbitmq.scrape.rounds", "cluster", cluster.toString(), "call", "nodes");
        }

        deleted.onClusterDeleted(new ReplicaSignal("cluster-deleted", gone.toString()));

        assertThat(meters.find("rahdar.rabbitmq.scrape.rounds")
                        .tag("cluster", gone.toString())
                        .meters())
                .isEmpty();
        assertThat(meters.find("rahdar.rabbitmq.scrape.rounds")
                        .tag("cluster", kept.toString())
                        .meters())
                .hasSize(1);
    }
}
