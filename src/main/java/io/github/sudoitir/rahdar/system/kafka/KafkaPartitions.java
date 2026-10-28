package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * How many partitions of a cluster have no leader, which makes their records unreadable and unwritable (ADR-0215).
 * One batched listing of the partitions with only their leader asked for, refreshed by the node scrape each cycle
 * and, for a replica that does not scrape the cluster, by the first health read after the reading goes stale. A
 * listing that fails leaves the last reading to age out rather than invent a zero.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class KafkaPartitions {

    /** A reading older than this is read again by whoever asks. */
    static final Duration FRESH = Duration.ofSeconds(60);

    private static final String LEADER = "kafka.leader";
    private static final int PAGE = 1000;

    private record Reading(Instant at, long offline) {}

    private final BrokerGateway gateway;
    private final Map<UUID, Reading> readings = new ConcurrentHashMap<>();

    /** Reads the cluster's partitions now and keeps the count of those without a leader. */
    void refresh(UUID clusterId) {
        long offline = 0;
        Optional<String> next = Optional.empty();
        try {
            do {
                ResourceQuery query = ResourceQuery.of(KindId.of("kafka.partition"))
                        .project(Set.of(LEADER))
                        .limit(PAGE);
                ResourcePage page = gateway.cluster(clusterId)
                        .resources()
                        .read(next.map(query::continueFrom).orElse(query))
                        .orThrow();
                offline += page.items().stream()
                        .filter(partition -> partition.attributes().raw(LEADER).isEmpty())
                        .count();
                next = page.continueToken();
            } while (next.isPresent());
        } catch (BrokerException e) {
            log.debug("Listing the partitions of cluster {} failed: {}", clusterId, e.toString());
            return;
        }
        readings.put(clusterId, new Reading(Instant.now(), offline));
    }

    /** The partitions without a leader, read again when the last reading is stale; empty when it cannot be read. */
    OptionalLong offline(UUID clusterId) {
        Reading reading = readings.get(clusterId);
        if (reading == null || Duration.between(reading.at(), Instant.now()).compareTo(FRESH) > 0) {
            refresh(clusterId);
            reading = readings.get(clusterId);
        }
        return reading == null ? OptionalLong.empty() : OptionalLong.of(reading.offline());
    }

    void forget(UUID clusterId) {
        readings.remove(clusterId);
    }
}
