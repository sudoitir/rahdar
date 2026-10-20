package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertFiringRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity.Condition;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertStateRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link AlertEvaluator} evaluating a real threshold rule against real
 * {@code subscription_snapshot} rows (design.md decisions 3-4). Exercises the wiring
 * an isolated {@code AlertStateMachineTest} cannot: rule lookup, subject
 * discovery, and the {@code alert_state}/{@code alert_firing} writes.
 */
class AlertEvaluatorIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    AlertEvaluator evaluator;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    AlertRuleRepository rules;

    @Autowired
    AlertStateRepository states;

    @Autowired
    AlertFiringRepository firings;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private UUID clusterId;

    @AfterEach
    void tearDown() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private void snapshot(UUID clusterId, UUID nodeId, String queueName, long messageCount) {
        jdbc.update("""
                INSERT INTO subscription_snapshot (node_id, subscription, cluster_id, destination, kind, backlog)
                VALUES (:n, :q, :c, :q, 'artemis.queue', :mc)
                ON CONFLICT (node_id, subscription) DO UPDATE SET backlog = :mc
                """, Map.of("n", nodeId, "q", queueName, "c", clusterId, "mc", messageCount));
    }

    @Test
    void aSustainedBreachFiresAfterTheDebounceAndResolvesWhenItClears() {
        ClusterEntity cluster = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null));
        clusterId = cluster.getId();
        BrokerNodeEntity node = nodes.save(BrokerNodeEntity.fromSeed(clusterId, "n1", "NONE", "node-1"));

        AlertRuleEntity rule = rules.save(AlertRuleEntity.threshold(
                clusterId, "Deep queue", new Condition("openbroker.backlog", "GT", 100.0), 0, "WARNING", null));

        snapshot(clusterId, node.getId(), "orders", 150);
        evaluator.evaluate(clusterId, "METRIC_THRESHOLD");

        assertThat(states.findByRuleId(rule.getId())).hasSize(1);
        assertThat(states.findByRuleId(rule.getId()).get(0).getState()).isEqualTo("FIRING");
        assertThat(firings.findOpenVisible(clusterId, false, true, List.of(UUID.randomUUID())))
                .hasSize(1);

        snapshot(clusterId, node.getId(), "orders", 10);
        evaluator.evaluate(clusterId, "METRIC_THRESHOLD");

        assertThat(states.findByRuleId(rule.getId())).isEmpty(); // resolved back to OK, row dropped
        assertThat(firings.findOpenVisible(clusterId, false, true, List.of(UUID.randomUUID())))
                .isEmpty();
    }

    @Test
    void aDisabledRuleNeverEvaluates() {
        ClusterEntity cluster = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null));
        clusterId = cluster.getId();
        BrokerNodeEntity node = nodes.save(BrokerNodeEntity.fromSeed(clusterId, "n1", "NONE", "node-1"));

        AlertRuleEntity rule = rules.save(AlertRuleEntity.threshold(
                clusterId, "Deep queue", new Condition("openbroker.backlog", "GT", 100.0), 0, "WARNING", null));
        rule.setEnabled(false);
        rules.save(rule);

        snapshot(clusterId, node.getId(), "orders", 150);
        evaluator.evaluate(clusterId, "METRIC_THRESHOLD");

        assertThat(states.findByRuleId(rule.getId())).isEmpty();
    }
}
