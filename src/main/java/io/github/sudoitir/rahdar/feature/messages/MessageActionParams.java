package io.github.sudoitir.rahdar.feature.messages;

import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.MessageActionRequest;
import java.util.UUID;

/**
 * What the gate holds of a message action. One record per action, because a gated operation is found by its
 * parameter class (ADR-0179); {@code node} is the node the caller asked for, or {@code null} for the one Rahdar picks.
 */
sealed interface MessageActionParams {

    UUID clusterId();

    String queue();

    UUID nodeId();

    MessageActionRequest request();

    boolean override();

    record MessageMoveParams(UUID clusterId, String queue, UUID nodeId, MessageActionRequest request, boolean override)
            implements MessageActionParams {}

    record MessageRetryParams(UUID clusterId, String queue, UUID nodeId, MessageActionRequest request, boolean override)
            implements MessageActionParams {}

    record MessageDeleteParams(
            UUID clusterId, String queue, UUID nodeId, MessageActionRequest request, boolean override)
            implements MessageActionParams {}

    record MessageExpireParams(
            UUID clusterId, String queue, UUID nodeId, MessageActionRequest request, boolean override)
            implements MessageActionParams {}

    /** A purge removes every message, so it carries no selection. */
    record QueuePurgeParams(UUID clusterId, String queue, UUID nodeId, boolean override) {}

    /** What the gate holds of a purge of a log's subscription: the subscription's canonical id. */
    record LogPurgeParams(UUID clusterId, String subscription) {}
}
