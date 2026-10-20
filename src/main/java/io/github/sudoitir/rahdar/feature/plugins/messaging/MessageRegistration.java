package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
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
 */
@PluginApi
public record MessageRegistration(
        String key,
        UUID clusterId,
        ResourceRef source,
        RegistrationMode mode,
        int concurrency,
        UUID actingUserId,
        RegistrationState state,
        String detail,
        Long droppedCopies,
        List<NodeState> nodes,
        Instant updatedAt) {

    /** One node's part of a registration. {@code droppedCopies} is {@code null} when not measured. */
    @PluginApi
    public record NodeState(UUID nodeId, String nodeName, RegistrationState state, String detail, Long droppedCopies) {}
}
