package io.github.sudoitir.rahdar.system.kafka;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Whether the cluster answering at a registered address is still the cluster that was registered. A Kafka cluster
 * re-provisioned at the same bootstrap reports a new cluster id; the scrape records the change here, the health reads
 * it, and both stay on it until the live id matches the registered one again, so the two clusters' data is never
 * mixed.
 */
@Component
class KafkaIdentityWatch {

    private final Map<UUID, String> changes = new ConcurrentHashMap<>();

    /** Compares the live cluster id with the registered one, and returns whether they differ. */
    boolean check(UUID clusterId, String live, String registered) {
        if (live != null && registered != null && !live.equals(registered)) {
            changes.put(
                    clusterId,
                    "The cluster at this address changed: it reports cluster id " + live + ", registered as "
                            + registered + ". Re-register it or restore the original cluster.");
            return true;
        }
        changes.remove(clusterId);
        return false;
    }

    /** The words for a cluster whose identity changed, if it did. */
    Optional<String> change(UUID clusterId) {
        return Optional.ofNullable(changes.get(clusterId));
    }

    void forget(UUID clusterId) {
        changes.remove(clusterId);
    }
}
