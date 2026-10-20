package io.github.sudoitir.rahdar.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.platform.broker.QueueRow;
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
 * What {@link MetricSampleWriter} records per queue per sweep.
 *
 * <p>The set is pinned deliberately: {@code deliveringCount} is what separates a stalled
 * consumer from a starved one (ADR-0089), so silently dropping it would not fail any
 * verdict test — both would simply become {@code STARVED} — while making the product
 * unable to tell an operator which of two opposite things to go and do.
 */
class MetricSampleWriterTest extends PostgresIntegrationTest {

    @Autowired
    MetricSampleWriter writer;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private UUID clusterId;
    private UUID nodeId;

    @AfterEach
    void tearDown() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
            clusterId = null;
        }
    }

    @Test
    void recordsSixMetricsPerQueue() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        nodeId = nodes.save(BrokerNodeEntity.fromSeed(clusterId, "n1", "STANDALONE", "node-1"))
                .getId();

        writer.appendQueueSamples(List.of(
                new QueueRow(clusterId, nodeId, "orders", "orders", "ANYCAST", true, 42, 3, 7, 1, 900, 850, 5, false)));

        assertThat(metricsWritten())
                .containsExactlyInAnyOrder(
                        "openbroker.backlog",
                        "openbroker.consumer.count",
                        "openbroker.enqueued",
                        "openbroker.acknowledged",
                        "openbroker.in_flight",
                        "openbroker.expired");
        assertThat(valueOf("openbroker.in_flight")).isEqualTo(7.0);
        assertThat(valueOf("openbroker.expired")).isEqualTo(5.0);
    }

    @Test
    void writesNothingForAnEmptySweep() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();

        writer.appendQueueSamples(List.of());

        assertThat(metricsWritten()).isEmpty();
    }

    private List<String> metricsWritten() {
        return jdbc.queryForList(
                "SELECT metric FROM metric_sample WHERE cluster_id = :c", Map.of("c", clusterId), String.class);
    }

    private double valueOf(String metric) {
        return jdbc.queryForObject(
                "SELECT value FROM metric_sample WHERE cluster_id = :c AND metric = :m",
                Map.of("c", clusterId, "m", metric),
                Double.class);
    }
}
