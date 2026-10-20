package io.github.sudoitir.rahdar.feature.triage;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.triage.ConsumerHealth.Source;
import io.github.sudoitir.rahdar.feature.triage.ConsumerHealth.Verdict;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The consumer-health verdict ladder (ADR-0089) against real {@code subscription_snapshot} and
 * {@code metric_sample} rows.
 *
 * <p>Integration rather than isolated for the same reasons {@code SlowConsumerConditionTest}
 * is: {@code subscription_snapshot} has no setters, the {@code paused} exclusion is only real
 * against the actual column, and the rate and slope derivations this rests on live in SQL
 * rather than Java — a mocked repository would test the mock.
 *
 * <p>Each rung gets its own case. The ladder's order is a compatibility surface: it
 * decides what an alert fires on and what an agent reports, so a reordering must fail
 * here rather than in production.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class ConsumerHealthServiceTest extends PostgresIntegrationTest {

    @Autowired
    ConsumerHealthService service;

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

    // ---- the ladder, rung by rung ----------------------------------------

    @Test
    void tooFewSamplesIsNeverHealthy() {
        givenCluster();
        queue("orders", 2, 5_000, 10, false);
        // Exactly one sample of each counter: a rate needs two.
        sample("orders", "openbroker.enqueued", 100, Instant.now().minusSeconds(5));
        sample("orders", "openbroker.acknowledged", 100, Instant.now().minusSeconds(5));

        ConsumerHealth health = verdictFor("orders");

        assertThat(health.verdict()).isEqualTo(Verdict.INSUFFICIENT_DATA);
        assertThat(health.verdict().known()).isFalse();
        // The distinction the whole feature exists for: unmeasured is not zero.
        assertThat(health.ackRate()).isNull();
    }

    @Test
    void pausedOutranksEveryConsumerVerdict() {
        givenCluster();
        // Also satisfies NO_CONSUMERS and STALLED; paused must win, because the backlog
        // is expected and the action is "resume", not "go debug a consumer".
        queue("orders", 0, 5_000, 0, true);
        counterSamples("orders", 100, 100, 100, 100);

        assertThat(verdictFor("orders").verdict()).isEqualTo(Verdict.PAUSED);
    }

    @Test
    void backlogWithNoConsumersIsNoConsumers() {
        givenCluster();
        queue("orders", 0, 5_000, 0, false);
        counterSamples("orders", 100, 200, 100, 100);

        ConsumerHealth health = verdictFor("orders");

        assertThat(health.verdict()).isEqualTo(Verdict.NO_CONSUMERS);
        assertThat(health.verdict().severity()).isEqualTo(4);
    }

    @Test
    void consumersHoldingMessagesAndAckingNoneIsStalled() {
        givenCluster();
        // Delivering above zero: the consumers have the messages and are not acking.
        queue("orders", 3, 5_000, 30, false);
        counterSamples("orders", 100, 200, 100, 100);

        ConsumerHealth health = verdictFor("orders");

        assertThat(health.verdict()).isEqualTo(Verdict.STALLED);
        assertThat(health.delivering()).isEqualTo(30);
        assertThat(health.source()).isEqualTo(Source.DERIVED);
    }

    @Test
    void consumersReceivingNothingIsStarvedNotStalled() {
        givenCluster();
        // Identical to the stalled case but for delivering — and the operator's next
        // action is the opposite one, so these must never share a verdict.
        queue("orders", 3, 5_000, 0, false);
        counterSamples("orders", 100, 200, 100, 100);

        assertThat(verdictFor("orders").verdict()).isEqualTo(Verdict.STARVED);
    }

    @Test
    void ackingSlowerThanArrivingIsFallingBehind() {
        givenCluster();
        queue("orders", 3, 5_000, 30, false);
        // Added climbs by 1000, acked by 100: net positive.
        counterSamples("orders", 1_000, 2_000, 1_000, 1_100);
        depthClimbing("orders", 4_000, 5_000);

        ConsumerHealth health = verdictFor("orders");

        assertThat(health.verdict()).isEqualTo(Verdict.FALLING_BEHIND);
        assertThat(health.netRate()).isGreaterThan(0.0);
        assertThat(health.ackRatePerConsumer()).isNotNull();
    }

    @Test
    void ackingFasterThanArrivingIsDrainingWithAnEta() {
        givenCluster();
        queue("orders", 3, 5_000, 30, false);
        // Acked climbs faster than added: net negative.
        counterSamples("orders", 1_000, 1_100, 1_000, 2_000);

        ConsumerHealth health = verdictFor("orders");

        assertThat(health.verdict()).isEqualTo(Verdict.DRAINING);
        assertThat(health.drainEta()).isNotNull();
        assertThat(health.netRate()).isLessThan(0.0);
    }

    @Test
    void keepingUpIsHealthy() {
        givenCluster();
        queue("orders", 3, 0, 0, false);
        counterSamples("orders", 1_000, 1_100, 1_000, 1_100);

        assertThat(verdictFor("orders").verdict()).isEqualTo(Verdict.HEALTHY);
    }

    @Test
    void theBrokersOwnVerdictOutranksTheDerivedOne() {
        givenCluster();
        // By signal alone this is FALLING_BEHIND: acking, but arriving faster.
        queue("orders", 3, 5_000, 30, false);
        counterSamples("orders", 1_000, 2_000, 1_000, 1_100);
        depthClimbing("orders", 4_000, 5_000);
        brokerSlowConsumer("orders", "consumer-7");

        ConsumerHealth health = verdictFor("orders");

        // ADR-0044: where the broker has judged, Rahdar does not offer a second opinion.
        assertThat(health.verdict()).isEqualTo(Verdict.BROKER_SLOW);
        assertThat(health.source()).isEqualTo(Source.BROKER);
        assertThat(health.brokerConsumerName()).isEqualTo("consumer-7");
    }

    @Test
    void anOldBrokerNotificationIsHistoryNotTheCurrentState() {
        givenCluster();
        queue("orders", 3, 5_000, 30, false);
        counterSamples("orders", 1_000, 2_000, 1_000, 1_100);
        depthClimbing("orders", 4_000, 5_000);
        // Well outside the brokerSlowWindow default of 10 minutes.
        brokerSlowConsumerAt("orders", "consumer-7", Instant.now().minusSeconds(3_600));

        assertThat(verdictFor("orders").verdict()).isEqualTo(Verdict.FALLING_BEHIND);
    }

    // ---- ranking ----------------------------------------------------------

    @Test
    void worstRanksFirstAndUnknownNeverRanksAsHealthy() {
        givenCluster();
        queue("healthy", 3, 0, 0, false);
        counterSamples("healthy", 1_000, 1_100, 1_000, 1_100);
        queue("broken", 0, 9_000, 0, false);
        counterSamples("broken", 100, 200, 100, 100);
        queue("unsampled", 1, 10, 0, false);
        sample("unsampled", "openbroker.acknowledged", 5, Instant.now().minusSeconds(5));

        var page = service.page(clusterId, ResourceQuery.of(null, 1, 50, null));
        var order = page.data().stream().map(ConsumerHealth::queueName).toList();

        assertThat(order).containsExactly("broken", "unsampled", "healthy");
    }

    // ---- helpers ----------------------------------------------------------

    private ConsumerHealth verdictFor(String queue) {
        return service.forQueue(clusterId, queue).orElseThrow();
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

    /** Two samples of each counter inside the evaluation window, so both rates resolve. */
    private void counterSamples(String queue, double addFirst, double addSecond, double ackFirst, double ackSecond) {
        Instant now = Instant.now();
        sample(queue, "openbroker.enqueued", addFirst, now.minusSeconds(20));
        sample(queue, "openbroker.enqueued", addSecond, now.minusSeconds(1));
        sample(queue, "openbroker.acknowledged", ackFirst, now.minusSeconds(20));
        sample(queue, "openbroker.acknowledged", ackSecond, now.minusSeconds(1));
    }

    /** A rising depth series, so the regression slope is positive. */
    private void depthClimbing(String queue, double first, double second) {
        Instant now = Instant.now();
        sample(queue, "openbroker.backlog", first, now.minusSeconds(20));
        sample(queue, "openbroker.backlog", second, now.minusSeconds(1));
    }

    private void brokerSlowConsumer(String address, String consumer) {
        brokerSlowConsumerAt(address, consumer, Instant.now().minusSeconds(30));
    }

    private void brokerSlowConsumerAt(String address, String consumer, Instant at) {
        Map<String, Object> p = new HashMap<>();
        p.put("ts", Timestamp.from(at));
        p.put("address", address);
        p.put("consumer", consumer);
        p.put("c", clusterId);
        p.put("n", nodeId);
        jdbc.update("""
                INSERT INTO broker_event (occurred_at, type, destination, consumer, cluster_id, node_id)
                VALUES (:ts, 'openbroker.consumer.slow', :address, :consumer, :c, :n)
                """, p);
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
