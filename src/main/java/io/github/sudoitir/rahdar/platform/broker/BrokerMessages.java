package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.messaging.BrowseRequest;
import io.github.sudoitir.openbroker.messaging.BulkOutcome;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.messaging.MessageAction;
import io.github.sudoitir.openbroker.messaging.MessageField;
import io.github.sudoitir.openbroker.messaging.MessageFilter;
import io.github.sudoitir.openbroker.messaging.MessageRef;
import io.github.sudoitir.openbroker.messaging.OutboundMessage;
import io.github.sudoitir.openbroker.messaging.Position;
import io.github.sudoitir.openbroker.messaging.ReceiveOptions;
import io.github.sudoitir.openbroker.messaging.Receiver;
import io.github.sudoitir.openbroker.messaging.Selection;
import io.github.sudoitir.openbroker.messaging.SendReceipt;
import io.github.sudoitir.openbroker.messaging.SendTarget;
import io.github.sudoitir.openbroker.messaging.Total;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.openbroker.operation.Operation;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The messages of one queue on one node, through the broker SDK. A filter is the system's own: its
 * native dialect, as an operator types it. Every failure is one of Rahdar's broker exceptions.
 */
@Component
public class BrokerMessages {

    /** The most messages one browse returns, whatever is asked for. */
    public static final int PAGE_CAP = 200;

    /** The most body bytes a browse reads of one message; a longer body is marked truncated. */
    public static final long MAX_BODY_BYTES = 1024L * 1024;

    private final BrokerGateway gateway;

    public BrokerMessages(BrokerGateway gateway) {
        this.gateway = gateway;
    }

    /** One page of a queue on a node, 1-based, with the broker's own count of the queue. */
    public BrowsePage browse(UUID clusterId, UUID nodeId, String queue, int page, int size, String filter) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            NodeId node = BrokerGateway.idOf(nodeId);
            Position at = page <= 1
                    ? Position.earliest()
                    : new Position.AtOffsets(Map.of(Shard.node(node), (long) (page - 1) * size));
            var result = c.messages()
                    .browse(
                            queueId(c, queue),
                            Scope.node(node),
                            new BrowseRequest(at, size, filter(c, filter), MAX_BODY_BYTES));
            List<BrowsedMessage> messages =
                    result.messages().stream().map(BrowsedMessages::of).toList();
            return switch (result.total().count()) {
                case Count.Exact(var e) -> new BrowsePage(messages, e);
                case Count.Estimate(var e) -> new BrowsePage(messages, e);
                case Count.Unavailable(var u) -> new BrowsePage(messages, null, u);
            };
        });
    }

    /**
     * The ids of the queue's messages on the node that match a filter in any dialect the system takes,
     * read a page at a time without their bodies.
     */
    public Set<Long> ids(UUID clusterId, UUID nodeId, String queue, MessageFilter filter) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            NodeId node = BrokerGateway.idOf(nodeId);
            ResourceId id = queueId(c, queue);
            Set<Long> found = new LinkedHashSet<>();
            long offset = 0;
            while (true) {
                var page = c.messages()
                        .browse(
                                id,
                                Scope.node(node),
                                new BrowseRequest(
                                        new Position.AtOffsets(Map.of(Shard.node(node), offset)),
                                        PAGE_CAP,
                                        Optional.of(filter),
                                        0));
                page.messages().forEach(m -> found.add(Long.parseLong(m.ref().position())));
                if (page.messages().size() < PAGE_CAP) {
                    return found;
                }
                offset += PAGE_CAP;
            }
        });
    }

    /** Sends one message to an address through a node; returns its id, when the system gives one. */
    public Optional<String> send(UUID clusterId, UUID nodeId, String address, OutboundMessage message) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            SendReceipt receipt = c.messages()
                    .send(
                            SendTarget.destination(ResourceIds.of(c, ResourceClass.DESTINATION, address)),
                            Scope.node(BrokerGateway.idOf(nodeId)),
                            message);
            return receipt.messageId();
        });
    }

    /** How many messages the queue holds on the node, or how many match a filter. */
    public long count(UUID clusterId, UUID nodeId, String queue, String filter) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            Total total = c.messages()
                    .count(queueId(c, queue), Scope.node(BrokerGateway.idOf(nodeId)), filter(c, filter))
                    .orThrow();
            return exact(total.count());
        });
    }

    /** The queue's messages on the node, counted by a header or property's values. */
    public Map<String, Long> countBy(UUID clusterId, UUID nodeId, String queue, MessageField field) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            return c.messages()
                    .countBy(queueId(c, queue), Scope.node(BrokerGateway.idOf(nodeId)), field)
                    .orThrow();
        });
    }

    /** Acts on the messages with these ids on the node. */
    public BulkOutcome byIds(UUID clusterId, UUID nodeId, String queue, List<Long> ids, MessageAction action) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            ResourceId id = queueId(c, queue);
            NodeId node = BrokerGateway.idOf(nodeId);
            List<MessageRef> refs = ids.stream()
                    .map(m -> new MessageRef(id, Shard.node(node), String.valueOf(m)))
                    .toList();
            return c.messages()
                    .bulk(id, Scope.node(node), new Selection.ById(Set.copyOf(refs)), action)
                    .orThrow();
        });
    }

    /**
     * Acts on the messages that match a filter, or on all of them when the filter is blank, produced at
     * or before {@code bound} when one is given.
     */
    public BulkOutcome byFilter(
            UUID clusterId, UUID nodeId, String queue, String filter, Optional<Instant> bound, MessageAction action) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            Selection selection = filter(c, filter)
                    .<Selection>map(f -> new Selection.ByFilter(f, bound))
                    .orElseGet(() -> new Selection.All(bound));
            return c.messages()
                    .bulk(queueId(c, queue), Scope.node(BrokerGateway.idOf(nodeId)), selection, action)
                    .orThrow();
        });
    }

    /** Acts on the messages that match a filter in any dialect the system takes, the portable one included. */
    public BulkOutcome byFilter(UUID clusterId, UUID nodeId, String queue, MessageFilter filter, MessageAction action) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            return c.messages()
                    .bulk(
                            queueId(c, queue),
                            Scope.node(BrokerGateway.idOf(nodeId)),
                            new Selection.ByFilter(filter, Optional.empty()),
                            action)
                    .orThrow();
        });
    }

    /** Removes every message of the queue on the node; returns how many the broker removed. */
    public long purge(UUID clusterId, UUID nodeId, String queue) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            return c.operations()
                    .execute(queueId(c, queue), new Operation.Purge(), Scope.node(BrokerGateway.idOf(nodeId)))
                    .orThrow()
                    .affected()
                    .orElse(0L);
        });
    }

    /**
     * The queue's identity on the node, as the approval gate pins it: which incarnation of it is there.
     * A queue deleted and created again since has another; a backlog that grew does not change it.
     */
    public String identity(UUID clusterId, UUID nodeId, String queue) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            return c.operations()
                    .preview(queueId(c, queue), new Operation.Purge(), Scope.node(BrokerGateway.idOf(nodeId)))
                    .etag();
        });
    }

    /**
     * The queue's identity across the cluster, as the approval gate pins a delete: which nodes hold it and
     * which incarnation each holds. A queue recreated, or a node that took it on since, changes it.
     */
    public String identity(UUID clusterId, String queue) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            return c.operations()
                    .preview(queueId(c, queue), new Operation.Delete(false, false), Scope.whole())
                    .etag();
        });
    }

    /**
     * A receiver on the queue on the node, for a drain that takes messages off it. The caller owns it
     * and closes it. Its failures, opening it included, are the SDK's own {@link BrokerException}: a
     * drain tells a lost connection from a queue that admits no more consumers by their reason.
     */
    public Receiver receiver(UUID clusterId, UUID nodeId, String queue, ReceiveOptions options) {
        ClusterConnection c = gateway.cluster(clusterId);
        return c.transport()
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE,
                        "This cluster has no data-plane connection to receive messages over."))
                .receiver(queueId(c, queue), Scope.node(BrokerGateway.idOf(nodeId)), options);
    }

    /** A move or copy's target: the queue of that name on the same system. */
    public ResourceId queueId(UUID clusterId, String queue) {
        return queueId(gateway.cluster(clusterId), queue);
    }

    /** The system's own filter for an expression an operator typed; empty for a blank one. */
    public static Optional<MessageFilter> filter(ClusterConnection c, String expression) {
        if (expression == null || expression.isBlank()) {
            return Optional.empty();
        }
        List<String> dialects = c.system().filterDialects();
        String dialect = dialects.isEmpty() ? MessageFilter.PORTABLE : dialects.getFirst();
        return Optional.of(new MessageFilter(dialect, expression));
    }

    private static ResourceId queueId(ClusterConnection c, String queue) {
        return ResourceIds.of(c, ResourceClass.SUBSCRIPTION, queue);
    }

    private static long exact(Count count) {
        return switch (count) {
            case Count.Exact(var e) -> e;
            case Count.Estimate(var e) -> e;
            case Count.Unavailable(var u) ->
                throw new BrokerConnectionException(
                        BrokerConnectionException.Kind.BAD_RESPONSE, "The broker did not count: " + u);
        };
    }

    /**
     * A call, failing as Rahdar's broker exceptions. A filter the system refused is the caller's
     * mistake, never echoed: it can carry sensitive literals.
     */
    private static <T> T translated(Supplier<T> call) {
        try {
            return call.get();
        } catch (BrokerException e) {
            if (ErrorReasons.INVALID_FILTER.equals(e.reason())) {
                throw new IllegalArgumentException("Invalid message filter.", e);
            }
            throw BrokerFailures.translate(e);
        }
    }
}
