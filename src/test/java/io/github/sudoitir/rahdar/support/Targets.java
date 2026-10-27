package io.github.sudoitir.rahdar.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.model.AttributeSchema;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Kind;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.KindLabel;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.RouteClass;
import io.github.sudoitir.openbroker.model.RouteEndpoint;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A node for tests that mock what is done to it: its connection knows Artemis's kinds, and a listing of its
 * resources finds nothing.
 */
public final class Targets {

    private Targets() {}

    public static BrokerTarget target() {
        ClusterConnection connection = mock(ClusterConnection.class);
        Resources resources = mock(Resources.class);
        when(connection.resources()).thenReturn(resources);
        when(resources.kinds())
                .thenReturn(List.of(
                        kind("artemis.queue", ResourceClass.SUBSCRIPTION),
                        kind("artemis.address", ResourceClass.DESTINATION),
                        kind("artemis.connection", ResourceClass.CONNECTION),
                        kind("artemis.session", ResourceClass.CHANNEL),
                        kind("artemis.consumer", ResourceClass.CONSUMER),
                        kind("artemis.producer", ResourceClass.PRODUCER),
                        kind("artemis.divert", ResourceClass.ROUTE),
                        kind("artemis.bridge", ResourceClass.ROUTE)));
        when(resources.read(any(ResourceQuery.class))).thenReturn(page());
        Operations operations = mock(Operations.class);
        when(connection.operations()).thenReturn(operations);
        when(operations.execute(any(), any(), any())).thenReturn(done());
        return new BrokerTarget(connection, NodeId.of("node"));
    }

    /** The target answers every read with these resources, narrowed by the query's kind, name and fields. */
    public static void holding(BrokerTarget target, Resource... held) {
        when(target.connection().resources().read(any(ResourceQuery.class))).thenAnswer(call -> {
            ResourceQuery query = call.getArgument(0);
            return page(List.of(held).stream().filter(r -> answers(query, r)).toArray(Resource[]::new));
        });
    }

    /** What the target is asked to do, as the SDK sees it. */
    public static Operations operations(BrokerTarget target) {
        return target.connection().operations();
    }

    /** A failure of the broker, as the SDK raises it. */
    public static BrokerException failure(String reason, Map<String, String> metadata, String message) {
        return new BrokerException(
                Status.FAILED_PRECONDITION,
                ErrorInfo.of(reason, metadata),
                Dispatch.REJECTED,
                NodeId.of("node"),
                message);
    }

    public static Partial<OperationResult> done() {
        return new Partial<>(OperationResult.done(), Map.of(), Map.of());
    }

    public static Partial<OperationResult> already() {
        return new Partial<>(OperationResult.already(), Map.of(), Map.of());
    }

    /** A queue as a node lists it. */
    public static Resource queue(String name, String address, long consumers, long backlog) {
        return resource(
                id("artemis.queue", name),
                Attributes.builder()
                        .put(StandardAttributes.DESTINATION, id("artemis.address", address))
                        .put(MetricKeys.CONSUMER_COUNT, MetricValue.exact(consumers))
                        .put(MetricKeys.BACKLOG, MetricValue.exact(backlog))
                        .build());
    }

    /** An address as a node lists it. */
    public static Resource address(String name) {
        return resource(id("artemis.address", name), Attributes.empty());
    }

    /** A divert as a node lists it. */
    public static Resource divert(String name, String from, String to) {
        return resource(
                id("artemis.divert", name),
                Attributes.builder()
                        .put(StandardAttributes.ROUTE_CLASS, RouteClass.FORWARD)
                        .put(StandardAttributes.ROUTE_SOURCE, new RouteEndpoint.Local(id("artemis.address", from)))
                        .put(StandardAttributes.ROUTE_TARGET, new RouteEndpoint.Local(id("artemis.address", to)))
                        .putRaw("artemis.routing_name", name)
                        .putRaw("artemis.routing_type", "STRIP")
                        .putRaw("artemis.exclusive", false)
                        .build());
    }

    public static Resource resource(ResourceId id, Attributes attributes) {
        return new Resource(id, attributes, List.of(), List.of(), Instant.now());
    }

    private static Partial<ResourcePage> page(Resource... items) {
        return new Partial<>(new ResourcePage(List.of(items), Optional.empty()), Map.of(), Map.of());
    }

    private static boolean answers(ResourceQuery query, Resource r) {
        String kind = r.id().kind().value();
        boolean ofKind = query.kind()
                .map(k -> k.equals(r.id().kind()))
                .orElseGet(() -> query.resourceClass().orElseThrow() == classOf(kind));
        return ofKind
                && query.namePattern().map(r.id().name()::equals).orElse(true)
                && query.fieldSelector().entrySet().stream()
                        .allMatch(e -> r.attributes()
                                .raw(e.getKey())
                                .map(String::valueOf)
                                .filter(e.getValue()::equals)
                                .isPresent());
    }

    private static ResourceClass classOf(String kind) {
        return switch (kind) {
            case "artemis.queue" -> ResourceClass.SUBSCRIPTION;
            case "artemis.address" -> ResourceClass.DESTINATION;
            default -> ResourceClass.ROUTE;
        };
    }

    /** An Artemis resource id. */
    public static ResourceId id(String kind, String... path) {
        return new ResourceId(KindId.of(kind), ResourcePath.of(path));
    }

    private static Kind kind(String id, ResourceClass resourceClass) {
        return new Kind(
                KindId.of(id),
                KindLabel.of(id, id + "s"),
                resourceClass,
                Set.of(),
                Optional.empty(),
                new AttributeSchema(List.of()),
                Set.of());
    }
}
