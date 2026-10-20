package io.github.sudoitir.rahdar.feature.plugins.messaging.internal;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.plugins.messaging.RegistrationMode;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.security.UserAccounts;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether a user may use a queue or address of a cluster for a registration or a send, as their account
 * stands now (ADR-0111). Grants, team roles and shares are read from the database every time, as
 * {@code OperatorHandoff} does for long-running work: a registration outlives any session, and a withdrawn
 * right must stop it.
 */
@Component
@RequiredArgsConstructor
public class AccessCheck {

    private final UserAccounts accounts;
    private final PermissionResolver perm;

    /** One permission the acting user needs on one queue or address of the cluster. */
    public record Need(ResourceRef resource, String permission) {}

    /**
     * What a registration needs on its queue: reading it for a tap, and also purging it for a consumer,
     * because consuming removes messages.
     */
    public static List<Need> needsOf(RegistrationMode mode, String queue) {
        ResourceRef resource = ResourceRef.subscription(queue);
        return mode == RegistrationMode.TAP
                ? List.of(new Need(resource, MessagePermissions.MESSAGE_READ))
                : List.of(
                        new Need(resource, MessagePermissions.MESSAGE_READ),
                        new Need(resource, MessagePermissions.SUBSCRIPTION_PURGE));
    }

    /** What a send needs: {@code message:send} on the address it goes to. */
    public static List<Need> sendNeeds(String address) {
        return List.of(new Need(ResourceRef.destination(address), MessagePermissions.MESSAGE_SEND));
    }

    /** Why the user may not, in words, or empty when they may. */
    public Optional<String> denial(UUID userId, UUID clusterId, List<Need> needs) {
        if (userId == null) {
            return Optional.of("No acting user was given, and every registration and send acts for one.");
        }
        Optional<UserAccounts.Account> account = accounts.byId(userId);
        if (account.isEmpty()) {
            return Optional.of("The acting user " + userId + " no longer exists.");
        }
        if (account.get().disabled()) {
            return Optional.of("The acting user '" + account.get().username() + "' is disabled.");
        }
        RahdarPrincipal principal = RahdarPrincipal.live(userId, account.get().username(), false);
        for (Need need : needs) {
            if (!perm.can(principal, clusterId, need.resource(), need.permission())) {
                return Optional.of("The acting user '" + account.get().username() + "' does not hold "
                        + need.permission() + " on "
                        + need.resource().type().name().toLowerCase(Locale.ROOT) + " "
                        + need.resource().name() + " of cluster " + clusterId + ".");
            }
        }
        return Optional.empty();
    }

    /** The acting user's name, for audit rows, or their id when the account is gone. */
    public String nameOf(UUID userId) {
        return userId == null
                ? "unknown"
                : accounts.byId(userId).map(UserAccounts.Account::username).orElse(userId.toString());
    }
}
