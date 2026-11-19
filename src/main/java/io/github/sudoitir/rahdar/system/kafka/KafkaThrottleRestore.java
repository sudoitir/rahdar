package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation.Update;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaReassignmentEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaReassignmentRepository;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigChangeRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Puts a move's throttle back once the broker no longer lists that reassignment (ADR-0230). A key this move added is
 * removed. A key that already had a value is set back to it. A second pass finds no row and writes nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class KafkaThrottleRestore {

    private static final String BROKER_PREFIX = "broker.";

    private final BrokerGateway gateway;
    private final KafkaReassignmentRepository reassignments;

    /** Restores every finished move of the cluster. A row whose reassignment is still listed is left for later. */
    void restore(UUID clusterId) {
        for (KafkaReassignmentEntity row : reassignments.findByClusterId(clusterId)) {
            if (stillMoving(clusterId, row)) {
                continue;
            }
            try {
                writeBack(clusterId, row);
                reassignments.delete(row);
            } catch (RuntimeException _) {
                log.warn(
                        "Replication throttle was not restored topic={} partition={}",
                        row.getTopic(),
                        row.getPartition());
            }
        }
    }

    private boolean stillMoving(UUID clusterId, KafkaReassignmentEntity row) {
        try {
            Resource resource = KafkaGroups.read(
                            gateway.cluster(clusterId),
                            ResourceQuery.of(KafkaKinds.PARTITION)
                                    .where(Map.of("topic", row.getTopic()))
                                    .named(Integer.toString(row.getPartition()))
                                    .project(Set.of(KafkaKinds.REASSIGNMENT)))
                    .items()
                    .stream()
                    .filter(item -> KafkaPlacement.partition(item) == row.getPartition()
                            && row.getTopic().equals(KafkaPlacement.topic(item)))
                    .findFirst()
                    .orElse(null);
            if (resource == null) {
                return false;
            }
            return resource.attributes()
                    .raw(KafkaKinds.REASSIGNMENT)
                    .map(String::valueOf)
                    .filter(value -> !value.isBlank())
                    .isPresent();
        } catch (NotFoundException _) {
            return false;
        }
    }

    private void writeBack(UUID clusterId, KafkaReassignmentEntity row) {
        Map<String, String> previous = row.getPreviousThrottle() == null ? Map.of() : row.getPreviousThrottle();
        List<String> added = split(row.getAddedKeys());
        Map<Target, Map<String, String>> sets = new LinkedHashMap<>();
        Map<Target, List<String>> removes = new LinkedHashMap<>();
        previous.forEach((key, value) -> sets.computeIfAbsent(target(key), ignored -> new LinkedHashMap<>())
                .put(setting(key), value));
        for (String key : added) {
            if (previous.containsKey(key)) {
                continue;
            }
            removes.computeIfAbsent(target(key), ignored -> new ArrayList<>()).add(setting(key));
        }
        LinkedHashMap<Target, Boolean> targets = new LinkedHashMap<>();
        sets.keySet().forEach(target -> targets.put(target, true));
        removes.keySet().forEach(target -> targets.putIfAbsent(target, true));
        for (Target target : targets.keySet()) {
            Map<String, String> set = sets.getOrDefault(target, Map.of());
            List<String> remove = removes.getOrDefault(target, List.of());
            if (set.isEmpty() && remove.isEmpty()) {
                continue;
            }
            write(clusterId, row, target, set, remove);
        }
    }

    private void write(
            UUID clusterId, KafkaReassignmentEntity row, Target target, Map<String, String> set, List<String> remove) {
        ConfigChangeRequest request = new ConfigChangeRequest(set, remove, null);
        ResourceId id;
        Attributes patch;
        if (target.broker()) {
            id = KafkaBrokerConfigs.id(target.id());
            patch = KafkaBrokerConfigs.patch(request);
        } else {
            id = new ResourceId(KafkaKinds.TOPIC, ResourcePath.of(row.getTopic()));
            patch = Attributes.of(KafkaTopicConfigs.patch(request));
        }
        KafkaFailures.call(() -> gateway.cluster(clusterId)
                .operations()
                .execute(id, new Update(patch), Scope.whole(), Precondition.none())
                .orThrow());
        if (target.broker()) {
            log.info(
                    "Restored replication throttle topic={} partition={} broker={}",
                    row.getTopic(),
                    row.getPartition(),
                    target.id());
        } else {
            log.info("Restored replication throttle topic={} partition={}", row.getTopic(), row.getPartition());
        }
    }

    /** {@code broker.<id>.<setting>} or {@code topic.<setting>}, as the driver names a throttle key. */
    private static Target target(String key) {
        if (key.startsWith(BROKER_PREFIX)) {
            int second = key.indexOf('.', BROKER_PREFIX.length());
            if (second < 0) {
                throw new IllegalArgumentException("Not a throttle key: " + key);
            }
            return new Target(true, key.substring(BROKER_PREFIX.length(), second));
        }
        if (key.startsWith("topic.")) {
            return new Target(false, null);
        }
        throw new IllegalArgumentException("Not a throttle key: " + key);
    }

    private static String setting(String key) {
        if (key.startsWith(BROKER_PREFIX)) {
            return key.substring(key.indexOf('.', BROKER_PREFIX.length()) + 1);
        }
        return key.substring("topic.".length());
    }

    private static List<String> split(String addedKeys) {
        if (addedKeys == null || addedKeys.isBlank()) {
            return List.of();
        }
        return List.of(addedKeys.split(","));
    }

    private record Target(boolean broker, String id) {}
}
