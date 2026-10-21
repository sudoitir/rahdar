package io.github.sudoitir.rahdar.kernel.security;

import io.github.sudoitir.rahdar.kernel.security.internal.PluginSignIn;
import io.github.sudoitir.rahdar.kernel.security.internal.SessionTerminator;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository.EnabledAccount;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asks each plugin sign-in provider which of its users it no longer vouches for, and takes their
 * access away (ADR-0156). A session holds the grants resolved at sign-in, so a user removed at the
 * source would otherwise keep working until their session ends. For every subject a provider
 * returns, in one transaction, the user's sessions end, their API tokens are revoked, their trusted
 * devices are forgotten and {@code IDENTITY_REVOKED} is audited. The account stays enabled: a user
 * restored at the source signs in again, and the next {@code authenticate} refuses one who is not.
 * Run every few minutes by a job of the plugins module; it pauses while a plugin is stopped.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PluginIdentityRevalidation {

    private final PluginSignIn signIn;
    private final AppUserRepository users;
    private final SessionTerminator sessions;
    private final AdministrationAudit audit;
    private final Optional<PersonalTokens> personalTokens;
    private final Optional<SecondFactors> secondFactors;
    private final TransactionTemplate transactions;

    /** One pass over every attached, verified provider; a provider or an account that fails is skipped until the next. */
    public void run() {
        // ponytail: one call per provider with every enabled subject; batch the subjects if one ever times out.
        ScopedValue.where(ActorResolver.ON_BEHALF_OF, Actor.ofSystem()).run(() -> {
            for (PluginSignIn.Provider provider : signIn.verified()) {
                revalidate(provider);
            }
        });
    }

    private void revalidate(PluginSignIn.Provider provider) {
        List<EnabledAccount> accounts = users.findByProviderIdAndDisabledFalse(provider.id());
        Map<String, EnabledAccount> bySubject = new HashMap<>();
        accounts.stream()
                .filter(a -> a.getExternalSubject() != null)
                .forEach(a -> bySubject.put(a.getExternalSubject(), a));
        if (bySubject.isEmpty()) {
            return;
        }
        Set<String> gone = provider.noLongerValid(Set.copyOf(bySubject.keySet()));
        for (String subject : gone) {
            EnabledAccount account = bySubject.get(subject);
            if (account != null) {
                try {
                    revoke(provider.id(), account);
                } catch (RuntimeException e) {
                    // The transaction rolled back, so the account is retried by the next pass.
                    log.warn(
                            "Could not revoke access of {} (no longer valid at {})",
                            account.getUsername(),
                            provider.id(),
                            e);
                }
            }
        }
    }

    /** In one transaction, so the sessions end only if the rest is recorded. */
    private void revoke(String providerId, EnabledAccount account) {
        transactions.executeWithoutResult(_ -> revokeAccess(providerId, account));
    }

    private void revokeAccess(String providerId, EnabledAccount account) {
        sessions.endSessionsOf(List.of(account.getUsername()));
        int tokens = personalTokens.map(t -> t.revokeAllOf(account.getId())).orElse(0);
        secondFactors.ifPresent(f -> f.revokeTrustedDevices(account.getId(), "no longer valid at " + providerId));
        audit.changed(
                "IDENTITY_REVOKED",
                "user",
                account.getUsername(),
                Map.of("provider", providerId, "subject", account.getExternalSubject(), "tokensRevoked", tokens));
        log.info("Revoked access of {} (no longer valid at {})", account.getUsername(), providerId);
    }
}
