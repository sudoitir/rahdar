package io.github.sudoitir.rahdar.feature.messages;

import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.DlqAddress;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.DlqQueue;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.DlqQueueDepth;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.DlqView;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceKind;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.DeadLetterDestinations;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final DeadLetterDestinations deadLetters;

    public DlqView view(UUID clusterId) {
        clusterAccess.requireVisible(clusterId);
        ResourceFilter readableQueues = permissions.filter(clusterId, ResourceKind.QUEUE);
        ResourceFilter readableAddresses = permissions.filter(clusterId, ResourceKind.ADDRESS);
        ClusterNode manageable = brokerNodes.nodes(clusterId).stream()
                .filter(n -> n.getManagementUrl() != null)
                .findFirst()
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE,
                        "This cluster has no node with a management URL yet."));

        Optional<Map<String, String>> kinds = deadLetters.defaults(clusterId, manageable.getId());
        if (kinds.isEmpty()) {
            return new DlqView(List.of(), false);
        }

        Map<UUID, String> nodeNames = new LinkedHashMap<>();
        brokerNodes.nodes(clusterId).forEach(n -> nodeNames.put(n.getId(), n.getName()));
        List<QueueSnapshot> all = queueSnapshots.forCluster(clusterId);

        List<DlqAddress> addresses = new ArrayList<>();
        for (Map.Entry<String, String> entry : kinds.get().entrySet()) {
            String address = entry.getKey();
            List<DlqQueue> queues = readableQueuesOn(address, all, nodeNames, readableQueues);
            if (!queues.isEmpty() || readableAddresses.readable(address)) {
                addresses.add(new DlqAddress(address, entry.getValue(), queues));
            }
        }
        return new DlqView(addresses, true);
    }

    private static List<DlqQueue> readableQueuesOn(
            String address, List<QueueSnapshot> all, Map<UUID, String> nodeNames, ResourceFilter readableQueues) {
        Map<String, List<QueueSnapshot>> byQueue = new LinkedHashMap<>();
        for (QueueSnapshot s : all) {
            if (address.equals(s.address())) {
                byQueue.computeIfAbsent(s.queueName(), k -> new ArrayList<>()).add(s);
            }
        }
        List<DlqQueue> queues = new ArrayList<>();
        byQueue.forEach((queueName, rows) -> {
            if (!readableQueues.readable(queueName)) {
                return;
            }
            List<DlqQueueDepth> perNode = rows.stream()
                    .map(r -> new DlqQueueDepth(
                            r.nodeId(), nodeNames.getOrDefault(r.nodeId(), "unknown"), r.messageCount()))
                    .toList();
            long totalDepth = perNode.stream().mapToLong(DlqQueueDepth::depth).sum();
            queues.add(new DlqQueue(queueName, address, totalDepth, perNode));
        });
        return queues;
    }
}
