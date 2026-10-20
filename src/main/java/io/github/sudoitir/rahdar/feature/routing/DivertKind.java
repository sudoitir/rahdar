package io.github.sudoitir.rahdar.feature.routing;

import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;

/**
 * The divert operations Rahdar exposes (ADR-0065), shared by the HTTP API and the {@code divert_lifecycle}
 * MCP tool so a kind cannot be reachable through one and not the other.
 *
 * <p>A divert changes where traffic goes rather than what a queue holds, so it has its own authority
 * ({@code divert:write}) and not the queue ones. Deleting one destroys no messages, but it is marked
 * destructive because it silently changes routing for every producer on the source address and cannot be
 * noticed by them.
 */
public enum DivertKind {
    CREATE_DIVERT(false),
    DELETE_DIVERT(true);

    /** What the audit trail files a divert under. */
    public static final String TARGET_TYPE = "ROUTE";

    private final boolean destructive;

    DivertKind(boolean destructive) {
        this.destructive = destructive;
    }

    public String auditName() {
        return this == CREATE_DIVERT ? "CREATE_ROUTE" : "DELETE_ROUTE";
    }

    public String permission() {
        return ResourcePermissions.ROUTE_WRITE;
    }

    /** Whether this kind needs a typed confirmation in the UI and a matching {@code confirm} over MCP. */
    public boolean destructive() {
        return destructive;
    }
}
