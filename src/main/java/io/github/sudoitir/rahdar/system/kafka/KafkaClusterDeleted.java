package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Lets go of what this replica keeps in memory for a Kafka cluster once the cluster is deleted, on any replica. Its
 * stored health row goes with the cluster.
 */
@Component
@RequiredArgsConstructor
class KafkaClusterDeleted {

    private final KafkaPartitions partitions;
    private final KafkaScrape scrape;

    @EventListener(condition = "#signal.kind() == 'cluster-deleted'")
    void onClusterDeleted(ReplicaSignal signal) {
        forget(UUID.fromString(signal.key()));
    }

    void forget(UUID clusterId) {
        partitions.forget(clusterId);
        scrape.forget(clusterId);
    }
}
