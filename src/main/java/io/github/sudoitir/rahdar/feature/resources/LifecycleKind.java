package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.rahdar.kernel.security.ResourceRef;

/**
 * The lifecycle operations Rahdar exposes (ADR-0049). One enum shared by the HTTP
 * API and the single MCP {@code queue_lifecycle} tool, so a kind cannot be
 * reachable through one and not the other.
 *
 * <p>Holding a message-level permission implies none of these. Purging a subscription and
 * destroying one are different authorities: one empties a resource the operator
 * keeps, the other removes the resource itself.
 */
public enum LifecycleKind {
    CREATE_SUBSCRIPTION(LifecycleKind.SUBSCRIPTION_TARGET, ResourcePermissions.SUBSCRIPTION_CREATE, false),
    UPDATE_RESOURCE(LifecycleKind.SUBSCRIPTION_TARGET, ResourcePermissions.SUBSCRIPTION_UPDATE, false),
    DELETE_SUBSCRIPTION(LifecycleKind.SUBSCRIPTION_TARGET, ResourcePermissions.SUBSCRIPTION_DELETE, true),
    PAUSE_SUBSCRIPTION(LifecycleKind.SUBSCRIPTION_TARGET, ResourcePermissions.SUBSCRIPTION_PAUSE, false),
    RESUME_SUBSCRIPTION(LifecycleKind.SUBSCRIPTION_TARGET, ResourcePermissions.SUBSCRIPTION_PAUSE, false),
    RESET_COUNTERS(LifecycleKind.SUBSCRIPTION_TARGET, ResourcePermissions.SUBSCRIPTION_UPDATE, false),
    CREATE_DESTINATION(LifecycleKind.DESTINATION_TARGET, ResourcePermissions.DESTINATION_CREATE, false),
    DELETE_DESTINATION(LifecycleKind.DESTINATION_TARGET, ResourcePermissions.DESTINATION_DELETE, true);

    private static final String SUBSCRIPTION_TARGET = "SUBSCRIPTION";
    private static final String DESTINATION_TARGET = "DESTINATION";

    private final String targetType;
    private final String permission;
    private final boolean destructive;

    LifecycleKind(String targetType, String permission, boolean destructive) {
        this.targetType = targetType;
        this.permission = permission;
        this.destructive = destructive;
    }

    public String auditName() {
        return name();
    }

    public String targetType() {
        return targetType;
    }

    public String permission() {
        return permission;
    }

    /**
     * Whether this kind destroys something that cannot be restored. Destructive
     * kinds require a typed confirmation in the UI and a matching {@code confirm}
     * argument over MCP, and a subscription delete additionally goes through the bulk cap.
     */
    public boolean destructive() {
        return destructive;
    }

    /** Whether re-running this kind leaves the cluster in the same state. */
    public boolean idempotent() {
        return this != RESET_COUNTERS;
    }

    /** The subscription or destination this kind acts on when it is given {@code name}, which its permission is checked against. */
    public ResourceRef resource(String name) {
        return isAddressKind() ? ResourceRef.destination(name) : ResourceRef.subscription(name);
    }

    public boolean isAddressKind() {
        return this == CREATE_DESTINATION || this == DELETE_DESTINATION;
    }

    /** Parse a name such as {@code create-subscription}. */
    public static LifecycleKind fromPath(String path) {
        String needle = path == null ? "" : path.replace('-', '_').toUpperCase();
        for (LifecycleKind kind : values()) {
            if (kind.name().equals(needle)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown lifecycle operation '" + path + "'.");
    }
}
