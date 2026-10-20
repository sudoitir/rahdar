package io.github.sudoitir.rahdar.feature.brokerconfig;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigOperations.ReadScope;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ServingNodes;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The observed side of every configuration comparison: one logical node per
 * cluster (the live member of an HA pair, from polled topology — never from
 * configuration, non-negotiable #4), each read through the per-node limiter, and a
 * read that fails turned into an {@code unreachable} observation rather than an
 * exception, so one dead node never hides the others.
 */
@Component
@RequiredArgsConstructor
public class BrokerConfigReads {

    private final ClusterDirectory brokerNodes;
    private final BrokerConnections connections;
    private final BrokerConfigOperations ops;
    private final ArtemisNodes artemisNodes;

    /** The cluster's logical nodes, live member first where one exists. */
    public List<ClusterNode> targets(UUID clusterId) {
        return ServingNodes.from(brokerNodes.nodes(clusterId));
    }

    /** Observe every logical node of the cluster within {@code scope}. */
    public List<ObservedNodeConfig> observe(UUID clusterId, ReadScope scope) {
        return targets(clusterId).stream()
                .map(node -> observe(clusterId, node, scope))
                .toList();
    }

    /** Observe one node; not-live and unreachable are answers, not failures. */
    public ObservedNodeConfig observe(UUID clusterId, ClusterNode node, ReadScope scope) {
        if (!Boolean.TRUE.equals(node.getActive())) {
            return ObservedNodeConfig.notLive(node.getId(), node.getName());
        }
        try {
            return ops.read(client(clusterId, node), node.getId(), node.getName(), scope);
        } catch (BrokerConnectionException e) {
            return ObservedNodeConfig.unreachable(node.getId(), node.getName(), e.kind() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            return ObservedNodeConfig.unreachable(node.getId(), node.getName(), "BAD_RESPONSE: " + e.getMessage());
        }
    }

    /**
     * A client for one node; a write path uses this too. The client waits for the node's ceiling
     * before every request it sends (ADR-0076), so a fifty-step apply is charged fifty permits.
     */
    public ArtemisNode client(UUID clusterId, ClusterNode node) {
        return artemisNodes.on(clusterId, node.getId());
    }
}
