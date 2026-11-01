package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.ResourceId;
import java.util.Set;
import java.util.UUID;

/**
 * Where a partition of a log starts and ends. Only a system that keeps its messages in a partitioned log has this,
 * so its module provides it (ADR-0216); a browse of the log is placed against it, so a request before the start
 * is read from the start and says so, and one past the end is an empty page that states the end.
 */
public interface LogBounds extends SystemExtension {

    /** The first offset still in the partition, and the one after its last. */
    record Bounds(long start, long end) {}

    Bounds of(UUID clusterId, ResourceId log, int partition);

    /**
     * The kinds that are themselves one log, read as a single partition 0 (a stream), as against a kind whose log
     * is its partitions' parent. Empty by default.
     */
    default Set<KindId> logs(UUID clusterId) {
        return Set.of();
    }
}
