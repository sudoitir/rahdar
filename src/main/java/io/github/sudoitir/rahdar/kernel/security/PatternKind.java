package io.github.sudoitir.rahdar.kernel.security;

import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import java.util.EnumSet;
import java.util.Set;

/** What a team pattern or a share covers: subscription names, destination names, or any. */
public enum PatternKind {
    SUBSCRIPTION(EnumSet.of(ResourceType.SUBSCRIPTION)),
    DESTINATION(EnumSet.of(ResourceType.DESTINATION)),
    ANY(EnumSet.allOf(ResourceType.class));

    private final Set<ResourceType> types;

    PatternKind(Set<ResourceType> types) {
        this.types = types;
    }

    /** The resource types whose names it covers. */
    public Set<ResourceType> types() {
        return types;
    }
}
