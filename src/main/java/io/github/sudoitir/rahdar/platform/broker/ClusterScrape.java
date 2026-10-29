package io.github.sudoitir.rahdar.platform.broker;

import java.util.List;
import java.util.UUID;

/**
 * Reading a cluster's state on the scrape schedule for a system that is not reached node by node over Jolokia: the
 * scheduler hands such a cluster to its system's scrape instead of probing each node (ADR-0015), and Artemis, which
 * has none, is probed as before. One batched read of the cluster per cycle.
 */
public interface ClusterScrape extends SystemExtension {

    /** Reads every node of the cluster once and records its state, or the error that kept it from answering. */
    void nodes(UUID clusterId, long cycle);

    /**
     * Reads every subscription of the cluster once, each tagged with a node of the cluster that carries it in the
     * snapshot cache: a system with subscriptions that belong to no node picks one.
     */
    List<QueueRow> subscriptions(UUID clusterId);
}
