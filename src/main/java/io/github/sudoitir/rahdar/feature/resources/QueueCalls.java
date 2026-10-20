package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.openbroker.operation.Direction;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateDestinationRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateSubscriptionRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.UpdateResourceRequest;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.DivertRoute;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the queue and address lifecycle asks of one node, in the broker SDK's terms: a queue is a
 * subscription and an address a destination of the system the node runs. The service decides what to
 * do and what to record; this class is every call it makes.
 *
 * <p>The request fields become the SDK's attributes: the standard ones where the SDK has a word for
 * them, and the system's own, prefixed with its id, where it does not.
 */
final class QueueCalls {

    private static final String ROUTING_TYPE = "artemis.routing_type";
    private static final String ROUTING_TYPES = "artemis.routing_types";
    private static final String AUTO_CREATE_ADDRESS = "artemis.auto_create_address";
    private static final String PURGE_ON_NO_CONSUMERS = "artemis.purge_on_no_consumers";
    private static final String EXCLUSIVE = "artemis.exclusive";
    private static final String NON_DESTRUCTIVE = "artemis.non_destructive";
    private static final String RING_SIZE = "artemis.ring_size";

    /** What a node holds of a queue and of the queues bound to its address. */
    record NodeQueues(boolean present, long consumerCount, long messageCount, List<String> addressQueues) {}

    private QueueCalls() {}

    // ---- queues ----------------------------------------------------------

    static NodeStatus createQueue(BrokerTarget node, CreateSubscriptionRequest req) {
        ClusterConnection c = node.connection();
        Attributes.Builder attributes = Attributes.builder()
                .put(
                        StandardAttributes.DESTINATION,
                        ResourceIds.of(c, ResourceClass.DESTINATION, req.destinationName()))
                .putRaw(ROUTING_TYPE, req.routingType().toUpperCase(Locale.ROOT))
                .put(StandardAttributes.DURABLE, req.durable())
                .putRaw(AUTO_CREATE_ADDRESS, req.autoCreateAddress());
        if (req.filter() != null && !req.filter().isBlank()) {
            attributes.put(StandardAttributes.FILTER, req.filter());
        }
        if (req.maxConsumers() != null) {
            attributes.put(StandardAttributes.MAX_CONSUMERS, req.maxConsumers().longValue());
        }
        putRaw(attributes, PURGE_ON_NO_CONSUMERS, req.purgeOnNoConsumers());
        putRaw(attributes, EXCLUSIVE, req.exclusive());
        putRaw(attributes, NON_DESTRUCTIVE, req.nonDestructive());
        putRaw(attributes, RING_SIZE, req.ringSize());
        return status(node.execute(queue(c, req.name()), new Operation.Create(attributes.build())));
    }

    /** The attributes an update changes, empty when the request names none. A filter of "" clears it. */
    static Attributes patch(UpdateResourceRequest req) {
        Attributes.Builder patch = Attributes.builder();
        if (req.filter() != null) {
            patch.put(StandardAttributes.FILTER, req.filter());
        }
        if (req.maxConsumers() != null) {
            patch.put(StandardAttributes.MAX_CONSUMERS, req.maxConsumers().longValue());
        }
        putRaw(patch, PURGE_ON_NO_CONSUMERS, req.purgeOnNoConsumers());
        putRaw(patch, EXCLUSIVE, req.exclusive());
        putRaw(patch, NON_DESTRUCTIVE, req.nonDestructive());
        putRaw(patch, RING_SIZE, req.ringSize());
        return patch.build();
    }

    static NodeStatus updateQueue(BrokerTarget node, String name, Attributes patch) {
        return status(node.execute(queue(node.connection(), name), new Operation.Update(patch)));
    }

    static NodeStatus deleteQueue(BrokerTarget node, String name, boolean disconnectConsumers) {
        return status(node.execute(queue(node.connection(), name), new Operation.Delete(false, disconnectConsumers)));
    }

    static NodeStatus setPaused(BrokerTarget node, String name, boolean paused) {
        Operation operation =
                paused ? new Operation.Pause(Direction.DELIVERY) : new Operation.Resume(Direction.DELIVERY);
        return status(node.execute(queue(node.connection(), name), operation));
    }

    /** The queue on the node and every queue bound to its address, read in one call. */
    static NodeQueues queues(BrokerTarget node, String address, String name) {
        ClusterConnection c = node.connection();
        ResourceQuery bound = ResourceQuery.of(ResourceClass.SUBSCRIPTION)
                .where(Map.of(
                        StandardAttributes.DESTINATION.name(),
                        ResourceIds.of(c, ResourceClass.DESTINATION, address).toString()))
                .in(node.scope());
        List<Resource> rows = BrokerFailures.translated(
                () -> c.resources().read(bound).orThrow().items());
        List<String> names = rows.stream().map(r -> r.id().name()).toList();
        Resource row = rows.stream()
                .filter(r -> name.equals(r.id().name()))
                .findFirst()
                .orElse(null);
        return new NodeQueues(
                row != null,
                row == null ? 0L : ResourceIds.count(row, MetricKeys.CONSUMER_COUNT),
                row == null ? 0L : ResourceIds.count(row, MetricKeys.BACKLOG),
                names);
    }

    /** How many messages the queue holds on the node, zero when it is not there. */
    static long messageCount(BrokerTarget node, String name) {
        ClusterConnection c = node.connection();
        ResourceQuery query =
                ResourceQuery.of(ResourceClass.SUBSCRIPTION).named(name).in(node.scope());
        return BrokerFailures.translated(() -> c.resources().read(query).orThrow().items().stream()
                .filter(r -> name.equals(r.id().name()))
                .mapToLong(r -> ResourceIds.count(r, MetricKeys.BACKLOG))
                .sum());
    }

    // ---- addresses -------------------------------------------------------

    static NodeStatus createAddress(BrokerTarget node, CreateDestinationRequest req) {
        Attributes attributes = Attributes.builder()
                .putRaw(ROUTING_TYPES, req.routingTypes().toUpperCase(Locale.ROOT))
                .build();
        return status(node.execute(address(node.connection(), req.name()), new Operation.Create(attributes)));
    }

    static NodeStatus deleteAddress(BrokerTarget node, String name) {
        return status(node.execute(address(node.connection(), name), Operation.delete()));
    }

    // ---- diverts a queue delete removes ----------------------------------

    static NodeStatus deleteDivert(BrokerTarget node, String name) {
        return status(node.execute(DivertRoute.id(name), Operation.delete()));
    }

    // ---- helpers ---------------------------------------------------------

    private static ResourceId queue(ClusterConnection c, String name) {
        return ResourceIds.of(c, ResourceClass.SUBSCRIPTION, name);
    }

    private static ResourceId address(ClusterConnection c, String name) {
        return ResourceIds.of(c, ResourceClass.DESTINATION, name);
    }

    private static void putRaw(Attributes.Builder attributes, String name, Object value) {
        if (value != null) {
            attributes.putRaw(name, value);
        }
    }

    private static NodeStatus status(OperationResult result) {
        return result.alreadyInState() ? NodeStatus.ALREADY : NodeStatus.APPLIED;
    }

    static long total(Count count) {
        return switch (count) {
            case Count.Exact(var e) -> e;
            case Count.Estimate(var e) -> e;
            case Count.Unavailable(var u) -> 0L;
        };
    }
}
