package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import java.util.List;
import java.util.Optional;

/**
 * Whether the cluster answering at a registered address is still the cluster that was registered. A Kafka cluster
 * re-provisioned at the same bootstrap reports a new cluster id. The scrape writes the words for that into every
 * stored broker's last error, which every replica reads, so health and the estate agree on any replica; it holds
 * there, and the scrape reads nothing further of the cluster, until the ids match and a broker answers again.
 */
final class KafkaClusterIdentity {

    private static final String CHANGED = "The cluster at this address changed: it reports cluster id ";

    private KafkaClusterIdentity() {}

    /** The words for a live cluster id that is not the registered one; empty when either is unknown or they match. */
    static Optional<String> change(String live, String registered) {
        if (live == null || registered == null || live.equals(registered)) {
            return Optional.empty();
        }
        return Optional.of(
                CHANGED + live + ", registered as " + registered + ". Re-register it or restore the original cluster.");
    }

    /** The change recorded on the cluster's stored brokers, if the scrape found one and it still stands. */
    static Optional<String> recorded(List<ClusterNode> nodes) {
        return nodes.stream()
                .map(ClusterNode::getLastError)
                .filter(error -> error != null && error.startsWith(CHANGED))
                .findFirst();
    }
}
