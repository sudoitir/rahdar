package io.github.sudoitir.rahdar.feature.messages;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.DeadLetterDepth;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.DeadLetterDestination;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.DeadLetterSubscription;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.DeadLetterView;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.DeadLetterDestinations;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Dead-letter / expiry view (ADR-0021, D8). The DLA and EA come from the broker's
 * own {@code getAddressSettingsAsJSON("#")}, never a name match — if that read
 * fails the view says so ({@code settingsAvailable = false}) and infers nothing.
 * Queues on those addresses and their per-node depth come from the
 * {@code subscription_snapshot} cache. A queue the caller may not read is left out, and an address appears only
 * through a queue they may read or when they may read the address.
 */
@Service
@RequiredArgsConstructor
public class DlqService {

    private final QueueSnapshots queueSnapshots;
    private final ClusterDirectory brokerNodes;
    private final BrokerConnections connections;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver permissions;
    private final SystemExtensions extensions;
    private final BrokerGateway gateway;

    public DeadLetterView view(UUID clusterId) {
        clusterAccess.requireVisible(clusterId);
        // A system that keeps no default dead-letter destinations in its brokers' configuration has none to show.
        Optional<DeadLetterDestinations> own = extensions.forCluster(clusterId, DeadLetterDestinations.class);
        if (own.isEmpty()) {
            return new DeadLetterView(List.of(), false);
        }
        ResourceFilter readableQueues = permissions.filter(clusterId, ResourceType.SUBSCRIPTION);
        ResourceFilter readableAddresses = permissions.filter(clusterId, ResourceType.DESTINATION);
        ClusterNode manageable = brokerNodes.nodes(clusterId).stream()
                .filter(n -> n.getManagementUrl() != null)
                .findFirst()
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE,
                        "This cluster has no node with a management URL yet."));

        Optional<Map<String, String>> kinds = own.get().defaults(clusterId, manageable.getId());
        if (kinds.isEmpty()) {
            return new DeadLetterView(List.of(), false);
        }

        Map<UUID, String> nodeNames = new LinkedHashMap<>();
        brokerNodes.nodes(clusterId).forEach(n -> nodeNames.put(n.getId(), n.getName()));
        List<QueueSnapshot> all = queueSnapshots.forCluster(clusterId);
        ClusterConnection connection = gateway.cluster(clusterId);

        List<DeadLetterDestination> destinations = new ArrayList<>();
        for (Map.Entry<String, String> entry : kinds.get().entrySet()) {
            String address = entry.getKey();
            List<DeadLetterSubscription> queues = readableQueuesOn(connection, address, all, nodeNames, readableQueues);
            if (!queues.isEmpty() || readableAddresses.readable(address)) {
                ResourceId id = ResourceIds.of(connection, ResourceClass.DESTINATION, address);
                destinations.add(new DeadLetterDestination(
                        id.format(),
                        id.kind().value(),
                        ResourceIds.label(connection, id.kind()),
                        address,
                        entry.getValue(),
                        queues));
            }
        }
        return new DeadLetterView(destinations, true);
    }

    private static List<DeadLetterSubscription> readableQueuesOn(
            ClusterConnection connection,
            String address,
            List<QueueSnapshot> all,
            Map<UUID, String> nodeNames,
            ResourceFilter readableQueues) {
        Map<String, List<QueueSnapshot>> byQueue = new LinkedHashMap<>();
        for (QueueSnapshot s : all) {
            if (address.equals(s.address())) {
                byQueue.computeIfAbsent(s.queueName(), k -> new ArrayList<>()).add(s);
            }
        }
        List<DeadLetterSubscription> queues = new ArrayList<>();
        byQueue.forEach((queueName, rows) -> {
            if (!readableQueues.readable(queueName)) {
                return;
            }
            List<DeadLetterDepth> perNode = rows.stream()
                    .map(r -> new DeadLetterDepth(
                            r.nodeId(), nodeNames.getOrDefault(r.nodeId(), "unknown"), r.messageCount()))
                    .toList();
            List<Long> known = perNode.stream()
                    .map(DeadLetterDepth::depth)
                    .filter(Objects::nonNull)
                    .toList();
            Long totalDepth = known.isEmpty()
                    ? null
                    : Long.valueOf(known.stream().mapToLong(Long::longValue).sum());
            ResourceId id = ResourceIds.of(connection, ResourceClass.SUBSCRIPTION, queueName);
            queues.add(new DeadLetterSubscription(
                    id.format(),
                    id.kind().value(),
                    ResourceIds.label(connection, id.kind()),
                    queueName,
                    ResourceIds.of(connection, ResourceClass.DESTINATION, address)
                            .format(),
                    totalDepth,
                    perNode.size() - known.size(),
                    perNode));
        });
        return queues;
    }
}
