package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.openbroker.artemis.ArtemisMBeans;
import io.github.sudoitir.openbroker.artemis.JolokiaRequest;
import io.github.sudoitir.openbroker.artemis.JolokiaResponse;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ServingNodes;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Where a queue lives in a cluster: its address and routing type on each node that has it.
 *
 * <p>The scraped snapshot answers first. A queue the scrape has not reached yet — created a
 * moment ago, or on a node whose sweep has not come round — is looked up live, with one
 * pattern read per live node that answers where the queue is and how many messages it holds,
 * so an operator acting on a queue they can see on the broker is not told it does not exist.
 */
@Component
@RequiredArgsConstructor
public class QueueLocator {

    private final QueueSnapshots snapshots;
    private final ClusterDirectory clusters;
    private final BrokerConnections connections;
    private final ArtemisNodes artemisNodes;

    /**
     * One node's copy of a queue. {@code routingType} is upper case, as the snapshot stores it;
     * {@code messageCount} is the scraped depth, or the depth read live for a queue the scrape
     * has not reached.
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
        BrokerConnectionException unread = null;
        for (ClusterNode node : ServingNodes.from(clusters.nodes(clusterId))) {
            if (Boolean.TRUE.equals(node.getActive())) {
                try {
                    readLocations(clusterId, node, queueName, found);
                } catch (BrokerConnectionException e) {
                    unread = e;
                }
            }
        }
        if (found.isEmpty() && unread != null) {
            throw unread;
        }
        return found;
    }

    private void readLocations(UUID clusterId, ClusterNode node, String queueName, List<QueueLocation> found) {
        try {
            ArtemisNode client = artemisNodes.on(clusterId, node.getId());
            // A pattern read answers for every match at once, keyed by MBean name; a node
            // without the queue answers 404, which is simply not a location.
            JolokiaResponse read = client.single(JolokiaRequest.read(
                    ArtemisMBeans.queuePattern(client.resolveBrokerObjectName(), queueName), "MessageCount"));
            if (!read.ok() || read.value() == null || !read.value().isObject()) {
                return;
            }
            for (var entry : read.value().properties()) {
                ObjectName mbean = new ObjectName(entry.getKey());
                found.add(new QueueLocation(
                        node.getId(),
                        queueName,
                        value(mbean, "address"),
                        value(mbean, "routing-type").toUpperCase(Locale.ROOT),
                        entry.getValue().path("MessageCount").asLong()));
            }
        } catch (MalformedObjectNameException _) {
            // A name the broker answered with that is not an MBean name is not a location.
        }
    }

    /** Artemis quotes these values; an unquoted one is taken as it is. */
    private static String value(ObjectName mbean, String key) {
        String raw = mbean.getKeyProperty(key);
        return raw.startsWith("\"") ? ObjectName.unquote(raw) : raw;
    }
}
