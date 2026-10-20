package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.QueueNodeCell;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.QueueView;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code subscription_snapshot} rows sharing an {@code (address, queueName, routingType)}
 * key → one {@link QueueView}: a per-node cell for each row, cluster totals, and
 * node presence (ADR-0017).
 */
@Component
public class QueueViewMapper {

    /** The aggregation key — a logical queue across the cluster. */
    public record QueueKey(String address, String queueName, String routingType) {}

    public QueueView toView(
            QueueKey key, List<QueueSnapshot> rows, Map<UUID, String> nodeNames, int nodesTotal, Instant staleBefore) {
        List<QueueNodeCell> cells = rows.stream()
                .map(r -> new QueueNodeCell(
                        r.nodeId(),
                        nodeNames.getOrDefault(r.nodeId(), r.nodeId().toString()),
                        r.ts() != null && r.ts().isBefore(staleBefore),
                        r.ts(),
                        r.messageCount(),
                        r.consumerCount(),
                        r.deliveringCount(),
                        r.scheduledCount(),
                        r.paused()))
                .sorted((a, b) -> a.nodeName().compareToIgnoreCase(b.nodeName()))
                .toList();

        return new QueueView(
                key.address(),
                key.queueName(),
                key.routingType(),
                rows.stream().anyMatch(QueueSnapshot::durable),
                cells.stream().mapToLong(QueueNodeCell::messageCount).sum(),
                cells.stream().mapToLong(QueueNodeCell::consumerCount).sum(),
                cells.stream().mapToLong(QueueNodeCell::deliveringCount).sum(),
                cells.stream().mapToLong(QueueNodeCell::scheduledCount).sum(),
                (int) cells.stream().map(QueueNodeCell::nodeId).distinct().count(),
                nodesTotal,
                cells.stream().anyMatch(QueueNodeCell::paused),
                cells,
                List.of(),
                null);
    }
}
