package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaReassignmentEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaReassignmentRepository;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReassignmentList;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReassignmentRow;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The throttle baseline of each replica move this product started (ADR-0230). A second move of the same partition
 * keeps a value the first move already stored. Listing joins those rows with the moves the broker still reports.
 */
@Component
@RequiredArgsConstructor
class KafkaReassignmentLog {

    private final KafkaReassignmentRepository reassignments;
    private final Clock clock;

    void remember(
            UUID clusterId,
            String topic,
            int partition,
            Long operationId,
            Map<String, String> previous,
            List<String> added) {
        long auditId = operationId == null ? 0L : operationId;
        Optional<KafkaReassignmentEntity> found =
                reassignments.findByClusterIdAndTopicAndPartition(clusterId, topic, partition);
        if (found.isEmpty()) {
            reassignments.save(new KafkaReassignmentEntity(
                    clusterId, topic, partition, auditId, previous, String.join(",", added), clock.instant()));
            return;
        }
        KafkaReassignmentEntity row = found.get();
        Map<String, String> merged = new LinkedHashMap<>(row.getPreviousThrottle());
        LinkedHashSet<String> keys = new LinkedHashSet<>(split(row.getAddedKeys()));
        previous.forEach((key, value) -> {
            if (!merged.containsKey(key) && !keys.contains(key)) {
                merged.put(key, value);
            }
        });
        keys.addAll(added);
        row.replace(auditId, merged, String.join(",", keys));
        reassignments.save(row);
    }

    ReassignmentList list(UUID clusterId, List<Resource> moving) {
        Map<String, KafkaReassignmentEntity> stored = new LinkedHashMap<>();
        reassignments
                .findByClusterId(clusterId)
                .forEach(row -> stored.put(key(row.getTopic(), row.getPartition()), row));
        Map<String, ReassignmentRow> rows = new LinkedHashMap<>();
        for (Resource resource : moving) {
            String topic = KafkaPlacement.topic(resource);
            int partition = KafkaPlacement.partition(resource);
            String id = key(topic, partition);
            rows.put(
                    id,
                    new ReassignmentRow(
                            topic,
                            partition,
                            resource.attributes()
                                    .raw(KafkaKinds.REASSIGNMENT)
                                    .map(String::valueOf)
                                    .orElse(null),
                            stored.containsKey(id)));
        }
        stored.forEach(
                (id, row) -> rows.putIfAbsent(id, new ReassignmentRow(row.getTopic(), row.getPartition(), null, true)));
        return new ReassignmentList(List.copyOf(rows.values()));
    }

    private static List<String> split(String addedKeys) {
        if (addedKeys == null || addedKeys.isBlank()) {
            return List.of();
        }
        return List.of(addedKeys.split(","));
    }

    private static String key(String topic, int partition) {
        return topic + "\0" + partition;
    }
}
