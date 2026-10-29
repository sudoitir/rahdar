package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import java.time.Clock;
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
 * One batched listing of the partitions with only their leader asked for, read by the node scrape at most every
 * {@link #EVERY}. Health only reads the kept count, so a slow broker never holds a health request, and a count that
 * is older than {@link #STALE}, or was never read, is unknown rather than an invented zero.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class KafkaPartitions {

    /** The scrape reads the partitions again once this long has passed since its last try. */
    static final Duration EVERY = Duration.ofSeconds(30);

    /** A reading older than this is no longer a reading. */
    static final Duration STALE = Duration.ofMinutes(2);

    private static final String LEADER = "kafka.leader";
    private static final int PAGE = 1000;

    private record Reading(Instant at, long offline) {}

    private final BrokerGateway gateway;
    private final Clock clock;
    private final Map<UUID, Reading> readings = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> tried = new ConcurrentHashMap<>();

    /** Whether the scrape should read the cluster's partitions now. */
    boolean due(UUID clusterId) {
        Instant last = tried.get(clusterId);
        return last == null || Duration.between(last, clock.instant()).compareTo(EVERY) >= 0;
    }

    /** Reads the cluster's partitions now and keeps the count of those without a leader. */
    void refresh(UUID clusterId) {
        tried.put(clusterId, clock.instant());
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
        readings.put(clusterId, new Reading(clock.instant(), offline));
    }

    /** The partitions without a leader as last read; empty when they were never read or the reading is stale. */
    OptionalLong offline(UUID clusterId) {
        Reading reading = readings.get(clusterId);
        if (reading == null || Duration.between(reading.at(), clock.instant()).compareTo(STALE) > 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(reading.offline());
    }

    /** Whether a read of the cluster's partitions has been tried yet, whether or not it succeeded. */
    boolean tried(UUID clusterId) {
        return tried.containsKey(clusterId);
    }

    void forget(UUID clusterId) {
        readings.remove(clusterId);
        tried.remove(clusterId);
    }
}
