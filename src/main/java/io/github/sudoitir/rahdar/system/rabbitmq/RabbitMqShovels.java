package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.RouteEndpoint;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.Shovel;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ShovelPage;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The shovels of a RabbitMQ cluster: read, and deleted through {@link RabbitMqBindings#delete}. A shovel's addresses
 * carry credentials in RabbitMQ; the driver removes them, and nothing here takes or shows one.
 */
@Service
@RequiredArgsConstructor
public class RabbitMqShovels {

    static final String REMEDY = "Enable the rabbitmq_shovel and rabbitmq_shovel_management plugins on every node"
            + " (rabbitmq-plugins enable rabbitmq_shovel rabbitmq_shovel_management) to see shovels.";

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;

    public ShovelPage list(UUID clusterId, String vhost, String cursor, Integer limit) {
        access.requireCluster(clusterId, Permissions.DESTINATION_READ);
        Map<String, String> selector =
                vhost == null || vhost.isBlank() ? Map.of() : Map.of(RabbitMqIds.VHOST_SELECTOR, vhost);
        try {
            ResourcePage page = RabbitMqReads.rawPage(
                    gateway.cluster(clusterId),
                    RabbitMqReads.of(RabbitMqIds.kind(RabbitMqIds.SHOVEL), selector),
                    cursor,
                    limit);
            return new ShovelPage(
                    true,
                    null,
                    page.items().stream().map(RabbitMqShovels::shovel).toList(),
                    page.continueToken().orElse(null));
        } catch (BrokerException e) {
            if (pluginMissing(e)) {
                return new ShovelPage(false, REMEDY, List.of(), null);
            }
            throw RabbitMqFailures.translate(e);
        }
    }

    static Shovel shovel(Resource resource) {
        String state = resource.conditions().stream()
                .filter(c -> c.type().equals(ConditionTypes.CONNECTED))
                .findFirst()
                .map(c -> c.status() == ConditionStatus.TRUE ? "running" : c.reason())
                .orElse(null);
        return new Shovel(
                resource.id().format(),
                resource.id().path().segments().get(0),
                resource.id().name(),
                state,
                endpoint(resource, StandardAttributes.ROUTE_SOURCE.name()),
                endpoint(resource, StandardAttributes.ROUTE_TARGET.name()),
                resource.attributes().get(StandardAttributes.MANAGED_BY).orElse(null));
    }

    /** Where a route starts or ends: a resource of the cluster by name, or another broker by address, without credentials. */
    static String endpoint(Resource resource, String key) {
        Optional<Object> value = resource.attributes().raw(key);
        return switch (value.orElse(null)) {
            case RouteEndpoint.Local local ->
                String.join(" / ", local.resource().path().segments());
            case RouteEndpoint.Remote remote ->
                withoutCredentials(remote.uri()) + (remote.name().isEmpty() ? "" : " " + remote.name());
            case null, default -> "";
        };
    }

    /** The address with its user, password and query left out, whatever the driver sent. */
    static String withoutCredentials(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return (uri.getScheme() == null ? "amqp" : uri.getScheme()) + "://" + host + port + path;
    }

    /** Whether the read failed because the plugin that serves it is not enabled. */
    static boolean pluginMissing(BrokerException e) {
        return e.status() == Status.NOT_FOUND || e.status() == Status.UNIMPLEMENTED;
    }
}
