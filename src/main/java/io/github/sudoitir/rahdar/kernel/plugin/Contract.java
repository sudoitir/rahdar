package io.github.sudoitir.rahdar.kernel.plugin;

/**
 * The extension contract version (ADR-0070). Mirrored by {@code CONTRACT} in
 * {@code web/src/kernel/feature.ts}; bumped only when a contribution type changes
 * incompatibly.
 */
public final class Contract {

    public static final int VERSION = 16;

    /** The startup property that enables or disables a feature. */
    public static String enabledProperty(String featureId) {
        return "rahdar.features." + featureId + ".enabled";
    }

    private Contract() {}
}
