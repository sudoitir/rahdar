package io.github.sudoitir.rahdar.feature.plugins.messaging.internal;

import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import java.util.UUID;

/**
 * The names of a plugin tap's broker objects (ADR-0111):
 * {@code rahdar.plugin.<instance>.<registration id>}, its queue {@code ….q} and its
 * routing name {@code ….routing}.
 *
 * <p>Only the instance and the registration are in the name. The plugin and the source queue are
 * in the database, and a tap that outlives its row is an orphan whatever it copies, so nothing else
 * needs reading back out of a broker's listing.
 */
final class TapNames {

    private TapNames() {}

    static String of(String instanceId, UUID registrationId) {
        return RahdarNames.PLUGIN_TAP_PREFIX + instanceId + '.' + registrationId;
    }

    static String queueOf(String name) {
        return name + ".q";
    }

    static String routingOf(String name) {
        return name + ".routing";
    }

    /** Every object of this instance's taps, and nothing of another instance's or of capture's. */
    static String matchFor(String instanceId) {
        return RahdarNames.PLUGIN_TAP_PREFIX + instanceId + ".#";
    }

    static boolean ownedBy(String name, String instanceId) {
        return name != null && name.startsWith(RahdarNames.PLUGIN_TAP_PREFIX + instanceId + '.');
    }

    /** The registration a tap serves, or {@code null} when the name does not parse. */
    static UUID registrationOf(String name) {
        int lastDot = name == null ? -1 : name.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(lastDot + 1));
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
