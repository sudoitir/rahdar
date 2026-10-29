package io.github.sudoitir.rahdar.platform.scrape;

import io.github.sudoitir.rahdar.platform.scrape.internal.persistence.QueueSnapshotEntity;
import io.github.sudoitir.rahdar.platform.scrape.internal.persistence.QueueSnapshotRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads of the scraped queue state. Other modules read snapshots through this, never
 * through the scrape module's entity or repository.
 */
@Component
@RequiredArgsConstructor
public class QueueSnapshots {

    private final QueueSnapshotRepository repository;

    /** Every queue on every node of a cluster. */
    @Transactional(readOnly = true)
    public List<QueueSnapshot> forCluster(UUID clusterId) {
        return repository.findByClusterId(clusterId).stream()
                .map(QueueSnapshots::toSnapshot)
                .toList();
    }

    /** Every queue on one node. */
    @Transactional(readOnly = true)
    public List<QueueSnapshot> forNode(UUID nodeId) {
        return repository.findByNodeId(nodeId).stream()
                .map(QueueSnapshots::toSnapshot)
                .toList();
    }

    /** The distinct addresses the cluster's queues are bound to. */
    @Transactional(readOnly = true)
    public List<String> addresses(UUID clusterId) {
        return repository.findDistinctAddressesByClusterId(clusterId);
    }

    private static QueueSnapshot toSnapshot(QueueSnapshotEntity e) {
        return new QueueSnapshot(
                e.getClusterId(),
                e.getNodeId(),
                e.getQueueName(),
                e.getAddress(),
                e.getRoutingType(),
                e.isDurable(),
                e.isPaused(),
                e.getTs(),
                e.getMessageCount(),
                e.getConsumerCount(),
                e.getDeliveringCount(),
                e.getScheduledCount(),
                e.getMessagesAdded(),
                e.getMessagesAcked(),
                e.getMessagesExpired(),
                e.getKind(),
                e.getNativeAttributes());
    }
}
