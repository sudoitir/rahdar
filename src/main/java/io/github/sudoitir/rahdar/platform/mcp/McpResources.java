package io.github.sudoitir.rahdar.platform.mcp;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.platform.broker.InvalidResourceIdException;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads a tool argument that names a resource, which holds its canonical id such as
 * {@code artemis.queue/orders}, into the resource it names on a cluster the key can see. The tools
 * share this with the REST routes through {@link ResourceTargets}, so an id means the same thing on
 * both surfaces and a kind the cluster's system does not have is refused the same way.
 *
 * <p>Call it inside {@link McpErrors#guard}: a cluster the key cannot see is a not-found there, said
 * before anything about the id is.
 */
@Component
@RequiredArgsConstructor
public class McpResources {

    /** A resource an argument names, and the class its kind belongs to. */
    public record Target(ResourceId id, ResourceClass resourceClass) {

        /** The resource's name, which the broker calls and the confirmation take. */
        public String name() {
            return id.name();
        }
    }

    private final ResourceTargets targets;

    /** The resource {@code id} names, whose class must be one of {@code allowed}. */
    public Target require(UUID clusterId, String field, String id, ResourceClass... allowed) {
        String text = McpArgs.required(field, id);
        int slash = text.indexOf('/');
        if (slash <= 0) {
            throw malformed(field, text);
        }
        try {
            ResourceTargets.Target target =
                    targets.require(clusterId, text.substring(0, slash), text.substring(slash + 1), allowed);
            return new Target(target.id(), target.resourceClass());
        } catch (InvalidResourceIdException e) {
            throw McpErrors.invalidParams(field + ": " + e.getMessage());
        }
    }

    /** The id of a resource still to be created, whose kind must be a kind of {@code expected} on the cluster. */
    public ResourceId newId(UUID clusterId, String field, String id, ResourceClass expected) {
        String text = McpArgs.required(field, id);
        int slash = text.indexOf('/');
        if (slash <= 0) {
            throw malformed(field, text);
        }
        try {
            targets.requireKind(clusterId, text.substring(0, slash), expected);
            return ResourceId.parse(text);
        } catch (InvalidResourceIdException e) {
            throw McpErrors.invalidParams(field + ": " + e.getMessage());
        } catch (IllegalArgumentException _) {
            throw malformed(field, text);
        }
    }

    private static RuntimeException malformed(String field, String text) {
        return McpErrors.invalidParams(field + " must be a resource id such as artemis.queue/orders, as "
                + McpToolCatalog.LIST_TOOL + " returns it; got \"" + text + "\".");
    }
}
