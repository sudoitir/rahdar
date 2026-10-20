package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.rahdar.feature.sql.PredicateSplitter.Split;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Source;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Notice;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Target;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetRegistry.ClockOffset;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator.QueueLocation;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a validated {@link QueryAst} into a {@link QueryPlan} (ADR-0058 D6).
 *
 * <p>Planning contacts no broker. Targets come from {@code subscription_snapshot}, the cost
 * estimate from the message counts already in it, and the per-node "now" from the
 * measured clock offsets. That is what lets the console state the cost of a query
 * while the operator is still typing — the only moment at which stating it is
 * useful.
 */
@Component
public class QueryPlanner {

    private final QueueSnapshots snapshots;
    private final QueueLocator locator;
    private final ClusterDirectory nodes;
    private final PredicateSplitter splitter;
    private final SelectorRenderer selectors;
    private final ClockOffsetService clocks;
    private final SqlProperties properties;
    private final MessageIndexCoverage coverage;
    private final Clock clock;

    /** Two constructors, so the container is told which one is the injectable one. */
    @Autowired
    public QueryPlanner(
            QueueSnapshots snapshots,
            QueueLocator locator,
            ClusterDirectory nodes,
            PredicateSplitter splitter,
            SelectorRenderer selectors,
            ClockOffsetService clocks,
            SqlProperties properties,
            MessageIndexCoverage coverage) {
        this(snapshots, locator, nodes, splitter, selectors, clocks, properties, coverage, Clock.systemUTC());
    }

    QueryPlanner(
            QueueSnapshots snapshots,
            QueueLocator locator,
            ClusterDirectory nodes,
            PredicateSplitter splitter,
            SelectorRenderer selectors,
            ClockOffsetService clocks,
            SqlProperties properties,
            MessageIndexCoverage coverage,
            Clock clock) {
        this.snapshots = snapshots;
        this.locator = locator;
        this.nodes = nodes;
        this.splitter = splitter;
        this.selectors = selectors;
        this.clocks = clocks;
        this.properties = properties;
        this.coverage = coverage;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public QueryPlan plan(UUID clusterId, QueryAst ast) {
        Split split = splitter.split(ast.where());
        List<Notice> notices = new ArrayList<>();

        List<Target> targets = resolveTargets(clusterId, ast, split, notices);
        Source source = resolveSource(clusterId, ast, targets);
        rejectIndexOnlyColumnsOnBroker(ast, source);

        String selector = renderSelector(split.pushdown(), targets);
        int limit = effectiveLimit(ast);
        long estimate = estimate(targets, split, limit);

        if (source == Source.INDEX) {
            notices.addAll(coverage.check(clusterId, ast, targets));
        }

        return new QueryPlan(
                ast,
                source,
                targets,
                selector,
                split.requiresScan(),
                describe(split.pushdown()),
                describe(split.scan()),
                estimate,
                limit,
                source == Source.INDEX && coverage.isCaptured(clusterId, targets),
                List.copyOf(notices));
    }

    /**
     * Refuses a plan whose estimate is over the ceiling. Called before execution and
     * never for a plan-only request, so the console can show an over-budget estimate
     * while the operator is still in a position to narrow it.
     */
    public void enforceCostCeiling(QueryPlan plan) {
        if (plan.resolvedSource() != Source.BROKER) {
            // The index answers from one indexed statement; the ceiling exists to
            // protect brokers, not Postgres, which has its own statement timeout.
            return;
        }
        long ceiling = properties.costCeiling();
        if (plan.estimatedMessagesExamined() > ceiling) {
            throw new CostRefusedException(plan.estimatedMessagesExamined(), ceiling, narrowingHint(plan));
        }
    }

    private String narrowingHint(QueryPlan plan) {
        if (plan.requiresScan() && plan.targets().size() > 1) {
            return "Name one queue instead of a wildcard, or add a header or property predicate "
                    + "the broker can filter on before the body has to be read.";
        }
        if (plan.requiresScan()) {
            return "Add a header or property predicate the broker can filter on, or a narrower "
                    + "time window, so fewer messages have to be read.";
        }
        return "Narrow the FROM pattern, or add a LIMIT.";
    }

    // ---- targets --------------------------------------------------------

    /**
     * Separates the two halves of a de-duplication key. A NUL cannot appear in an
     * Artemis node id or queue name, so no pair of distinct targets can collide by
     * their concatenation — which a printable separator could not promise.
     */
    private static final String SEPARATOR = "\u0000";

    private List<Target> resolveTargets(UUID clusterId, QueryAst ast, Split split, List<Notice> notices) {
        Map<UUID, ClusterNode> nodesById = new LinkedHashMap<>();
        nodes.nodes(clusterId).forEach(n -> nodesById.put(n.getId(), n));

        List<QueueLocation> matched = snapshots.forCluster(clusterId).stream()
                .filter(s -> QueueNamePattern.matches(ast.queuePattern(), s.queueName()))
                .map(s -> new QueueLocation(s.nodeId(), s.queueName(), s.address(), s.routingType(), s.messageCount()))
                .toList();
        if (matched.isEmpty() && !QueueNamePattern.isPattern(ast.queuePattern())) {
            // A queue named outright that the scrape has not reached yet — created a moment
            // ago — is looked up live, rather than reported as not existing.
            matched = locator.locate(clusterId, ast.queuePattern());
        }

        if (matched.isEmpty()) {
            // An unmatched pattern is not an empty result. The difference between
            // "there is no such queue" and "that queue holds nothing" is the whole
            // answer to the question the operator asked.
            notices.add(new Notice(
                    Notice.Kind.NO_QUEUE_MATCHED, "No queue on this cluster matches " + ast.queuePattern() + "."));
            return List.of();
        }

        // A primary and its synced backup share an Artemis node id and are one
        // logical node; subscription_snapshot holds a row per endpoint. Without this a
        // paired cluster returns every matching message twice.
        Set<String> seen = new HashSet<>();
        List<Target> targets = new ArrayList<>();
        for (QueueLocation location : matched) {
            ClusterNode node = nodesById.get(location.nodeId());
            if (node != null && seen.add(logicalKey(node) + SEPARATOR + location.queueName())) {
                Optional<ClockOffset> offset = clocks.offsetFor(node.getId());
                Instant brokerNow = clock.instant()
                        .plusMillis(offset.map(ClockOffset::offsetMs).orElse(0L));
                targets.add(new Target(
                        node.getId(),
                        node.getName(),
                        location.queueName(),
                        location.address(),
                        location.routingType(),
                        location.messageCount(),
                        brokerNow,
                        offset.isPresent()));
            }
        }

        List<Target> filtered = targets.stream()
                .filter(t -> split.target() == null || TargetPredicateEvaluator.matches(split.target(), t))
                .sorted(Comparator.comparing(Target::queueName).thenComparing(Target::nodeName))
                .limit(properties.maxTargets())
                .toList();

        if (targets.size() > filtered.size() && split.target() == null) {
            notices.add(new Notice(
                    Notice.Kind.TARGET_CAPPED,
                    "The pattern matched " + targets.size() + " targets; only the first " + properties.maxTargets()
                            + " were read."));
        }
        noteUnmeasuredClocks(ast, filtered, notices);
        return filtered;
    }

    /**
     * A relative window resolved against an unmeasured clock is a guess. Saying so
     * beats returning nothing and letting the operator conclude the messages are not
     * there (ADR-0053).
     */
    private void noteUnmeasuredClocks(QueryAst ast, List<Target> targets, List<Notice> notices) {
        if (!usesRelativeTime(ast.where())) {
            return;
        }
        String unmeasured = targets.stream()
                .filter(t -> !t.clockOffsetKnown())
                .map(Target::nodeName)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
        if (unmeasured != null) {
            notices.add(new Notice(
                    Notice.Kind.CLOCK_OFFSET_UNKNOWN,
                    "The time window was not normalised for " + unmeasured
                            + " — that node's clock offset has not been measured yet."));
        }
    }

    private static String logicalKey(ClusterNode node) {
        return node.getNativeNodeId() != null ? node.getNativeNodeId() : "id:" + node.getId();
    }

    // ---- source ---------------------------------------------------------

    private Source resolveSource(UUID clusterId, QueryAst ast, List<Target> targets) {
        if (ast.source() != Source.DEFAULT) {
            return ast.source();
        }
        // Only capture active on every target's node makes the index the answer to the plain
        // query; a sampled index is read when the operator names it (ADR-0086).
        return coverage.isCaptured(clusterId, targets) ? Source.INDEX : Source.BROKER;
    }

    /**
     * {@code observedAt} and {@code lastSeenAt} describe an observation, not a
     * message. Against a live broker they would evaluate to null for every row, so
     * the query would silently return nothing — which is exactly the failure this
     * feature exists to stop making.
     */
    private void rejectIndexOnlyColumnsOnBroker(QueryAst ast, Source source) {
        if (source != Source.BROKER) {
            return;
        }
        List<String> indexOnly = new ArrayList<>();
        collectIndexOnly(ast.where(), indexOnly);
        ast.projection().stream().filter(Column::indexOnly).map(Column::sqlName).forEach(indexOnly::add);
        if (!indexOnly.isEmpty()) {
            throw new SqlSyntaxException(
                    "'" + indexOnly.getFirst()
                            + "' describes an indexed observation, so it has no meaning against a live broker."
                            + " Query index.\"...\" instead.",
                    indexOnly.getFirst());
        }
    }

    private void collectIndexOnly(Predicate predicate, List<String> into) {
        if (predicate == null) {
            return;
        }
        switch (predicate) {
            case Predicate.And(var parts) -> parts.forEach(p -> collectIndexOnly(p, into));
            case Predicate.Or(var parts) -> parts.forEach(p -> collectIndexOnly(p, into));
            case Predicate.Not(var inner) -> collectIndexOnly(inner, into);
            case Predicate.Compare compare -> collectTerm(compare.term(), into);
            case Predicate.In in -> collectTerm(in.term(), into);
            case Predicate.IsNull isNull -> collectTerm(isNull.term(), into);
            case Predicate.Like like -> collectTerm(like.term(), into);
            case Predicate.Between between -> collectTerm(between.term(), into);
            // MATCH() reads the stored body, which only the index has.
            case Predicate.Match _ -> into.add("MATCH(body, ...)");
        }
    }

    private void collectTerm(Term term, List<String> into) {
        switch (term) {
            case Term.ColumnTerm(var column) when column.indexOnly() -> into.add(column.sqlName());
            case Term.CaseFold fold -> collectTerm(fold.inner(), into);
            case Term.ColumnTerm _, Term.PropertyTerm _, Term.JsonTerm _ -> {
                // Readable from the broker as well, so they need no index.
            }
            case Term.MatchRank _ -> into.add("match_rank");
        }
    }

    // ---- selector, limit, estimate --------------------------------------

    /**
     * The selector rendered against the first target's clock. The executor re-renders
     * per node when a relative window is involved and the offsets actually differ, so
     * this one is the plan's display value and the common case's real value at once.
     */
    private String renderSelector(Predicate pushdown, List<Target> targets) {
        if (pushdown == null) {
            return null;
        }
        Instant now = targets.isEmpty() ? clock.instant() : targets.getFirst().brokerNow();
        return selectors.render(pushdown, now);
    }

    private int effectiveLimit(QueryAst ast) {
        int cap = properties.maxRows();
        return ast.limit() == null ? cap : Math.min(ast.limit(), cap);
    }

    /**
     * How many messages the query will examine. With no residual predicate the broker
     * filters and stops once the limit is met; with one, every message the broker
     * returns has to be read — which is the entire cost of a body predicate, and the
     * reason the estimate is shown before the query runs.
     */
    private long estimate(List<Target> targets, Split split, int limit) {
        long depth = targets.stream().mapToLong(Target::messageCount).sum();
        if (!split.requiresScan()) {
            return Math.min(depth, (long) limit * Math.max(1, targets.size()));
        }
        return depth;
    }

    private boolean usesRelativeTime(Predicate predicate) {
        if (predicate == null) {
            return false;
        }
        return switch (predicate) {
            case Predicate.And(var parts) -> parts.stream().anyMatch(this::usesRelativeTime);
            case Predicate.Or(var parts) -> parts.stream().anyMatch(this::usesRelativeTime);
            case Predicate.Not(var inner) -> usesRelativeTime(inner);
            case Predicate.Compare compare -> compare.value() instanceof QueryAst.Literal.RelativeTime;
            case Predicate.In in -> in.values().stream().anyMatch(v -> v instanceof QueryAst.Literal.RelativeTime);
            case Predicate.Between between ->
                between.low() instanceof QueryAst.Literal.RelativeTime
                        || between.high() instanceof QueryAst.Literal.RelativeTime;
            case Predicate.IsNull _ -> false;
            case Predicate.Like _ -> false;
            case Predicate.Match _ -> false;
        };
    }

    private List<String> describe(Predicate predicate) {
        if (predicate == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        describeInto(predicate, out);
        return List.copyOf(out);
    }

    private void describeInto(Predicate predicate, List<String> out) {
        if (predicate instanceof Predicate.And(var parts)) {
            parts.forEach(p -> describeInto(p, out));
        } else {
            out.add(PredicateText.of(predicate));
        }
    }

    /** Exposed for the executor, which needs the same split the plan was built from. */
    public Split splitOf(QueryAst ast) {
        return splitter.split(ast.where());
    }

    /** Exposed for the executor: a selector rendered against one node's own clock. */
    public String selectorFor(Predicate pushdown, Instant brokerNow) {
        return pushdown == null ? null : selectors.render(pushdown, brokerNow);
    }

    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
