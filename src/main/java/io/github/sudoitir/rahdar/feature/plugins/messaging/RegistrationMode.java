package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/** How a plugin receives a queue's messages (ADR-0111). */
@PluginApi
public enum RegistrationMode {
    /**
     * A copy of every message routed to the queue. Existing consumers receive exactly what they
     * did before and producers are never slowed; when the plugin falls behind, the oldest copies
     * are dropped and counted. Needs {@code message:read} on the queue.
     */
    TAP,
    /**
     * The plugin competes with the queue's other consumers and settles each message it receives:
     * {@link Disposition#ACCEPT} removes it, anything else leaves it for redelivery. Needs
     * {@code message:read} and {@code subscription:purge} on the queue, because consuming removes messages.
     */
    CONSUME
}
