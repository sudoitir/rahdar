package io.github.sudoitir.rahdar.platform.scrape;

import java.time.Instant;
import java.util.UUID;

/**
 * The last scraped state of one queue on one node (ADR-0015). A read of the disposable
 * {@code subscription_snapshot} cache, so {@code ts} says how old it is.
 */
public record QueueSnapshot(
        UUID clusterId,
        UUID nodeId,
        String queueName,
        String address,
        String routingType,
        boolean durable,
        boolean paused,
        Instant ts,
        long messageCount,
        long consumerCount,
        long deliveringCount,
        long scheduledCount,
        long messagesAdded,
        long messagesAcked,
        long messagesExpired) {}
