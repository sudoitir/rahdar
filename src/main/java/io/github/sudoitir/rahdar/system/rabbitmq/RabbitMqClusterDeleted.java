package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Lets go of what this replica keeps in memory for a RabbitMQ cluster once the cluster is deleted, on any replica.
 * Its stored health row goes with the cluster.
 */
@Component
@RequiredArgsConstructor
class RabbitMqClusterDeleted {

    private final RabbitMqScrape scrape;

    @EventListener(condition = "#signal.kind() == 'cluster-deleted'")
    void onClusterDeleted(ReplicaSignal signal) {
        forget(UUID.fromString(signal.key()));
    }

    void forget(UUID clusterId) {
        scrape.forget(clusterId);
    }
}
