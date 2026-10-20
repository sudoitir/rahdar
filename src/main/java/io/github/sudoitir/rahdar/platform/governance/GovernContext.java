package io.github.sudoitir.rahdar.platform.governance;

import java.util.UUID;

/**
 * Who is reading what: the cluster and destination a message came from, and whether the caller holds
 * {@code message:clear} there. Resolved once per request by {@link ContentPolicy#context}.
 */
public record GovernContext(UUID clusterId, String destination, boolean clearAccess) {

    /** For stored copies: nobody is reading, so everything sensitive is masked. */
    public static GovernContext masked(UUID clusterId, String destination) {
        return new GovernContext(clusterId, destination, false);
    }
}
