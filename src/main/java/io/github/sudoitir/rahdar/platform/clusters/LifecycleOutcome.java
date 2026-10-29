package io.github.sudoitir.rahdar.platform.clusters;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The result of one cluster-wide lifecycle command (ADR-0049 D2).
 *
 * <p>A lifecycle command names a cluster, not a node, and Artemis cluster nodes
 * each own their own queues — so the result is inherently a list, not a count.
 * {@code MessageService.Outcome} carries a single {@code node} and is deliberately
 * left alone; widening it would drag the message API into a shape it does not
 * need.
 *
 * @param dryRun whether this was a preview; a preview makes no mutating call
 * @param cap the bulk safety cap in force (ADR-0022), for a destructive command
 * @param overCap whether the summed estimate exceeds the cap
 * @param nodes one entry per node considered — including the ones skipped
 */
public record LifecycleOutcome(boolean dryRun, long cap, boolean overCap, List<NodeOutcome> nodes) {

    public enum NodeStatus {
        /** Dry run: the command would be applied here. */
        WOULD_APPLY,
        /** The command was applied here. */
        APPLIED,
        /**
         * The node was already in the requested state — the queue exists, or the
         * thing being removed was already gone. A success: treating it as an error
         * would make a re-run after a partial failure impossible, which is exactly
         * the situation where a re-run is what the operator needs.
         */
        ALREADY,
        /**
         * The node was not live when the command was issued, so it never received
         * it. Reported as skipped rather than failed, because nothing was attempted.
         */
        SKIPPED_NOT_LIVE,
        /**
         * The node runs an Artemis release without the operation (ADR-0142), so it was
         * not called. A skip, not a failure: the other nodes of a mixed cluster still
         * get the command, and the error names the release needed.
         */
        UNSUPPORTED_VERSION,
        /** The node received the command and refused or failed it. */
        FAILED
    }

    /**
     * @param affected messages that were, or would be, destroyed here; null when the
     *     command destroys nothing or the count could not be established
     * @param error why this node failed, or null
     * @param skipped records a purge of a log passed over without deleting them, or null
     */
    public record NodeOutcome(
            UUID nodeId, String nodeName, NodeStatus status, Long affected, String error, Long skipped) {

        public NodeOutcome(UUID nodeId, String nodeName, NodeStatus status, Long affected, String error) {
            this(nodeId, nodeName, status, affected, error, null);
        }

        public static NodeOutcome skipped(UUID nodeId, String nodeName) {
            return new NodeOutcome(nodeId, nodeName, NodeStatus.SKIPPED_NOT_LIVE, null, null);
        }

        public static NodeOutcome failed(UUID nodeId, String nodeName, String error) {
            return new NodeOutcome(nodeId, nodeName, NodeStatus.FAILED, null, error);
        }
    }

    /** Whether any node the command reached failed — what makes the audit row a failure (D4). */
    public boolean anyFailed() {
        return nodes.stream().anyMatch(n -> n.status() == NodeStatus.FAILED);
    }

    /** The total messages destroyed, or that would be destroyed, across every node. */
    public long totalAffected() {
        return nodes.stream()
                .map(NodeOutcome::affected)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
    }
}
