package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BacklogBreakdown;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A group's backlog on a topic by partition: its committed offset, the partition's end offset and the lag between
 * them, or why the lag is not known (a partition the group has no offset on is never shown as zero lag).
 */
@Component
@RequiredArgsConstructor
class KafkaBacklogBreakdown implements BacklogBreakdown {

    private static final String OFFSETS = "kafka.offsets";
    private static final String LAG_UNAVAILABLE = "kafka.lag_unavailable";
    private static final String LOG_END_OFFSET = "kafka.log_end_offset";

    private final BrokerGateway gateway;

    @Override
    public String system() {
        return KafkaModule.SYSTEM;
    }

    @Override
    public Breakdown read(UUID clusterId, ResourceId subscription) {
        List<String> path = subscription.path().segments();
        if (path.size() != 2) {
            throw new NotFoundException("subscription", subscription.format());
        }
        ClusterConnection connection = gateway.cluster(clusterId);
        try {
            Resource group = connection
                    .resources()
                    .read(ResourceQuery.of(subscription.kind())
                            .where(Map.of("group", path.get(0), "topic", path.get(1))))
                    .orThrow()
                    .items()
                    .stream()
                    .filter(r -> r.id().equals(subscription))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("subscription", subscription.format()));
            Map<Integer, Long> ends = ends(connection, path.get(1));
            return new Breakdown(
                    group.observedAt(),
                    partitions(group.attributes(), ends),
                    group.attributes().get(StandardAttributes.DESTINATION).orElse(null));
        } catch (BrokerException e) {
            throw BrokerFailures.translate(e);
        }
    }

    private static Map<Integer, Long> ends(ClusterConnection connection, String topic) {
        Map<Integer, Long> ends = new TreeMap<>();
        for (Resource partition : connection
                .resources()
                .read(ResourceQuery.of(KindId.of("kafka.partition"))
                        .where(Map.of("topic", topic))
                        .project(Set.of(LOG_END_OFFSET)))
                .orThrow()
                .items()) {
            partition
                    .attributes()
                    .raw(LOG_END_OFFSET)
                    .ifPresent(end ->
                            ends.put(Integer.parseInt(partition.id().name()), Long.parseLong(String.valueOf(end))));
        }
        return ends;
    }

    /** The partitions of the subscription's attributes, with the end offsets read beside them; slowest first. */
    static List<PartitionLag> partitions(Attributes attributes, Map<Integer, Long> ends) {
        Map<Integer, Long> committed = numbers(attributes.raw(OFFSETS).orElse(Map.of()));
        Map<Integer, String> unavailable = unavailable(attributes);
        Map<Integer, Long> lags = lags(attributes);
        List<PartitionLag> out = new ArrayList<>();
        Set<Integer> all = new TreeSet<>(ends.keySet());
        all.addAll(committed.keySet());
        all.addAll(unavailable.keySet());
        for (int partition : all) {
            out.add(lagOf(partition, committed, ends, unavailable, lags));
        }
        out.sort(Comparator.comparing((PartitionLag p) -> p.lag() == null ? -1L : p.lag())
                .reversed()
                .thenComparingInt(PartitionLag::partition));
        return out;
    }

    private static Map<Integer, String> unavailable(Attributes attributes) {
        Map<Integer, String> unavailable = new TreeMap<>();
        if (attributes.raw(LAG_UNAVAILABLE).orElse(Map.of()) instanceof Map<?, ?> reasons) {
            reasons.forEach((p, why) -> unavailable.put(Integer.parseInt(String.valueOf(p)), String.valueOf(why)));
        }
        return unavailable;
    }

    private static Map<Integer, Long> lags(Attributes attributes) {
        Map<Integer, Long> lags = new TreeMap<>();
        MetricValue backlog = attributes.get(MetricKeys.BACKLOG).orElse(null);
        if (backlog != null) {
            backlog.breakdown()
                    .forEach((shard, lag) -> partitionOf(shard).ifPresent(p -> lags.put(p, lag.longValue())));
        }
        return lags;
    }

    private static PartitionLag lagOf(
            int partition,
            Map<Integer, Long> committed,
            Map<Integer, Long> ends,
            Map<Integer, String> unavailable,
            Map<Integer, Long> lags) {
        Long lag = lags.get(partition);
        // A lag is measured from the end, so the end is the position plus the lag when it was not read apart.
        Long end = endOf(partition, committed, ends, lag);
        String reason = unavailable.get(partition);
        if (lag == null && reason == null) {
            reason = end == null ? "The partition's end offset could not be read." : "The lag could not be computed.";
        }
        return new PartitionLag(partition, committed.get(partition), end, lag, reason);
    }

    private static Long endOf(int partition, Map<Integer, Long> committed, Map<Integer, Long> ends, Long lag) {
        if (ends.containsKey(partition)) {
            return ends.get(partition);
        }
        if (committed.containsKey(partition) && lag != null) {
            return committed.get(partition) + lag;
        }
        return null;
    }

    private static Optional<Integer> partitionOf(Shard shard) {
        if (shard instanceof Shard.Partition(PartitionId id)) {
            return Optional.of(Integer.parseInt(id.value()));
        }
        return Optional.empty();
    }

    private static Map<Integer, Long> numbers(Object raw) {
        Map<Integer, Long> out = new TreeMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(Integer.parseInt(String.valueOf(k)), ((Number) v).longValue()));
        }
        return out;
    }
}
