package io.github.sudoitir.rahdar.kernel.security;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;

/** A subscription or a destination of a cluster, by name: what a resource permission is checked against. */
@PluginApi
public record ResourceRef(ResourceType type, String name) {

    /** The permission that lets a caller see a resource of this type at all, and that every other permission on it requires. */
    public String readPermission() {
        return Permissions.readOf(type);
    }

    public static ResourceRef subscription(String name) {
        return new ResourceRef(ResourceType.SUBSCRIPTION, name);
    }

    public static ResourceRef destination(String name) {
        return new ResourceRef(ResourceType.DESTINATION, name);
    }
}
