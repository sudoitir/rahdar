package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.alerting.AlertCondition.Evaluation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link SlowConsumerCondition} against real {@code subscription_snapshot} and
 * {@code metric_sample} rows (ADR-0044). The definition under test is the triple,
 * not the rate — <b>consumers attached, backlog present, acknowledgements near
 * zero</b> — and each guard gets its own case, because dropping any one of them is
 * what turns this rule into the 3am pager.
 *
 * <p>Integration rather than isolated on purpose: {@code subscription_snapshot} is a
 * write-through cache with no setters (writes go through {@code QueueSnapshotUpsert}),
 * the {@code paused} exclusion is only real against the actual column, and the
 * never-negative rate clamp this relies on lives in the SQL of
 * {@code MetricSamples}, not in Java.
 */
class SlowConsumerConditionTest extends PostgresIntegrationTest {

    @Autowired
    SlowConsumerCondition condition;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private UUID clusterId;
    private UUID nodeId;

    private void givenCluster() {
        ClusterEntity cluster = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null));
        clusterId = cluster.getId();
        nodeId = nodes.save(BrokerNodeEntity.fromSeed(clusterId, "n1", "NONE", "node-1"))
                .getId();
    }

    @AfterEach
    void tearDown() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
            clusterId = null;
        }
    }

    private void queue(String name, long consumers, long messages, boolean paused) {
        jdbc.update(
                """
                INSERT INTO subscription_snapshot
                  (node_id, subscription, cluster_id, destination, kind, native_attributes,
                   consumer_count, backlog, paused)
                VALUES (:n, :q, :c, :q, 'artemis.queue', '{"artemis.routing_type":"ANYCAST"}', :cc, :mc, :paused)
                """,
                Map.of(
                        "n", nodeId,
                        "q", name,
                        "c", clusterId,
                        "cc", consumers,
                        "mc", messages,
                        "paused", paused));
    }

    /** Two acknowledgement samples a minute apart, so the window yields a real rate. */
    private void ackSamples(String queue, double first, double second) {
        Instant now = Instant.now();
        sample(queue, first, now.minusSeconds(20));
        sample(queue, second, now.minusSeconds(1));
    }

    private void sample(String queue, double value, Instant at) {
        Map<String, Object> p = new HashMap<>();
        p.put("ts", Timestamp.from(at));
        p.put("value", value);
        p.put("subject", queue);
        p.put("c", clusterId);
        p.put("n", nodeId);
        jdbc.update("""
                INSERT INTO metric_sample (ts, value, subject_kind, subject_path, metric, cluster_id, node_id)
                VALUES (:ts, :value, 'QUEUE', :subject, 'openbroker.acknowledged', :c, :n)
                """, p);
    }

    private static AlertRuleSpec rule(double threshold, String scope) {
        return new AlertRuleSpec(UUID.randomUUID(), true, SlowConsumerCondition.METRIC, "LT", threshold, scope, null);
    }

    @Test
    void consumersButNoBacklogIsIdleNotSlow() {
        givenCluster();
        queue("orders", 3, 0, false);
        ackSamples("orders", 100, 100);

        Evaluation result = condition.evaluate(clusterId, rule(1.0, null));

        assertThat(result.universe()).isEmpty();
        assertThat(result.active()).isEmpty();
    }

    @Test
    void backlogButNoConsumersIsNotASlowConsumer() {
        givenCluster();
        queue("orders", 0, 5_000, false);
        ackSamples("orders", 100, 100);

        assertThat(condition.evaluate(clusterId, rule(1.0, null)).universe()).isEmpty();
    }

    @Test
    void attachedBackloggedAndNotDrainingIsActive() {
        givenCluster();
        queue("orders", 2, 5_000, false);
        ackSamples("orders", 100, 100); // no acknowledgements in the window at all

        Evaluation result = condition.evaluate(clusterId, rule(1.0, null));

        assertThat(result.universe()).containsExactly("queue:orders");
        assertThat(result.active()).containsKey("queue:orders");
        assertThat(result.active().get("queue:orders")).isZero();
    }

    @Test
    void attachedBackloggedAndDrainingIsEvaluatedButNotActive() {
        givenCluster();
        queue("orders", 2, 5_000, false);
        ackSamples("orders", 0, 10_000); // draining hard

        Evaluation result = condition.evaluate(clusterId, rule(1.0, null));

        assertThat(result.universe()).containsExactly("queue:orders");
        assertThat(result.active()).isEmpty();
    }

    @Test
    void aPausedQueueIsExcludedRatherThanLeftToFire() {
        givenCluster();
        // Correctly slow by every measure Rahdar can take, and operationally expected.
        queue("orders", 2, 5_000, true);
        ackSamples("orders", 100, 100);

        assertThat(condition.evaluate(clusterId, rule(1.0, null)).universe()).isEmpty();
    }

    @Test
    void aSubjectWithOneSampleInTheWindowIsAbsentNotZero() {
        givenCluster();
        queue("orders", 2, 5_000, false);
        sample("orders", 100, Instant.now().minusSeconds(1));

        // Absent, not zero: a subject in the universe with no verdict would resolve a
        // firing that is still true.
        Evaluation result = condition.evaluate(clusterId, rule(1.0, null));

        assertThat(result.universe()).isEmpty();
        assertThat(result.active()).isEmpty();
    }

    @Test
    void aCounterResetIsClampedAndCannotProduceANegativeRate() {
        givenCluster();
        queue("orders", 2, 5_000, false);
        // A broker restart: the monotonic counter goes backwards.
        ackSamples("orders", 900_000, 12);

        Evaluation result = condition.evaluate(clusterId, rule(-1.0, null));

        // ADR-0033's GREATEST(..., 0) clamp is reused, not reimplemented, so the rate
        // is never negative and a threshold of "< -1" can never match.
        assertThat(result.universe()).containsExactly("queue:orders");
        assertThat(result.active()).isEmpty();
    }

    @Test
    void nodeScopedRulesKeySubjectsLikeTheGaugeCondition() {
        givenCluster();
        queue("orders", 2, 5_000, false);
        ackSamples("orders", 100, 100);

        Evaluation result = condition.evaluate(clusterId, rule(1.0, "{\"node\":\"" + nodeId + "\"}"));

        assertThat(result.universe()).containsExactly("node:" + nodeId + "/queue:orders");
    }

    @Test
    void anotherMetricIsNotThisConditionsBusiness() {
        givenCluster();
        AlertRuleSpec r = new AlertRuleSpec(UUID.randomUUID(), true, "openbroker.backlog", "LT", 1.0, null, null);

        assertThat(condition.supports(r)).isFalse();
        assertThat(condition.evaluate(clusterId, r)).isEqualTo(Evaluation.EMPTY);
    }
}
