package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.Serving;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scheduler's persistence adapter: each method is a short transaction that
 * runs <em>after</em> the network I/O, never around it (ADR-0015 — "network I/O
 * never inside a DB transaction").
 */
@Component
@RequiredArgsConstructor
public class NodeStateRecorder {

    private final BrokerNodeRepository nodes;
    private final BrokerNodeMapper nodeMapper;
    private final ClusterIdentityClaims identityClaims;

    /**
     * Apply a tier-A HA read to one node, tagged with the cycle it was observed in. A NodeID that differs
     * from the stored one (the broker's journal was replaced) moves the cluster's claim with it (ADR-0167).
     */
    @Transactional
    public void applyTierA(UUID nodeId, NodeDescription ha, long cycle) {
        nodes.findById(nodeId).ifPresent(node -> {
            String nodeIdBefore = node.getNativeNodeId();
            String state = ha.conditions().stream()
                    .filter(c -> c.type().equals(ConditionTypes.STARTED))
                    .findFirst()
                    .map(c -> switch (c.status()) {
                        case TRUE -> "STARTED";
                        case FALSE -> "STOPPED";
                        case UNKNOWN -> "UNKNOWN";
                    })
                    .orElse("UNKNOWN");
            String haDesignation = ha.membership().designation().name();
            node.applyHaState(
                    new HaObservation(
                            ha.membership().serving() == Serving.ACTIVE,
                            state,
                            haDesignation,
                            ha.membership().inSync().orElse(null),
                            ha.version().orElse(null),
                            ha.nativeId().isEmpty() ? null : ha.nativeId()),
                    cycle,
                    Instant.now());
            if (!Objects.equals(nodeIdBefore, node.getNativeNodeId())) {
                identityClaims.sync(node.getClusterId());
            }
        });
    }

    /**
     * Record that the tier-A probe got no usable answer from a node, without disturbing last-known-good HA
     * state. The kind says whether the broker did not answer or refused the credentials.
     */
    @Transactional
    public void recordNodeError(UUID nodeId, String message, BrokerConnectionException.Kind kind) {
        nodes.findById(nodeId).ifPresent(node -> node.recordError(Instant.now(), message, kind));
    }

    /**
     * Persist a corroboration pass: every node of the cluster takes the verdict of its NodeID, and
     * one the pass has no verdict for is not in split-brain. Written with the tier-A state so every
     * replica reads what the owner decided (ADR-0152).
     */
    @Transactional
    public void recordSplitBrain(UUID clusterId, Map<String, SplitBrainStatus> verdictsByNodeId) {
        for (BrokerNodeEntity node : nodes.findByClusterIdOrderByNameAsc(clusterId)) {
            String nodeId = node.getNativeNodeId();
            node.recordSplitBrain(
                    nodeId == null
                            ? SplitBrainStatus.NONE
                            : verdictsByNodeId.getOrDefault(nodeId, SplitBrainStatus.NONE));
        }
    }

    /** Freshly persisted endpoints for a cluster — the input to split-brain corroboration. */
    @Transactional(readOnly = true)
    public List<NodeEndpoint> endpoints(UUID clusterId) {
        return nodeMapper.toEndpoints(nodes.findByClusterIdOrderByNameAsc(clusterId));
    }
}
