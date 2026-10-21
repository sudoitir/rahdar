package io.github.sudoitir.rahdar.platform.scrape;

import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Emits SSE change signals only when the scrape actually changed the persisted
 * state (ADR-0018 — "emitted only on a real change"). Keeps a cheap per-cluster
 * signature so a broker that reports the same numbers every tick produces no
 * stream traffic beyond the heartbeat.
 */
@Component
@RequiredArgsConstructor
public class StreamSignals {

    private final SseHub hub;

    private final Map<UUID, String> topologySignature = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> queueState = new ConcurrentHashMap<>();

    /** After a tier-A tick: publish topology + health if the endpoint set / roles / liveness moved. */
    public void afterTierA(UUID clusterId, List<NodeEndpoint> endpoints) {
        String signature = endpoints.stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .map(e -> e.nativeNodeId() + "|" + e.haDesignation() + "|" + e.state() + "|" + e.active() + "|"
                        + e.replicaSync() + "|" + (e.lastError() != null))
                .collect(Collectors.joining(";"));
        String previous = topologySignature.put(clusterId, signature);
        if (!signature.equals(previous)) {
            hub.publish(clusterId, "topology");
            hub.publish(clusterId, "health");
        }
    }

    /**
     * After a tier-B/C queue scrape: publish queues, about the queues whose counters moved since they were
     * last seen, so a subscriber who may read only some of the queues is told only of theirs. The first
     * scrape of a cluster, and one that moved more queues than a frame can name, is about the cluster.
     */
    public void afterQueueScrape(UUID clusterId, List<QueueRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Long> seen = queueState.computeIfAbsent(clusterId, k -> new ConcurrentHashMap<>());
        boolean first = seen.isEmpty();
        Set<String> moved = new LinkedHashSet<>();
        for (QueueRow r : rows) {
            long counters = r.messageCount() + r.consumerCount() + r.deliveringCount() + r.scheduledCount();
            Long previous = seen.put(r.nodeId() + "|" + r.queueName(), counters);
            if (previous == null || previous != counters) {
                moved.add(r.queueName());
            }
        }
        if (first) {
            hub.publish(clusterId, "subscriptions");
        } else if (!moved.isEmpty()) {
            hub.publishAbout(clusterId, "subscriptions", moved, List.of());
        }
    }

    public void forget(UUID clusterId) {
        topologySignature.remove(clusterId);
        queueState.remove(clusterId);
    }
}
