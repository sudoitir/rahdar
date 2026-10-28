package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;

/** Artemis-only management, through the broker SDK's Artemis extension. Module descriptor (ADR-0070). */
public final class ArtemisModule {

    /** The broker SDK's id of the system, which prefixes its kinds. */
    public static final String SYSTEM = "artemis";

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("artemis")
            .title("Artemis")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .required(true)
            .build();

    private ArtemisModule() {}
}
