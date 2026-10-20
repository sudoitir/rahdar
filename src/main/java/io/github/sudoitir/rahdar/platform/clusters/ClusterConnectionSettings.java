package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.kernel.security.SecretVault;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.ConnectionSettingsSource;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerCredentialEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerCredentialRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerTlsEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerTlsRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves a persisted cluster's connection settings: the stored credential, decrypted
 * through {@link SecretVault} (ADR-0009), and the {@code broker_tls} SSL-bundle name.
 *
 * <p>Registration builds its own settings straight from the request body — nothing is
 * persisted or encrypted until the cluster exists.
 */
@Component
@RequiredArgsConstructor
class ClusterConnectionSettings implements ConnectionSettingsSource {

    private static final String MANAGEMENT = "MANAGEMENT";
    private static final String DATA = "DATA";

    private final BrokerCredentialRepository credentials;
    private final BrokerTlsRepository tlsRepository;
    private final SecretVault vault;

    @Override
    public BrokerConnectionSettings jolokiaSettings(UUID clusterId) {
        String username = null;
        String password = null;
        var credential = credentials.findByClusterIdAndKind(clusterId, MANAGEMENT);
        if (credential.isPresent()) {
            username = credential.get().getUsername();
            password = vault.open(
                    SecretVault.aad(clusterId, MANAGEMENT), credential.get().getSealed());
        }

        BrokerTlsEntity tls = tlsRepository.findByClusterId(clusterId).orElse(null);
        String bundle = tls != null ? tls.getTruststoreRef() : null;
        boolean verifyHostname = tls == null || tls.isVerifyHostname();

        return new BrokerConnectionSettings(clusterId, username, password, bundle, verifyHostname);
    }

    /** Each row is decrypted with the kind it was sealed under — the {@link SecretVault} AAD is {@code clusterId|kind}. */
    @Override
    public CoreConnectionSettings coreSettings(UUID clusterId) {
        BrokerCredentialEntity credential = credentials
                .findByClusterIdAndKind(clusterId, DATA)
                .or(() -> credentials.findByClusterIdAndKind(clusterId, MANAGEMENT))
                .orElse(null);

        BrokerTlsEntity tls = tlsRepository.findByClusterId(clusterId).orElse(null);
        String bundle = tls != null ? tls.getTruststoreRef() : null;
        boolean verifyHostname = tls == null || tls.isVerifyHostname();

        if (credential == null) {
            return new CoreConnectionSettings(clusterId, null, null, bundle, verifyHostname);
        }
        String password = vault.open(SecretVault.aad(clusterId, credential.getKind()), credential.getSealed());
        return new CoreConnectionSettings(clusterId, credential.getUsername(), password, bundle, verifyHostname);
    }
}
