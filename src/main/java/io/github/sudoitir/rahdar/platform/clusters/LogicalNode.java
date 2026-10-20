package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import java.util.List;
import java.util.Optional;

/**
 * One logical Artemis node — a primary and its backup share a NodeID, so they are
 * one identity with a reflection, not two nodes. {@code nativeNodeId} is the
 * pair key; {@code endpoints} holds one entry (standalone) or two (a pair).
 *
 * @param splitBrain corroborated per ADR-0012
 * @param replicationBehind a backup endpoint reports {@code ReplicaSync=false}
 */
public record LogicalNode(
        String nativeNodeId, List<NodeEndpoint> endpoints, SplitBrainStatus splitBrain, boolean replicationBehind) {

    /** Endpoints actually serving traffic now. Two of these is the split-brain shape. */
    public List<NodeEndpoint> serving() {
        return endpoints.stream().filter(NodeEndpoint::live).toList();
    }

    /** The standby side, if one is known. */
    public Optional<NodeEndpoint> replica() {
        return endpoints.stream().filter(e -> !e.live()).findFirst();
    }

    /** True when the pair has a serving side but no standby side. */
    public boolean replicaMissing() {
        return endpoints.stream().noneMatch(e -> !e.live()) || endpoints.size() < 2;
    }
}
