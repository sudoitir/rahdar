package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.rahdar.kernel.security.ResourceRef;

/**
 * The lifecycle operations Rahdar exposes (ADR-0049). One enum shared by the HTTP
 * API and the single MCP {@code queue_lifecycle} tool, so a kind cannot be
 * reachable through one and not the other.
 *
 * <p>Holding a message-level permission implies none of these. Purging a queue and
 * destroying one are different authorities: one empties a resource the operator
 * keeps, the other removes the resource itself.
 */
public enum LifecycleKind {
    CREATE_QUEUE("CREATE_QUEUE", LifecycleKind.QUEUE_TARGET, ResourcePermissions.SUBSCRIPTION_CREATE, false),
    UPDATE_QUEUE("UPDATE_QUEUE", LifecycleKind.QUEUE_TARGET, ResourcePermissions.SUBSCRIPTION_UPDATE, false),
    DELETE_QUEUE("DELETE_QUEUE", LifecycleKind.QUEUE_TARGET, ResourcePermissions.SUBSCRIPTION_DELETE, true),
    PAUSE_QUEUE("PAUSE_QUEUE", LifecycleKind.QUEUE_TARGET, ResourcePermissions.SUBSCRIPTION_PAUSE, false),
    RESUME_QUEUE("RESUME_QUEUE", LifecycleKind.QUEUE_TARGET, ResourcePermissions.SUBSCRIPTION_PAUSE, false),
    RESET_QUEUE_COUNTER(
            "RESET_QUEUE_COUNTER", LifecycleKind.QUEUE_TARGET, ResourcePermissions.SUBSCRIPTION_UPDATE, false),
    CREATE_ADDRESS("CREATE_ADDRESS", LifecycleKind.ADDRESS_TARGET, ResourcePermissions.DESTINATION_CREATE, false),
    DELETE_ADDRESS("DELETE_ADDRESS", LifecycleKind.ADDRESS_TARGET, ResourcePermissions.DESTINATION_DELETE, true);

    private static final String QUEUE_TARGET = "QUEUE";
    private static final String ADDRESS_TARGET = "ADDRESS";

    private final String auditName;
    private final String targetType;
    private final String permission;
    private final boolean destructive;

    LifecycleKind(String auditName, String targetType, String permission, boolean destructive) {
        this.auditName = auditName;
        this.targetType = targetType;
        this.permission = permission;
        this.destructive = destructive;
    }

    public String auditName() {
        return auditName;
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
     * argument over MCP, and a queue delete additionally goes through the bulk cap.
     */
    public boolean destructive() {
        return destructive;
    }

    /** Whether re-running this kind leaves the cluster in the same state. */
    public boolean idempotent() {
        return this != RESET_QUEUE_COUNTER;
    }

    /** The subscription or destination this kind acts on when it is given {@code name}, which its permission is checked against. */
    public ResourceRef resource(String name) {
        return isAddressKind() ? ResourceRef.destination(name) : ResourceRef.subscription(name);
    }

    public boolean isAddressKind() {
        return this == CREATE_ADDRESS || this == DELETE_ADDRESS;
    }

    /** Parse a path segment such as {@code create-queue}. */
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
