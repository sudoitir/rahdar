package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.kernel.security.SecretVault;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerCredentialEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerCredentialRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Named credentials a cluster's features hold, in the {@code broker_credential} table
 * the cluster's own Jolokia and Core credentials already use, sealed by
 * {@link SecretVault} under {@code clusterId|kind} (ADR-0009).
 *
 * <p>The one caller today is a declared bridge, which authenticates to the broker it
 * forwards to (ADR-0092): the declaration carries only the reference, and the secret
 * is fetched here at the moment of the broker call, so it reaches no document, no
 * revision, no diff, no audit parameter, no tool response and no exported fragment.
 *
 * <p>{@link #resolve} is the only way back out, and it returns the password to a
 * caller that is about to send it to a broker — never to anything that renders.
 */
@Component
@RequiredArgsConstructor
public class ClusterSecrets {

    /** The {@code broker_credential.kind} prefix, so these never collide with the MANAGEMENT and DATA credentials. */
    private static final String PREFIX = "REF:";

    private final BrokerCredentialRepository credentials;
    private final SecretVault vault;

    /** A stored credential. The password is only ever handed to a broker call. */
    public record Credential(String ref, String username, String password) {}

    /** Store or replace the credential under {@code ref}. */
    @Transactional
    public void store(UUID clusterId, String ref, String username, String password) {
        String kind = PREFIX + ref;
        byte[] sealed = vault.seal(SecretVault.aad(clusterId, kind), password == null ? "" : password);
        credentials
                .findByClusterIdAndKind(clusterId, kind)
                .ifPresentOrElse(
                        e -> {
                            e.replaceSecret(username, sealed);
                            credentials.save(e);
                        },
                        () -> credentials.save(new BrokerCredentialEntity(clusterId, kind, username, sealed)));
    }

    /** The credential behind a reference, or empty when nothing is stored under it. */
    @Transactional(readOnly = true)
    public Optional<Credential> resolve(UUID clusterId, String ref) {
        return credentials
                .findByClusterIdAndKind(clusterId, PREFIX + ref)
                .map(e -> new Credential(
                        ref, e.getUsername(), vault.open(SecretVault.aad(clusterId, e.getKind()), e.getSealed())));
    }

    /** Which references this cluster holds, with their usernames and never a password. */
    @Transactional(readOnly = true)
    public List<Credential> list(UUID clusterId) {
        return credentials.findByClusterId(clusterId).stream()
                .filter(e -> e.getKind().startsWith(PREFIX))
                .map(e -> new Credential(e.getKind().substring(PREFIX.length()), e.getUsername(), null))
                .toList();
    }

    /** Forget a reference. A reference that was never stored is already forgotten. */
    @Transactional
    public void forget(UUID clusterId, String ref) {
        credentials.findByClusterIdAndKind(clusterId, PREFIX + ref).ifPresent(credentials::delete);
    }
}
