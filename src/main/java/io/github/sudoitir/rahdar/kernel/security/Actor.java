package io.github.sudoitir.rahdar.kernel.security;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import java.util.UUID;

/**
 * Who is performing a mutating action (ADR-0041, superseding ADR-0023).
 * {@code username} is the authenticated principal's username or the literal
 * {@code "anonymous"}; {@code userId} is that principal's id when one is
 * authenticated, else {@code null}. Scheduler-originated actions use
 * {@link #ofSystem()}.
 */
@PluginApi
public record Actor(String username, String sourceIp, String requestId, UUID userId, String tokenName) {

    public static final String ANONYMOUS = "anonymous";
    public static final String SYSTEM = "system";

    public Actor(String username, String sourceIp, String requestId, UUID userId) {
        this(username, sourceIp, requestId, userId, null);
    }

    public static Actor ofSystem() {
        return new Actor(SYSTEM, null, null, null, null);
    }

    /** The value written to {@code audit_event.username} — the owner, with the token name folded in when present. */
    public String displayName() {
        return tokenName == null ? username : username + " [token: " + tokenName + "]";
    }
}
