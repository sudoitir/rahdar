package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.platform.broker.SystemExtension;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Forms a cluster's health verdict the way its system's brokers are organised (ADR-0215): Artemis from its HA pairs,
 * Kafka from its brokers serving, its partitions in sync and its controller quorum. Every system answers on the same
 * scale, so the estate compares clusters of both, and states what it could not read in the notes rather than
 * counting it as healthy.
 */
public interface ClusterHealthEvaluator extends SystemExtension {

    /**
     * @param nodes the cluster's stored nodes grouped as logical nodes
     * @param rejectedNodeIds the stored nodes whose data-plane account a broker refused
     */
    ClusterHealth evaluate(UUID clusterId, List<LogicalNode> nodes, Set<UUID> rejectedNodeIds);
}
