package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.messaging.BrowseRequest;
import io.github.sudoitir.openbroker.messaging.BulkOutcome;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.MessageAction;
import io.github.sudoitir.openbroker.messaging.MessageField;
import io.github.sudoitir.openbroker.messaging.MessageFilter;
import io.github.sudoitir.openbroker.messaging.MessageHandler;
import io.github.sudoitir.openbroker.messaging.MessageRef;
import io.github.sudoitir.openbroker.messaging.OutboundMessage;
import io.github.sudoitir.openbroker.messaging.Position;
import io.github.sudoitir.openbroker.messaging.Processor;
import io.github.sudoitir.openbroker.messaging.ProcessorOptions;
import io.github.sudoitir.openbroker.messaging.Receiver;
import io.github.sudoitir.openbroker.messaging.ReceiverOptions;
import io.github.sudoitir.openbroker.messaging.Selection;
import io.github.sudoitir.openbroker.messaging.SendReceipt;
import io.github.sudoitir.openbroker.messaging.SendTarget;
import io.github.sudoitir.openbroker.messaging.SubscriptionCapabilities;
import io.github.sudoitir.openbroker.messaging.Total;
import io.github.sudoitir.openbroker.messaging.Transport;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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

    /** The most body bytes one page holds in all; a page that would hold more stops there and says so. */
    public static final long PAGE_BYTE_BUDGET = 8L * 1024 * 1024;

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
            Within<BrowsedMessage> kept = within(result.messages(), BrowsedMessages::of);
            return switch (result.total().count()) {
                case Count.Exact(var e) -> new BrowsePage(kept.items(), e, null, kept.truncated());
                case Count.Estimate(var e) -> new BrowsePage(kept.items(), e, null, kept.truncated());
                case Count.Unavailable(var u) -> new BrowsePage(kept.items(), null, u, kept.truncated());
            };
        });
    }

    /**
     * One page of a partition of a log, read from {@code at} without joining a group or committing anything. A
     * filter is refused with the driver's reason, because a log cannot be filtered where it is kept.
     */
    public LogPage browseLog(UUID clusterId, ResourceId log, int partition, Position at, int size, String filter) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            io.github.sudoitir.openbroker.messaging.BrowsePage result;
            try {
                result = c.messages()
                        .browse(
                                log,
                                Scope.shards(Set.of(Shard.partition(PartitionId.of(String.valueOf(partition))))),
                                new BrowseRequest(at, size, filter(c, filter), MAX_BODY_BYTES));
            } catch (BrokerException e) {
                if (ErrorReasons.NOT_SUPPORTED.equals(e.reason())) {
                    throw new IllegalArgumentException(e.getMessage(), e);
                }
                throw e;
            }
            Within<LogPage.LogRecord> kept = within(result.messages(), m -> {
                BrowsedMessages.Key key = BrowsedMessages.keyOf(m);
                return new LogPage.LogRecord(
                        BrowsedMessages.of(m),
                        m.body().isTombstone(),
                        key == null ? null : key.text(),
                        key == null ? null : key.encoding());
            });
            Long next = kept.truncated()
                    ? Long.valueOf(
                            result.messages().get(kept.items().size()).ref().position())
                    : result.next()
                            .map(cursor -> Long.parseLong(
                                    cursor.token().substring(cursor.token().indexOf(':') + 1)))
                            .orElse(null);
            return new LogPage(kept.items(), next, kept.truncated());
        });
    }

    /** What was kept of a page, and whether the budget stopped it short. */
    private record Within<R>(List<R> items, boolean truncated) {}

    /** Converts messages in order until their bodies add up to {@link #PAGE_BYTE_BUDGET}; the first is always kept. */
    private static <R> Within<R> within(List<Message> messages, Function<Message, R> convert) {
        List<R> out = new ArrayList<>();
        long bytes = 0;
        for (Message m : messages) {
            bytes += Math.min(m.body().size(), MAX_BODY_BYTES);
            if (bytes > PAGE_BYTE_BUDGET && !out.isEmpty()) {
                return new Within<>(List.copyOf(out), true);
            }
            out.add(convert.apply(m));
        }
        return new Within<>(List.copyOf(out), false);
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

    /**
     * Sends one message through a node to a destination, which routes it, or straight to a subscription;
     * returns its id, when the system gives one.
     *
     * @throws SendInDoubtException when the broker's confirmation never came back, so the message may or
     *     may not be there
     */
    public Optional<String> sendTo(UUID clusterId, UUID nodeId, ResourceRef target, OutboundMessage message) {
        return translated(() -> {
            ClusterConnection c = gateway.cluster(clusterId);
            SendReceipt receipt;
            try {
                receipt = c.messages().send(sendTarget(c, target), Scope.node(BrokerGateway.idOf(nodeId)), message);
            } catch (BrokerException e) {
                if (e.dispatch() == Dispatch.IN_DOUBT) {
                    throw new SendInDoubtException(
                            "The message was sent, but the broker's confirmation never came back: it may or may not"
                                    + " be on the broker. Look for it before sending it again, or it may be there twice.",
                            e);
                }
                throw e;
            }
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

    /**
     * Why the cluster's system offers no such action on messages, in its own words, or empty when it does or when it
     * cannot say. {@code capability} is the SDK's id, such as {@code messages.move}.
     */
    public Optional<String> unavailableBecause(UUID clusterId, String capability) {
        return translated(() -> {
            var assessment = gateway.cluster(clusterId)
                    .access()
                    .check(new io.github.sudoitir.openbroker.access.Capability(capability), Optional.empty());
            return assessment.status() == io.github.sudoitir.openbroker.access.Assessment.Status.UNAVAILABLE
                    ? Optional.of(assessment.reason())
                    : Optional.<String>empty();
        });
    }

    /** What purging a subscription of a log would skip, and the state it was counted against. */
    public record LogPurge(long records, String etag) {}

    /**
     * What purging the subscription of a log (moving its position to the end) would skip. The driver refuses a
     * group that has members or is rebalancing, naming them, and that refusal is a conflict with its reason.
     */
    public LogPurge previewLogPurge(UUID clusterId, ResourceId subscription) {
        try {
            var preview =
                    gateway.cluster(clusterId).operations().preview(subscription, new Operation.Purge(), Scope.whole());
            // The driver names a group's active members in the preview; moving a busy group's position is refused.
            for (var change : preview.diff()) {
                if (change.key().endsWith(".members") && change.before().isPresent()) {
                    throw new ConflictException(
                            "purge-refused",
                            "Cannot purge " + subscription + " while its group has active members: "
                                    + change.before().get() + ". Stop them first.");
                }
            }
            long records =
                    switch (preview.estimate().count()) {
                        case Count.Exact(var n) -> n;
                        case Count.Estimate(var n) -> n;
                        case Count.Unavailable(var _) -> 0L;
                    };
            return new LogPurge(records, preview.etag());
        } catch (BrokerException e) {
            throw refusedPurge(e);
        }
    }

    /** Moves the subscription's position to the end of the log, if its state is still the one counted. */
    public long purgeLog(UUID clusterId, ResourceId subscription, String etag) {
        try {
            return gateway.cluster(clusterId)
                    .operations()
                    .execute(subscription, new Operation.Purge(), Scope.whole(), Precondition.ifMatch(etag))
                    .orThrow()
                    .affected()
                    .orElse(0L);
        } catch (BrokerException e) {
            throw refusedPurge(e);
        }
    }

    private static RuntimeException refusedPurge(BrokerException e) {
        if (e.status() == Status.FAILED_PRECONDITION || ErrorReasons.PRECONDITION_FAILED.equals(e.reason())) {
            return new ConflictException("purge-refused", e.getMessage());
        }
        return BrokerFailures.translate(e);
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
     * A receiver on the queue on the node, for a drain that takes messages off it, named {@code owner}
     * in diagnostics. The caller owns it and closes it. Its failures, opening it included, are the
     * SDK's own {@link BrokerException}: a drain tells a lost connection from a queue that admits no
     * more consumers by their reason.
     */
    public Receiver receiver(UUID clusterId, UUID nodeId, String queue, ReceiverOptions options, String owner) {
        ClusterConnection c = gateway.cluster(clusterId);
        Receiver receiver = transport(c, "receive messages over")
                .receiver(queueId(c, queue), Scope.node(BrokerGateway.idOf(nodeId)), options);
        gateway.label(receiver.id(), owner);
        return receiver;
    }

    /**
     * Runs {@code handler} on the subscription's messages, in the options' lanes, until the returned
     * processor is closed; named {@code owner} in diagnostics. A {@code nodeId} reads that node's copy of the
     * subscription, and none reads it for the whole cluster (a partitioned log). Failures, opening included,
     * are the SDK's own {@link BrokerException}, as for {@link #receiver}.
     */
    public Processor process(
            UUID clusterId,
            UUID nodeId,
            String kind,
            String path,
            ProcessorOptions options,
            MessageHandler handler,
            String owner) {
        ClusterConnection c = gateway.cluster(clusterId);
        Scope scope = nodeId == null ? Scope.whole() : Scope.node(BrokerGateway.idOf(nodeId));
        Processor processor =
                transport(c, "receive messages over").process(ResourceIds.parse(kind, path), scope, options, handler);
        gateway.label(processor.id(), owner);
        return processor;
    }

    /** What the cluster's system can do with a subscription of this kind: its orderings, retry and release counting. */
    public SubscriptionCapabilities subscriptionCapabilities(UUID clusterId, String kind) {
        return transport(gateway.cluster(clusterId), "receive messages over")
                .capabilities()
                .subscription(KindId.of(kind));
    }

    private static Transport transport(ClusterConnection c, String purpose) {
        return c.transport()
                .orElseThrow(() -> new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE,
                        "This cluster has no data-plane connection to " + purpose + "."));
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

    private static SendTarget sendTarget(ClusterConnection c, ResourceRef target) {
        return switch (target.type()) {
            case DESTINATION -> SendTarget.destination(ResourceIds.of(c, ResourceClass.DESTINATION, target.name()));
            case SUBSCRIPTION -> SendTarget.subscription(ResourceIds.of(c, ResourceClass.SUBSCRIPTION, target.name()));
        };
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
