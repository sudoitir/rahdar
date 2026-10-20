package io.github.sudoitir.rahdar.platform.clusters;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Whether an HA pair has two nodes claiming to be live, and how sure Rahdar is
 * (ADR-0012).
 *
 * <ul>
 *   <li>{@link #NONE} — at most one endpoint reports {@code Active=true}.
 *   <li>{@link #SUSPECTED} — two endpoints sharing a NodeID reported
 *       {@code Active=true} in the <em>same</em> refresh cycle, seen once. This is
 *       normal for a few seconds during a failover; Rahdar does not page on it.
 *   <li>{@link #CRITICAL} — that condition held again on the next consecutive
 *       cycle. Producers may be splitting across both nodes and the journals are
 *       diverging.
 * </ul>
 */
public enum SplitBrainStatus {
    NONE,
    SUSPECTED,
    CRITICAL;

    /**
     * The persisted verdicts of a cluster's nodes, keyed by NodeID, for {@link HaStateEvaluator}. Both
     * members of a pair carry the same verdict, so the worst one stands for the NodeID.
     */
    public static Map<String, SplitBrainStatus> byNodeId(Collection<? extends ClusterNode> rows) {
        Map<String, SplitBrainStatus> byNodeId = new HashMap<>();
        for (ClusterNode row : rows) {
            if (row.getNativeNodeId() != null) {
                byNodeId.merge(row.getNativeNodeId(), row.getSplitBrain(), (a, b) -> a.compareTo(b) >= 0 ? a : b);
            }
        }
        return byNodeId;
    }
}
