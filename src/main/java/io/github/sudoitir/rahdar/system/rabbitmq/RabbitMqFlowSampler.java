package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.RouteEndpoint;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * What flow reads from a RabbitMQ cluster: the consumers on each queue, with the connection each one came in
 * on, and the objects that route messages: bindings from an exchange to a queue or to another exchange,
 * shovels and federation links. The management API answers for the whole cluster, so flow asks it once,
 * through one node. Names are the ones the queue listing uses, a virtual host and a name, so a consumer, a
 * binding and a queue meet in the graph. The driver leaves the default exchange's bindings out.
 */
@Component
@RequiredArgsConstructor
class RabbitMqFlowSampler implements FlowSampler {

    static final String PRODUCERS_NOTE = "Rahdar does not read producers from RabbitMQ, so none are shown: only the"
            + " clients consuming each queue are.";

    private static final int PAGE = 500;

    private final BrokerGateway gateway;

    /** What one listing found, and whether the cap cut it short. */
    private record Found(List<Resource> items, boolean truncated) {}

    @Override
    public String system() {
        return RabbitMqModule.SYSTEM;
    }

    @Override
    public boolean clusterWide() {
        return true;
    }

    @Override
    public boolean memberCounters() {
        return false;
    }

    @Override
    public String producersNote() {
        return PRODUCERS_NOTE;
    }

    @Override
    public FlowSample sample(UUID clusterId, UUID nodeId, int rowCap) {
        ClusterConnection connection = gateway.cluster(clusterId);
        Found consumerRows = RabbitMqFailures.call(() -> readAll(connection, RabbitMqIds.CONSUMER, rowCap));
        Map<String, Resource> connections = new HashMap<>();
        if (!consumerRows.items().isEmpty()) {
            RabbitMqFailures.call(() -> readAll(connection, RabbitMqIds.CONNECTION, RabbitMqReads.CAP))
                    .items()
                    .forEach(c -> connections.put(c.id().name(), c));
        }
        List<FlowMember> consumers =
                consumerRows.items().stream().map(c -> consumer(c, connections)).toList();
        long total = consumers.size() + (consumerRows.truncated() ? 1 : 0);

        List<String> routingErrors = new ArrayList<>();
        List<FlowRoute> routes = new ArrayList<>();
        route(connection, RabbitMqIds.BINDING, false, routingErrors, routes);
        route(connection, RabbitMqIds.EXCHANGE_BINDING, false, routingErrors, routes);
        route(connection, RabbitMqIds.SHOVEL, true, routingErrors, routes);
        route(connection, RabbitMqIds.FEDERATION_LINK, true, routingErrors, routes);
        return new FlowSample(
                List.of(),
                0,
                consumers,
                total,
                routes,
                routingErrors.isEmpty() ? null : routingErrors.getFirst(),
                routingErrors.isEmpty() ? null : ROUTING_UNAVAILABLE);
    }

    private static FlowMember consumer(Resource consumer, Map<String, Resource> connections) {
        Attributes a = consumer.attributes();
        Optional<ResourceId> viaConnection = a.get(StandardAttributes.CONNECTION);
        Resource owner =
                viaConnection.map(ResourceId::name).map(connections::get).orElse(null);
        Attributes o = owner == null ? Attributes.empty() : owner.attributes();
        String queue = a.get(StandardAttributes.SUBSCRIPTION)
                .map(RabbitMqFlowSampler::path)
                .orElse(null);
        String clientId = o.get(StandardAttributes.CLIENT_ID)
                .orElse(viaConnection.map(ResourceId::name).orElse(null));
        return new FlowMember(
                path(consumer.id()),
                clientId,
                o.get(StandardAttributes.PRINCIPAL).orElse(null),
                o.get(StandardAttributes.REMOTE_ADDRESS).orElse(null),
                o.get(StandardAttributes.PROTOCOL).orElse(null),
                null,
                queue,
                0,
                0);
    }

    /** Adds the routes of one kind. A plugin-served kind that is not enabled has no routes, which is not a fault. */
    private static void route(
            ClusterConnection connection, String kind, boolean optional, List<String> errors, List<FlowRoute> out) {
        Found found;
        try {
            found = readAll(connection, kind, RabbitMqReads.CAP);
        } catch (BrokerException e) {
            if (optional && RabbitMqShovels.pluginMissing(e)) {
                return;
            }
            errors.add("The " + kind.substring(kind.indexOf('.') + 1) + " list could not be read: " + e.getMessage());
            return;
        }
        if (found.truncated()) {
            errors.add("More than " + RabbitMqReads.CAP + " " + kind.substring(kind.indexOf('.') + 1) + "s: only the"
                    + " first " + RabbitMqReads.CAP + " are read.");
        }
        for (Resource resource : found.items()) {
            out.add(route(kind, resource));
        }
    }

    private static FlowRoute route(String kind, Resource resource) {
        List<String> segments = resource.id().path().segments();
        return switch (kind) {
            case RabbitMqIds.BINDING, RabbitMqIds.EXCHANGE_BINDING -> {
                String source = segments.get(1);
                boolean toQueue = RabbitMqIds.BINDING.equals(kind);
                yield new FlowRoute(
                        toQueue ? "BINDING" : "EXCHANGE_BINDING",
                        path(resource.id()),
                        name(segments.get(0), source),
                        name(segments.get(0), segments.get(2)),
                        resource.attributes()
                                .raw(RabbitMqIds.FILTER)
                                .map(String::valueOf)
                                .orElse(null),
                        null,
                        false,
                        true,
                        0);
            }
            case RabbitMqIds.SHOVEL ->
                new FlowRoute(
                        "SHOVEL",
                        name(segments.get(0), resource.id().name()),
                        endpoint(resource, StandardAttributes.ROUTE_SOURCE.name()),
                        endpoint(resource, StandardAttributes.ROUTE_TARGET.name()),
                        null,
                        null,
                        false,
                        connected(resource),
                        0);
            default ->
                new FlowRoute(
                        "FEDERATION",
                        path(resource.id()),
                        endpoint(resource, StandardAttributes.ROUTE_SOURCE.name()),
                        name(segments.get(0), resource.id().name()),
                        null,
                        null,
                        false,
                        connected(resource),
                        0);
        };
    }

    private static boolean connected(Resource resource) {
        return resource.conditions().stream()
                .filter(c -> c.type().equals(ConditionTypes.CONNECTED))
                .findFirst()
                .map(c -> c.status() == ConditionStatus.TRUE)
                .orElse(true);
    }

    /** A route's end: a queue or exchange of this cluster by the name the graph uses, or another broker without credentials. */
    private static String endpoint(Resource resource, String key) {
        return switch (resource.attributes().raw(key).orElse(null)) {
            case RouteEndpoint.Local(var id) -> path(id);
            case RouteEndpoint.Remote(var uri, var label) ->
                RabbitMqShovels.withoutCredentials(uri) + (label.isEmpty() ? "" : " " + label);
            case null, default -> "";
        };
    }

    /** The name the queue listing gives a queue or exchange: its virtual host and name, encoded as an id path. */
    private static String name(String vhost, String name) {
        return path(RabbitMqIds.id(RabbitMqIds.EXCHANGE, vhost, name));
    }

    /** An id without its kind, as the snapshot of a queue keeps it. */
    private static String path(ResourceId id) {
        return id.format().substring(id.kind().value().length() + 1);
    }

    private static Found readAll(ClusterConnection connection, String kind, int cap) {
        KindId id = RabbitMqIds.kind(kind);
        List<Resource> out = new ArrayList<>();
        Optional<String> next = Optional.empty();
        do {
            ResourceQuery base = ResourceQuery.of(id).limit(Math.min(PAGE, cap + 1 - out.size()));
            ResourceQuery asked = next.map(base::continueFrom).orElse(base);
            ResourcePage page = connection.resources().read(asked).orThrow();
            out.addAll(page.items());
            next = page.continueToken();
            if (out.size() > cap) {
                return new Found(List.copyOf(out.subList(0, cap)), true);
            }
        } while (next.isPresent());
        return new Found(List.copyOf(out), false);
    }
}
