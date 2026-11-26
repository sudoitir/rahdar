package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.kernel.security.SecretVault;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Seals and opens a cluster's schema-registry credential. The plaintext is handed only to the driver
 * option that sends it; nothing that renders, audits or logs calls {@link #open}.
 */
@Component
@RequiredArgsConstructor
class KafkaRegistrySecrets {

    /** Additional authenticated data kind, so a blob copied onto another cluster does not open. */
    static final String AAD_KIND = "kafka.registry";

    private final SecretVault vault;

    byte[] seal(UUID clusterId, String credentials) {
        return vault.seal(SecretVault.aad(clusterId, AAD_KIND), credentials);
    }

    String open(UUID clusterId, byte[] sealed) {
        if (sealed == null) {
            return null;
        }
        return vault.open(SecretVault.aad(clusterId, AAD_KIND), sealed);
    }
}
