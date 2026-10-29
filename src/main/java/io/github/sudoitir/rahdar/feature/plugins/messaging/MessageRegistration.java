package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A registration as Rahdar holds it, with what the last pass found on each node.
 *
 * @param source the subscription it receives from
 * @param concurrency the messages handled at once on each serving node, as registered
 * @param state the overall state: {@link RegistrationState#ACTIVE} when every node it covers is
 *     active, otherwise the state that most needs attention
 * @param detail why it is not active, in words, or {@code null}
 * @param droppedCopies for a tap, copies dropped because the plugin fell behind, summed over nodes
 *     since each node's tap was installed; {@code null} when not measured
 * @param releasesCounted whether the broker counts a {@link Disposition#RELEASE} as a delivery attempt on this
 *     subscription (Kafka share groups do), so a plugin that keeps releasing one message is eventually
 *     dead-lettered; false where a release is free (Artemis queues)
 */
@PluginApi
public record MessageRegistration(
        String key,
        UUID clusterId,
        SubscriptionId source,
        RegistrationMode mode,
        int concurrency,
        UUID actingUserId,
        RegistrationState state,
        String detail,
        Long droppedCopies,
        boolean releasesCounted,
        List<NodeState> nodes,
        Instant updatedAt) {

    /**
     * One node's part of a registration; {@code nodeId} is {@code null} (and {@code nodeName} says so) for a
     * subscription read for the whole cluster. {@code droppedCopies} is {@code null} when not measured.
     */
    @PluginApi
    public record NodeState(UUID nodeId, String nodeName, RegistrationState state, String detail, Long droppedCopies) {}
}
