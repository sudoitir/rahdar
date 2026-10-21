package io.github.sudoitir.rahdar.feature.triage;

import io.github.sudoitir.rahdar.feature.alerting.AlertCondition;
import io.github.sudoitir.rahdar.feature.alerting.AlertRuleSpec;
import io.github.sudoitir.rahdar.feature.alerting.AlertScope;
import io.github.sudoitir.rahdar.feature.alerting.Comparators;
import io.github.sudoitir.rahdar.feature.triage.ConsumerHealth.Verdict;
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
 * Alerts on the shared consumer-health verdict (ADR-0089).
 *
 * <p>It lives in {@code feature.triage}, not in {@code feature.alerting}, because triage
 * already depends on alerting and the reverse edge would be a cycle. That is what
 * {@link AlertCondition}'s bean discovery exists for: alerting evaluates this without
 * importing it.
 *
 * <p>The compared value is the verdict's <em>severity rank</em>, so the existing
 * comparator, threshold column, debounce and resolution machinery all work untouched and
 * {@code alert_rule.metric} stays free text — no migration. The rank is an internal
 * encoding; the rule form renders a severity select, so no operator types one.
 *
 * <p>A queue whose verdict is {@link Verdict#INSUFFICIENT_DATA} is left out of the
 * universe entirely rather than reported inactive. A subject present in the universe but
 * absent from {@code active} <em>resolves</em> a firing, and a queue that merely stopped
 * being sampled must not resolve a stall that is still happening — the same rule the
 * other conditions apply to a subject with too few samples.
 */
@Component
@Order(25)
@RequiredArgsConstructor
public class HealthVerdictCondition implements AlertCondition {

    /** The derived metric's name. Free text in {@code alert_rule.metric} — no migration. */
    public static final String METRIC = "consumerHealth";

    private final ConsumerHealthService consumerHealth;
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

        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (ConsumerHealth health : consumerHealth.evaluate(clusterId)) {
            // No verdict this tick is not healthy, and not a resolution either.
            if (!scope.matchesDestination(health.destination())
                    || !scope.matchesSubscription(health.subscription())
                    || !health.verdict().known()) {
                continue;
            }
            String key = "subscription:" + health.subscription();
            universe.add(key);
            double severity = health.verdict().severity();
            if (Comparators.test(rule.comparator(), severity, rule.threshold())) {
                active.put(key, severity);
            }
        }
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }
}
