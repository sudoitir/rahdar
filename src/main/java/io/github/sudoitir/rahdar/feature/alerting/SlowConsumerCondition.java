package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.rahdar.platform.scrape.MetricSamples;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.platform.scrape.ScrapeProperties;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Rahdar's own slow-consumer derivation (ADR-0044) — the fallback for brokers where
 * native {@code slow-consumer-threshold} detection is not configured. The broker's
 * own slow-consumer notification is the truth source when it is.
 *
 * <p>The definition that matters is a triple, not a rate: <b>consumers attached,
 * backlog present, acknowledgements near zero</b>. A plain {@code openbroker.acknowledged}
 * rate rule pages on every quiet queue at 3am; a queue with no consumers is not a
 * slow consumer, and a queue with no backlog and a zero ack rate is simply idle.
 * A <b>paused</b> queue satisfies all three and is operationally expected, so it is
 * excluded rather than left to fire (the broker reports {@code paused} on every
 * {@code listQueues} row, so this costs no extra call).
 *
 * <p>The rate comes from {@link MetricSamples#latestRateBySubject}, reused
 * rather than reimplemented, so ADR-0033's restart-safe never-negative clamp applies
 * and a broker restart resetting the monotonic counter cannot produce a firing. A
 * subject with fewer than two samples in the window is absent from the evaluation,
 * not zero — matching {@link RateCondition}.
 *
 * <p><b>Known limitation, stated in the UI rather than papered over:</b>
 * {@code listAllConsumersAsJSON} carries no per-consumer acknowledgement counter, so
 * this resolves to {@code (node, queue)} and never to an individual consumer.
 * Attribution to a named consumer comes only from the broker's own notification.
 * The rate itself is per queue name across the cluster (that is the grain
 * {@code metric_sample} records); in an HA pair only one endpoint serves a queue, so
 * that is the serving node's rate.
 */
@Component
@Order(20)
@RequiredArgsConstructor
public class SlowConsumerCondition implements AlertCondition {

    /** The derived metric's name. Free text in {@code alert_rule.metric} — no migration. */
    public static final String METRIC = "ackRatePerConsumer";

    private static final String ACK_METRIC = MetricKeys.ACKNOWLEDGED.name();

    private final QueueSnapshots snapshots;
    private final MetricSamples series;
    private final ScrapeProperties properties;
    private final ObjectMapper mapper;

    @Override
    public boolean supports(AlertRuleSpec rule) {
        return rule.thresholdRule() && METRIC.equals(rule.metric());
    }

    @Override
    public Evaluation evaluate(UUID clusterId, AlertRuleSpec rule) {
        if (!supports(rule)) {
            return Evaluation.EMPTY;
        }
        AlertScope scope = AlertScope.parse(rule.scope(), mapper);
        boolean nodeScoped = scope.node() != null && !scope.node().isBlank();

        Instant to = Instant.now();
        Instant from = to.minus(properties.tierBInterval().multipliedBy(2));
        Map<String, Double> ackRateByQueue = series.latestRateBySubject(clusterId, ACK_METRIC, from, to);

        // Consumers attached AND a backlog present AND not paused. Anything else is
        // not a slow consumer, and must not even enter the universe — a subject in
        // the universe with no verdict would resolve a firing that is still true.
        Map<String, Long> consumersBySubject = new HashMap<>();
        Map<String, String> queueNameBySubject = new HashMap<>();
        for (QueueSnapshot row : snapshots.forCluster(clusterId)) {
            if (!hasBacklogWithConsumers(row) || !inScope(row, scope, nodeScoped)) {
                continue;
            }
            String key = nodeScoped
                    ? "node:" + row.nodeId() + "/subscription:" + row.queueName()
                    : "subscription:" + row.queueName();
            consumersBySubject.merge(key, row.consumerCount(), Long::sum);
            queueNameBySubject.put(key, row.queueName());
        }

        return verdicts(rule, consumersBySubject, queueNameBySubject, ackRateByQueue);
    }

    private static boolean hasBacklogWithConsumers(QueueSnapshot row) {
        return row.consumerCount() > 0 && row.messageCount() > 0 && !row.paused();
    }

    private static boolean inScope(QueueSnapshot row, AlertScope scope, boolean nodeScoped) {
        return scope.matchesDestination(row.address())
                && scope.matchesSubscription(row.queueName())
                && (!nodeScoped || scope.node().equals(row.nodeId().toString()));
    }

    private static Evaluation verdicts(
            AlertRuleSpec rule,
            Map<String, Long> consumersBySubject,
            Map<String, String> queueNameBySubject,
            Map<String, Double> ackRateByQueue) {
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (Map.Entry<String, Long> e : consumersBySubject.entrySet()) {
            String subject = e.getKey();
            Double ackRate = ackRateByQueue.get(queueNameBySubject.get(subject));
            if (ackRate == null) {
                // Fewer than two samples in the window: no evaluable value this tick.
                continue;
            }
            double perConsumer = ackRate / e.getValue();
            universe.add(subject);
            if (Comparators.test(rule.comparator(), perConsumer, rule.threshold())) {
                active.put(subject, perConsumer);
            }
        }
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
