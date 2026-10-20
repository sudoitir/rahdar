package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.MessagePredicate.EvalContext;
import io.github.sudoitir.rahdar.feature.sql.PredicateSplitter.Split;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Source;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Notice;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Target;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Bound;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.BrowsePage;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs a {@link QueryPlan} against the live brokers (ADR-0058).
 *
 * <p>Nothing here is a new way to read a broker: it is the existing
 * {@link BrokerMessages#browse} loop, one virtual thread per node, behind the same
 * {@link NodeCallLimiter} permit every other management call takes. That is what
 * makes non-negotiable #1 hold by construction — a runaway query throttles itself
 * against the broker rather than the other way round.
 *
 * <p>Four bounds stop it: targets, messages examined, rows returned, and wall clock.
 * Every one of them is reported when reached, because a bounded result that does not
 * say it is bounded is read as a complete one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BrokerQueryExecutor {

    /** The broker will not return more than this per browse, whatever we ask for. */
    static final int PAGE_SIZE = BrokerMessages.PAGE_CAP;

    private final ClusterDirectory nodes;
    private final MessagePredicate residuals;
    private final QueryPlanner planner;
    private final SqlProperties properties;
    private final SqlGovernance governance;
    private final BrokerMessages messages;

    /** Where a caller receives rows and per-node outcomes as they happen. */
    public interface Sink {
        void row(Row row);

        void nodeFinished(NodeOutcome outcome);

        /** True once the caller has gone away, so no further broker read is issued. */
        boolean isCancelled();

        /** Sensitive values this sink served in clear so far, by class, for the query's audit record. */
        default Map<String, Long> clearServed() {
            return Map.of();
        }
    }

    public QueryResult execute(UUID clusterId, QueryPlan plan, Sink sink) {
        return execute(clusterId, plan, sink, target -> null);
    }

    public QueryResult execute(UUID clusterId, QueryPlan plan, Sink sink, Function<Target, String> extraSelector) {
        return execute(clusterId, plan, sink, extraSelector, Integer.MAX_VALUE);
    }

    /**
     * As {@link #execute(UUID, QueryPlan, Sink)}, with one extra
     * selector clause per target — the tail's high-water mark (ADR-0058 D7).
     *
     * <p>It is ANDed into the pushed-down selector rather than filtered in Rahdar, so
     * the broker skips what has already been seen. Filtering here instead would mean
     * re-reading an entire queue every few seconds, which is exactly the thing
     * non-negotiable #1 exists to prevent.
     *
     * <p>{@code maxPagesPerTarget} bounds how much of one queue a single call reads, so a tail
     * indexing a deep backlog walks it over several ticks rather than in one (message-index spec).
     */
    public QueryResult execute(
            UUID clusterId, QueryPlan plan, Sink sink, Function<Target, String> extraSelector, int maxPagesPerTarget) {
        return new Run(clusterId, plan, sink, extraSelector, maxPagesPerTarget).execute();
    }

    /**
     * One query's execution: the bounds it is measured against, and what accumulates while
     * its nodes are read.
     */
    private final class Run {

        private final UUID clusterId;
        private final QueryPlan plan;
        private final Sink sink;
        private final Function<Target, String> extraSelector;
        private final int maxPagesPerTarget;
        private final SqlProperties limits = properties;
        private final Split split;
        private final boolean clearAccess;
        private final Map<UUID, ClusterNode> nodesById = new LinkedHashMap<>();

        // Shared across the fan-out: rows and the examined counter are what the
        // bounds are measured against, so every node has to see the same ones.
        private final List<Row> rows = Collections.synchronizedList(new ArrayList<>());
        private final List<NodeOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        private final List<Bound> bounds = Collections.synchronizedList(new ArrayList<>());
        private final List<Notice> notices;
        private final AtomicLong examined = new AtomicLong();
        private final AtomicBoolean truncationSeen = new AtomicBoolean();
        private final long deadline;

        Run(UUID clusterId, QueryPlan plan, Sink sink, Function<Target, String> extraSelector, int maxPagesPerTarget) {
            this.clusterId = clusterId;
            this.plan = plan;
            this.sink = sink;
            this.extraSelector = extraSelector;
            this.maxPagesPerTarget = maxPagesPerTarget;
            this.split = planner.splitOf(plan.ast());
            // Resolved here, on the caller's thread: the per-node threads below carry no security context.
            // A tail poll has none either, so it evaluates over masked content — the safe side.
            this.clearAccess = governance.clearAccess(clusterId, plan.ast().queuePattern());
            nodes.nodes(clusterId).forEach(n -> nodesById.put(n.getId(), n));
            this.notices = new ArrayList<>(plan.notices());
            this.deadline = System.nanoTime() + limits.timeout().toNanos();
        }

        QueryResult execute() {
            // Grouped by node, and one virtual thread per node. Within a node the rate
            // limiter serialises the calls anyway, so parallelism there would buy
            // nothing; across nodes it is the difference between a wildcard query over a
            // cluster finishing inside the timeout and not.
            Map<UUID, List<Target>> byNode = new LinkedHashMap<>();
            plan.targets()
                    .forEach(target -> byNode.computeIfAbsent(target.nodeId(), k -> new ArrayList<>())
                            .add(target));

            try (var scope = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> running = byNode.values().stream()
                        .<Future<?>>map(nodeTargets -> scope.submit(() -> readNode(nodeTargets)))
                        .toList();
                awaitAll(running);
            }

            if (truncationSeen.get() && split.requiresScan()) {
                // A body predicate over a truncated body can be a false negative, and a
                // false negative during an incident reads as "the message is not there".
                notices.add(new Notice(
                        Notice.Kind.BODY_TRUNCATED,
                        "Some bodies were truncated by the broker's management-message-attribute-size-limit,"
                                + " so a body predicate may have missed matches. A Core connection returns full"
                                + " bodies."));
            }
            // Nodes race each other to the row limit, so the result can overshoot it by
            // less than a page. Trim to exactly what was asked for, and report the bound
            // whenever the limit was actually reached — stopping at the limit and not
            // saying so is how a partial result gets read as a complete one.
            List<Row> capped = new ArrayList<>(rows);
            if (capped.size() >= plan.effectiveLimit()) {
                capped = capped.subList(0, plan.effectiveLimit());
                recordBound(new Bound(Bound.Kind.ROW_LIMIT, plan.effectiveLimit()));
            }
            return new QueryResult(
                    List.copyOf(capped), List.copyOf(outcomes), List.copyOf(bounds), List.copyOf(notices));
        }

        private void awaitAll(List<Future<?>> running) {
            for (Future<?> future : running) {
                try {
                    future.get();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    recordBound(new Bound(Bound.Kind.TIMEOUT, limits.timeout().toSeconds()));
                    return;
                } catch (ExecutionException e) {
                    log.debug("SQL console fan-out task failed", e.getCause());
                }
            }
        }

        /** One node's targets, in order, on that node's own thread. */
        private void readNode(List<Target> nodeTargets) {
            for (Target target : nodeTargets) {
                if (sink.isCancelled()) {
                    return;
                }
                Bound stop = boundReached();
                if (stop != null) {
                    recordBound(stop);
                    finished(new NodeOutcome(
                            target.nodeId(),
                            target.nodeName(),
                            target.queueName(),
                            NodeOutcome.Status.NOT_READ,
                            0,
                            0,
                            "Stopped before this queue was read: " + describe(stop) + "."));
                } else if (nodesById.containsKey(target.nodeId())) {
                    finished(readTarget(nodesById.get(target.nodeId()), target));
                }
            }
        }

        private void finished(NodeOutcome outcome) {
            outcomes.add(outcome);
            sink.nodeFinished(outcome);
        }

        // ---- one target ----------------------------------------------------

        private NodeOutcome readTarget(ClusterNode node, Target target) {
            // The selector is re-rendered per node, because a relative window means
            // something different on a node whose clock is measurably offset (ADR-0053).
            String selector =
                    and(planner.selectorFor(split.pushdown(), target.brokerNow()), extraSelector.apply(target));
            TargetRead read = new TargetRead(
                    node,
                    target,
                    selector,
                    residuals.compile(
                            split.scan(),
                            new EvalContext(
                                    target.queueName(), target.address(), target.nodeName(), target.brokerNow())));
            int page = 1;
            boolean more = true;
            while (more) {
                if (sink.isCancelled()) {
                    return read.outcome(NodeOutcome.Status.NOT_READ, "The query was abandoned.");
                }
                Bound stop = boundReached();
                if (stop != null) {
                    recordBound(stop);
                    more = false;
                } else {
                    Fetched fetched = fetch(read, page);
                    if (fetched.failure() != null) {
                        return fetched.failure();
                    }
                    List<BrowsedMessage> batch = fetched.page().messages();
                    scan(read, batch);
                    more = batch.size() >= PAGE_SIZE && rows.size() < plan.effectiveLimit() && page < maxPagesPerTarget;
                    page++;
                }
            }
            return read.outcome(NodeOutcome.Status.ANSWERED, null);
        }

        /** A page of one target, or the outcome that ends the target because it could not be read. */
        private record Fetched(BrowsePage page, NodeOutcome failure) {}

        private Fetched fetch(TargetRead read, int page) {
            try {
                // Every read waits for the node's ceiling first (ADR-0076).
                return new Fetched(
                        messages.browse(
                                clusterId, read.node.getId(), read.target.queueName(), page, PAGE_SIZE, read.selector),
                        null);
            } catch (BrokerConnectionException e) {
                return new Fetched(null, read.outcome(NodeOutcome.Status.FAILED, e.getMessage()));
            } catch (RuntimeException e) {
                log.debug("SQL console browse of {} failed", read.target.queueName(), e);
                return new Fetched(
                        null,
                        read.outcome(
                                NodeOutcome.Status.FAILED,
                                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }

        private void scan(TargetRead read, List<BrowsedMessage> messages) {
            for (BrowsedMessage message : messages) {
                read.examinedHere++;
                examined.incrementAndGet();
                if (message.bodyTruncated()) {
                    truncationSeen.set(true);
                }
                // A body search over the raw body would confirm a value the caller only ever sees masked.
                BrowsedMessage evaluated = split.scan() == null || clearAccess
                        ? message
                        : governance.forEvaluation(clusterId, read.target.address(), message);
                if (read.residual.test(evaluated)) {
                    read.matched++;
                    Row row = toRow(message, read.node, read.target);
                    rows.add(row);
                    sink.row(row);
                    if (rows.size() >= plan.effectiveLimit()) {
                        break;
                    }
                }
            }
        }

        // ---- bounds ---------------------------------------------------------

        /** One entry per kind; a bound reached on three nodes is still one bound. */
        private void recordBound(Bound bound) {
            synchronized (bounds) {
                if (bounds.stream().noneMatch(b -> b.kind() == bound.kind())) {
                    bounds.add(bound);
                }
            }
        }

        private Bound boundReached() {
            if (System.nanoTime() > deadline) {
                return new Bound(Bound.Kind.TIMEOUT, limits.timeout().toSeconds());
            }
            if (examined.get() >= limits.scanCap()) {
                return new Bound(Bound.Kind.SCAN_CAP, limits.scanCap());
            }
            if (rows.size() >= limits.maxRows()) {
                return new Bound(Bound.Kind.ROW_LIMIT, limits.maxRows());
            }
            return null;
        }

        /** Both clauses, parenthesised, so operator precedence cannot change either one's meaning. */
        private String and(String selector, String extra) {
            if (extra == null || extra.isBlank()) {
                return selector;
            }
            if (selector == null || selector.isBlank()) {
                return extra;
            }
            return "(" + selector + ") AND (" + extra + ")";
        }

        private String describe(Bound bound) {
            return switch (bound.kind()) {
                case SCAN_CAP -> "the scan cap of " + bound.value() + " messages was reached";
                case ROW_LIMIT -> "the row limit of " + bound.value() + " was reached";
                case TIMEOUT -> "the query timed out after " + bound.value() + "s";
                case TARGET_CAP -> "the target cap of " + bound.value() + " queues was reached";
            };
        }

        private Row toRow(BrowsedMessage message, ClusterNode node, Target target) {
            Map<String, Object> merged = new HashMap<>();
            merged.putAll(message.stringProperties());
            merged.putAll(message.intProperties());
            merged.putAll(message.longProperties());
            merged.putAll(message.doubleProperties());
            merged.putAll(message.booleanProperties());
            return new Row(
                    node.getId(),
                    target.nodeName(),
                    target.queueName(),
                    target.address(),
                    message.messageId(),
                    message.type(),
                    message.durable(),
                    message.priority(),
                    message.timestamp(),
                    message.expiration(),
                    message.size(),
                    message.contentType(),
                    message.correlationId(),
                    message.groupId(),
                    message.userId(),
                    message.replyTo(),
                    message.body(),
                    message.bodyTruncated(),
                    message.bodyEncoding() == BodyEncoding.BASE64,
                    message.bodyCompression().apiName(),
                    Map.copyOf(merged),
                    Source.BROKER,
                    null,
                    null,
                    null,
                    null);
        }
    }

    /** Where one target's read has got to: what it is reading with, and what it has seen so far. */
    private static final class TargetRead {
        private final ClusterNode node;
        private final Target target;
        private final String selector;
        private final Predicate<BrowsedMessage> residual;
        private long matched;
        private long examinedHere;

        TargetRead(ClusterNode node, Target target, String selector, Predicate<BrowsedMessage> residual) {
            this.node = node;
            this.target = target;
            this.selector = selector;
            this.residual = residual;
        }

        NodeOutcome outcome(NodeOutcome.Status status, String detail) {
            return new NodeOutcome(
                    node.getId(), target.nodeName(), target.queueName(), status, examinedHere, matched, detail);
        }
    }

    /** A sink that only collects, for callers that want the whole result at once. */
    public static final class CollectingSink implements Sink {
        private final ConcurrentLinkedQueue<Row> rows = new ConcurrentLinkedQueue<>();
        private volatile boolean cancelled;

        @Override
        public void row(Row row) {
            rows.add(row);
        }

        @Override
        public void nodeFinished(NodeOutcome outcome) {
            // Collected from the returned QueryResult instead.
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            cancelled = true;
        }

        public Optional<Row> first() {
            return Optional.ofNullable(rows.peek());
        }
    }
}
