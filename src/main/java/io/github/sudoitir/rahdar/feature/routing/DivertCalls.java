package io.github.sudoitir.rahdar.feature.routing;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.RouteEndpoint;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.DivertRoute;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;

/** What the divert lifecycle asks of one node, in the broker SDK's terms. */
final class DivertCalls {

    private DivertCalls() {}

    /**
     * Creates the divert. The broker's own answer is verified by the driver: an identical divert already
     * there is ALREADY, a different one under the name is refused naming how, and one the broker accepted
     * but did not deploy is an error.
     */
    static NodeStatus create(BrokerTarget node, CreateDivertRequest req) {
        ClusterConnection c = node.connection();
        Attributes.Builder attributes = Attributes.builder()
                .put(StandardAttributes.ROUTE_SOURCE, endpoint(c, req.address()))
                .put(StandardAttributes.ROUTE_TARGET, endpoint(c, req.forwardingAddress()))
                .putRaw(
                        DivertRoute.ROUTING_NAME,
                        req.routingName() == null || req.routingName().isBlank() ? req.name() : req.routingName())
                .putRaw(DivertRoute.EXCLUSIVE, req.exclusive());
        if (req.filter() != null && !req.filter().isBlank()) {
            attributes.put(StandardAttributes.FILTER, req.filter());
        }
        if (req.routingType() != null && !req.routingType().isBlank()) {
            attributes.putRaw(DivertRoute.ROUTING_TYPE, req.routingType());
        }
        return status(node.execute(DivertRoute.id(req.name()), new Operation.Create(attributes.build())));
    }

    static NodeStatus delete(BrokerTarget node, String name) {
        return status(node.execute(DivertRoute.id(name), Operation.delete()));
    }

    /** Whether the address is there on the node. */
    static boolean addressExists(BrokerTarget node, String address) {
        ClusterConnection c = node.connection();
        ResourceQuery query =
                ResourceQuery.of(ResourceClass.DESTINATION).named(address).in(node.scope());
        return BrokerFailures.translated(() ->
                c.resources().read(query).orThrow().items().stream().anyMatch(r -> address.equals(r.id().name())));
    }

    private static RouteEndpoint endpoint(ClusterConnection c, String address) {
        ResourceId id = ResourceIds.of(c, ResourceClass.DESTINATION, address);
        return new RouteEndpoint.Local(id);
    }

    private static NodeStatus status(OperationResult result) {
        return result.alreadyInState() ? NodeStatus.ALREADY : NodeStatus.APPLIED;
    }
}
