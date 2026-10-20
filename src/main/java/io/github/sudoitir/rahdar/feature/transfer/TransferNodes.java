package io.github.sudoitir.rahdar.feature.transfer;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerRelay;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance;
import io.github.sudoitir.rahdar.platform.broker.TransferStaging;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The nodes a transfer talks to. A run names each side by the broker's own node id, shared by a
 * live/backup pair, and resolves the endpoint serving it at each batch, so a failover continues on the
 * new live node.
 */
@Component
@RequiredArgsConstructor
class TransferNodes {

    private final ClusterDirectory directory;
    private final SystemExtensions extensions;

    /** A node of this cluster by Rahdar's id; 404 when it is not one. */
    ClusterNode node(UUID clusterId, UUID nodeId) {
        return directory
                .node(nodeId)
                .filter(n -> n.getClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("node", nodeId));
    }

    static boolean live(ClusterNode node) {
        return Boolean.TRUE.equals(node.getActive()) && node.getLastError() == null;
    }

    static boolean backup(ClusterNode node) {
        return "BACKUP".equals(node.getHaDesignation());
    }

    /** The manageable endpoint live now for this broker node id. */
    ClusterNode serving(UUID clusterId, String nativeNodeId, String name) {
        return directory.nodes(clusterId).stream()
                .filter(n -> nativeNodeId.equals(n.getNativeNodeId()))
                .filter(n -> n.getManagementUrl() != null && live(n) && !backup(n))
                .findFirst()
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE,
                        "Node " + name + " has no live, manageable endpoint now."));
    }

    /** The staging a node's cluster's system provides. */
    TransferStaging staging(ClusterNode node) {
        return staging(node.getClusterId());
    }

    /** The staging a cluster's system provides. */
    TransferStaging staging(UUID clusterId) {
        return extensions.require(clusterId, TransferStaging.class);
    }

    /** The acceptance rules a node's cluster's system provides. */
    TransferAcceptance acceptance(ClusterNode node) {
        return extensions.require(node.getClusterId(), TransferAcceptance.class);
    }

    /** The node as a relay end: one it reaches over its data plane, which it must have. */
    BrokerRelay.Endpoint core(ClusterNode node) {
        if (node.getDataUrl() == null) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "Node " + node.getName() + " has no Core URL, so Rahdar cannot relay messages to or from it.");
        }
        return new BrokerRelay.Endpoint(node.getClusterId(), node.getId());
    }
}
