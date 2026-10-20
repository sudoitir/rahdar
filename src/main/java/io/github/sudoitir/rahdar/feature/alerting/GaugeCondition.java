package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the latest per-{@code (node,queue)} gauge value from {@code subscription_snapshot}
 * (design.md decision 2) — one query per cluster per tick regardless of rule
 * count. A queue present on several nodes without a specific {@code scope.node}
 * is aggregated by summing, keyed {@code queue:<name>}; a node-scoped rule keys
 * {@code node:<nativeNodeId>/queue:<name>} on that node's own value.
 */
@Component
@Order(30)
@RequiredArgsConstructor
public class GaugeCondition implements AlertCondition {

    private static final Map<String, ToLongFunction<QueueSnapshot>> GAUGES = Map.of(
            MetricKeys.BACKLOG.name(), QueueSnapshot::messageCount,
            MetricKeys.CONSUMER_COUNT.name(), QueueSnapshot::consumerCount,
            MetricKeys.IN_FLIGHT.name(), QueueSnapshot::deliveringCount,
            MetricKeys.SCHEDULED.name(), QueueSnapshot::scheduledCount);

    private final QueueSnapshots snapshots;
    private final ObjectMapper mapper;

    @Override
    public boolean supports(AlertRuleSpec rule) {
        return rule.thresholdRule() && GAUGES.containsKey(rule.metric());
    }

    @Override
    public Evaluation evaluate(UUID clusterId, AlertRuleSpec rule) {
        ToLongFunction<QueueSnapshot> value = GAUGES.get(rule.metric());
        if (value == null) {
            return Evaluation.EMPTY;
        }
        AlertScope scope = AlertScope.parse(rule.scope(), mapper);

        boolean nodeScoped = scope.node() != null && !scope.node().isBlank();
        Set<String> universe = new HashSet<>();
        Map<String, Double> subjectValues = new HashMap<>();
        for (QueueSnapshot row : snapshots.forCluster(clusterId)) {
            if (!scope.matchesAddress(row.address())
                    || !scope.matchesQueue(row.queueName())
                    || (nodeScoped && !scope.node().equals(row.nodeId().toString()))) {
                continue;
            }
            String key = nodeScoped ? "node:" + row.nodeId() + "/queue:" + row.queueName() : "queue:" + row.queueName();
            universe.add(key);
            subjectValues.merge(key, (double) value.applyAsLong(row), Double::sum);
        }

        Map<String, Double> active = new HashMap<>();
        subjectValues.forEach((subject, v) -> {
            if (Comparators.test(rule.comparator(), v, rule.threshold())) {
                active.put(subject, v);
            }
        });
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
