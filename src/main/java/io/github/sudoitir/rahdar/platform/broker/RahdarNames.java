package io.github.sudoitir.rahdar.platform.broker;

import java.util.UUID;

/**
 * The name prefixes Rahdar reserves for the objects it creates on a broker for itself. An object
 * under one of them is Rahdar's by construction, and is never created or deleted as an operator's.
 */
public final class RahdarNames {

    /** The taps that serve message capture (ADR-0079). */
    public static final String CAPTURE_PREFIX = "rahdar.capture.";

    /** The taps Rahdar installs for plugins' message registrations (ADR-0111). */
    public static final String PLUGIN_TAP_PREFIX = "rahdar.plugin.";

    /** The staging queues a cross-broker move parks its selection in (ADR-0097). */
    public static final String TRANSFER_PREFIX = "rahdar.transfer.";

    private RahdarNames() {}

    /** Whether a name is one of the taps Rahdar installs for itself: a capture or a plugin tap. */
    public static boolean isRahdarTap(String name) {
        return name != null && (name.startsWith(CAPTURE_PREFIX) || name.startsWith(PLUGIN_TAP_PREFIX));
    }

    /** The staging queue (and its address) of a transfer run. */
    public static String stagingQueue(UUID runId) {
        return TRANSFER_PREFIX + runId;
    }
}
