package io.github.sudoitir.rahdar.feature.resources;

import java.util.UUID;

/**
 * Whether a capture tap outlives a queue delete, so the preview can say which taps their
 * subscription removes and which it keeps (ADR-0084 D5).
 *
 * <p>Declared here and implemented by the module that owns capture subscriptions, which
 * already depends on this one: the queues module cannot read them without a cycle.
 */
public interface CaptureTaps {

    /**
     * Whether the subscription owning {@code tapName} still covers {@code address} through a
     * queue other than {@code queueName}. False for a tap no subscription owns.
     */
    boolean coversWithout(UUID clusterId, String tapName, String address, String queueName);
}
