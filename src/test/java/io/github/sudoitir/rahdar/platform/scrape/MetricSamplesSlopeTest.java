package io.github.sudoitir.rahdar.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link MetricSamples#depthSlopeBySubject} against real rows (ADR-0089).
 *
 * <p>The case that matters is the oscillating one: it is exactly what the first-versus-last
 * trend this replaced reported as "growing", and reporting a queue that returns to where it
 * started as growing is how an operator is sent to debug a queue that is fine.
 */
class MetricSamplesSlopeTest extends PostgresIntegrationTest {

    @Autowired
    MetricSamples samples;

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
    void aSteadilyClimbingSeriesHasAPositiveSlope() {
        givenCluster();
        // +100 every 10s => +10.0/s.
        series("climbing", 0, 100, 200, 300);

        assertThat(slopeOf("climbing")).isCloseTo(10.0, Offset.offset(0.05));
    }

    @Test
    void aFallingSeriesHasANegativeSlope() {
        givenCluster();
        series("falling", 300, 200, 100, 0);

        assertThat(slopeOf("falling")).isLessThan(0.0);
    }

    @Test
    void aFlatSeriesHasNoSlope() {
        givenCluster();
        series("flat", 100, 100, 100, 100);

        assertThat(slopeOf("flat")).isCloseTo(0.0, Offset.offset(0.001));
    }

    @Test
    void anOscillatingSeriesIsNotReportedAsGrowing() {
        givenCluster();
        // Ends where it started. First-versus-last would also call this flat, but a
        // sawtooth that happens to end high would have read as growth; a regression
        // over every sample cannot be fooled by where the window's edges land.
        series("sawtooth", 100, 500, 100, 500, 100);

        assertThat(slopeOf("sawtooth")).isCloseTo(0.0, Offset.offset(0.5));
    }

    @Test
    void aSubjectWithOneSampleIsAbsentRatherThanFlat() {
        givenCluster();
        series("single", 100);

        // Omitted, never zero: "not measurable" and "not moving" are different facts.
        assertThat(slopes()).doesNotContainKey("single");
    }

    // ---- helpers ----------------------------------------------------------

    private double slopeOf(String subject) {
        return slopes().get(subject);
    }

    private Map<String, Double> slopes() {
        Instant now = Instant.now();
        return samples.depthSlopeBySubject(clusterId, "openbroker.backlog", now.minusSeconds(600), now.plusSeconds(1));
    }

    /** Values 10 seconds apart, oldest first, ending just before now. */
    private void series(String subject, double... values) {
        Instant now = Instant.now();
        for (int i = 0; i < values.length; i++) {
            long secondsAgo = (long) (values.length - i) * 10;
            Map<String, Object> p = new HashMap<>();
            p.put("ts", Timestamp.from(now.minusSeconds(secondsAgo)));
            p.put("value", values[i]);
            p.put("subject", subject);
            p.put("c", clusterId);
            p.put("n", nodeId);
            jdbc.update("""
                    INSERT INTO metric_sample (ts, value, subject_kind, subject_path, metric, cluster_id, node_id)
                    VALUES (:ts, :value, 'QUEUE', :subject, 'openbroker.backlog', :c, :n)
                    """, p);
        }
    }
}
