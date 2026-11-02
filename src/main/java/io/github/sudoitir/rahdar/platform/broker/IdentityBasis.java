package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.operation.EtagBasis;

/**
 * What a preview's etag holds, in the terms the views and the approval gate use (ADR-0224): with
 * {@link #INCARNATION} a resource deleted and created again since the preview is refused; with
 * {@link #CONFIGURATION} the system exposes no identity, so one created again with the same configuration is not
 * told apart.
 */
public enum IdentityBasis {
    INCARNATION,
    CONFIGURATION;

    private static final String CONFIGURATION_NOTE = "RabbitMQ does not tell a recreated resource apart: if it is"
            + " deleted and created again with the same configuration before this runs, this applies to the new one.";

    public static IdentityBasis of(EtagBasis basis) {
        return basis == EtagBasis.CONFIGURATION ? CONFIGURATION : INCARNATION;
    }

    /** The sentence an approver reads when the basis is weak, or {@code null} when the etag holds an identity. */
    public String note() {
        return this == CONFIGURATION ? CONFIGURATION_NOTE : null;
    }
}
