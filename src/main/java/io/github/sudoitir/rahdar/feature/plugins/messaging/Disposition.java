package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/** What a {@link PluginMessageHandler} did with a message. */
@PluginApi
public enum Disposition {
    /** Done with it. A consumed message is acknowledged and leaves the subscription. */
    ACCEPT,
    /**
     * Not done with it. A consumed message is redelivered, up to the broker's own
     * {@code max-delivery-attempts}; a tapped copy is discarded either way, since the original was
     * never taken from its subscription.
     */
    REJECT,
    /**
     * Not done with it, and not because of the message: the handler cannot process anything right
     * now, for example because the service it calls is down. A consumed message goes back to its
     * subscription without counting as a delivery attempt, so a release never brings it closer to the
     * broker's dead-letter address (a Kafka share group counts it, see
     * {@link MessageRegistration#releasesCounted}); a tapped copy is discarded, as with {@link #REJECT}. The broker
     * offers the message again at once, so a handler releases while it stops its own registration,
     * not in a loop that keeps receiving.
     */
    RELEASE
}
