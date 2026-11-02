package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.messaging.Position;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Notice;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.LogBounds;
import io.github.sudoitir.rahdar.platform.broker.LogBounds.Bounds;
import io.github.sudoitir.rahdar.platform.broker.LogPage;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The SQL console's reach into a system that keeps its messages in logs and has no selectors: Kafka topics, read
 * by partition and offset, and RabbitMQ streams. The cluster is read as one, so a target has no node of its own;
 * it is answered by the cluster's first node. The broker cannot evaluate a predicate there, so the executor
 * evaluates all of it in Rahdar over what it reads, within the scan cap.
 */
@Component
@RequiredArgsConstructor
public class LogTargets {

    private final ClusterKinds kinds;
    private final ClusterDirectory nodes;
    private final BrokerGateway gateway;
    private final BrokerMessages messages;
    private final SystemExtensions extensions;

    /** One log a query can read: its id, its name, and the offsets it holds when they could be read. */
    public record Log(ResourceId id, String name, Long messageCount) {}

    /** Whether the cluster is read as one, so its queries are answered by reading logs. */
    public boolean applies(UUID clusterId) {
        return kinds.readAsOne(clusterId);
    }

    /**
     * Refuses a message index on a cluster whose system the index cannot capture from.
     *
     * @throws ConflictException when the cluster is read as one
     */
    public void requireIndexable(UUID clusterId) {
        if (applies(clusterId)) {
            String system = kinds.systemName(clusterId);
            throw new ConflictException(
                    "unsupported-on-system",
                    "The message index captures Artemis queues; this cluster runs " + system + ". "
                            + (streamsOnly(clusterId)
                                    ? "Query a stream directly: its log is kept."
                                    : "Query the topic directly: the log is kept."));
        }
    }

    /** The node that answers for the cluster as a whole. */
    public ClusterNode node(UUID clusterId) {
        return nodes.nodes(clusterId).stream()
                .min(Comparator.comparing(ClusterNode::getName))
                .orElseThrow(() -> new ConflictException("no-node", "This cluster has no node to read from."));
    }

    /**
     * The logs whose names match {@code pattern}. A queue that can only be consumed is not a log: named outright
     * it is refused with the reason, and matched by a pattern it is skipped and the notice says so.
     */
    public List<Log> resolve(UUID clusterId, String pattern, List<Notice> notices) {
        boolean streams = streamsOnly(clusterId);
        Set<KindId> logKinds = logKinds(clusterId);
        List<Resource> matched =
                list(clusterId, streams ? ResourceClass.SUBSCRIPTION : ResourceClass.DESTINATION).stream()
                        .filter(r -> QueueNamePattern.matches(pattern, r.id().name()))
                        .toList();
        List<Resource> readable = matched.stream()
                .filter(r -> !streams || logKinds.contains(r.id().kind()))
                .toList();
        List<String> refused = matched.stream()
                .filter(r -> !readable.contains(r))
                .map(r -> r.id().name())
                .toList();
        if (!refused.isEmpty()) {
            String reason = kinds.systemName(clusterId)
                    + " can browse only streams; classic and quorum queues can only be consumed, which would take"
                    + " the messages.";
            if (readable.isEmpty()) {
                throw new ConflictException("not-browsable", reason + " Not read: " + String.join(", ", refused) + ".");
            }
            notices.add(
                    new Notice(Notice.Kind.NOT_BROWSABLE, reason + " Skipped: " + String.join(", ", refused) + "."));
        }
        List<Log> logs = new ArrayList<>();
        for (Resource r : readable) {
            logs.add(new Log(r.id(), r.id().name(), count(clusterId, r.id())));
        }
        return logs;
    }

    /** The partitions of a log with their bounds, by number. */
    public SortedMap<Integer, Bounds> partitions(UUID clusterId, ResourceId log) {
        return bounds(clusterId).partitions(clusterId, log);
    }

    /** A log that is its own partitions' parent (a topic), as against a stream, which is partition 0 alone. */
    public boolean partitioned(UUID clusterId) {
        return !streamsOnly(clusterId);
    }

    /** One page of a partition, from an offset, unfiltered: the filter is Rahdar's. */
    public LogPage page(UUID clusterId, ResourceId log, int partition, long offset, int size) {
        Position at =
                new Position.AtOffsets(Map.of(Shard.partition(PartitionId.of(String.valueOf(partition))), offset));
        return messages.browseLog(clusterId, log, partition, at, size, null);
    }

    private Long count(UUID clusterId, ResourceId log) {
        try {
            return partitions(clusterId, log).values().stream()
                    .mapToLong(b -> b.end() - b.start())
                    .sum();
        } catch (RuntimeException _) {
            // Unreadable now: the estimate is unknown, and the read says what failed.
            return null;
        }
    }

    private boolean streamsOnly(UUID clusterId) {
        return !logKinds(clusterId).isEmpty();
    }

    private Set<KindId> logKinds(UUID clusterId) {
        return extensions
                .forCluster(clusterId, LogBounds.class)
                .map(b -> b.logs(clusterId))
                .orElse(Set.of());
    }

    private LogBounds bounds(UUID clusterId) {
        return extensions.require(clusterId, LogBounds.class);
    }

    private List<Resource> list(UUID clusterId, ResourceClass resourceClass) {
        try {
            ClusterConnection connection = gateway.cluster(clusterId);
            List<Resource> items = new ArrayList<>();
            Optional<String> next = Optional.empty();
            do {
                ResourceQuery query = ResourceQuery.of(resourceClass);
                ResourcePage page = connection
                        .resources()
                        .read(next.map(query::continueFrom).orElse(query))
                        .orThrow();
                items.addAll(page.items());
                next = page.continueToken();
            } while (next.isPresent());
            return items;
        } catch (BrokerException e) {
            throw BrokerFailures.translate(e);
        }
    }
}
