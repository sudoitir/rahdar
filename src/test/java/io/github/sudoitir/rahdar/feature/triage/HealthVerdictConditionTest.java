package io.github.sudoitir.rahdar.feature.triage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.alerting.AlertCondition.Evaluation;
import io.github.sudoitir.rahdar.feature.alerting.AlertRuleSpec;
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
 * {@link HealthVerdictCondition} — alerting on the shared verdict (ADR-0089).
 *
 * <p>The case that carries the most weight is the unsampled one. A subject in the
 * universe but absent from {@code active} <em>resolves</em> a firing, so a queue that
 * merely stopped being sampled must not appear there at all; getting that wrong would
 * quietly close a stall that is still happening.
 *
 * <p><b>Deliberately runs with no authenticated principal</b>, unlike
 * {@code ConsumerHealthServiceTest}. Alerting evaluates on a scrape thread where there is
 * no user, so a permission check on this path protects nothing and fails as a 404 —
 * "cluster does not exist" about a cluster that plainly does. That is exactly how this
 * broke once, silently, in every scrape tick while the REST path looked fine.
 */
class HealthVerdictConditionTest extends PostgresIntegrationTest {

    @Autowired
    HealthVerdictCondition condition;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private UUID clusterId;
    private UUID nodeId;

    private void givenCluster() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
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

    @Test
    void claimsOnlyItsOwnMetric() {
        assertThat(condition.supports(rule("GTE", 3))).isTrue();
        assertThat(condition.supports(
                        new AlertRuleSpec(UUID.randomUUID(), true, "openbroker.backlog", "GTE", 3.0, null, null)))
                .isFalse();
        // A state rule is not a threshold rule and belongs to StateCondition.
        assertThat(condition.supports(
                        new AlertRuleSpec(UUID.randomUUID(), false, null, null, null, null, "SPLIT_BRAIN")))
                .isFalse();
    }

    @Test
    void aQueueAtTheSelectedSeverityIsActive() {
        givenCluster();
        // No consumers on a backlog: severity 4.
        queue("broken", 0, 5_000, 0, false);
        counterSamples("broken", 100, 200, 100, 100);

        Evaluation result = condition.evaluate(clusterId, rule("GTE", 3));

        assertThat(result.universe()).contains("subscription:broken");
        assertThat(result.active()).containsEntry("subscription:broken", 4.0);
    }

    @Test
    void aHealthyQueueIsInTheUniverseButNotActive() {
        givenCluster();
        queue("fine", 3, 0, 0, false);
        counterSamples("fine", 1_000, 1_100, 1_000, 1_100);

        Evaluation result = condition.evaluate(clusterId, rule("GTE", 3));

        // Present, so a firing on it resolves; not active, so it does not fire.
        assertThat(result.universe()).contains("subscription:fine");
        assertThat(result.active()).doesNotContainKey("subscription:fine");
    }

    @Test
    void aPausedQueueDoesNotPage() {
        givenCluster();
        queue("maintenance", 0, 5_000, 0, true);
        counterSamples("maintenance", 100, 200, 100, 100);

        Evaluation result = condition.evaluate(clusterId, rule("GTE", 3));

        // PAUSED ranks 1: in the universe, below the threshold, so it never fires.
        assertThat(result.universe()).contains("subscription:maintenance");
        assertThat(result.active()).doesNotContainKey("subscription:maintenance");
    }

    @Test
    void anUnsampledQueueNeitherFiresNorResolves() {
        givenCluster();
        queue("new-queue", 1, 10, 0, false);
        // One sample only: no computable rate, so INSUFFICIENT_DATA.
        sample("new-queue", "openbroker.acknowledged", 5, Instant.now().minusSeconds(5));

        Evaluation result = condition.evaluate(clusterId, rule("GTE", 3));

        assertThat(result.universe()).doesNotContain("subscription:new-queue");
        assertThat(result.active()).doesNotContainKey("subscription:new-queue");
    }

    // ---- helpers ----------------------------------------------------------

    private static AlertRuleSpec rule(String comparator, double threshold) {
        return new AlertRuleSpec(
                UUID.randomUUID(), true, HealthVerdictCondition.METRIC, comparator, threshold, null, null);
    }

    private void queue(String name, long consumers, long messages, long delivering, boolean paused) {
        jdbc.update(
                """
                INSERT INTO subscription_snapshot
                  (node_id, subscription, cluster_id, destination, kind, native_attributes,
                   consumer_count, backlog, in_flight, paused)
                VALUES (:n, :q, :c, :q, 'artemis.queue', '{"artemis.routing_type":"ANYCAST"}', :cc, :mc, :dc, :paused)
                """,
                Map.of(
                        "n", nodeId,
                        "q", name,
                        "c", clusterId,
                        "cc", consumers,
                        "mc", messages,
                        "dc", delivering,
                        "paused", paused));
    }

    private void counterSamples(String queue, double addFirst, double addSecond, double ackFirst, double ackSecond) {
        Instant now = Instant.now();
        sample(queue, "openbroker.enqueued", addFirst, now.minusSeconds(20));
        sample(queue, "openbroker.enqueued", addSecond, now.minusSeconds(1));
        sample(queue, "openbroker.acknowledged", ackFirst, now.minusSeconds(20));
        sample(queue, "openbroker.acknowledged", ackSecond, now.minusSeconds(1));
    }

    private void sample(String queue, String metric, double value, Instant at) {
        Map<String, Object> p = new HashMap<>();
        p.put("ts", Timestamp.from(at));
        p.put("value", value);
        p.put("subject", queue);
        p.put("metric", metric);
        p.put("c", clusterId);
        p.put("n", nodeId);
        jdbc.update("""
                INSERT INTO metric_sample (ts, value, subject_kind, subject_path, metric, cluster_id, node_id)
                VALUES (:ts, :value, 'QUEUE', :subject, :metric, :c, :n)
                """, p);
    }
}
