package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageCaptureNodeEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageCaptureNodeRepository;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.Ids;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceKind;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.SettingsPermissions;
import io.github.sudoitir.rahdar.kernel.settings.RahdarInstance;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Index subscriptions: the deliberate act of storing message payload (ADR-0059).
 *
 * <p>Creating one is a configuration change, not a query, so it takes the settings
 * write permission and is audited — including what it will keep and for how long.
 * Deleting one destroys everything it captured, so the count that will be destroyed
 * is available before the deletion is armed, and the deletion reports what it
 * actually removed.
 */
@Service
@RequiredArgsConstructor
public class MessageIndexService {

    private final MessageIndexSubscriptionRepository subscriptions;
    private final QueueSnapshots snapshots;
    private final MessageIndexCapture capture;
    private final MessageCaptureNodeRepository captureNodes;
    private final ClusterDirectory nodes;
    private final ClusterAccessGuard clusterAccess;
    private final ActorResolver actorResolver;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final CaptureAddresses captureAddresses;
    private final CaptureConsumer consumers;
    private final CaptureReconciler reconciler;
    private final CaptureTap tap;
    private final CaptureProperties captureProperties;
    private final RahdarInstance instance;
    private final LifecycleRegistry lifecycle;

    /** What creating a capture subscription would do on the broker, resolved without changing anything. */
    public record Preview(
            List<String> addresses,
            List<String> nodes,
            long ringMessages,
            long ringBytes,
            List<String> brokerObjects,
            String brokerXml,
            String refusal) {}

    /**
     * The dry run of {@link #create} for a capture subscription (message-capture spec): the same
     * permission and the same validation, then what it resolves to — addresses, target nodes,
     * bounds and broker objects — with nothing saved and no broker contacted.
     */
    @Transactional(readOnly = true)
    public Preview preview(UUID clusterId, Spec spec) {
        String pattern = validPattern(spec.queuePattern());
        clusterAccess.requireOnAll(clusterId, ResourceKind.QUEUE, pattern, SqlPermissions.CAPTURE_WRITE);
        MessageIndexSubscriptionEntity draft = new MessageIndexSubscriptionEntity();
        draft.setId(Ids.v7());
        draft.setClusterId(clusterId);
        draft.setQueuePattern(pattern);
        draft.setMode(CaptureMode.CAPTURE);
        if (spec.retentionDays() != null) {
            draft.setRetentionDays(validRetention(spec.retentionDays()));
        }
        applyBounds(draft, spec);

        List<String> addresses = List.copyOf(captureAddresses.of(clusterId, draft));
        List<String> nodeNames = reconciler.servingNodes(clusterId).stream()
                .map(ClusterNode::getName)
                .toList();
        List<String> objects = new ArrayList<>();
        for (String address : addresses) {
            String name = CaptureNames.of(instance.id(), address, draft.getId());
            objects.add("divert " + name + " (non-exclusive, from " + address + ")");
            objects.add("queue " + CaptureNames.queueOf(name) + " (non-durable, ring " + draft.getRingSize() + ")");
        }
        if (!addresses.isEmpty()) {
            objects.add("address-setting " + CaptureNames.matchFor(instance.id()));
            objects.add("security-setting " + CaptureNames.matchFor(instance.id()));
        }
        String refusal = refusal(addresses, draft);
        return new Preview(
                addresses,
                nodeNames,
                draft.getRingSize(),
                captureProperties.maxRingBytes().toBytes(),
                List.copyOf(objects),
                tap.captureBrokerXml(clusterId, instance.id(), draft.getRingSize()),
                refusal);
    }

    /**
     * What a subscription is currently costing.
     *
     * @param messages rows the index holds for this subscription's queues
     * @param payloadBytes the bodies and properties held, which is the part an
     *     operator is deciding about; the table's own overhead is not included
     */
    public record Footprint(long messages, long payloadBytes, Instant oldest) {}

    /**
     * @param notCapturing why this subscription is recording nothing, or {@code null}
     *     when it is running. An empty index and a broken subscription look identical
     *     from a query, so the reason travels with the subscription rather than being
     *     left in a log.
     * @param backlogInProgress whether a sampled subscription is still indexing the messages
     *     that were already on its queues, so its index is not yet up to date
     */
    public record Subscription(
            MessageIndexSubscriptionEntity entity,
            Footprint footprint,
            String notCapturing,
            boolean backlogInProgress,
            List<CaptureNode> nodes) {}

    /** Per-node capture state with the node's name resolved, which the state row does not carry. */
    public record CaptureNode(MessageCaptureNodeEntity state, String nodeName) {}

    private Subscription describe(MessageIndexSubscriptionEntity entity, Footprint footprint) {
        // A sampled subscription has no per-node capture state, and reporting an empty
        // list for it is the honest answer rather than a missing one.
        List<CaptureNode> captureState = entity.getMode() == CaptureMode.CAPTURE
                ? captureNodes.findBySubscriptionId(entity.getId()).stream()
                        .map(state -> new CaptureNode(
                                state,
                                nodes.node(state.getNodeId())
                                        .map(ClusterNode::getName)
                                        .orElse(null)))
                        .toList()
                : List.of();
        return new Subscription(
                entity,
                footprint,
                capture.notCapturingReason(entity.getId()).orElse(null),
                entity.getMode() != CaptureMode.CAPTURE && capture.backlogInProgress(entity.getId()),
                captureState);
    }

    /** The bounds and mode a subscription is created or changed with. Null means "leave it". */
    public record Spec(
            String queuePattern,
            Long intervalMs,
            Integer retentionDays,
            Boolean enabled,
            CaptureMode mode,
            Long ringSize,
            String filterString,
            Long maxBytes,
            Integer maxRate,
            Integer bodyCapBytes) {}

    @Transactional(readOnly = true)
    public List<Subscription> list(UUID clusterId) {
        clusterAccess.requireVisible(clusterId);
        return subscriptions.findByClusterId(clusterId).stream()
                .filter(s -> readable(clusterId, s))
                .map(s -> describe(s, footprint(s)))
                .toList();
    }

    @Transactional
    public Subscription create(UUID clusterId, Spec spec) {
        CaptureMode mode = spec.mode() == null ? CaptureMode.SAMPLE : spec.mode();
        String pattern = validPattern(spec.queuePattern());
        requireMayChange(clusterId, mode, pattern);

        MessageIndexSubscriptionEntity entity = new MessageIndexSubscriptionEntity();
        entity.setId(Ids.v7());
        entity.setClusterId(clusterId);
        entity.setQueuePattern(pattern);
        entity.setIntervalMs(spec.intervalMs() == null ? 5000L : validInterval(spec.intervalMs()));
        entity.setRetentionDays(
                spec.retentionDays() == null
                        ? Math.min(7, storeRetentionDays())
                        : validRetention(spec.retentionDays()));
        entity.setCaptureFrom(Instant.now());
        entity.setCreatedAt(Instant.now());
        entity.setCreatedBy(actorName());
        entity.setEnabled(true);
        entity.setMode(mode);
        applyBounds(entity, spec);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("queuePattern", pattern);
        params.put("mode", mode.name());
        params.put(INTERVAL_MS, entity.getIntervalMs());
        params.put(RETENTION_DAYS, entity.getRetentionDays());
        params.put("storesMessageBodies", true);
        // What sets exposure is exactly what gets audited: every bound the capture runs under.
        params.putAll(bounds(entity));
        AuditEvent event = audit.begin(
                actorResolver.resolve(), "sql.index.create", CLUSTER, pattern, clusterId, null, params, false);
        try {
            MessageIndexSubscriptionEntity saved = subscriptions.save(entity);
            audit.succeed(event, 1);
            converge(saved);
            return describe(saved, new Footprint(0, 0, null));
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    public Subscription update(UUID clusterId, UUID id, Spec spec) {
        return committing(() -> change(clusterId, id, spec));
    }

    private Subscription change(UUID clusterId, UUID id, Spec spec) {
        MessageIndexSubscriptionEntity entity = subscriptions
                .findById(id)
                .filter(sub -> sub.getClusterId().equals(clusterId))
                .filter(sub -> readable(clusterId, sub))
                .orElseThrow(() -> new NotFoundException("Index subscription", id));
        CaptureMode target = spec.mode() == null ? entity.getMode() : spec.mode();
        // Gated on what the subscription will be as well as what it is: turning capture
        // on is the act that needs the authority, and turning it off needs it too —
        // stopping capture stops recording payload someone is relying on.
        requireMayChange(
                clusterId,
                target == CaptureMode.CAPTURE || entity.getMode() == CaptureMode.CAPTURE
                        ? CaptureMode.CAPTURE
                        : CaptureMode.SAMPLE,
                entity.getQueuePattern());

        Map<String, Object> before = bounds(entity);
        CaptureMode modeBefore = entity.getMode();
        boolean enabledBefore = entity.isEnabled();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("enabled", String.valueOf(spec.enabled()));
        params.put("mode", String.valueOf(target));
        params.put(INTERVAL_MS, String.valueOf(spec.intervalMs()));
        params.put(RETENTION_DAYS, String.valueOf(spec.retentionDays()));
        before.forEach((k, v) -> params.put(k + "Before", String.valueOf(v)));
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "sql.index.update",
                CLUSTER,
                entity.getQueuePattern(),
                clusterId,
                null,
                params,
                false);
        try {
            if (spec.enabled() != null) {
                entity.setEnabled(spec.enabled());
            }
            if (spec.intervalMs() != null) {
                entity.setIntervalMs(validInterval(spec.intervalMs()));
            }
            if (spec.retentionDays() != null) {
                entity.setRetentionDays(validRetention(spec.retentionDays()));
            }
            entity.setMode(target);
            applyBounds(entity, spec);
            MessageIndexSubscriptionEntity saved = subscriptions.save(entity);
            Map<String, Object> after = bounds(saved);
            audit.succeed(event, 1);
            // A disabled subscription stops recording at once rather than at the next
            // reconcile: an operator who turns capture off has usually just decided
            // that this payload should not be stored.
            capture.stop(id);
            boolean stillCapturing = saved.getMode() == CaptureMode.CAPTURE && saved.isEnabled();
            if (modeBefore == CaptureMode.CAPTURE && (!stillCapturing || !enabledBefore)) {
                consumers.stopSubscription(id);
            }
            if (modeBefore == CaptureMode.CAPTURE && !stillCapturing) {
                // With the drain stopped, the divert would keep copying into a capture queue
                // nothing empties until the next scheduled pass. Remove it now, the way a
                // deletion does, once this change has committed. A pass already holding the
                // cluster's lock skips this sweep, and the pass after it removes the divert.
                afterCommit(() -> reconciler.reconcileNow(clusterId));
            }
            if (stillCapturing && !after.equals(before)) {
                // The tap on the broker still carries the old filter and bounds, and a divert is
                // never changed in place: re-create it once this change has committed.
                afterCommit(() -> reconciler.reinstall(clusterId, id));
            }
            converge(saved);
            return describe(saved, footprint(saved));
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    /** What a deletion destroyed, and whether it left broker objects to be swept. */
    public record Deleted(long messagesDestroyed, boolean hadCapture) {}

    /** Deletes the subscription and every message it captured. Returns how many were destroyed. */
    public Deleted delete(UUID clusterId, UUID id) {
        return committing(() -> remove(clusterId, id));
    }

    private Deleted remove(UUID clusterId, UUID id) {
        MessageIndexSubscriptionEntity entity = require(clusterId, id);
        if (!readable(clusterId, entity)) {
            throw new NotFoundException("Index subscription", id);
        }
        requireMayChange(clusterId, entity.getMode(), entity.getQueuePattern());
        Footprint before = footprint(entity);

        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "sql.index.delete",
                CLUSTER,
                entity.getQueuePattern(),
                clusterId,
                null,
                Map.of("queuePattern", entity.getQueuePattern(), "capturedMessages", before.messages()),
                false);
        try {
            // Recording stops before any row is deleted, so nothing captured after the operator's
            // decision survives it (message-capture spec).
            capture.stop(id);
            consumers.stopSubscription(id);
            long destroyed = deleteCaptured(entity);
            subscriptions.delete(entity);
            audit.succeed(event, destroyed);
            boolean hadCapture = entity.getMode() == CaptureMode.CAPTURE;
            if (hadCapture) {
                // The taps this subscription owned are now orphans. The reconciler is the only
                // thing that removes one (ADR-0062 D4), under the cluster lock, once this
                // transaction has committed — never from in here, which would make broker calls
                // inside the transaction that deleted the rows.
                afterCommit(() -> reconciler.reconcileNow(clusterId));
            }
            return new Deleted(destroyed, hadCapture);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    // ---- internals ------------------------------------------------------

    /**
     * A capture records the messages of every queue its pattern can match, so it is changed only by a caller
     * who holds {@code capture:write} on all of them. A sample is a cluster-level setting.
     */
    private void requireMayChange(UUID clusterId, CaptureMode mode, String pattern) {
        if (mode == CaptureMode.CAPTURE) {
            clusterAccess.requireOnAll(clusterId, ResourceKind.QUEUE, pattern, SqlPermissions.CAPTURE_WRITE);
        } else {
            clusterAccess.requireCluster(clusterId, SettingsPermissions.SETTINGS_WRITE);
        }
    }

    /** A subscription is seen by a caller who may read every message it indexes. */
    private boolean readable(UUID clusterId, MessageIndexSubscriptionEntity subscription) {
        return clusterAccess.mayOnAll(
                clusterId, ResourceKind.QUEUE, subscription.getQueuePattern(), MessagePermissions.MESSAGE_READ);
    }

    /** The largest capture queue, in messages (ADR-0079). The byte bound on the broker is the other half. */
    static final long MAX_RING_SIZE = 1_000_000L;

    private static final String INTERVAL_MS = "intervalMs";
    private static final String RETENTION_DAYS = "retentionDays";
    private static final String CLUSTER = "CLUSTER";

    /** Why a capture cannot start, or null when nothing stands in its way. */
    private String refusal(List<String> addresses, MessageIndexSubscriptionEntity draft) {
        if (addresses.isEmpty()) {
            return "The pattern '" + draft.getQueuePattern() + "' matches no address on this cluster, so nothing would"
                    + " be captured.";
        }
        String role = captureProperties.brokerRole();
        if (role == null || role.isBlank()) {
            return "Capture needs rahdar.capture.broker-role (RAHDAR_CAPTURE_BROKER_ROLE) set to"
                    + " the broker role Rahdar's own user holds.";
        }
        return null;
    }

    /**
     * Bounds outside their range are refused, naming the field and the range, never adjusted.
     * A value silently changed into range is a value the operator did not choose, applied to
     * how much production payload is copied and stored (operator-ui spec). An unset bound keeps
     * whatever the entity already carries, which for a new subscription is the field's own
     * default — the defaults live on the entity so that a subscription created any other way is
     * bounded too.
     */
    private static void applyBounds(MessageIndexSubscriptionEntity entity, Spec spec) {
        if (spec.ringSize() != null) {
            entity.setRingSize(inRange("ringSize", spec.ringSize(), 100L, MAX_RING_SIZE));
        }
        if (spec.maxBytes() != null) {
            entity.setMaxBytes(inRange("maxBytes", spec.maxBytes(), 1L, Long.MAX_VALUE));
        }
        if (spec.maxRate() != null) {
            entity.setMaxRate((int) inRange("maxRate", spec.maxRate(), 1, 1_000_000));
        }
        if (spec.bodyCapBytes() != null) {
            entity.setBodyCapBytes((int) inRange("bodyCapBytes", spec.bodyCapBytes(), 1024, 16L * 1024 * 1024));
        }
        if (spec.filterString() != null) {
            if (spec.filterString().length() > 4096) {
                throw new IllegalArgumentException("filterString is longer than 4096 characters.");
            }
            entity.setFilterString(spec.filterString().isBlank() ? null : spec.filterString());
        }
    }

    private static long validInterval(long intervalMs) {
        return inRange(INTERVAL_MS, intervalMs, 1000, 3_600_000);
    }

    /** The message-index store's retention in whole days: the most any subscription may keep (ADR-0134). */
    private int storeRetentionDays() {
        return (int) lifecycle.retention(MessageIndexStore.ID).orElseThrow().toDays();
    }

    private int validRetention(int retentionDays) {
        int max = storeRetentionDays();
        if (retentionDays < 1 || retentionDays > max) {
            throw new IllegalArgumentException("retentionDays must be between 1 and " + max
                    + ", the message index's retention; got " + retentionDays + ".");
        }
        return retentionDays;
    }

    private static long inRange(String field, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    field + " must be between " + min + " and " + max + "; got " + value + ".");
        }
        return value;
    }

    /** Every bound that sets what a subscription copies and keeps. */
    private static Map<String, Object> bounds(MessageIndexSubscriptionEntity entity) {
        Map<String, Object> bounds = new LinkedHashMap<>();
        bounds.put("ringSize", entity.getRingSize());
        bounds.put("maxBytes", entity.getMaxBytes());
        bounds.put("maxRate", entity.getMaxRate());
        bounds.put("bodyCapBytes", entity.getBodyCapBytes());
        bounds.put("filterString", String.valueOf(entity.getFilterString()));
        return bounds;
    }

    /** Broker work to do once the transaction of {@link #committing} has committed and released its connection. */
    private static final ThreadLocal<List<Runnable>> AFTER_COMMIT = new ThreadLocal<>();

    private final TransactionTemplate transactions;

    /**
     * Runs the body in a transaction, then the broker work it asked for through {@link #afterCommit}. Spring's
     * own after-commit hook runs with the transaction's connection still held, which is a broker call inside a
     * database transaction.
     */
    private <T> T committing(Supplier<T> body) {
        List<Runnable> after = new ArrayList<>();
        AFTER_COMMIT.set(after);
        T result;
        try {
            result = transactions.execute(status -> body.get());
        } finally {
            AFTER_COMMIT.remove();
        }
        after.forEach(Runnable::run);
        return result;
    }

    private static void afterCommit(Runnable action) {
        AFTER_COMMIT.get().add(action);
    }

    /**
     * Make the world match the subscription that was just written.
     *
     * <p>Only the sampled path converges inline. A capture pass makes management calls
     * to every live node, and this method runs inside the transaction that wrote the
     * subscription — holding a database connection open across broker HTTP is the
     * shape of an outage, not of a fast response. The reconciler runs on its own
     * schedule and is idempotent, so the tap is installed on the next pass; the screen
     * says exactly that rather than implying it already happened. Removal usually does not
     * wait for it: turning capture off or deleting it sweeps once the change has committed,
     * unless a pass holds the cluster's lock at that moment, and then the next pass removes it.
     */
    private void converge(MessageIndexSubscriptionEntity entity) {
        if (entity.getMode() != CaptureMode.CAPTURE) {
            capture.reconcileSampling();
        }
    }

    private MessageIndexSubscriptionEntity require(UUID clusterId, UUID id) {
        return subscriptions
                .findById(id)
                .filter(s -> s.getClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("Index subscription", id));
    }

    /**
     * The names this subscription's rows are stored under. Rows are keyed by name rather than
     * by subscription id, because a message two subscriptions match is one message, not two.
     * A sampled row carries the queue it was seen on; a captured row carries the address it was
     * routed to, which is not the queue name for a multicast address or a queue named
     * differently from its address.
     */
    private List<String> queuesOf(MessageIndexSubscriptionEntity subscription) {
        if (subscription.getMode() == CaptureMode.CAPTURE) {
            return List.copyOf(captureAddresses.of(subscription.getClusterId(), subscription));
        }
        return snapshots.forCluster(subscription.getClusterId()).stream()
                .map(QueueSnapshot::queueName)
                .distinct()
                .filter(q -> QueueNamePattern.matches(subscription.getQueuePattern(), q))
                .toList();
    }

    private Footprint footprint(MessageIndexSubscriptionEntity subscription) {
        List<String> queues = queuesOf(subscription);
        if (queues.isEmpty()) {
            return new Footprint(0, 0, null);
        }
        List<Object> binds = new ArrayList<>();
        binds.add(subscription.getClusterId());
        binds.addAll(queues);
        String placeholders = String.join(", ", Collections.nCopies(queues.size(), "?"));
        return jdbc.queryForObject(
                """
                SELECT count(*) AS messages,
                       coalesce(sum(coalesce(octet_length(body), 0) + pg_column_size(props)), 0) AS payload_bytes,
                       min(observed_at) AS oldest
                  FROM message_index
                 WHERE cluster_id = ? AND subscription IN (%s)
                """.formatted(placeholders),
                (rs, rowNum) -> new Footprint(
                        rs.getLong("messages"),
                        rs.getLong("payload_bytes"),
                        rs.getTimestamp("oldest") == null
                                ? null
                                : rs.getTimestamp("oldest").toInstant()),
                binds.toArray());
    }

    private long deleteCaptured(MessageIndexSubscriptionEntity subscription) {
        List<String> queues = queuesOf(subscription);
        if (queues.isEmpty()) {
            return 0;
        }
        // Everything the subscription could have captured, back to the start of its
        // retention window — bounded so the statement cannot run away on a partition
        // that a longer-lived subscription still owns.
        Instant floor = Instant.now().minus(Duration.ofDays(subscription.getRetentionDays() + 1L));
        List<Object> binds = new ArrayList<>();
        binds.add(subscription.getClusterId());
        binds.add(Timestamp.from(floor));
        binds.addAll(queues);
        String placeholders = String.join(", ", Collections.nCopies(queues.size(), "?"));
        return jdbc.update(
                "DELETE FROM message_index WHERE cluster_id = ? AND observed_at >= ? AND subscription IN ("
                        + placeholders + ')',
                binds.toArray());
    }

    /**
     * The pattern goes into a quoted SQL identifier when capture plans its query, so a
     * quote in it would be a second place where operator text becomes SQL text. There
     * is no legal Artemis queue name containing one.
     */
    private String validPattern(String pattern) {
        String trimmed = pattern == null ? "" : pattern.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A subscription needs a queue name or pattern.");
        }
        if (trimmed.indexOf('"') >= 0 || trimmed.indexOf('\'') >= 0) {
            throw new IllegalArgumentException("A queue pattern cannot contain a quote character.");
        }
        if (trimmed.length() > 1000) {
            throw new IllegalArgumentException("A queue pattern is longer than 1000 characters.");
        }
        if (CaptureAddresses.isCaptureObject(trimmed)) {
            // Rahdar's own capture queues are never captured: doing so taps the taps (ADR-0079).
            throw new IllegalArgumentException("A pattern under "
                    + RahdarNames.CAPTURE_PREFIX + " or "
                    + RahdarNames.PLUGIN_TAP_PREFIX
                    + " names Rahdar's own capture or plugin tap objects, which cannot be captured.");
        }
        return trimmed;
    }

    private String actorName() {
        var actor = actorResolver.resolve();
        return actor == null ? null : actor.displayName();
    }
}
