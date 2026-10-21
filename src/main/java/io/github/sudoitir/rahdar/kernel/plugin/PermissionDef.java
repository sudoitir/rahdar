package io.github.sudoitir.rahdar.kernel.plugin;

import java.util.Set;

/**
 * One permission string a module checks (ADR-0038), with its role-editor label, the scope it takes
 * effect at and the permissions a role must hold with it.
 *
 * @param resourceTypes the types of resource a {@link PermissionScope#RESOURCE} permission acts on;
 *     empty for every other scope
 * @param requires permissions that must be held wherever this one is, from this catalogue or another
 *     module's
 */
public record PermissionDef(
        String action, String label, PermissionScope scope, Set<ResourceType> resourceTypes, Set<String> requires) {

    public PermissionDef {
        if (scope == PermissionScope.RESOURCE && resourceTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Permission '" + action + "' acts on a resource but names no resource type");
        }
        if (scope != PermissionScope.RESOURCE && !resourceTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Permission '" + action + "' names resource types but is not 'resource'");
        }
        resourceTypes = Set.copyOf(resourceTypes);
        requires = Set.copyOf(requires);
    }

    public static PermissionDef global(String action, String label) {
        return new PermissionDef(action, label, PermissionScope.GLOBAL, Set.of(), Set.of());
    }

    public static PermissionDef cluster(String action, String label, String... requires) {
        return new PermissionDef(action, label, PermissionScope.CLUSTER, Set.of(), Set.of(requires));
    }

    public static PermissionDef resource(String action, String label, ResourceType kind, String... requires) {
        return new PermissionDef(action, label, PermissionScope.RESOURCE, Set.of(kind), Set.of(requires));
    }
}
