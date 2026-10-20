package io.github.sudoitir.rahdar.platform.broker;

import java.util.UUID;

/**
 * Everything {@link BrokerGateway} needs to connect to a cluster:
 * the (already decrypted) HTTP Basic credentials and the optional TLS bundle.
 * Credentials are per cluster ({@code broker_credential} is keyed by
 * {@code (cluster_id, kind)}), so one of these serves every node in a cluster.
 */
public record BrokerConnectionSettings(
        UUID clusterId, String username, String password, String tlsBundle, boolean verifyHostname) {

    public static BrokerConnectionSettings basicAuth(UUID clusterId, String username, String password) {
        return new BrokerConnectionSettings(clusterId, username, password, null, true);
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }

    public boolean hasTls() {
        return tlsBundle != null && !tlsBundle.isBlank();
    }
}
