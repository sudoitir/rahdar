package io.github.sudoitir.rahdar.platform.broker;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a cross-broker move needs from a node (ADR-0097): a Rahdar-owned staging queue that a selection
 * is parked in, and the moves of messages in and out of it. Only systems that can move messages between
 * queues on a node provide it.
 *
 * <p>Every operation is safe to repeat, so a resumed run can call it again whatever the previous attempt
 * got through.
 */
public interface TransferStaging extends SystemExtension {

    /** A queue on a node: its destination, its name and its routing type. */
    record TransferSource(String destination, String name, String routingType) {}

    /** Create the run's staging queue (see {@link RahdarNames#stagingQueue}), or leave it as it is. */
    void create(UUID clusterId, UUID nodeId, UUID runId);

    /**
     * Destroy an empty staging queue. A queue that still holds messages is left alone and {@code false}
     * returned.
     *
     * @throws IllegalArgumentException for a queue that is not a staging queue
     */
    boolean destroyIfEmpty(UUID clusterId, UUID nodeId, String stagingQueue);

    /** The staging queues on the node, whether or not a run still knows them. */
    List<String> list(UUID clusterId, UUID nodeId);

    /** How many messages a staging queue holds. */
    long stagedCount(UUID clusterId, UUID nodeId, String stagingQueue);

    /** The ids of the messages a staging queue holds. */
    List<Long> stagedIds(UUID clusterId, UUID nodeId, String stagingQueue);

    /** Move these staged messages to a queue. */
    BulkResult moveStagedIds(UUID clusterId, UUID nodeId, String stagingQueue, List<Long> ids, String toQueue);

    /** Move up to {@code max} staged messages to a queue; how many moved. */
    long moveStaged(UUID clusterId, UUID nodeId, String stagingQueue, int max, String toQueue);

    /** Move these messages out of a queue to another queue on the node. */
    BulkResult moveIds(UUID clusterId, UUID nodeId, TransferSource from, List<Long> ids, String toQueue);

    /**
     * Move up to {@code max} of the messages that match a filter, as the filter reads at {@code t0}, to
     * another queue on the node; how many moved.
     */
    long moveSelected(
            UUID clusterId, UUID nodeId, TransferSource from, String filter, Instant t0, int max, String toQueue);

    /** How many messages match the filter, as it reads at {@code t0}. */
    long countSelected(UUID clusterId, UUID nodeId, TransferSource from, String filter, Instant t0);

    /** Whether the system's filter dialect reads this text as a whole expression, safe to freeze and combine. */
    boolean isCompleteFilter(String filter);
}
