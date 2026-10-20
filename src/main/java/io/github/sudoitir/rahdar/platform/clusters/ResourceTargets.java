package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.InvalidResourceIdException;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import java.util.Arrays;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads the {@code {kind}/{path}} of a resource route into the resource it names, on a cluster the caller can
 * see. A cluster the caller cannot see is a 404 before anything about the id is said; an id that does not
 * parse, or whose kind the cluster's system does not have or is not one of the routes's classes, is a 400
 * {@code invalid-resource-id}.
 */
@Component
@RequiredArgsConstructor
public class ResourceTargets {

    /** A resource a route names, and the class its kind belongs to. */
    public record Target(ResourceId id, ResourceClass resourceClass) {

        /** The resource's name, which is what the broker calls take for a one-segment path. */
        public String name() {
            return id.name();
        }
    }

    private final ClusterAccessGuard access;
    private final BrokerGateway gateway;

    /** The resource {@code kind} and {@code path} name, whose class must be one of {@code allowed}. */
    public Target require(UUID clusterId, String kind, String path, ResourceClass... allowed) {
        access.requireVisible(clusterId);
        ResourceId id = ResourceIds.parse(kind, path);
        ResourceClass resourceClass =
                ResourceIds.kindOf(gateway.cluster(clusterId), id).resourceClass();
        if (!Arrays.asList(allowed).contains(resourceClass)) {
            throw new InvalidResourceIdException("'" + id.format() + "' is a "
                    + resourceClass.name().toLowerCase().replace('_', ' ') + ", which this does not apply to.");
        }
        return new Target(id, resourceClass);
    }

    /** Checks that {@code kind} is a kind of this class on the cluster's system, for a resource still to be created. */
    public void requireKind(UUID clusterId, String kind, ResourceClass expected) {
        access.requireVisible(clusterId);
        KindId id;
        try {
            id = KindId.of(kind);
        } catch (RuntimeException e) {
            throw new InvalidResourceIdException("'" + kind + "' is not a resource kind.");
        }
        boolean matches = gateway.cluster(clusterId).resources().kinds().stream()
                .anyMatch(k -> k.id().equals(id) && k.resourceClass() == expected);
        if (!matches) {
            throw new InvalidResourceIdException(
                    "This cluster has no " + expected.name().toLowerCase() + " kind '" + kind + "'.");
        }
    }
}
