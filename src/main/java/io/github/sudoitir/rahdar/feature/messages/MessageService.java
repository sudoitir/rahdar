package io.github.sudoitir.rahdar.feature.messages;

import io.github.sudoitir.openbroker.messaging.BulkOutcome;
import io.github.sudoitir.openbroker.messaging.MessageAction.Delete;
import io.github.sudoitir.openbroker.messaging.MessageAction.Move;
import io.github.sudoitir.openbroker.messaging.MessageAction.Retry;
import io.github.sudoitir.openbroker.messaging.MessageField;
import io.github.sudoitir.openbroker.messaging.MessageFilter;
import io.github.sudoitir.openbroker.messaging.Position;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.LogPurgeParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageDeleteParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageExpireParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageMoveParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.MessageRetryParams;
import io.github.sudoitir.rahdar.feature.messages.MessageActionParams.QueuePurgeParams;
import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.MessageActionRequest;
import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.SendMessageRequest;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.LogView;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.MessageDetailView;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.MessagePageView;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.MessageSummaryView;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard.Requirement;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BacklogBreakdown;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.BrowsePage;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.broker.BulkResult;
import io.github.sudoitir.rahdar.platform.broker.LogBounds;
import io.github.sudoitir.rahdar.platform.broker.LogPage;
import io.github.sudoitir.rahdar.platform.broker.MessageExpiry;
import io.github.sudoitir.rahdar.platform.broker.MessageKind;
import io.github.sudoitir.rahdar.platform.broker.SendInDoubtException;
import io.github.sudoitir.rahdar.platform.broker.SendNames;
import io.github.sudoitir.rahdar.platform.broker.SentMessages;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.governance.ClearViewAudit;
import io.github.sudoitir.rahdar.platform.governance.ContentPolicy;
import io.github.sudoitir.rahdar.platform.governance.GovernContext;
import io.github.sudoitir.rahdar.platform.governance.GovernanceViews;
import io.github.sudoitir.rahdar.platform.governance.GovernedMessage;
import io.github.sudoitir.rahdar.platform.governance.MessageContent;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator.QueueLocation;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Message browse and the destructive message operations for one queue on one
 * node (ADR-0021, ADR-0022). {@code address} / {@code routingType} come from the
 * cached {@code subscription_snapshot} row, or a live read for a queue the scrape has not reached,
 * never the client. Every broker request waits for
 * the node's rate ceiling in the transport itself (non-negotiable #1, ADR-0076); every mutation writes
 * an {@code audit_event} in its own transaction, before the broker call, updated
 * with the outcome (non-negotiable #3); a dry run is a broker-side estimate,
 * still audited with {@code dry_run = true}. A successful mutation nudges the SSE
 * {@code queues} topic after commit so the grid refreshes at once.
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    static final int BROKER_PAGE_CAP = BrokerMessages.PAGE_CAP;

    private static final String CORRELATION_ID = "correlationId";
    private static final String GROUP_ID = "groupId";

    private final QueueLocator queueLocator;
    private final ClusterDirectory brokerNodes;
    private final BrokerMessages messages;
    private final MessageExpiry expiry;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final SettingsService settings;
    private final SseHub sseHub;
    private final ClusterAccessGuard clusterAccess;
    private final ContentPolicy contentPolicy;
    private final ClearViewAudit clearViews;
    private final OperationGate gate;
    private final SystemExtensions extensions;

    /** Resolved (node, address, routingType) for a queue name on a cluster. */
    record ResolvedQueue(ClusterNode node, String address, String routingType) {}

    /** What an operation would touch: how many messages, and the queue's identity for the gate's state key. */
    public record Reach(long count, String identity) {}

    /** A mutation result: an executed affected-count, or a point-in-time dry-run estimate. */
    public sealed interface Outcome {
        UUID node();

        record Affected(long count, UUID node) implements Outcome {}

        record DryRun(long count, long cap, boolean overCap, UUID node) implements Outcome {}

        /**
         * A purge of a partitioned log moves a group's position and deletes nothing: {@code skipped} records are passed
         * over, none is destroyed, so the bulk cap, which counts destroyed messages, does not apply to it.
         */
        record LogPurged(long skipped, UUID node) implements Outcome {}

        /** What a purge of a partitioned log would skip, point in time: it destroys nothing and is not capped. */
        record LogPurgeDryRun(long skipped, long cap, UUID node) implements Outcome {}

        /**
         * An operation by ids that stopped part-way: {@code count} were acted on, and
         * {@code notDone} holds every id that was not — refused, or never sent after {@code error}.
         */
        record Partial(long count, List<Long> notDone, String error, UUID node) implements Outcome {}

        /**
         * A send whose confirmation never came back: the message may or may not be on the broker, so it
         * is neither a success nor a failure, and sending it again may duplicate it (ADR-0211).
         */
        record InDoubt(String detail, UUID node) implements Outcome {}
    }

    // ---- browse -----------------------------------------------------------

    public MessagePageView browse(
            UUID clusterId, String queueName, UUID nodeId, String filter, int page, int requestedSize) {
        clusterAccess.requireResource(clusterId, ResourceRef.subscription(queueName), MessagePermissions.MESSAGE_READ);
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        // The broker serves at most BROKER_PAGE_CAP rows; the page size reported and used for hasNext is that one.
        int size = Math.min(requestedSize, BROKER_PAGE_CAP);
        BrowsePage result = messages.browse(clusterId, resolved.node().getId(), queueName, page, size, filter);
        GovernContext context = contentPolicy.context(clusterId, resolved.address(), queueName);
        List<BrowsedMessage> browsed = result.messages();
        List<GovernedMessage> governed = browsed.stream()
                .map(m -> contentPolicy.govern(context, content(m)))
                .toList();
        List<MessageSummaryView> rows = new ArrayList<>(browsed.size());
        for (int i = 0; i < browsed.size(); i++) {
            rows.add(toSummary(browsed.get(i), governed.get(i), false, null, null));
        }
        clearViews.recordClear(context, "SUBSCRIPTION", queueName, governed);
        Long total = result.total();
        // ponytail: with no broker count, a full page is taken to have a successor; the last page can be a false yes
        boolean hasNext = total != null ? (long) page * size < total : rows.size() == size;
        return new MessagePageView(
                rows,
                page,
                size,
                total,
                hasNext,
                result.totalUnavailable(),
                resolved.node().getId(),
                null,
                result.truncated(),
                result.truncated()
                        ? "The page was cut short at its " + (BrokerMessages.PAGE_BYTE_BUDGET >> 20)
                                + " MB budget: ask for a smaller page to see every message."
                        : null);
    }

    // ---- browse a partition of a log --------------------------------------

    /** Where a read of a partition of a log starts. */
    public record LogStart(Kind kind, long offset, Instant time) {

        public enum Kind {
            EARLIEST,
            LATEST,
            OFFSET,
            TIME
        }

        /** {@code from} is earliest, latest, offset or time; the last two take a {@code value}. */
        public static LogStart parse(String from, String value) {
            String kind = from == null || from.isBlank() ? "earliest" : from;
            try {
                return switch (kind) {
                    case "earliest" -> new LogStart(Kind.EARLIEST, 0, null);
                    case "latest" -> new LogStart(Kind.LATEST, 0, null);
                    case "offset" -> new LogStart(Kind.OFFSET, Long.parseLong(value), null);
                    case "time" -> new LogStart(Kind.TIME, 0, Instant.parse(value));
                    default -> throw new IllegalArgumentException("from must be earliest, latest, offset or time.");
                };
            } catch (NumberFormatException | NullPointerException | DateTimeParseException _) {
                throw new IllegalArgumentException("from=" + kind + " needs a value that is "
                        + (kind.equals("offset") ? "an offset." : "an ISO-8601 time."));
            }
        }
    }

    /**
     * One page of a partition of a log (ADR-0216), read without joining a group. The request is placed against the
     * partition's start and end first: before the start it is read from the start and says retention moved it, and
     * past the end it is an empty page that states the end. {@code latest} is the last page. A filter is refused
     * with the driver's reason.
     */
    public MessagePageView browseLog(
            UUID clusterId, ResourceId log, int partition, LogStart from, String filter, int requestedSize) {
        return browseLog(clusterId, log, null, partition, from, filter, requestedSize);
    }

    /**
     * The same, through a group's subscription of the log: its records are those of the topic the subscription
     * reads, and the page says where the group stands on the partition.
     */
    public MessagePageView browseSubscriptionLog(
            UUID clusterId, ResourceId subscription, int partition, LogStart from, String filter, int requestedSize) {
        BacklogBreakdown.Breakdown breakdown = subscriptionLog(clusterId, subscription);
        return browseLog(clusterId, breakdown.destination(), breakdown, partition, from, filter, requestedSize);
    }

    /** One record of the log a group's subscription reads. */
    public MessageDetailView detailSubscriptionLog(
            UUID clusterId, ResourceId subscription, int partition, long offset) {
        return detailLog(clusterId, subscriptionLog(clusterId, subscription).destination(), partition, offset);
    }

    /** The subscription's breakdown, which names the destination whose log it reads; the caller may read both. */
    private BacklogBreakdown.Breakdown subscriptionLog(UUID clusterId, ResourceId subscription) {
        clusterAccess.requireResource(
                clusterId,
                ResourceRef.subscription(subscription.path().segments().getFirst()),
                MessagePermissions.MESSAGE_READ);
        BacklogBreakdown.Breakdown breakdown =
                extensions.require(clusterId, BacklogBreakdown.class).read(clusterId, subscription);
        if (breakdown.destination() == null) {
            throw new NotFoundException("destination of subscription", subscription.format());
        }
        return breakdown;
    }

    private MessagePageView browseLog(
            UUID clusterId,
            ResourceId log,
            BacklogBreakdown.Breakdown through,
            int partition,
            LogStart from,
            String filter,
            int requestedSize) {
        clusterAccess.requireResource(clusterId, ResourceRef.destination(log.name()), MessagePermissions.MESSAGE_READ);
        LogBounds.Bounds bounds = extensions.require(clusterId, LogBounds.class).of(clusterId, log, partition);
        int size = Math.min(requestedSize, BROKER_PAGE_CAP);
        Shard shard = Shard.partition(PartitionId.of(String.valueOf(partition)));
        boolean moved = false;
        long offset = bounds.start();
        switch (from.kind()) {
            case LATEST -> offset = Math.max(bounds.start(), bounds.end() - size);
            case OFFSET -> {
                moved = from.offset() < bounds.start();
                offset = Math.clamp(from.offset(), bounds.start(), bounds.end());
            }
            case EARLIEST, TIME -> {
                // Earliest stays at the partition start. A time is placed by Position.AtTime below.
            }
        }
        if (from.kind() == LogStart.Kind.TIME && from.time().isAfter(Instant.now())) {
            throw new IllegalArgumentException("The start time " + from.time()
                    + " is in the future, so no record can be at or after it. Choose a time that has passed, or"
                    + " read from latest.");
        }
        Position at = from.kind() == LogStart.Kind.TIME
                ? new Position.AtTime(from.time())
                : new Position.AtOffsets(Map.of(shard, offset));
        LogPage page = from.kind() != LogStart.Kind.TIME && offset >= bounds.end()
                ? new LogPage(List.of(), null)
                : messages.browseLog(clusterId, log, partition, at, size, filter);
        GovernContext context = contentPolicy.context(clusterId, log.name(), log.name());
        List<GovernedMessage> governed = page.records().stream()
                .map(r -> contentPolicy.govern(context, content(r.message())))
                .toList();
        List<MessageSummaryView> rows = new ArrayList<>(governed.size());
        for (int i = 0; i < governed.size(); i++) {
            LogPage.LogRecord record = page.records().get(i);
            rows.add(toSummary(
                    record.message(), governed.get(i), record.tombstone(), record.key(), record.keyEncoding()));
        }
        clearViews.recordClear(context, "DESTINATION", log.name(), governed);
        String notice =
                from.kind() == LogStart.Kind.TIME ? timeNotice(clusterId, log, partition, from, bounds, page) : null;
        return new MessagePageView(
                rows,
                1,
                size,
                null,
                page.next() != null,
                "A log is read by offset: its size is its end offset less its start, and compaction leaves gaps.",
                null,
                new LogView(
                        partition,
                        bounds.start(),
                        bounds.end(),
                        page.next(),
                        moved,
                        true,
                        through == null
                                ? null
                                : through.partitions().stream()
                                        .filter(p -> p.partition() == partition)
                                        .map(BacklogBreakdown.PartitionLag::committed)
                                        .filter(Objects::nonNull)
                                        .findFirst()
                                        .orElse(null)),
                page.truncated(),
                notice);
    }

    /**
     * Why a read from a time found nothing, or what it could not match: an empty page says where the partition's
     * records end in time, and records with no timestamp are named as unmatchable by time.
     */
    private String timeNotice(
            UUID clusterId, ResourceId log, int partition, LogStart from, LogBounds.Bounds bounds, LogPage page) {
        if (!page.records().isEmpty()) {
            return page.records().stream().anyMatch(r -> r.message().timestamp() <= 0)
                    ? "Some records carry no timestamp, so a start time cannot place them."
                    : null;
        }
        if (bounds.end() <= bounds.start()) {
            return "The partition holds no records.";
        }
        LogPage last = messages.browseLog(
                clusterId,
                log,
                partition,
                new Position.AtOffsets(
                        Map.of(Shard.partition(PartitionId.of(String.valueOf(partition))), bounds.end() - 1)),
                1,
                null);
        long at = last.records().isEmpty()
                ? 0
                : last.records().getFirst().message().timestamp();
        return "No record at or after " + from.time() + "; the partition's last record "
                + (at > 0
                        ? "is at " + Instant.ofEpochMilli(at) + "."
                        : "carries no timestamp, so it cannot be placed by time.");
    }

    /** One record of a partition of a log, expanded. */
    public MessageDetailView detailLog(UUID clusterId, ResourceId log, int partition, long offset) {
        clusterAccess.requireResource(clusterId, ResourceRef.destination(log.name()), MessagePermissions.MESSAGE_READ);
        LogPage page = messages.browseLog(
                clusterId,
                log,
                partition,
                new Position.AtOffsets(Map.of(Shard.partition(PartitionId.of(String.valueOf(partition))), offset)),
                1,
                null);
        LogPage.LogRecord record = page.records().stream()
                .filter(r -> r.message().messageId() == offset)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("record", offset));
        GovernContext context = contentPolicy.context(clusterId, log.name(), log.name());
        GovernedMessage governed = contentPolicy.govern(context, content(record.message()));
        clearViews.recordClear(context, "MESSAGE", log.name() + "/" + partition + "/" + offset, List.of(governed));
        return toDetail(record.message(), governed, null, record.tombstone(), record.key(), record.keyEncoding());
    }

    /** An action request that moves into the queue of this name, for a caller that holds names rather than ids. */
    public MessageActionRequest actionRequest(UUID clusterId, List<Long> ids, String filter, String targetQueue) {
        return new MessageActionRequest(
                ids.stream().map(String::valueOf).toList(),
                filter,
                targetQueue == null
                        ? null
                        : messages.queueId(clusterId, targetQueue).format());
    }

    public MessageDetailView detail(UUID clusterId, String queueName, long messageId, UUID nodeId, String filter) {
        clusterAccess.requireResource(clusterId, ResourceRef.subscription(queueName), MessagePermissions.MESSAGE_READ);
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        BrowsePage result = messages.browse(clusterId, resolved.node().getId(), queueName, 1, BROKER_PAGE_CAP, filter);
        BrowsedMessage message = result.messages().stream()
                .filter(m -> m.messageId() == messageId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("message", messageId));
        GovernContext context = contentPolicy.context(clusterId, resolved.address(), queueName);
        GovernedMessage governed = contentPolicy.govern(context, content(message));
        clearViews.recordClear(context, "MESSAGE", queueName + "/" + messageId, List.of(governed));
        return toDetail(message, governed, resolved.node().getId());
    }

    /**
     * Refuses a per-message action the cluster's system does not offer, with the system's own reason: a
     * partitioned log is append-only, so a single record is neither moved nor deleted (409).
     */
    public void requireOffered(UUID clusterId, MessageAction action) {
        String capability =
                switch (action) {
                    case MOVE, RETRY -> "messages.move";
                    case DELETE, EXPIRE -> "messages.delete";
                };
        messages.unavailableBecause(clusterId, capability).ifPresent(reason -> {
            throw new ConflictException("action-unavailable", reason);
        });
    }

    // ---- send (Slice 4) -------------------------------------------------

    public Attempt<Outcome> send(
            UUID clusterId, String queueName, UUID nodeId, SendMessageRequest req, boolean dryRun) {
        // A message is sent to the address the queue is bound to; a queue whose address the caller may not
        // use is, to them, a queue that is not there.
        clusterAccess.requireResource(clusterId, ResourceRef.subscription(queueName), Permissions.SUBSCRIPTION_READ);
        SendNames.validate(req.headers(), req.properties());
        requireDecodableBody(req);
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        try {
            clusterAccess.requireResource(
                    clusterId, ResourceRef.destination(resolved.address()), MessagePermissions.MESSAGE_SEND);
        } catch (NotFoundException _) {
            throw new NotFoundException("queue", queueName);
        }
        AuditEvent event = begin(
                "SEND_MESSAGE",
                queueName,
                clusterId,
                resolved.node().getId(),
                Map.of("kind", req.kind().name()),
                dryRun);
        if (dryRun) {
            audit.succeed(event, 1);
            return new Attempt.Ok<>(new Outcome.DryRun(
                    1,
                    settings.intValue(BrokerSettings.BULK_CAP),
                    false,
                    resolved.node().getId()));
        }
        try {
            messages.sendTo(
                    clusterId,
                    resolved.node().getId(),
                    ResourceRef.destination(resolved.address()),
                    SentMessages.of(req.kind(), req.durable(), req.body(), req.headers(), req.properties()));
            audit.succeed(event, 1);
            publishQueuesAfterCommit(clusterId, queueName);
            return new Attempt.Ok<>(new Outcome.Affected(1, resolved.node().getId()));
        } catch (SendInDoubtException e) {
            audit.inDoubt(event, e.getMessage());
            publishQueuesAfterCommit(clusterId, queueName);
            return new Attempt.Ok<>(
                    new Outcome.InDoubt(e.getMessage(), resolved.node().getId()));
        } catch (BrokerConnectionException e) {
            audit.fail(event, e.getMessage());
            return new Attempt.Failed<>(e.kind(), e.getMessage());
        }
    }

    private static void requireDecodableBody(SendMessageRequest req) {
        if (req.kind() != MessageKind.BYTES) {
            return;
        }
        try {
            Base64.getDecoder().decode(req.body());
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException("The body of a BYTES message must be valid base64.");
        }
    }

    // ---- move / retry / delete / expire (Slices 5 + 6) ----------------

    /**
     * Runs a message action. A dry run is never gated; a real one passes the approval gate as the {@code
     * message.<action>} operation (ADR-0179).
     */
    public Attempt<Outcome> execute(
            UUID clusterId,
            String queueName,
            UUID nodeId,
            MessageAction action,
            MessageActionRequest req,
            boolean dryRun,
            boolean override) {
        // Every caller, the screens, the tool and a replay alike, is refused a per-record action on a log before any
        // broker call.
        requireOffered(clusterId, action);
        requireAllowed(clusterId, queueName, action, req);
        if (dryRun) {
            return run(clusterId, queueName, nodeId, action, req, true, override);
        }
        return switch (action) {
            case MOVE -> move(new MessageMoveParams(clusterId, queueName, nodeId, req, override));
            case RETRY -> retry(new MessageRetryParams(clusterId, queueName, nodeId, req, override));
            case DELETE -> delete(new MessageDeleteParams(clusterId, queueName, nodeId, req, override));
            case EXPIRE -> expire(new MessageExpireParams(clusterId, queueName, nodeId, req, override));
        };
    }

    @Gated("message.move")
    private Attempt<Outcome> move(MessageMoveParams p) {
        return gate.run(Operation.of(p), () -> runOf(p, MessageAction.MOVE));
    }

    @Gated("message.retry")
    private Attempt<Outcome> retry(MessageRetryParams p) {
        return gate.run(Operation.of(p), () -> runOf(p, MessageAction.RETRY));
    }

    @Gated("message.delete")
    private Attempt<Outcome> delete(MessageDeleteParams p) {
        return gate.run(Operation.of(p), () -> runOf(p, MessageAction.DELETE));
    }

    @Gated("message.expire")
    private Attempt<Outcome> expire(MessageExpireParams p) {
        return gate.run(Operation.of(p), () -> runOf(p, MessageAction.EXPIRE));
    }

    private Attempt<Outcome> runOf(MessageActionParams p, MessageAction action) {
        return run(p.clusterId(), p.queue(), p.nodeId(), action, p.request(), false, p.override());
    }

    /**
     * What an action would touch now, read without an audit row or a broker change: the gate estimates with it on
     * every request and again before it runs.
     */
    public Reach reach(UUID clusterId, String queueName, UUID nodeId, MessageAction action, MessageActionRequest req) {
        requireAllowed(clusterId, queueName, action, req);
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        if (!req.byFilter() && !req.ids().isEmpty()) {
            return new Reach(req.ids().size(), identityOf(clusterId, resolved, queueName));
        }
        return new Reach(
                estimate(clusterId, resolved, queueName, action, req), identityOf(clusterId, resolved, queueName));
    }

    /** What a purge would remove now, read as {@link #reach} reads an action. */
    public Reach reachOfPurge(UUID clusterId, String queueName, UUID nodeId) {
        clusterAccess.requireResource(
                clusterId, ResourceRef.subscription(queueName), MessagePermissions.SUBSCRIPTION_PURGE);
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        return new Reach(
                messages.count(clusterId, resolved.node().getId(), queueName, null),
                identityOf(clusterId, resolved, queueName));
    }

    /**
     * The queue as the gate pins it: which incarnation of it is on the node, so a queue deleted and created
     * again since the request differs, and a backlog that grew does not.
     */
    private String identityOf(UUID clusterId, ResolvedQueue resolved, String queueName) {
        return messages.identity(clusterId, resolved.node().getId(), queueName);
    }

    private Attempt<Outcome> run(
            UUID clusterId,
            String queueName,
            UUID nodeId,
            MessageAction action,
            MessageActionRequest req,
            boolean dryRun,
            boolean override) {
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        UUID node = resolved.node().getId();
        RetryScope scope = RetryScope.UNRESTRICTED;
        if (action == MessageAction.RETRY) {
            try {
                scope = requireOriginalAddresses(clusterId, queueName, resolved);
            } catch (BrokerConnectionException e) {
                return new Attempt.Failed<>(e.kind(), e.getMessage());
            }
        }

        AuditEvent event = begin(action.auditName(), queueName, clusterId, node, auditParams(req), dryRun);

        requireMoveTarget(event, action, req);
        // Artemis has no by-filter retry — RETRY is by explicit id, or "retry all"
        // (the DLQ replay). A filter on a RETRY is ignored, not an error.
        boolean retryAll = action == MessageAction.RETRY && req.ids().isEmpty();

        long cap = settings.intValue(BrokerSettings.BULK_CAP);

        // A by-id dry run needs no broker call at all — the estimate is the id count.
        boolean idBased = !retryAll && !req.byFilter() && !req.ids().isEmpty();
        if (dryRun && idBased) {
            long estimate = req.ids().size();
            audit.succeed(event, estimate);
            return new Attempt.Ok<>(new Outcome.DryRun(estimate, cap, estimate > cap, node));
        }

        try {
            long estimate = estimate(clusterId, resolved, queueName, action, req);
            boolean over = estimate > cap;
            if (dryRun) {
                audit.succeed(event, estimate);
                return new Attempt.Ok<>(new Outcome.DryRun(estimate, cap, over, node));
            }
            if (over && !override) {
                audit.fail(event, "Over the safety cap (" + estimate + " > " + cap + ").");
                throw new BulkCapExceededException(estimate, cap);
            }

            QueueAction call = new QueueAction(clusterId, resolved, queueName, action, req, scope);
            if (idBased) {
                return executeByIds(event, call, clusterId, queueName, node);
            }
            long affected = perform(call);
            audit.succeed(event, affected);
            publishQueuesAfterCommit(clusterId, queueName);
            return new Attempt.Ok<>(new Outcome.Affected(affected, node));
        } catch (BrokerConnectionException e) {
            audit.fail(event, e.getMessage());
            return new Attempt.Failed<>(e.kind(), e.getMessage());
        } catch (IllegalArgumentException e) {
            // A filter the broker rejected. The row is closed as failed rather than left pending.
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    private void requireMoveTarget(AuditEvent event, MessageAction action, MessageActionRequest req) {
        if (action == MessageAction.MOVE
                && (req.targetQueue() == null || req.targetQueue().isBlank())) {
            audit.fail(event, "MOVE requires a target queue.");
            throw new IllegalArgumentException("MOVE requires a target queue.");
        }
    }

    private static Map<String, Object> auditParams(MessageActionRequest req) {
        Map<String, Object> params = new HashMap<>();
        if (req.byFilter()) {
            params.put("filter", req.filter());
        } else {
            params.put("ids", req.ids().size());
        }
        if (req.targetQueue() != null) {
            params.put("target", req.targetQueue());
        }
        return params;
    }

    private Attempt<Outcome> executeByIds(
            AuditEvent event, QueueAction call, UUID clusterId, String queueName, UUID node) {
        BulkResult result = performByIds(call);
        MessageActionRequest req = call.req();
        publishQueuesAfterCommit(clusterId, queueName);
        if (result.partial()) {
            // Reported as partial, never as a plain failure: some messages already moved.
            audit.failPartial(
                    event,
                    result.affected(),
                    "Stopped after " + result.affected() + " of " + req.ids().size() + ": " + result.error());
            return new Attempt.Ok<>(new Outcome.Partial(result.affected(), result.notDone(), result.error(), node));
        }
        audit.succeed(event, result.affected());
        return new Attempt.Ok<>(new Outcome.Affected(result.affected(), node));
    }

    // ---- purge (Slice 7) ---------------------------------------------

    /** Purges a queue. A dry run is never gated; a real one passes the approval gate as {@code subscription.purge}. */
    public Attempt<Outcome> purge(UUID clusterId, String queueName, UUID nodeId, boolean dryRun, boolean override) {
        clusterAccess.requireResource(
                clusterId, ResourceRef.subscription(queueName), MessagePermissions.SUBSCRIPTION_PURGE);
        if (dryRun) {
            return runPurge(clusterId, queueName, nodeId, true, override);
        }
        return gatedPurge(new QueuePurgeParams(clusterId, queueName, nodeId, override));
    }

    /**
     * Purges the subscription of a partitioned log: its position moves to the end, which the driver refuses for a
     * group that has members or is rebalancing, naming them (409). The records are not deleted.
     */
    public Attempt<Outcome> purgeLog(UUID clusterId, ResourceId subscription, boolean dryRun) {
        clusterAccess.requireResource(
                clusterId,
                ResourceRef.subscription(subscription.path().segments().getFirst()),
                MessagePermissions.SUBSCRIPTION_PURGE);
        if (dryRun) {
            return runPurgeLog(clusterId, subscription, true);
        }
        return gatedPurgeLog(new LogPurgeParams(clusterId, subscription.format()));
    }

    @Gated("subscription.purge-log")
    private Attempt<Outcome> gatedPurgeLog(LogPurgeParams p) {
        return gate.run(Operation.of(p), () -> runPurgeLog(p.clusterId(), ResourceId.parse(p.subscription()), false));
    }

    /** What purging the subscription would skip and the state it was counted against, for the gate's estimate. */
    public Reach reachOfLogPurge(LogPurgeParams p) {
        BrokerMessages.LogPurge preview = messages.previewLogPurge(p.clusterId(), ResourceId.parse(p.subscription()));
        return new Reach(preview.records(), preview.etag());
    }

    private Attempt<Outcome> runPurgeLog(UUID clusterId, ResourceId subscription, boolean dryRun) {
        AuditEvent event = begin("PURGE_SUBSCRIPTION", subscription.format(), clusterId, null, Map.of(), dryRun);
        try {
            BrokerMessages.LogPurge preview = messages.previewLogPurge(clusterId, subscription);
            if (dryRun) {
                audit.succeed(event, preview.records());
                return new Attempt.Ok<>(new Outcome.LogPurgeDryRun(
                        preview.records(), settings.intValue(BrokerSettings.BULK_CAP), null));
            }
            // The driver counts the partitions it moved; the operator is told the records they skip.
            messages.purgeLog(clusterId, subscription, preview.etag());
            audit.succeed(event, preview.records());
            return new Attempt.Ok<>(new Outcome.LogPurged(preview.records(), null));
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    @Gated("subscription.purge")
    private Attempt<Outcome> gatedPurge(QueuePurgeParams p) {
        return gate.run(Operation.of(p), () -> runPurge(p.clusterId(), p.queue(), p.nodeId(), false, p.override()));
    }

    private Attempt<Outcome> runPurge(UUID clusterId, String queueName, UUID nodeId, boolean dryRun, boolean override) {
        ResolvedQueue resolved = resolve(clusterId, queueName, nodeId);
        UUID node = resolved.node().getId();
        AuditEvent event = begin("PURGE_QUEUE", queueName, clusterId, node, Map.of(), dryRun);
        try {
            long cap = settings.intValue(BrokerSettings.BULK_CAP);

            if (dryRun) {
                long estimate = messages.count(clusterId, node, queueName, null);
                audit.succeed(event, estimate);
                return new Attempt.Ok<>(new Outcome.DryRun(estimate, cap, estimate > cap, node));
            }

            long estimate = messages.count(clusterId, node, queueName, null);
            if (estimate > cap && !override) {
                audit.fail(event, "Over the safety cap (" + estimate + " > " + cap + ").");
                throw new BulkCapExceededException(estimate, cap);
            }
            long removed = messages.purge(clusterId, node, queueName);
            audit.succeed(event, removed);
            publishQueuesAfterCommit(clusterId, queueName);
            return new Attempt.Ok<>(new Outcome.Affected(removed, node));
        } catch (BrokerConnectionException e) {
            audit.fail(event, e.getMessage());
            return new Attempt.Failed<>(e.kind(), e.getMessage());
        }
    }

    /**
     * What the action needs, on every resource it touches, checked before anything is done: the action's
     * permission on the queue, and for a move also {@code message:send} on the address of the queue the
     * messages go to.
     */
    private void requireAllowed(UUID clusterId, String queueName, MessageAction action, MessageActionRequest req) {
        List<Requirement> needs = new ArrayList<>();
        needs.add(new Requirement(ResourceRef.subscription(queueName), permissionFor(action)));
        if (action == MessageAction.MOVE
                && req.targetQueue() != null
                && !req.targetQueue().isBlank()) {
            // Messages go into the target queue, so it is read and sent to through the address it is bound to.
            needs.add(new Requirement(ResourceRef.subscription(req.targetQueue()), Permissions.SUBSCRIPTION_READ));
            needs.add(new Requirement(
                    ResourceRef.destination(addressOf(clusterId, req.targetQueue())), MessagePermissions.MESSAGE_SEND));
        }
        clusterAccess.requireAll(clusterId, needs);
    }

    /**
     * The address the queue is bound to, found on the scrape or the live nodes. A queue no node has is refused
     * as one the caller may not read is, never guessed from its name.
     */
    private String addressOf(UUID clusterId, String queueName) {
        clusterAccess.requireVisible(clusterId);
        return queueLocator.locate(clusterId, queueName).stream()
                .findFirst()
                .map(QueueLocation::address)
                .orElseThrow(() -> clusterAccess.unreadableAmongSeveral(clusterId));
    }

    /**
     * A retry sends each message back to the address it came from, so the caller needs
     * {@code message:send} on every address the queue's messages came from, unless they hold it on the
     * whole cluster. It is the queue's whole content that is checked, not only the messages chosen. The
     * addresses checked are returned, for the retry to keep to; a caller who holds it cluster-wide is
     * {@link RetryScope#UNRESTRICTED}.
     */
    private RetryScope requireOriginalAddresses(UUID clusterId, String queueName, ResolvedQueue resolved) {
        if (clusterAccess.holds(clusterId, MessagePermissions.MESSAGE_SEND)) {
            return RetryScope.UNRESTRICTED;
        }
        Set<String> origins = new TreeSet<>(
                messages.countBy(clusterId, resolved.node().getId(), queueName, MessageField.header("origin"))
                        .keySet());
        origins.removeIf(o -> o.isBlank() || "null".equals(o));
        clusterAccess.requireAll(
                clusterId,
                origins.stream()
                        .map(a -> new Requirement(ResourceRef.destination(a), MessagePermissions.MESSAGE_SEND))
                        .toList());
        return new RetryScope(true, origins);
    }

    private static String permissionFor(MessageAction action) {
        return switch (action) {
            case MOVE, RETRY -> MessagePermissions.MESSAGE_MOVE;
            case DELETE, EXPIRE -> MessagePermissions.MESSAGE_DELETE;
        };
    }

    // ---- estimate / perform dispatch --------------------------------

    private long estimate(
            UUID clusterId, ResolvedQueue resolved, String queueName, MessageAction action, MessageActionRequest req) {
        UUID node = resolved.node().getId();
        if (action == MessageAction.RETRY && req.ids().isEmpty()) {
            return messages.count(clusterId, node, queueName, null);
        }
        if (req.byFilter()) {
            return messages.count(clusterId, node, queueName, req.filter());
        }
        return req.ids().size();
    }

    /** A retry-all or a by-filter operation, which the broker counts itself. */
    private long perform(QueueAction call) {
        MessageActionRequest req = call.req();
        UUID node = call.resolved().node().getId();
        if (call.action() == MessageAction.RETRY && req.ids().isEmpty()) {
            return call.scope().restricted()
                    ? retryFromCheckedOrigins(call)
                    : messages.byFilter(call.clusterId(), node, call.queue(), null, Optional.empty(), new Retry())
                            .done();
        }
        return switch (call.action()) {
            case MOVE ->
                messages.byFilter(
                                call.clusterId(),
                                node,
                                call.queue(),
                                req.filter(),
                                Optional.empty(),
                                new Move(messages.queueId(call.clusterId(), req.targetQueue())))
                        .done();
            case DELETE ->
                messages.byFilter(call.clusterId(), node, call.queue(), req.filter(), Optional.empty(), new Delete())
                        .done();
            case EXPIRE -> expiry.byFilter(call.clusterId(), node, call.queue(), req.filter());
            case RETRY -> throw new IllegalStateException("unreachable");
        };
    }

    /**
     * A retry sends each message back to the address it came from, which the caller was checked against
     * a moment ago. Retrying the whole queue would also retry what arrived since, so the messages to
     * retry are the ones from the checked addresses: a message from another address is not among them.
     */
    private long retryFromCheckedOrigins(QueueAction call) {
        long retried = 0;
        for (String origin : call.scope().addresses()) {
            retried += messages.byFilter(
                            call.clusterId(),
                            call.resolved().node().getId(),
                            call.queue(),
                            originFilter(origin),
                            new Retry())
                    .done();
        }
        return retried;
    }

    /**
     * The chosen ids that came from the addresses the caller was checked against: a message chosen by id
     * that arrived from another address since the check is left out, as a retry of everything leaves it.
     */
    private List<Long> fromCheckedOrigins(QueueAction call, List<Long> chosen) {
        Set<Long> allowed = new HashSet<>();
        for (String origin : call.scope().addresses()) {
            allowed.addAll(
                    messages.ids(call.clusterId(), call.resolved().node().getId(), call.queue(), originFilter(origin)));
        }
        return chosen.stream().filter(allowed::contains).toList();
    }

    private static MessageFilter originFilter(String origin) {
        return MessageFilter.portable("header.origin = '" + origin.replace("'", "''") + "'");
    }

    private BulkResult performByIds(QueueAction call) {
        MessageActionRequest req = call.req();
        UUID node = call.resolved().node().getId();
        List<Long> ids = call.action() == MessageAction.RETRY && call.scope().restricted()
                ? fromCheckedOrigins(call, req.ids())
                : req.ids();
        if (call.action() == MessageAction.EXPIRE) {
            return expiry.byIds(call.clusterId(), node, call.queue(), ids);
        }
        io.github.sudoitir.openbroker.messaging.MessageAction act =
                switch (call.action()) {
                    case MOVE -> new Move(messages.queueId(call.clusterId(), req.targetQueue()));
                    case RETRY -> new Retry();
                    case DELETE -> new Delete();
                    case EXPIRE -> throw new IllegalStateException("unreachable");
                };
        BulkOutcome outcome = messages.byIds(call.clusterId(), node, call.queue(), ids, act);
        List<Long> notDone = outcome.notDone().stream()
                .map(ref -> Long.parseLong(ref.position()))
                .toList();
        return new BulkResult(
                outcome.done(), notDone, outcome.notDoneReason().isEmpty() ? null : outcome.notDoneReason());
    }

    /** A retry's reach: the addresses it was checked against, or none when {@code message:send} is cluster-wide. */
    private record RetryScope(boolean restricted, Set<String> addresses) {
        static final RetryScope UNRESTRICTED = new RetryScope(false, Set.of());
    }

    /** One message action, ready to run against a resolved queue. */
    private record QueueAction(
            UUID clusterId,
            ResolvedQueue resolved,
            String queue,
            MessageAction action,
            MessageActionRequest req,
            RetryScope scope) {}

    // ---- resolution + plumbing ------------------------------------

    private AuditEvent begin(
            String action, String queueName, UUID clusterId, UUID node, Map<String, ?> params, boolean dryRun) {
        Actor actor = actorResolver.resolve();
        return audit.begin(actor, action, "SUBSCRIPTION", queueName, clusterId, node, params, dryRun);
    }

    /** Tells the subscribers who read the queue that it changed, once the transaction commits. */
    private void publishQueuesAfterCommit(UUID clusterId, String queueName) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sseHub.publishAbout(clusterId, "subscriptions", List.of(queueName), List.of());
                }
            });
        } else {
            sseHub.publishAbout(clusterId, "subscriptions", List.of(queueName), List.of());
        }
    }

    ResolvedQueue resolve(UUID clusterId, String queueName, UUID nodeId) {
        List<QueueLocation> locations = queueLocator.locate(clusterId, queueName);
        if (locations.isEmpty()) {
            throw new NotFoundException("queue", queueName);
        }
        QueueLocation any = locations.get(0);

        Map<UUID, ClusterNode> byId = brokerNodes.nodes(clusterId).stream()
                .collect(Collectors.toMap(ClusterNode::getId, Function.identity()));
        // Every live node of a cluster holds its own copy of the queue, so "the live node" is
        // not one node. Unasked, open the copy holding the most messages: the first live node
        // listed can hold none of them, and the view then reads as an empty queue.
        List<ClusterNode> candidates = locations.stream()
                .sorted(Comparator.comparingLong(QueueLocation::messageCount).reversed())
                .map(l -> byId.get(l.nodeId()))
                .filter(n -> n != null && n.getManagementUrl() != null)
                .toList();
        if (candidates.isEmpty()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "No manageable node holds queue '" + queueName + "'.");
        }

        ClusterNode chosen;
        if (nodeId != null) {
            chosen = candidates.stream()
                    .filter(n -> n.getId().equals(nodeId))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("node", nodeId));
        } else {
            chosen = candidates.stream()
                    .filter(n -> Boolean.TRUE.equals(n.getActive()))
                    .findFirst()
                    .orElse(candidates.get(0));
        }
        return new ResolvedQueue(chosen, any.address(), any.routingType());
    }

    /**
     * The broker's message in the policy's neutral shape: its identifying headers and every property.
     * Shared with the SQL console, which governs the same messages.
     */
    public static MessageContent content(BrowsedMessage m) {
        Map<String, String> headers = new HashMap<>();
        headers.put(CORRELATION_ID, m.correlationId());
        headers.put(GROUP_ID, m.groupId());
        headers.put("userId", m.userId());
        headers.put("replyTo", m.replyTo());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.putAll(m.stringProperties());
        properties.putAll(m.intProperties());
        properties.putAll(m.longProperties());
        properties.putAll(m.doubleProperties());
        properties.putAll(m.booleanProperties());
        return new MessageContent(
                headers, properties, m.body(), m.bodyEncoding() == BodyEncoding.BASE64, m.contentType());
    }

    private static MessageSummaryView toSummary(
            BrowsedMessage m, GovernedMessage g, boolean tombstone, String key, BodyEncoding keyEncoding) {
        String body = g.body();
        return new MessageSummaryView(
                String.valueOf(m.messageId()),
                m.type(),
                m.durable(),
                m.priority(),
                producedAt(m),
                m.expiration(),
                m.size(),
                g.headers().get(GROUP_ID),
                g.headers().get(CORRELATION_ID),
                preview(body),
                m.bodyTruncated(),
                m.propertyCount(),
                GovernanceViews.redactions(g),
                tombstone,
                key,
                keyEncoding == null ? null : keyEncoding.name());
    }

    /** When the producer stamped the message, or null: a message with no time is not one from 1970. */
    private static Long producedAt(BrowsedMessage m) {
        return m.timestamp() > 0 ? m.timestamp() : null;
    }

    private static String preview(String body) {
        if (body == null || body.length() <= 200) {
            return body;
        }
        return body.substring(0, 200);
    }

    private static MessageDetailView toDetail(BrowsedMessage m, GovernedMessage g, UUID node) {
        return toDetail(m, g, node, false, null, null);
    }

    private static MessageDetailView toDetail(
            BrowsedMessage m, GovernedMessage g, UUID node, boolean tombstone, String key, BodyEncoding keyEncoding) {
        // A masked value is a marker string whatever its original type, so it is listed with the strings.
        Map<String, String> strings = new LinkedHashMap<>();
        Map<String, Long> ints = new LinkedHashMap<>();
        Map<String, Long> longs = new LinkedHashMap<>();
        Map<String, Double> doubles = new LinkedHashMap<>();
        Map<String, Boolean> booleans = new LinkedHashMap<>();
        g.properties().forEach((name, value) -> {
            switch (value) {
                case Long l when m.intProperties().containsKey(name) -> ints.put(name, l);
                case Long l when m.longProperties().containsKey(name) -> longs.put(name, l);
                case Double d -> doubles.put(name, d);
                case Boolean b -> booleans.put(name, b);
                case null, default -> strings.put(name, String.valueOf(value));
            }
        });
        return new MessageDetailView(
                String.valueOf(m.messageId()),
                m.type(),
                m.durable(),
                m.priority(),
                producedAt(m),
                m.expiration(),
                m.size(),
                g.headers().get(GROUP_ID),
                g.headers().get(CORRELATION_ID),
                g.headers().get("userId"),
                g.body(),
                m.bodyEncoding().name(),
                m.bodyCompression().apiName(),
                m.contentType(),
                m.bodyTruncated(),
                m.observedLimitBytes(),
                node,
                strings,
                ints,
                longs,
                doubles,
                booleans,
                GovernanceViews.redactions(g),
                GovernanceViews.withheld(g),
                tombstone,
                key,
                keyEncoding == null ? null : keyEncoding.name());
    }
}
