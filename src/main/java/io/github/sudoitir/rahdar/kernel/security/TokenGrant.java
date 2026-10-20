package io.github.sudoitir.rahdar.kernel.security;

import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import java.util.Set;
import java.util.UUID;

/**
 * One permission an API token was narrowed to, at a scope, and optionally only for the queues or
 * addresses of that scope whose names match a pattern. A token acts within these and within what its
 * owner holds at the time ({@link PermissionResolver}).
 *
 * @param action a permission, or a wildcard such as {@code message:*}
 * @param kind {@code null} for a grant on the whole scope
 * @param pattern {@code null} exactly when {@code kind} is
 */
public record TokenGrant(
        Grant.ScopeType scopeType, UUID scopeId, String action, ResourceType kind, ResourcePattern pattern) {

    public TokenGrant {
        if ((kind == null) != (pattern == null)) {
            throw new IllegalArgumentException(
                    "A token grant names both the kind and the pattern of what it is limited to, or neither.");
        }
    }

    /** A grant on the whole scope. */
    public static TokenGrant of(Grant.ScopeType scopeType, UUID scopeId, String action) {
        return new TokenGrant(scopeType, scopeId, action, null, null);
    }

    /** Whether it is limited to names matching a pattern rather than covering its whole scope. */
    public boolean limited() {
        return pattern != null;
    }

    /** Whether the permission asked about is this grant's, or one its wildcard covers. */
    boolean covers(String permission) {
        return Grant.covers(Set.of(action), permission);
    }
}
