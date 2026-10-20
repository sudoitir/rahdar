package io.github.sudoitir.rahdar.platform.scrape;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ServingNodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Where a queue lives in a cluster: its address and routing type on each node that has it.
 *
 * <p>The scraped snapshot answers first. A queue the scrape has not reached yet — created a
 * moment ago, or on a node whose sweep has not come round — is looked up live through the broker
 * SDK, one listing by name per live node, so an operator acting on a queue they can see on the
 * broker is not told it does not exist.
 */
@Component
@RequiredArgsConstructor
public class QueueLocator {

    private final QueueSnapshots snapshots;
    private final ClusterDirectory clusters;
    private final BrokerGateway gateway;

    /**
     * One node's copy of a queue. {@code routingType} is upper case, as the snapshot stores it, and
     * {@code null} on a system without one; {@code messageCount} is the scraped depth, or the depth
     * read live for a queue the scrape has not reached.
     */
    public record QueueLocation(UUID nodeId, String queueName, String address, String routingType, long messageCount) {}

    /** Every node's copy of the queue; empty when no node has it. */
    public List<QueueLocation> locate(UUID clusterId, String queueName) {
        List<QueueLocation> cached = snapshots.forCluster(clusterId).stream()
                .filter(s -> s.queueName().equals(queueName))
                .map(s -> new QueueLocation(s.nodeId(), queueName, s.address(), s.routingType(), s.messageCount()))
                .toList();
        return cached.isEmpty() ? live(clusterId, queueName) : cached;
    }

    /**
     * Reads every active node. A node that cannot be read has not shown the queue, but when no node
     * shows it and one could not be read, the queue may still be there: that is reported as the node's
     * failure (throttled or unreachable), never as a queue that does not exist.
     */
    private List<QueueLocation> live(UUID clusterId, String queueName) {
        List<QueueLocation> found = new ArrayList<>();
        RuntimeException unread = null;
        for (ClusterNode node : ServingNodes.from(clusters.nodes(clusterId))) {
            if (Boolean.TRUE.equals(node.getActive())) {
                try {
                    read(clusterId, node, queueName, found);
                } catch (BrokerConnectionException e) {
                    unread = e;
                } catch (BrokerException e) {
                    unread = BrokerFailures.translate(e);
                }
            }
        }
        if (found.isEmpty() && unread != null) {
            throw unread;
        }
        return found;
    }

    private void read(UUID clusterId, ClusterNode node, String queueName, List<QueueLocation> found) {
        ClusterConnection c = gateway.cluster(clusterId);
        List<Resource> items = c.resources()
                .read(ResourceQuery.of(ResourceClass.SUBSCRIPTION)
                        .named(queueName)
                        .in(Scope.node(BrokerGateway.idOf(node.getId()))))
                .orThrow()
                .items();
        for (Resource r : items) {
            if (!r.id().name().equals(queueName)) {
                continue;
            }
            String routingType = r.attributes()
                    .raw(QueueRow.ROUTING_TYPE)
                    .map(t -> String.valueOf(t).toUpperCase(Locale.ROOT))
                    .orElse(null);
            found.add(new QueueLocation(
                    node.getId(),
                    queueName,
                    ResourceIds.name(r, StandardAttributes.DESTINATION),
                    routingType,
                    ResourceIds.count(r, MetricKeys.BACKLOG)));
        }
    }
}
