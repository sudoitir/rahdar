package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/**
 * A send whose outcome is unknown: the message left Rahdar, and the broker's confirmation never came
 * back, so it may or may not be on the broker. It is neither a success nor a failure. Sending it again
 * may deliver it twice, so a plugin that must not duplicate gives the message an id its consumers can
 * recognise, or checks for it first.
 */
@PluginApi
public class MessageInDoubtException extends RuntimeException {
    public MessageInDoubtException(String message) {
        super(message);
    }
}
