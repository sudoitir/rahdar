package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The addresses a capture subscription covers — the one answer the reconciler, the loss
 * measurement, the footprint, deletion and retention all use.
 *
 * <p>Captured rows are stored under the address they were routed to (ADR-0062 D2), not under
 * the names of the queues bound to it. Matching them by queue name made every multicast
 * address, and every queue named differently from its address, invisible to footprint,
 * deletion and retention, and counted every message on it as lost.
 *
 * <p>Rahdar's own capture addresses are never covered (ADR-0079): a pattern that would match
 * them would tap the taps and grow the broker's objects on every pass.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CaptureAddresses {

    private final QueryPlanner planner;
    private final SqlQueryParser parser;

    /**
     * Resolved through the same planner an operator's own query uses, so "which queues does
     * this pattern mean" has exactly one answer in the product. One address however many queues
     * are bound to it: a divert copies at address routing (D3).
     */
    public Set<String> of(UUID clusterId, MessageIndexSubscriptionEntity subscription) {
        return targets(clusterId, subscription).stream()
                .map(CaptureAddresses::addressOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The queues the pattern matches, Rahdar's own capture objects left out; empty when it does not resolve. */
    public List<QueryPlan.Target> targets(UUID clusterId, MessageIndexSubscriptionEntity subscription) {
        QueryPlan plan;
        try {
            plan = planner.plan(
                    clusterId, parser.parse("SELECT * FROM broker.\"" + subscription.getQueuePattern() + '"'));
        } catch (RuntimeException e) {
            log.debug("Capture pattern '{}' could not be resolved: {}", subscription.getQueuePattern(), e.getMessage());
            return List.of();
        }
        return plan.targets().stream()
                .filter(t -> !isCaptureObject(addressOf(t)))
                .toList();
    }

    /** The address a target's messages are routed to, and so captured under. */
    public static String addressOf(QueryPlan.Target target) {
        return target.address() == null ? target.queueName() : target.address();
    }

    /** Whether a name belongs to Rahdar's own capture objects, which no capture may cover. */
    public static boolean isCaptureObject(String name) {
        // A plugin's tap is Rahdar's own object too, and a capture of it would copy a copy.
        return RahdarNames.isRahdarTap(name);
    }
}
