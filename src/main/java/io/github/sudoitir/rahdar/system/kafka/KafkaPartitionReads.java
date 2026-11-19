package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.PartitionPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.PartitionRow;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReplicaSuggestion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Where a topic's partitions sit, and which of a cluster's partitions are not fully placed (ADR-0230). Reads ask for
 * the partition attributes in one listing. A suggestion keeps the current replicas and names a rack it would add. It
 * changes nothing.
 */
@Service
@RequiredArgsConstructor
public class KafkaPartitionReads {

    private static final String TOPIC = "topic";

    /** Everything a placement row shows. An empty projection would also do, and would read more than the row uses. */
    static final Set<String> PROJECTION = Set.of(
            KafkaKinds.LEADER,
            KafkaKinds.REPLICAS,
            KafkaKinds.IN_SYNC_REPLICAS,
            KafkaKinds.ELIGIBLE_LEADER_REPLICAS,
            KafkaKinds.LOG_START_OFFSET,
            KafkaKinds.LOG_END_OFFSET,
            KafkaKinds.SIZE_BYTES,
            KafkaKinds.REPLICA_SIZE_BYTES,
            KafkaKinds.OFFSET_LAG,
            KafkaKinds.FUTURE_REPLICA,
            KafkaKinds.LOG_DIRS_UNAVAILABLE,
            KafkaKinds.LOCAL_LOG_START_OFFSET,
            KafkaKinds.TIERED_OFFSET,
            KafkaKinds.TIERED_OFFSET_REASON,
            KafkaKinds.REASSIGNMENT);

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;

    /** One page of a topic's partitions, in the cluster's order. */
    public PartitionPage partitions(UUID clusterId, String topic, String cursor, Integer limit) {
        access.requireResource(clusterId, ResourceRef.destination(topic), Permissions.DESTINATION_READ);
        ResourceQuery query = ResourceQuery.of(KafkaKinds.PARTITION)
                .where(Map.of(TOPIC, topic))
                .project(PROJECTION)
                .limit(KafkaPlacement.pageSize(limit));
        if (cursor != null && !cursor.isBlank()) {
            query = query.continueFrom(cursor);
        }
        ResourcePage page = KafkaGroups.read(gateway.cluster(clusterId), query);
        List<PartitionRow> rows = page.items().stream().map(KafkaPlacement::row).toList();
        return new PartitionPage(rows, page.continueToken().orElse(null));
    }

    /**
     * Partitions that are under-replicated, have an offline replica, or are not led by their first replica. A healthy
     * partition is not returned. The cursor is an offset into that filtered list.
     */
    public PartitionPage replication(UUID clusterId, String topic, String cursor, Integer limit) {
        if (topic != null && !topic.isBlank()) {
            access.requireResource(clusterId, ResourceRef.destination(topic), Permissions.DESTINATION_READ);
        } else {
            access.requireCluster(clusterId, Permissions.DESTINATION_READ);
        }
        List<Resource> interesting = readAll(clusterId, topic).stream()
                .filter(KafkaPlacement::needsAttention)
                .sorted(Comparator.comparing(KafkaPlacement::topic).thenComparingInt(KafkaPlacement::partition))
                .toList();
        int from = KafkaPlacement.cursor(cursor);
        int to = Math.min(interesting.size(), from + KafkaPlacement.pageSize(limit));
        if (from > interesting.size()) {
            return new PartitionPage(List.of(), null);
        }
        List<PartitionRow> rows =
                interesting.subList(from, to).stream().map(KafkaPlacement::row).toList();
        return new PartitionPage(rows, to < interesting.size() ? Integer.toString(to) : null);
    }

    /** A rack suggestion for one partition. The current replicas stay, in their order, and nothing is sent. */
    public ReplicaSuggestion suggest(UUID clusterId, String topic, int partition) {
        access.requireResource(clusterId, ResourceRef.destination(topic), Permissions.DESTINATION_READ);
        Resource resource = find(clusterId, topic, partition);
        List<Integer> current = KafkaPlacement.ints(
                resource.attributes().raw(KafkaKinds.REPLICAS).orElse(null));
        List<NodeDescription> nodes = KafkaFailures.call(
                () -> gateway.cluster(clusterId).nodes().describe(Scope.whole()).orThrow());
        List<Integer> replicas = KafkaPlacement.suggest(current, KafkaPlacement.racks(nodes));
        return new ReplicaSuggestion(
                KafkaPlacement.SUGGESTION, topic, partition, current, replicas, KafkaPlacement.SUGGESTION_NOTE);
    }

    Resource find(UUID clusterId, String topic, int partition) {
        ResourceId id = new ResourceId(KafkaKinds.PARTITION, ResourcePath.of(topic, Integer.toString(partition)));
        return KafkaGroups.read(
                        gateway.cluster(clusterId),
                        ResourceQuery.of(KafkaKinds.PARTITION)
                                .where(Map.of(TOPIC, topic))
                                .named(Integer.toString(partition))
                                .project(PROJECTION))
                .items()
                .stream()
                .filter(resource -> resource.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("partition", topic + "-" + partition));
    }

    private List<Resource> readAll(UUID clusterId, String topic) {
        ResourceQuery query = ResourceQuery.of(KafkaKinds.PARTITION).project(PROJECTION);
        if (topic != null && !topic.isBlank()) {
            query = query.where(Map.of(TOPIC, topic));
        }
        List<Resource> all = new ArrayList<>();
        Optional<String> cursor = Optional.empty();
        ResourceQuery start = query;
        do {
            ResourcePage page = KafkaGroups.read(
                    gateway.cluster(clusterId), cursor.map(start::continueFrom).orElse(start));
            all.addAll(page.items());
            cursor = page.continueToken();
        } while (cursor.isPresent());
        return all;
    }
}
