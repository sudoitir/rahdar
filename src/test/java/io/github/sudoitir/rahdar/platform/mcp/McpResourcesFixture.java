package io.github.sudoitir.rahdar.platform.mcp;

import static org.mockito.Mockito.mock;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;

/** A {@link McpResources} for unit tests of the tools: it reads an id as written, with no cluster behind it. */
public final class McpResourcesFixture {

    private McpResourcesFixture() {}

    /** Resolves every id to a resource of the first class the call allows, on any cluster. */
    public static McpResources resolving() {
        return mock(McpResources.class, invocation -> {
            String text = invocation.getArgument(2);
            ResourceId id = ResourceId.parse(McpArgs.required(invocation.getArgument(1), text));
            return switch (invocation.getMethod().getName()) {
                case "require" -> new McpResources.Target(id, (ResourceClass) invocation.getArgument(3));
                case "newId" -> id;
                default -> null;
            };
        });
    }
}
