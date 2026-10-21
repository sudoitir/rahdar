package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.QueueSettings;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator.QueueLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * What a queue is configured as, on each node that has it.
 *
 * <p>An update replaces the broker's whole configuration rather than merging into
 * it, so the edit form has to show what is there before an operator changes one
 * field of it: a form of blank inputs cannot say what the queue runs, and after an
 * update it cannot say whether the update took. The system reads it for the screen
 * the way it does for the update itself.
 *
 * <p>The configuration of a queue the caller may not read is not found, exactly as one that does not
 * exist is.
 *
 * <p>One read per node that has the queue, and a node that cannot be read says so
 * rather than dropping out of the list — an absent node reads as a queue that is
 * not there.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionConfigurationReader {

    private final QueueLocator queueLocator;
    private final ClusterDirectory clusters;
    private final BrokerGateway gateway;
    private final SystemExtensions extensions;
    private final ClusterAccessGuard access;

    /** One node's copy of the queue: its configuration, or why it could not be read. */
    public record NodeConfiguration(
            UUID nodeId, String nodeName, Map<String, Object> values, String unavailableReason) {}

    /** Every node's copy, with the destination and the system's own properties the subscription is bound as. */
    public record SubscriptionConfiguration(
            String id,
            String kind,
            String kindLabel,
            String name,
            String destination,
            Map<String, String> nativeAttributes,
            List<NodeConfiguration> nodes) {}

    public SubscriptionConfiguration read(UUID clusterId, ResourceId id) {
        String queueName = id.name();
        access.requireResource(clusterId, ResourceRef.subscription(queueName), Permissions.SUBSCRIPTION_READ);
        List<QueueLocation> locations = queueLocator.locate(clusterId, queueName);
        if (locations.isEmpty()) {
            throw new NotFoundException("queue", queueName);
        }
        QueueSettings settings = extensions.require(clusterId, QueueSettings.class);
        List<ClusterNode> nodes = clusters.nodes(clusterId);
        List<NodeConfiguration> read = new ArrayList<>();
        for (QueueLocation location : locations) {
            ClusterNode node = nodes.stream()
                    .filter(n -> n.getId().equals(location.nodeId()))
                    .findFirst()
                    .orElse(null);
            if (node == null) {
                continue;
            }
            try {
                BrokerTarget target = gateway.node(clusterId, node.getId());
                Map<String, Object> values =
                        settings.read(target, location.queueName(), location.address(), location.routingType());
                read.add(new NodeConfiguration(node.getId(), node.getName(), values, null));
            } catch (BrokerConnectionException e) {
                read.add(new NodeConfiguration(node.getId(), node.getName(), Map.of(), e.getMessage()));
            }
        }
        QueueLocation first = locations.getFirst();
        return new SubscriptionConfiguration(
                id.format(),
                id.kind().value(),
                ResourceIds.label(id.kind()),
                queueName,
                ResourceIds.of(gateway.cluster(clusterId), ResourceClass.DESTINATION, first.address())
                        .format(),
                Map.of("routingType", first.routingType()),
                read);
    }
}
