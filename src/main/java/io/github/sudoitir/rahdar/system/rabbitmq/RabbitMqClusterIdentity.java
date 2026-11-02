package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import java.util.List;
import java.util.Optional;

/**
 * Whether the cluster answering at a registered address is still the cluster that was registered (ADR-0223). A
 * RabbitMQ cluster is known by its {@code cluster_name}, which every node reports. The scrape writes the words for a
 * change into every stored node's last error, which every replica reads, so health and the estate agree on any
 * replica; it holds there, and the scrape reads nothing further of the cluster, until the names match and a node
 * answers again.
 */
final class RabbitMqClusterIdentity {

    private static final String CHANGED = "The cluster at this address changed: it reports cluster name ";

    private RabbitMqClusterIdentity() {}

    /** The words for a live cluster name that is not the registered one; empty when either is unknown or they match. */
    static Optional<String> change(String live, String registered) {
        if (live == null || registered == null || live.equals(registered)) {
            return Optional.empty();
        }
        return Optional.of(CHANGED + live + ", registered as " + registered
                + ". If it was renamed, check and save the connection again to claim it.");
    }

    /** The change recorded on the cluster's stored nodes, if the scrape found one and it still stands. */
    static Optional<String> recorded(List<ClusterNode> nodes) {
        return nodes.stream()
                .map(ClusterNode::getLastError)
                .filter(error -> error != null && error.startsWith(CHANGED))
                .findFirst();
    }
}
