package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/**
 * A registration or send Rahdar will not accept; the message says why in words an operator can act
 * on — a missing permission, an unknown cluster, a reserved subscription.
 */
@PluginApi
public class RegistrationRefusedException extends RuntimeException {
    public RegistrationRefusedException(String message) {
        super(message);
    }
}
