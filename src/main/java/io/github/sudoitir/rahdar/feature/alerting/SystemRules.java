package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import java.util.Map;
import java.util.Set;

/**
 * Which rules a system's cluster can be watched by. Split-brain, replication, clock skew, node down, configuration
 * drift, setup risk and the slow-consumer metric read what only Artemis reports (live/backup pairs, the Jolokia
 * response clock, management URLs per node, {@code broker.xml}); a Kafka or RabbitMQ cluster is watched through its
 * own health, which its scrape judges, and through the backlog metrics every system records.
 */
final class SystemRules {

    static final String ARTEMIS = "artemis";

    private static final Set<String> ARTEMIS_STATES =
            Set.of("SPLIT_BRAIN", "NODE_DOWN", "REPLICATION_BEHIND", "CLOCK_SKEW", "CONFIG_DRIFT", "SETUP_RISK");

    private static final Set<String> ARTEMIS_METRICS = Set.of(SlowConsumerCondition.METRIC);

    private static final Map<String, String> NAMES = Map.of("kafka", "Kafka", "rabbitmq", "RabbitMQ");

    private SystemRules() {}

    /** Whether a rule reads something only an Artemis cluster reports. */
    static boolean artemisOnly(AlertRuleEntity rule) {
        return rule.isThreshold()
                ? ARTEMIS_METRICS.contains(rule.getMetric())
                : ARTEMIS_STATES.contains(rule.getStateCondition());
    }

    /** Refuses a rule the cluster's system cannot answer, naming the system and what to watch instead. */
    static void requireOn(String system, AlertRuleEntity rule) {
        if (system == null || ARTEMIS.equals(system) || !artemisOnly(rule)) {
            return;
        }
        String name = NAMES.getOrDefault(system, system);
        String what = rule.isThreshold() ? rule.getMetric() : rule.getStateCondition();
        throw new ConflictException(
                "unsupported-on-system",
                what + " reads what only Artemis reports, so a " + name + " cluster cannot be watched by it."
                        + " Watch CLUSTER_DEGRADED, which " + name + "'s own health answers, or a backlog metric.");
    }
}
