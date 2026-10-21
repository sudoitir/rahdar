package io.github.sudoitir.rahdar.kernel.security;

import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;

/**
 * The permission strings the security kernel itself checks (ADR-0038). Every other
 * permission is declared by the module that checks it, and the catalogue a role is
 * built from is assembled from the enabled modules' descriptors. A role may still
 * legally hold any string: {@code role_permission.action} is free-form data.
 */
public final class Permissions {

    public static final String WILDCARD = "*";

    /** Reading a cluster at all — the permission every cluster-scoped read checks first. */
    public static final String CLUSTER_READ = "cluster:read";

    public static final String USER_ADMIN = "user:admin";

    /**
     * Administering teams. Held at global scope it administers every team; held in a team role it
     * means "admin of that team".
     */
    public static final String TEAM_ADMIN = "team:admin";

    /**
     * Seeing a queue, and the read every permission that acts on a queue requires. Declared with the
     * queue permissions, named here because modules other than the queues' own require it.
     */
    public static final String SUBSCRIPTION_READ = "subscription:read";

    /** Seeing an address, and the read every permission that acts on an address requires. */
    public static final String DESTINATION_READ = "destination:read";

    /** The read permission of a kind of resource: what seeing one requires. */
    public static String readOf(ResourceType kind) {
        return kind == ResourceType.SUBSCRIPTION ? SUBSCRIPTION_READ : DESTINATION_READ;
    }

    private Permissions() {}
}
