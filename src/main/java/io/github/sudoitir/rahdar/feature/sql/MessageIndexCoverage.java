package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.QueryAst.Literal;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Term;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Notice;
import io.github.sudoitir.rahdar.feature.sql.QueryPlan.Target;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageCaptureNodeEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageCaptureNodeRepository;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers what the index can and cannot speak for (ADR-0059 D3).
 *
 * <p>The failure this exists to prevent is the quiet one: a query that reaches
 * before a subscription started capturing, or names a queue nothing captures,
 * returning a short list that the operator reads as the answer. Every gap here
 * becomes a {@link Notice} on the plan, so the shortfall is stated rather than
 * inferred.
 */
@Component
@RequiredArgsConstructor
public class MessageIndexCoverage {

    private final MessageIndexSubscriptionRepository subscriptions;
    private final MessageCaptureNodeRepository captureNodes;
    private final QueueSnapshots snapshots;

    /**
     * Whether every target is being captured on the node it sits on, which is what lets a query
     * with no source qualifier read the index (ADR-0086). Per node and
     * per target, because that is the granularity capture actually has: one node
     * refusing the tap makes the whole result partly sampled, and averaging over
     * nodes would let the console make a claim that is false for one of them.
     */
    @Transactional(readOnly = true)
    public boolean isCaptured(UUID clusterId, List<Target> targets) {
        if (targets.isEmpty()) {
            return false;
        }
        List<MessageIndexSubscriptionEntity> capturing = subscriptions.findByClusterId(clusterId).stream()
                .filter(MessageIndexSubscriptionEntity::isEnabled)
                .filter(s -> s.getMode() == CaptureMode.CAPTURE)
                .toList();
        return targets.stream()
                .allMatch(target -> capturing.stream()
                        .filter(s -> QueueNamePattern.matches(s.getSubscriptionPattern(), target.subscription()))
                        .anyMatch(s -> captureNodes
                                .findBySubscriptionIdAndNodeId(s.getId(), target.nodeId())
                                .map(MessageCaptureNodeEntity::getCaptureState)
                                .filter(state -> state == CaptureState.ACTIVE)
                                .isPresent()));
    }

    /** Every way this query reaches outside what the index holds. */
    @Transactional(readOnly = true)
    public List<Notice> check(UUID clusterId, QueryAst ast, List<Target> targets) {
        List<MessageIndexSubscriptionEntity> enabled = subscriptions.findByClusterId(clusterId).stream()
                .filter(MessageIndexSubscriptionEntity::isEnabled)
                .toList();
        List<Notice> notices = new ArrayList<>();

        List<String> uncovered = targets.stream()
                .map(Target::subscription)
                .distinct()
                .filter(queue ->
                        enabled.stream().noneMatch(s -> QueueNamePattern.matches(s.getSubscriptionPattern(), queue)))
                .toList();
        if (!uncovered.isEmpty()) {
            notices.add(new Notice(
                    Notice.Kind.INDEX_COVERAGE_GAP,
                    "The index holds nothing for " + String.join(", ", uncovered) + " — no subscription captures "
                            + (uncovered.size() == 1 ? "it." : "them.")));
        }

        if (!enabled.isEmpty()) {
            Instant earliestCapture = enabled.stream()
                    .map(MessageIndexSubscriptionEntity::getCaptureFrom)
                    .min(Instant::compareTo)
                    .orElseThrow();
            Instant retentionFloor = enabled.stream()
                    .map(s -> Instant.now().minus(Duration.ofDays(s.getRetentionDays())))
                    .min(Instant::compareTo)
                    .orElseThrow();
            Instant floor = earliestCapture.isAfter(retentionFloor) ? earliestCapture : retentionFloor;
            var window = reach(ast.where());
            if (window.isEmpty()) {
                // Capture never backfills: a message already on the queue when it began is not
                // in the index, and a query with no window asks for it too.
                notices.add(new Notice(
                        Notice.Kind.INDEX_COVERAGE_GAP,
                        "The index holds nothing routed before " + floor
                                + ": a message already on the queue then is not in this result."
                                + " Query broker.\"...\" for what is on the queue now."));
            } else if (Instant.now().minus(window.get()).isBefore(floor)) {
                notices.add(
                        new Notice(
                                Notice.Kind.INDEX_COVERAGE_GAP,
                                "The index has no coverage before " + floor
                                        + " — the window asked for reaches further back than capture began or retention keeps."));
            }
        }
        notices.addAll(captureNotices(clusterId, enabled, targets));
        return List.copyOf(notices);
    }

    /**
     * The two things a captured result cannot say for itself.
     *
     * <p><b>Per node.</b> Capture is installed node by node, and a node that refused
     * it, or was promoted after a failover and not yet reached, contributes nothing.
     * Rolling that up into one "indexed" answer would report a partial result as a
     * complete one, so the nodes it cannot speak for are named (ADR-0062 D4).
     *
     * <p><b>Per address.</b> A divert copies at address routing, before multicast
     * fan-out, so for an address with more than one bound queue the index holds one
     * row per routed message and genuinely cannot attribute it to a subscription
     * queue. Anycast is unaffected: address and queue coincide.
     */
    private List<Notice> captureNotices(
            UUID clusterId, List<MessageIndexSubscriptionEntity> enabled, List<Target> targets) {
        List<MessageIndexSubscriptionEntity> capturing =
                enabled.stream().filter(s -> s.getMode() == CaptureMode.CAPTURE).toList();
        if (capturing.isEmpty() || targets.isEmpty()) {
            return List.of();
        }
        List<Notice> notices = new ArrayList<>();

        List<String> uncoveredNodes = new ArrayList<>();
        for (Target target : targets) {
            boolean covered = capturing.stream()
                    .filter(s -> QueueNamePattern.matches(s.getSubscriptionPattern(), target.subscription()))
                    .anyMatch(s -> captureNodes
                            .findBySubscriptionIdAndNodeId(s.getId(), target.nodeId())
                            .map(MessageCaptureNodeEntity::getCaptureState)
                            .filter(state -> state == CaptureState.ACTIVE)
                            .isPresent());
            if (!covered && !uncoveredNodes.contains(target.nodeName())) {
                uncoveredNodes.add(target.nodeName());
            }
        }
        if (!uncoveredNodes.isEmpty()) {
            notices.add(new Notice(
                    Notice.Kind.CAPTURE_NODE_GAP,
                    "Not capturing on " + String.join(", ", uncoveredNodes)
                            + ", so this result is complete only for the nodes that are."));
        }

        List<String> fannedOut = targets.stream()
                .map(Target::destination)
                .distinct()
                .filter(address -> address != null && boundQueueCount(clusterId, address) > 1)
                .toList();
        if (!fannedOut.isEmpty()) {
            notices.add(new Notice(
                    Notice.Kind.DESTINATION_SCOPED_CAPTURE,
                    String.join(", ", fannedOut) + (fannedOut.size() == 1 ? " has" : " have")
                            + " more than one queue bound."));
        }
        return notices;
    }

    private long boundQueueCount(UUID clusterId, String address) {
        return snapshots.forCluster(clusterId).stream()
                .filter(row -> address.equals(row.address()))
                .map(QueueSnapshot::queueName)
                .distinct()
                .count();
    }

    /** How far back a relative time predicate asks the index to look, if it does. */
    private Optional<Duration> reach(Predicate predicate) {
        if (predicate == null) {
            return Optional.empty();
        }
        return switch (predicate) {
            case Predicate.And(var parts) -> widest(parts);
            case Predicate.Or(var parts) -> widest(parts);
            case Predicate.Not(var inner) -> reach(inner);
            case Predicate.Compare compare ->
                isTimeTerm(compare.term()) && compare.value() instanceof Literal.RelativeTime(var before)
                        ? Optional.of(before)
                        : Optional.empty();
            case Predicate.Between between ->
                isTimeTerm(between.term()) && between.low() instanceof Literal.RelativeTime(var before)
                        ? Optional.of(before)
                        : Optional.empty();
            case Predicate.In _ -> Optional.empty();
            case Predicate.IsNull _ -> Optional.empty();
            case Predicate.Like _ -> Optional.empty();
            case Predicate.Match _ -> Optional.empty();
        };
    }

    private Optional<Duration> widest(List<Predicate> parts) {
        return parts.stream().map(this::reach).flatMap(Optional::stream).max(Duration::compareTo);
    }

    private boolean isTimeTerm(Term term) {
        return term instanceof Term.ColumnTerm(var column) && column.type() == ColumnCatalogue.Type.TIMESTAMP;
    }
}
