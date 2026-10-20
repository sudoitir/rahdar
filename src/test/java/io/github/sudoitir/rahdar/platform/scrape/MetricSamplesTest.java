package io.github.sudoitir.rahdar.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.TestPartitions;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link MetricSamples} against a real Postgres — exercises the
 * {@code date_bin} queries with real {@code java.time.Instant} bind parameters
 * (a Mockito-based test never runs the SQL, and pgjdbc cannot infer a type for
 * a bare {@code Instant} without the {@code Timestamp} conversion this
 * verifies stays in place).
 */
class MetricSamplesTest extends PostgresIntegrationTest {

    @Autowired
    MetricSamples repository;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();

    /** The tests' fixed clock is long past, so its days have no partition unless made here. */
    @BeforeEach
    void partitionsForTheFixedClock() {
        for (String day : new String[] {"2025-12-31", "2026-01-01", "2026-01-02"}) {
            TestPartitions.ensure(jdbc.getJdbcTemplate(), "metric_sample", LocalDate.parse(day));
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM metric_sample WHERE cluster_id = :c", Map.of("c", clusterId));
    }

    private void sample(String metric, Instant ts, double value) {
        sample(metric, "Q", ts, value);
    }

    private void sample(String metric, String subjectName, Instant ts, double value) {
        sample(metric, subjectName, nodeId, ts, value);
    }

    private void sample(String metric, String subjectName, UUID node, Instant ts, double value) {
        jdbc.update(
                """
                INSERT INTO metric_sample (ts, value, subject_kind, subject_path, metric, cluster_id, node_id)
                VALUES (:ts, :value, 'QUEUE', :subject, :metric, :c, :n)
                """,
                Map.of(
                        "ts",
                        Timestamp.from(ts),
                        "value",
                        value,
                        "subject",
                        subjectName,
                        "metric",
                        metric,
                        "c",
                        clusterId,
                        "n",
                        node));
    }

    /** One queue on two nodes: 100 → 200 on this node, 90,000 → 90,050 on another, 10 s apart. */
    private void sampleOnTwoNodes(Instant base) {
        UUID otherNode = UUID.randomUUID();
        sample("openbroker.enqueued", "orders", base, 100.0);
        sample("openbroker.enqueued", "orders", base.plusSeconds(10), 200.0);
        sample("openbroker.enqueued", "orders", otherNode, base, 90_000.0);
        sample("openbroker.enqueued", "orders", otherNode, base.plusSeconds(10), 90_050.0);
    }

    @Test
    void latestRateBySubjectSumsPerNodeRatesSoAQueueOnTwoNodesDoesNotReadAsAHugeRate() {
        // Across nodes, max - min would be 89,950 messages: a false rate-threshold alert, and an ack
        // rate that hides a slow consumer.
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sampleOnTwoNodes(base);

        Map<String, Double> rates =
                repository.latestRateBySubject(clusterId, "openbroker.enqueued", base, base.plusSeconds(60));

        assertThat(rates).containsEntry("orders", 15.0); // 10/s on one node + 5/s on the other
    }

    @Test
    void rateSeriesSumsPerNodeDeltasSoAQueueOnTwoNodesDoesNotSpike() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sampleOnTwoNodes(base);

        List<MetricSamples.Bucket> buckets = repository.rateSeries(
                clusterId, "openbroker.enqueued", "orders", base, base.plusSeconds(60), Duration.ofSeconds(60));

        assertThat(buckets).hasSize(1);
        assertThat(buckets.get(0).value()).isEqualTo(150.0 / 60.0); // (100 + 50) messages over the bucket
    }

    @Test
    void gaugeSeriesAveragesWithinABucketAndReportsThePeak() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("openbroker.backlog", base, 10.0);
        sample("openbroker.backlog", base.plusSeconds(10), 20.0);

        List<MetricSamples.Bucket> buckets = repository.gaugeSeries(
                clusterId, "openbroker.backlog", "Q", base, base.plusSeconds(60), Duration.ofSeconds(60));

        assertThat(buckets).hasSize(1);
        assertThat(buckets.get(0).value()).isEqualTo(15.0);
        assertThat(buckets.get(0).peak()).isEqualTo(20.0);
    }

    @Test
    void aClusterGaugeIsTheTotalOfEveryQueuesLastSampleIncludingASlowTierQueueAbsentFromTheBucket() {
        // Issue #72: two fast queues sampled every 15 s, and a queue on the 5-minute slow sweep,
        // on another node, last sampled before the window. Its sample still stands at every bucket end.
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID otherNode = UUID.randomUUID();
        sample("openbroker.backlog", "slow", otherNode, base.minusSeconds(590), 5_000.0);
        sample("openbroker.backlog", "slow", otherNode, base.minusSeconds(290), 7_000.0);
        for (int i = 0; i < 8; i++) {
            Instant ts = base.plusSeconds(15L * i);
            sample("openbroker.backlog", "a", ts, 100.0 + i);
            sample("openbroker.backlog", "b", ts, 200.0);
        }

        List<MetricSamples.Bucket> buckets = repository.gaugeSeries(
                clusterId, "openbroker.backlog", null, base, base.plusSeconds(60), Duration.ofSeconds(30));

        // Bucket [0, 30): at its end, a's last sample is 101 (t=15), b's 200, slow's 7,000.
        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(0).value()).isEqualTo(101.0 + 200.0 + 7_000.0);
        assertThat(buckets.get(1).value()).isEqualTo(103.0 + 200.0 + 7_000.0);
        assertThat(buckets).allSatisfy(b -> assertThat(b.peak()).isNull());
    }

    @Test
    void aQueueThatStopsBeingSampledStopsCountingAfterItsOwnInterval() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("openbroker.backlog", "gone", base, 50.0);
        sample("openbroker.backlog", "gone", base.plusSeconds(10), 60.0); // last sample, 10 s interval
        sample("openbroker.backlog", "kept", base, 1.0);
        sample("openbroker.backlog", "kept", base.plusSeconds(55), 1.0);

        List<MetricSamples.Bucket> buckets = repository.gaugeSeries(
                clusterId, "openbroker.backlog", null, base, base.plusSeconds(60), Duration.ofSeconds(20));

        // End 20 s: gone's t=10 sample is 10 s old, within 1.5 × its 10 s gap. End 40 s: 30 s old, not.
        assertThat(buckets).extracting(MetricSamples.Bucket::value).containsExactly(61.0, 1.0, 1.0);
    }

    @Test
    void rateSeriesDerivesFromTheCounterDeltaAcrossTheBucket() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("openbroker.enqueued", base, 100.0);
        sample("openbroker.enqueued", base.plusSeconds(30), 130.0);

        List<MetricSamples.Bucket> buckets = repository.rateSeries(
                clusterId, "openbroker.enqueued", null, base, base.plusSeconds(60), Duration.ofSeconds(60));

        assertThat(buckets).hasSize(1);
        // (130 - 100) / 60s
        assertThat(buckets.get(0).value()).isEqualTo(30.0 / 60.0);
    }

    @Test
    void latestRateBySubjectNeverProducesANegativeRate() {
        // max(value) - min(value) is never negative by construction — the same
        // GREATEST(...,0) formula rateSeries uses — so a broker restart mid-window
        // (100 then 40) still yields a non-negative, if imprecise, rate rather than
        // a negative spike.
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("openbroker.enqueued", "orders", base, 100.0);
        sample("openbroker.enqueued", "orders", base.plusSeconds(30), 40.0);

        Map<String, Double> rates =
                repository.latestRateBySubject(clusterId, "openbroker.enqueued", base, base.plusSeconds(60));

        assertThat(rates.get("orders")).isNotNegative();
    }

    @Test
    void latestRateWithTimeDividesByTheSpanItCoversAndReportsItsAge() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("openbroker.enqueued", "orders", base.plusSeconds(10), 100.0);
        sample("openbroker.enqueued", "orders", base.plusSeconds(310), 400.0);
        sample("openbroker.enqueued", "fresh-queue", base, 5.0);

        Map<String, MetricSamples.SubjectRate> rates =
                repository.latestRateWithTimeBySubject(clusterId, "openbroker.enqueued", base, base.plusSeconds(900));

        assertThat(rates).doesNotContainKey("fresh-queue");
        MetricSamples.SubjectRate orders = rates.get("orders");
        assertThat(orders.rate()).isEqualTo(1.0); // 300 messages over the 300s the samples span
        assertThat(orders.asOf()).isEqualTo(base.plusSeconds(310));
        assertThat(orders.span()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void latestRateWithTimeSumsPerNodeRatesInsteadOfSubtractingOneNodesCounterFromAnothers() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID otherNode = UUID.randomUUID();
        sample("openbroker.enqueued", "orders", base, 100.0);
        sample("openbroker.enqueued", "orders", base.plusSeconds(10), 200.0);
        jdbc.update("""
                INSERT INTO metric_sample (ts, value, subject_kind, subject_path, metric, cluster_id, node_id)
                VALUES (:ts, :value, 'QUEUE', 'orders', 'openbroker.enqueued', :c, :n)
                """, Map.of("ts", Timestamp.from(base), "value", 90_000.0, "c", clusterId, "n", otherNode));
        jdbc.update(
                """
                INSERT INTO metric_sample (ts, value, subject_kind, subject_path, metric, cluster_id, node_id)
                VALUES (:ts, :value, 'QUEUE', 'orders', 'openbroker.enqueued', :c, :n)
                """,
                Map.of("ts", Timestamp.from(base.plusSeconds(10)), "value", 90_050.0, "c", clusterId, "n", otherNode));

        MetricSamples.SubjectRate orders = repository
                .latestRateWithTimeBySubject(clusterId, "openbroker.enqueued", base, base.plusSeconds(60))
                .get("orders");

        assertThat(orders.rate()).isEqualTo(15.0); // 10/s on one node + 5/s on the other
    }

    @Test
    void latestRateBySubjectOmitsAnUnderSampledSubject() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("openbroker.enqueued", "fresh-queue", base, 5.0); // only one sample in the window

        Map<String, Double> rates =
                repository.latestRateBySubject(clusterId, "openbroker.enqueued", base, base.plusSeconds(60));

        assertThat(rates).doesNotContainKey("fresh-queue");
    }

    @Test
    void perNodeLatestRatesAddUpToTheSubjectRate() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sampleOnTwoNodes(base);

        Map<UUID, MetricSamples.SubjectRate> perNode = repository
                .latestRateWithTimeBySubjectAndNode(clusterId, "openbroker.enqueued", base, base.plusSeconds(60))
                .get("orders");
        MetricSamples.SubjectRate total = repository
                .latestRateWithTimeBySubject(clusterId, "openbroker.enqueued", base, base.plusSeconds(60))
                .get("orders");

        assertThat(perNode).hasSize(2);
        assertThat(perNode.get(nodeId).rate()).isEqualTo(10.0);
        assertThat(MetricSamples.SubjectRate.sum(perNode.values())).isEqualTo(total);
    }

    @Test
    void aNodeSampledOnceHasNoRateRatherThanZero() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID once = UUID.randomUUID();
        sample("openbroker.enqueued", "orders", base, 100.0);
        sample("openbroker.enqueued", "orders", base.plusSeconds(10), 200.0);
        sample("openbroker.enqueued", "orders", once, base, 5.0);

        Map<UUID, MetricSamples.SubjectRate> perNode = repository
                .latestRateWithTimeBySubjectAndNode(clusterId, "openbroker.enqueued", base, base.plusSeconds(60))
                .get("orders");

        assertThat(perNode).containsOnlyKeys(nodeId);
    }

    @Test
    void aBucketHoldingOneSampleReadsTheRateBetweenBucketsOnEveryNode() {
        // Issue #73: at a 15 s step with 15 s sampling a bucket holds one sample, and max - min
        // inside it read 0 msg/s for a busy queue. Both nodes add 10 messages a second.
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID otherNode = UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            Instant ts = base.plusSeconds(15L * i);
            sample("openbroker.enqueued", "orders", ts, 1_000.0 + 150.0 * i);
            sample("openbroker.enqueued", "orders", otherNode, ts, 50_000.0 + 150.0 * i);
        }
        Instant to = base.plusSeconds(90);
        Duration step = Duration.ofSeconds(15);

        List<MetricSamples.Bucket> total =
                repository.rateSeries(clusterId, "openbroker.enqueued", "orders", base, to, step);
        List<MetricSamples.NodeBucket> byNode =
                repository.rateSeriesByNode(clusterId, "openbroker.enqueued", "orders", base, to, step);

        // The first bucket's samples have no earlier sample to count from; every later one does.
        assertThat(total).hasSize(5).allSatisfy(b -> assertThat(b.value()).isEqualTo(20.0));
        assertThat(byNode).hasSize(10).allSatisfy(b -> assertThat(b.value()).isEqualTo(10.0));
    }

    @Test
    void aCounterSampledBeforeTheWindowCountsTheFirstBucket() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sample("openbroker.enqueued", base.minusSeconds(15), 100.0);
        sample("openbroker.enqueued", base, 250.0);

        List<MetricSamples.Bucket> buckets = repository.rateSeries(
                clusterId, "openbroker.enqueued", null, base, base.plusSeconds(15), Duration.ofSeconds(15));

        assertThat(buckets).singleElement().satisfies(b -> assertThat(b.value()).isEqualTo(10.0));
    }

    @Test
    void perNodeRateSeriesAddUpToTheTotalSeries() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        sampleOnTwoNodes(base);

        List<MetricSamples.NodeBucket> byNode = repository.rateSeriesByNode(
                clusterId, "openbroker.enqueued", "orders", base, base.plusSeconds(60), Duration.ofSeconds(60));
        List<MetricSamples.Bucket> total = repository.rateSeries(
                clusterId, "openbroker.enqueued", "orders", base, base.plusSeconds(60), Duration.ofSeconds(60));

        assertThat(byNode).hasSize(2);
        assertThat(byNode.stream().mapToDouble(MetricSamples.NodeBucket::value).sum())
                .isCloseTo(total.get(0).value(), Offset.offset(1e-9));
    }

    @Test
    void perNodeGaugeSeriesKeepsEachNodeApartWithItsPeak() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID other = UUID.randomUUID();
        sample("openbroker.backlog", "orders", base, 10.0);
        sample("openbroker.backlog", "orders", base.plusSeconds(15), 30.0);
        sample("openbroker.backlog", "orders", other, base, 9_000.0);

        List<MetricSamples.NodeBucket> byNode = repository.gaugeSeriesByNode(
                clusterId, "openbroker.backlog", "orders", base, base.plusSeconds(60), Duration.ofSeconds(60));

        assertThat(byNode)
                .extracting(
                        b -> b.nodeId().equals(nodeId) ? "this:" + b.value() + "/" + b.peak() : "other:" + b.value())
                .containsExactlyInAnyOrder("this:20.0/30.0", "other:9000.0");
    }
}
