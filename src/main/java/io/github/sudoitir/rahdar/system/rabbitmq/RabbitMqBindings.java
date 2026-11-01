package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.RouteCreate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.RouteDelete;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.Binding;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.BindingPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.BindingRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ChangePreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ChangeResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The bindings of a RabbitMQ cluster: which exchange routes to which queue or exchange, and under which key. */
@Service
@RequiredArgsConstructor
public class RabbitMqBindings {

    /** The key the driver names a binding without a routing key by. */
    private static final String NO_KEY = "~";

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;
    private final RabbitMqOps ops;
    private final RabbitMqChanges changes;

    public BindingPage list(
            UUID clusterId, String vhost, String source, String destination, String cursor, Integer limit) {
        access.requireCluster(clusterId, Permissions.DESTINATION_READ);
        ClusterConnection connection = gateway.cluster(clusterId);
        Map<String, String> selector =
                vhost == null || vhost.isBlank() ? Map.of() : Map.of(RabbitMqIds.VHOST_SELECTOR, vhost);
        List<Resource> found = new ArrayList<>();
        for (String kind : List.of(RabbitMqIds.BINDING, RabbitMqIds.EXCHANGE_BINDING)) {
            found.addAll(RabbitMqReads.all(connection, RabbitMqReads.of(RabbitMqIds.kind(kind), selector))
                    .items());
        }
        List<Binding> matching = found.stream()
                .map(RabbitMqBindings::binding)
                .filter(b -> source == null || source.isBlank() || b.source().equals(source))
                .filter(b -> destination == null
                        || destination.isBlank()
                        || b.destination().equals(destination))
                .sorted(Comparator.comparing(Binding::id))
                .toList();
        int from = Math.min(RabbitMqReads.offset(cursor), matching.size());
        int to = Math.min(matching.size(), from + RabbitMqReads.limit(limit));
        return new BindingPage(matching.subList(from, to), to < matching.size() ? String.valueOf(to) : null);
    }

    static Binding binding(Resource resource) {
        List<String> segments = resource.id().path().segments();
        return new Binding(
                resource.id().format(),
                segments.get(0),
                segments.get(1),
                segments.get(2),
                RabbitMqIds.EXCHANGE_BINDING.equals(resource.id().kind().value()) ? "exchange" : "queue",
                RabbitMqReads.text(resource, RabbitMqIds.FILTER),
                RabbitMqReads.members(resource, RabbitMqIds.ARGUMENT_PREFIX));
    }

    public ChangePreview previewCreate(UUID clusterId, BindingRequest request) {
        access.requireCluster(clusterId, ResourcePermissions.ROUTE_WRITE);
        return RabbitMqOps.view(
                ops.preview(clusterId, id(request), Verb.CREATE, attributes(request)),
                Verb.CREATE,
                "Bind " + request.destinationType() + " " + request.destination() + " to exchange " + request.source()
                        + " with key '" + key(request) + "'.",
                List.of());
    }

    public ChangeResult create(UUID clusterId, BindingRequest request) {
        access.requireCluster(clusterId, ResourcePermissions.ROUTE_WRITE);
        return changes.apply(new RouteCreate(clusterId, id(request).format(), attributes(request), request.etag()));
    }

    /** What deleting a binding or a shovel would do. */
    public ChangePreview previewDelete(UUID clusterId, String id) {
        access.requireCluster(clusterId, ResourcePermissions.ROUTE_WRITE);
        ResourceId target = target(id);
        return RabbitMqOps.view(
                ops.preview(clusterId, target, Verb.DELETE, Map.of()),
                Verb.DELETE,
                RabbitMqChanges.describe(RabbitMqChanges.Type.ROUTE_DELETE, target),
                List.of());
    }

    public ChangeResult delete(UUID clusterId, String id, String etag) {
        access.requireCluster(clusterId, ResourcePermissions.ROUTE_WRITE);
        return changes.apply(new RouteDelete(clusterId, target(id).format(), Map.of(), etag));
    }

    private static ResourceId target(String id) {
        return RabbitMqIds.parse(id, RabbitMqIds.BINDING, RabbitMqIds.EXCHANGE_BINDING, RabbitMqIds.SHOVEL);
    }

    private static String key(BindingRequest request) {
        return request.routingKey() == null ? "" : request.routingKey();
    }

    private static ResourceId id(BindingRequest request) {
        if (blank(request.vhost()) || blank(request.source()) || blank(request.destination())) {
            throw new IllegalArgumentException("A binding needs a virtual host, a source exchange and a destination.");
        }
        String kind =
                switch (request.destinationType() == null ? "" : request.destinationType()) {
                    case "queue" -> RabbitMqIds.BINDING;
                    case "exchange" -> RabbitMqIds.EXCHANGE_BINDING;
                    default -> throw new IllegalArgumentException("A binding's destination is a queue or an exchange.");
                };
        return RabbitMqIds.id(
                kind,
                request.vhost(),
                request.source(),
                request.destination(),
                key(request).isEmpty() ? NO_KEY : key(request));
    }

    private static Map<String, Object> attributes(BindingRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(RabbitMqIds.FILTER, key(request));
        RabbitMqQueues.arguments(out, request.arguments());
        return out;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
