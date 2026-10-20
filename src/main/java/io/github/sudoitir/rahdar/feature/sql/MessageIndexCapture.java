package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDutyReleased;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps one live tail running per index subscription (ADR-0059, task 6.4).
 *
 * <p>Capture is not a second reading mechanism. A subscription is exactly the query
 * {@code SELECT * FROM broker."<pattern>"}, tailed forever, with the rows written to
 * {@code message_index} instead of to an operator's screen — so everything the tail
 * already guarantees about broker load, permits and marks holds here unchanged, and
 * there is one place where a sampled read of a queue is implemented.
 *
 * <p>It follows that capture inherits the tail's honesty problem too: a message that
 * arrives and is consumed between two polls is never seen and therefore never
 * indexed. The index is a record of what was observed, which is what the UI says it
 * is.
 *
 * <p>The reconcile pass re-plans each subscription so that a queue created after the
 * subscription was is picked up. A plan is only swapped when its target set actually
 * changed, because restarting a tail re-primes its high-water marks to "now".
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessageIndexCapture {

    /** How often subscriptions are turned into running tails. Not a broker call. */
    public static final Duration RECONCILE = Duration.ofSeconds(30);

    private final MessageIndexSubscriptionRepository subscriptions;
    private final SqlQueryParser parser;
    private final QueryPlanner planner;
    private final SqlTailPoller poller;
    private final MessageIndexWriter writer;
    private final ClusterOwnership ownership;

    /** Running captures, by subscription id. */
    private final Map<UUID, Capture> running = new ConcurrentHashMap<>();

    /**
     * Why a subscription is not capturing, by subscription id. A pattern that matches
     * nothing and a pattern that matches a busy queue both produce an empty index, and
     * without this they are indistinguishable — the operator is left reading an empty
     * result as an answer about their broker rather than about their subscription.
     */
    private final Map<UUID, String> notCapturing = new ConcurrentHashMap<>();

    private record Capture(SqlTailPoller.Tail tail, String fingerprint) {}

    /** The reason this subscription is not capturing, or empty when it is. */
    public Optional<String> notCapturingReason(UUID subscriptionId) {
        return Optional.ofNullable(notCapturing.get(subscriptionId));
    }

    /**
     * Subscriptions still walking the messages that were already on their queues when indexing
     * started. That walk is spread over several polls (message-index spec), and until it is done
     * the index is not up to date, which the subscription must say.
     */
    private final Set<UUID> walkingBacklog = ConcurrentHashMap.newKeySet();

    /** Whether this subscription is still indexing the messages that were on its queues when it started. */
    public boolean backlogInProgress(UUID subscriptionId) {
        return walkingBacklog.contains(subscriptionId);
    }

    /**
     * Registered with {@code JobRunner}; the tail poller does the actual reading. Only the owner of a
     * cluster tails it, so a subscription of a cluster this replica does not own is not wanted here and
     * its tail is stopped.
     */
    public synchronized void reconcileSampling() {
        // A CAPTURE subscription is drained by the capture consumer, not polled here.
        // Running both would double the broker load and write the same message twice,
        // once as SAMPLED and once as CAPTURED (ADR-0062).
        List<MessageIndexSubscriptionEntity> enabled = subscriptions.findByEnabledTrue().stream()
                .filter(s -> s.getMode() != CaptureMode.CAPTURE)
                .filter(s -> ownership.owns(s.getClusterId()))
                .toList();
        Set<UUID> wanted =
                enabled.stream().map(MessageIndexSubscriptionEntity::getId).collect(Collectors.toSet());

        running.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().tail().stop();
            return true;
        });

        notCapturing.keySet().retainAll(wanted);
        walkingBacklog.retainAll(wanted);

        for (MessageIndexSubscriptionEntity subscription : enabled) {
            try {
                start(subscription);
            } catch (RuntimeException e) {
                // A pattern that resolves to nothing, a cluster that is unreachable, a
                // planner refusal: none of them may stop the other subscriptions, and
                // all of them are retried on the next pass. But none of them may be
                // silent either — an operator reading an empty index has to be able to
                // tell "nothing matched the pattern" from "nothing was on the queue".
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (notCapturing.put(subscription.getId(), reason) == null) {
                    log.warn(
                            "Index capture for {} is not running: {}",
                            subscription.getSubscriptionPattern(),
                            reason,
                            e);
                }
            }
        }
    }

    /** A cluster this replica no longer owns: its tails stop now, not at the next pass. */
    @EventListener
    void onDutyReleased(ClusterDutyReleased released) {
        // Nobody joins this thread, so a failure must end here: when the application is closing, the
        // database is already going away and an uncaught exception reaches the JVM's handler instead.
        Thread.startVirtualThread(() -> {
            try {
                reconcileSampling();
            } catch (RuntimeException e) {
                log.warn("Reconciling index capture after releasing a cluster failed: {}", e.toString());
            }
        });
    }

    /** Stop every capture — used when a subscription's rows are being destroyed. */
    public void stop(UUID subscriptionId) {
        Capture capture = running.remove(subscriptionId);
        if (capture != null) {
            capture.tail().stop();
        }
    }

    public int activeCaptures() {
        return running.size();
    }

    private void start(MessageIndexSubscriptionEntity subscription) {
        UUID clusterId = subscription.getClusterId();
        // Explicitly the broker source: the default source would resolve to the index
        // for a queue this very subscription covers, and capture would read from what
        // it is supposed to be filling.
        QueryPlan plan = planner.plan(
                clusterId, parser.parse("SELECT * FROM broker.\"" + subscription.getSubscriptionPattern() + '"'));
        if (plan.targets().isEmpty()) {
            notCapturing.put(
                    subscription.getId(),
                    "the pattern '" + subscription.getSubscriptionPattern()
                            + "' matches no queue on any node of this cluster, so nothing is being indexed");
            return;
        }
        String fingerprint = plan.targets().stream()
                .map(t -> t.nodeId() + "/" + t.subscription())
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("");

        Capture current = running.get(subscription.getId());
        if (current != null) {
            if (current.fingerprint().equals(fingerprint)) {
                return;
            }
            current.tail().stop();
        }

        SqlTailPoller.Tail tail = poller.start(
                clusterId,
                plan,
                new IndexSink(clusterId, subscription.getId()),
                Duration.ofMillis(subscription.getIntervalMs()),
                true);
        running.put(subscription.getId(), new Capture(tail, fingerprint));
        notCapturing.remove(subscription.getId());
        log.info(
                "Indexing {} on {} target(s), every {} ms",
                subscription.getSubscriptionPattern(),
                plan.targets().size(),
                subscription.getIntervalMs());
    }

    /** Writes what a poll saw. Nothing is delivered anywhere else. */
    private final class IndexSink implements SqlTailPoller.Listener {
        private final UUID clusterId;
        private final UUID subscriptionId;

        private IndexSink(UUID clusterId, UUID subscriptionId) {
            this.clusterId = clusterId;
            this.subscriptionId = subscriptionId;
            // A new tail starts from the beginning, so it is walking a backlog until a poll says it is not.
            walkingBacklog.add(subscriptionId);
        }

        @Override
        public void observed(Row row) {
            writer.observe(clusterId, row, Instant.now());
        }

        @Override
        public void row(Row row) {
            // Already handled by observed(): a capture cares that a message was seen,
            // not whether this poll was the first time.
        }

        @Override
        public void nodeFinished(NodeOutcome outcome) {
            if (outcome.status() != NodeOutcome.Status.ANSWERED) {
                log.debug("Index capture: {} did not answer for {}", outcome.nodeName(), outcome.subscription());
            }
        }

        @Override
        public void status(SqlTailPoller.TailStatus status) {
            // The gap between enqueued and captured is reported to the operator on the
            // subscription, from the index's own counts, rather than kept per poll. Whether the
            // backlog walk is still going is only known here.
            if (status.backlogInProgress()) {
                walkingBacklog.add(subscriptionId);
            } else {
                walkingBacklog.remove(subscriptionId);
            }
        }

        @Override
        public boolean isCancelled() {
            // A capture ends when its subscription is disabled or deleted, which
            // reconcileSampling() acts on; it never ends because a client went away.
            return false;
        }
    }
}
