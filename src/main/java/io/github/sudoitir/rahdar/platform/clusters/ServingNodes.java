package io.github.sudoitir.rahdar.platform.clusters;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One manageable endpoint per Artemis NodeID — the active one where a live/backup
 * pair reports one (non-negotiable #4: liveness is polled, never configured).
 *
 * <p>Reducing a pair to its live member is what stops a fan-out from double-counting
 * a cluster's queues, and what stops a mutation from being attempted against a backup
 * that will refuse it.
 */
public final class ServingNodes {

    private ServingNodes() {}

    public static <N extends ClusterNode> List<N> from(Collection<N> nodes) {
        Map<String, N> perNodeId = new LinkedHashMap<>();
        for (N node : nodes) {
            if (node.getManagementUrl() == null) {
                continue;
            }
            String key = node.getNativeNodeId() != null ? node.getNativeNodeId() : "id:" + node.getId();
            perNodeId.merge(
                    key, node, (kept, candidate) -> Boolean.TRUE.equals(candidate.getActive()) ? candidate : kept);
        }
        return List.copyOf(perNodeId.values());
    }
}
