package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Kind;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.ResourceIdTexts;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Resource ids as the cluster's broker system makes them: its first kind of a class, and the kinds it has. */
@Component
@RequiredArgsConstructor
class ClusterResourceIdTexts implements ResourceIdTexts {

    private final BrokerGateway gateway;

    @Override
    public String subscription(UUID clusterId, String name) {
        return id(gateway.cluster(clusterId), ResourceClass.SUBSCRIPTION, name);
    }

    @Override
    public String destination(UUID clusterId, String name) {
        return id(gateway.cluster(clusterId), ResourceClass.DESTINATION, name);
    }

    @Override
    public Of of(UUID clusterId) {
        ClusterConnection connection = gateway.cluster(clusterId);
        return new Of() {
            @Override
            public String subscription(String name) {
                return id(connection, ResourceClass.SUBSCRIPTION, name);
            }

            @Override
            public String destination(String name) {
                return id(connection, ResourceClass.DESTINATION, name);
            }
        };
    }

    @Override
    public Optional<ResourceType> typeOf(UUID clusterId, String id) {
        ResourceId parsed = ResourceId.parse(id);
        return gateway.cluster(clusterId).resources().kinds().stream()
                .filter(k -> k.id().equals(parsed.kind()))
                .map(Kind::resourceClass)
                .map(ClusterResourceIdTexts::typeOf)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<ResourceType> typeOf(ResourceClass resourceClass) {
        return switch (resourceClass) {
            case SUBSCRIPTION -> Optional.of(ResourceType.SUBSCRIPTION);
            case DESTINATION -> Optional.of(ResourceType.DESTINATION);
            default -> Optional.empty();
        };
    }

    private static String id(ClusterConnection connection, ResourceClass resourceClass, String name) {
        if (name == null) {
            return null;
        }
        return ResourceIds.of(connection, resourceClass, name).format();
    }
}
