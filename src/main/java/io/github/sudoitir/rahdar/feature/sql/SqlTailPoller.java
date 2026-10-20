package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Target;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The live tail (ADR-0058 D7, ADR-0060). One mechanism: a plan is re-browsed at the
 * configured interval, from a per-target high-water mark on {@code (timestamp,
 * messageId)}, and whatever is new and still matches is emitted.
 *
 * <p>A tail that reads the queues is a <strong>sample, not a capture</strong>. Between two
 * polls a message can arrive and be consumed, and nothing here will ever see it. That is
 * inherent to polling and is the price of not mutating {@code broker.xml} and not consuming the
 * operator's messages. The consequence is stated permanently in the UI rather than buried — in
 * the sampled wording only, because a tail resolved onto the index makes the stronger claim and
 * gets different words (ADR-0062 D10).
 *
 * <p>The mark is pushed into the JMS selector, so a poll costs the broker a filtered
 * page rather than a full re-read of the queue. Every read still takes the node's
 * management-call permit, and the plan's bounds still apply per poll.
 *
 * <p>A plan that resolved onto the index is tailed from the index, not from the queues. It
 * resolved there because capture covers every queue it reads, and the copy the capture took is
 * the only place a message consumed the instant it arrived still exists — re-reading the queue
 * would show the operator nothing and call it quiet (ADR-0062 D2).
 *
 * <p>A tail's plan is fixed for its lifetime: the targets were resolved against the
 * caller's cluster scope when the tail started, and re-planning on a scheduler thread
 * has no caller to check permissions against. A queue created after the tail started
 * is therefore not tailed until the query is re-run.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SqlTailPoller {

    private final BrokerQueryExecutor executor;
    private final IndexQueryExecutor indexExecutor;
    private final QueueSnapshots snapshots;

    /** Live tails. A tail is removed when its client goes away. */
    private final Set<Tail> tails = ConcurrentHashMap.newKeySet();

    /**
     * Polls run off the scheduler thread. A poll waits on rate-limit permits and can
     * take seconds; the shared scheduler also drives the SSE heartbeat and the alert
     * dispatcher, and neither may be held up behind a tail.
     */
    private final AtomicReference<ExecutorService> polls =
            new AtomicReference<>(Executors.newVirtualThreadPerTaskExecutor());

    /**
     * Stop starting polls and wait, bounded, for the ones in flight — at shutdown, before the
     * broker clients they use are released. Replaced on {@link #resumePolls} because an executor
     * cannot be restarted and a stopped context can be started again.
     */
    public void closePolls() {
        ExecutorService closing = polls.get();
        closing.shutdown();
        try {
            if (!closing.awaitTermination(10, TimeUnit.SECONDS)) {
                closing.shutdownNow();
            }
        } catch (InterruptedException _) {
            closing.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Accept polls again after a stopped context is started. */
    public void resumePolls() {
        if (polls.get().isShutdown()) {
            polls.set(Executors.newVirtualThreadPerTaskExecutor());
        }
    }

    /** Where a tail's output goes. The poller knows nothing about how it is delivered. */
    public interface Listener {
        void row(Row row);

        /**
         * Every row a poll returned, new or not — where {@link #row(Row)} is only what
         * has not been delivered before. The index capture writes from here, because a
         * message being seen again is exactly what it needs to record.
         */
        default void observed(Row row) {}

        void nodeFinished(NodeOutcome outcome);

        void status(TailStatus status);

        /** True once the client has gone away, which is what stops the tail. */
        boolean isCancelled();
    }

    /**
     * What the tail has seen, and what it can prove it missed.
     *
     * @param enqueued messages enqueued on the tailed queues since the tail started,
     *     from the {@code MessagesAdded} deltas in {@code subscription_snapshot}
     * @param shown rows this tail has delivered
     * @param everyMessageMatches true when the query has no predicate at all, so every
     *     enqueued message would have matched and {@code enqueued - shown} is exactly
     *     the number that passed through unobserved. When false the difference also
     *     contains messages that simply did not match, and the two cannot be separated
     */
    public record TailStatus(
            long enqueued,
            long shown,
            long polls,
            Instant lastPollAt,
            boolean everyMessageMatches,
            /** True while a tail started from the beginning is still walking the messages already on a queue. */
            boolean backlogInProgress) {}

    /**
     * Pages of one queue a tail that starts from the beginning reads per tick. Its first
     * polls walk a backlog; this spreads a deep one over several ticks instead of reading
     * it all at once (message-index spec).
     */
    // ponytail: a constant, not a setting. Make it one if an operator ever needs to tune it.
    static final int BACKLOG_PAGES_PER_TICK = 5;

    /**
     * How far behind its mark an index tail re-reads. A row's {@code observed_at} is stamped when
     * its drain read it and the row appears when that drain's batch commits, so one node's row can
     * land after a later one from another. Re-reading this far back covers that; the mark filters
     * the repeats out.
     */
    // ponytail: a constant, not a setting. Make it one if a deployment ever commits further apart.
    private static final Duration INDEX_TAIL_OVERLAP = Duration.ofSeconds(30);

    /** A running tail. Held by the caller so it can stop it. */
    public final class Tail {
        private final UUID clusterId;
        private final QueryPlan plan;
        private final Listener listener;
        private final boolean everyMessageMatches;

        /**
         * The soonest this tail may be polled again. A capture subscription sets its
         * own interval, so one slow-moving queue is not read at the console's cadence.
         */
        private final Duration minInterval;

        private volatile Instant nextDue = Instant.EPOCH;

        /** Per target: the newest timestamp seen, and the ids seen at exactly it. */
        private final Map<String, Mark> marks = new ConcurrentHashMap<>();

        /** Per target: {@code MessagesAdded} at the previous poll. */
        private final Map<String, Long> lastAdded = new ConcurrentHashMap<>();

        private final AtomicLong enqueued = new AtomicLong();
        private final AtomicLong shown = new AtomicLong();
        private final AtomicLong pollCount = new AtomicLong();
        private final AtomicBoolean polling = new AtomicBoolean();
        private volatile boolean stopped;

        /** Pages per target per poll: bounded for a tail that walks a backlog, unbounded otherwise. */
        private final int maxPagesPerTarget;

        private volatile boolean backlogInProgress;

        /**
         * For an index tail: where the next poll's window starts. It follows what the query
         * returned, not what survived dedup — a page made entirely of rows this tail will not
         * deliver still moves the cursor past them, or the same page is re-read forever and the
         * tail goes quiet as soon as the index holds a page's worth inside the overlap.
         */
        private volatile Instant indexSince = Instant.now().minus(INDEX_TAIL_OVERLAP);

        private Tail(UUID clusterId, QueryPlan plan, Listener listener, Duration minInterval, boolean fromBeginning) {
            this.clusterId = clusterId;
            this.plan = plan;
            this.listener = listener;
            this.minInterval = minInterval;
            this.maxPagesPerTarget = fromBeginning ? BACKLOG_PAGES_PER_TICK : Integer.MAX_VALUE;
            this.backlogInProgress = fromBeginning;
            this.everyMessageMatches =
                    plan.pushedDown().isEmpty() && plan.scanned().isEmpty();
            for (Target target : plan.targets()) {
                // A console tail starts at "now": the operator has just seen the static
                // result and wants what comes next. A capture starts at zero, so the
                // first poll indexes the backlog already on the queue — otherwise a
                // subscription created during an incident can answer nothing about the
                // messages the incident is about.
                marks.put(
                        key(target),
                        new Mark(fromBeginning ? 0 : target.brokerNow().toEpochMilli(), new HashSet<>()));
            }
            primeAdded();
        }

        public void stop() {
            stopped = true;
            tails.remove(this);
        }

        /**
         * Advance the mark for a row the static phase already delivered, so the first
         * poll does not send it a second time.
         */
        public void seen(Row row) {
            advance(key(row.nodeId(), row.subscription()), row);
        }

        private boolean cancelled() {
            return stopped || listener.isCancelled();
        }

        /** Only what arrived after the mark, evaluated by the broker rather than here. */
        private String selectorFor(Target target) {
            Mark mark = marks.get(key(target));
            // Greater-or-equal rather than greater: a message stamped in the same
            // millisecond as the mark would otherwise be skipped. The duplicates that
            // admits are filtered by id below, which is what the id half of the mark
            // is for.
            return mark == null ? null : ColumnCatalogue.Column.TIMESTAMP.selectorId() + " >= " + mark.timestamp();
        }

        private boolean isNew(Row row) {
            Mark mark = marks.get(key(row.nodeId(), row.subscription()));
            if (mark == null) {
                return true;
            }
            if (row.timestamp() > mark.timestamp()) {
                return true;
            }
            return row.timestamp() == mark.timestamp() && !mark.ids().contains(row.messageId());
        }

        private synchronized void advance(String key, Row row) {
            Mark mark = marks.get(key);
            if (mark == null || row.timestamp() > mark.timestamp()) {
                Set<Long> ids = new HashSet<>();
                ids.add(row.messageId());
                marks.put(key, new Mark(row.timestamp(), ids));
            } else if (row.timestamp() == mark.timestamp()) {
                mark.ids().add(row.messageId());
            }
        }

        /**
         * Record where each target's counter stands now, so the first delta measures
         * what arrived after the tail started rather than since the broker booted.
         */
        private void primeAdded() {
            lastAdded.putAll(addedByTarget());
        }

        private Map<String, Long> addedByTarget() {
            Set<String> wanted = new HashSet<>();
            plan.targets().forEach(t -> wanted.add(key(t)));
            Map<String, Long> added = new LinkedHashMap<>();
            for (QueueSnapshot snapshot : snapshots.forCluster(clusterId)) {
                String key = key(snapshot.nodeId(), snapshot.queueName());
                if (wanted.contains(key)) {
                    added.put(key, snapshot.messagesAdded());
                }
            }
            return added;
        }
    }

    /** The high-water mark: the newest timestamp seen, and the ids seen at exactly it. */
    private record Mark(long timestamp, Set<Long> ids) {}

    /**
     * Start tailing a plan that has already been permission-checked, cost-gated and
     * run once. The returned handle is the only way to stop it, and the caller must
     * stop it when its client disconnects.
     */
    public Tail start(UUID clusterId, QueryPlan plan, Listener listener) {
        return start(clusterId, plan, listener, Duration.ZERO, false);
    }

    /**
     * As above, but polled no more often than {@code minInterval}, and optionally
     * starting from the beginning of each queue rather than from now. The tick still
     * runs at the console's cadence; a tail that is not due is skipped.
     */
    public Tail start(UUID clusterId, QueryPlan plan, Listener listener, Duration minInterval, boolean fromBeginning) {
        Tail tail = new Tail(clusterId, plan, listener, minInterval, fromBeginning);
        tails.add(tail);
        return tail;
    }

    /**
     * One poll per tail at a time. A tail whose poll is still running has a slower broker than
     * the interval assumes, and queueing more reads onto it is the wrong answer. Claims the
     * tail's poll when it is due.
     */
    private static boolean due(Tail tail) {
        return !Instant.now().isBefore(tail.nextDue) && tail.polling.compareAndSet(false, true);
    }

    /** Registered with {@code JobRunner} on the configured tail interval. */
    public void tick() {
        for (Tail tail : tails) {
            if (tail.cancelled()) {
                tails.remove(tail);
            } else if (due(tail)) {
                polls.get().execute(() -> {
                    try {
                        poll(tail);
                    } catch (RuntimeException e) {
                        log.debug("SQL tail poll failed", e);
                    } finally {
                        tail.nextDue = Instant.now().plus(tail.minInterval);
                        tail.polling.set(false);
                    }
                });
            }
        }
    }

    /** How many tails are running. */
    public int activeTails() {
        return tails.size();
    }

    private void poll(Tail tail) {
        if (tail.cancelled()) {
            return;
        }
        boolean fromIndex = tail.plan.resolvedSource() == QueryAst.Source.INDEX;
        QueryResult result = fromIndex
                ? indexExecutor.execute(tail.clusterId, tail.plan, new TailSink(tail), tail.indexSince)
                : executor.execute(
                        tail.clusterId, tail.plan, new TailSink(tail), tail::selectorFor, tail.maxPagesPerTarget);
        tail.pollCount.incrementAndGet();
        if (fromIndex) {
            advanceIndexWindow(tail, result);
        }
        if (tail.maxPagesPerTarget != Integer.MAX_VALUE) {
            // A target that filled every page it was allowed has more behind it.
            long full = (long) tail.maxPagesPerTarget * BrokerQueryExecutor.PAGE_SIZE;
            tail.backlogInProgress = result.nodes().stream().anyMatch(outcome -> outcome.examined() >= full);
        }
        measureGap(tail);
        tail.listener.status(new TailStatus(
                tail.enqueued.get(),
                tail.shown.get(),
                tail.pollCount.get(),
                Instant.now(),
                tail.everyMessageMatches,
                tail.backlogInProgress));
    }

    /**
     * Move an index tail's window to the end of the page it just read.
     *
     * <p>Normally it stays {@link #INDEX_TAIL_OVERLAP} behind the newest row, because a row whose
     * drain committed late can still land behind it; the tail's own mark filters the repeats.
     * A page the row limit cut short gets no overlap: everything behind its last row was in this
     * page, and re-reading it would fill the next page with the same rows and never reach what
     * came after — a tail that stalls silently, which is worse than one late row.
     */
    private void advanceIndexWindow(Tail tail, QueryResult result) {
        Instant newest = result.rows().stream()
                .map(Row::observedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (newest == null) {
            return;
        }
        boolean cutShort =
                result.boundsReached().stream().anyMatch(bound -> bound.kind() == QueryResult.Bound.Kind.ROW_LIMIT);
        Instant next = cutShort ? newest : newest.minus(INDEX_TAIL_OVERLAP);
        if (next.isAfter(tail.indexSince)) {
            tail.indexSince = next;
        }
    }

    /**
     * How many messages were enqueued on the tailed queues since the last poll, from
     * the {@code MessagesAdded} counters the scrape tiers already collect. A counter
     * that went backwards means the node restarted; that interval is not measurable,
     * so it counts as zero rather than as a negative or an absurd spike.
     */
    private void measureGap(Tail tail) {
        tail.addedByTarget().forEach((key, added) -> {
            Long previous = tail.lastAdded.put(key, added);
            if (previous != null && added > previous) {
                tail.enqueued.addAndGet(added - previous);
            }
        });
    }

    private static String key(Target target) {
        return key(target.nodeId(), target.subscription());
    }

    private static String key(UUID nodeId, String queueName) {
        return nodeId + " " + queueName;
    }

    /** Filters a poll's rows down to what is genuinely new, and advances the mark. */
    private final class TailSink implements BrokerQueryExecutor.Sink {
        private final Tail tail;

        private TailSink(Tail tail) {
            this.tail = tail;
        }

        @Override
        public void row(Row row) {
            tail.listener.observed(row);
            if (!tail.isNew(row)) {
                return;
            }
            tail.advance(key(row.nodeId(), row.subscription()), row);
            tail.shown.incrementAndGet();
            tail.listener.row(row);
        }

        @Override
        public void nodeFinished(NodeOutcome outcome) {
            // A node that stops answering mid-tail is the operator's business: the
            // rows they are watching stop arriving, and silence reads as "nothing is
            // being enqueued" rather than "this node is gone".
            if (outcome.status() != NodeOutcome.Status.ANSWERED) {
                tail.listener.nodeFinished(outcome);
            }
        }

        @Override
        public boolean isCancelled() {
            return tail.cancelled();
        }
    }
}
