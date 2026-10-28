package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.model.ResourceId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Where a subscription's backlog sits, partition by partition (ADR-0216). Only a system whose subscriptions read a
 * partitioned log has this, so its module provides it; where none does, the backlog has no breakdown.
 */
public interface BacklogBreakdown extends SystemExtension {

    /**
     * One partition of the log. {@code committed}, {@code end} and {@code lag} are absent, with the {@code reason},
     * where the subscription has no position on the partition or its end could not be read.
     */
    record PartitionLag(int partition, Long committed, Long end, Long lag, String reason) {}

    /**
     * The partitions, slowest (largest lag) first, as of {@code sampledAt}, and the destination whose log the
     * subscription reads (a topic), which is where its records are browsed.
     */
    record Breakdown(Instant sampledAt, List<PartitionLag> partitions, ResourceId destination) {}

    Breakdown read(UUID clusterId, ResourceId subscription);
}
