package io.github.sudoitir.rahdar.platform.scrape;

import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.github.sudoitir.rahdar.platform.clusters.SplitBrainStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Owns the refresh-cycle counter and the split-brain corroboration ratchet
 * (ADR-0012, ADR-0015). Both used to live on {@code HaRefreshTask} /
 * {@code HaStateEvaluator}; moving them here makes two things true:
 *
 * <ul>
 *   <li>The cycle counter is <em>per cluster</em>, so one cluster's scrape
 *       cadence never perturbs another's detection window.
 *   <li>Only the scrape schedule advances the ratchet. The verdict is persisted on
 *       the cluster's node rows; read endpoints read the rows, on any replica, so
 *       looking at health never corroborates a split-brain.
 * </ul>
 *
 * <p>The ratchet is in memory and stays with the replica that owns the cluster. A
 * restart, or a hand-over to another replica, resets the window, costing at most
 * one extra tier-A cycle (~5s) before a real split-brain re-escalates to CRITICAL.
 */
@Component
@RequiredArgsConstructor
public class ScrapeCycle {

    private final NodeStateRecorder persist;

    private final Map<UUID, AtomicLong> cycleByCluster = new ConcurrentHashMap<>();
    /** Per cluster: NodeID → the cycle a same-cycle dual-active was first seen in. */
    private final Map<UUID, Map<String, Long>> firstSuspectedCycle = new ConcurrentHashMap<>();

    /** The next tier-A cycle number for a cluster. Called once per cluster per tier-A tick. */
    public long next(UUID clusterId) {
        return cycleByCluster.computeIfAbsent(clusterId, k -> new AtomicLong()).incrementAndGet();
    }

    /** The current cycle number without advancing it. */
    public long current(UUID clusterId) {
        AtomicLong counter = cycleByCluster.get(clusterId);
        return counter == null ? 0L : counter.get();
    }

    /**
     * Advance the corroboration ratchet from a freshly persisted scrape and
     * publish the verdicts. The endpoints must carry the {@code observedCycle}
     * the scrape just wrote. Scheduler-only — the single place the ratchet moves.
     */
    public void corroborate(UUID clusterId, List<NodeEndpoint> endpoints) {
        Map<String, Long> ratchet = firstSuspectedCycle.computeIfAbsent(clusterId, k -> new ConcurrentHashMap<>());
        Map<String, SplitBrainStatus> statuses = new LinkedHashMap<>();

        Map<String, List<NodeEndpoint>> byNodeId = new LinkedHashMap<>();
        for (NodeEndpoint e : endpoints) {
            if (e.nativeNodeId() != null) {
                byNodeId.computeIfAbsent(e.nativeNodeId(), k -> new ArrayList<>())
                        .add(e);
            }
        }
        byNodeId.forEach((nodeId, eps) -> statuses.put(nodeId, evaluate(ratchet, nodeId, eps)));
        persist.recordSplitBrain(clusterId, statuses);
    }

    /** Forget a cluster's counters when it is removed. */
    public void forget(UUID clusterId) {
        cycleByCluster.remove(clusterId);
        firstSuspectedCycle.remove(clusterId);
    }

    /**
     * The ADR-0012 ratchet, moved verbatim from {@code HaStateEvaluator}: a
     * same-cycle dual-active seen once is SUSPECTED; seen again on a strictly
     * later cycle it is CRITICAL. Readings from different cycles are sampling
     * skew (a planned failover), not evidence — they clear the ratchet.
     */
    private static SplitBrainStatus evaluate(Map<String, Long> ratchet, String nodeId, List<NodeEndpoint> endpoints) {
        List<NodeEndpoint> active =
                endpoints.stream().filter(NodeEndpoint::live).toList();
        if (nodeId == null || active.size() < 2) {
            ratchet.remove(nodeId);
            return SplitBrainStatus.NONE;
        }

        Set<Long> cycles = active.stream().map(NodeEndpoint::observedCycle).collect(Collectors.toSet());
        if (cycles.contains(null) || cycles.size() != 1) {
            ratchet.remove(nodeId);
            return SplitBrainStatus.NONE;
        }

        long cycle = cycles.iterator().next();
        Long firstSeen = ratchet.putIfAbsent(nodeId, cycle);
        if (firstSeen != null && cycle > firstSeen) {
            return SplitBrainStatus.CRITICAL;
        }
        return SplitBrainStatus.SUSPECTED;
    }
}
