package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.ClusterRegistered;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Every new cluster gets its built-in state rules: ordinary, editable, unrouted rows an
 * operator can silence or route (design.md decision 8) — not an unconditional check. An Artemis
 * cluster is watched for split-brain, clock skew, down nodes and a backup behind its primary; a
 * Kafka or RabbitMQ cluster for its own health, which its scrape judges from offline partitions,
 * lost brokers, the controller quorum, or memory and disk alarms.
 */
@Component
@RequiredArgsConstructor
class BuiltinAlertRules {

    private final AlertRuleRepository rules;
    private final ClusterKinds clusterKinds;

    @EventListener
    void onClusterRegistered(ClusterRegistered event) {
        UUID clusterId = event.clusterId();
        if (!SystemRules.ARTEMIS.equals(clusterKinds.systemId(clusterId))) {
            rules.save(AlertRuleEntity.state(clusterId, "Cluster degraded", "CLUSTER_DEGRADED", 60, "CRITICAL"));
            return;
        }
        rules.save(AlertRuleEntity.state(clusterId, "Split-brain", "SPLIT_BRAIN", 0, "CRITICAL"));
        // Warning, not critical: a wrong clock does not stop the brokers, but it does
        // make Rahdar's own deadlines and latencies wrong, so it must not be silent
        // (ADR-0053). An ordinary rule like any other — editable and silenceable.
        rules.save(AlertRuleEntity.state(clusterId, "Clock skew", "CLOCK_SKEW", 0, "WARNING"));
        rules.save(AlertRuleEntity.state(clusterId, "Node down", "NODE_DOWN", 30, "CRITICAL"));
        rules.save(AlertRuleEntity.state(clusterId, "Replication behind", "REPLICATION_BEHIND", 120, "WARNING"));
    }
}
