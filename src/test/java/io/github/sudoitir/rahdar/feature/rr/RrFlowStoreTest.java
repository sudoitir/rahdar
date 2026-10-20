package io.github.sudoitir.rahdar.feature.rr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.rahdar.kernel.lifecycle.partition.PartitionDrops;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.TestPartitions;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@link RrFlowStore} and {@link CapturedPayloadStore} against a real Postgres. */
class RrFlowStoreTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate plain;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    PartitionDrops drops;

    private final UUID clusterId = UUID.randomUUID();
    private RrFlowStore flows;
    private CapturedPayloadStore payloads;

    @BeforeEach
    void seedCluster() {
        flows = new RrFlowStore(plain, drops);
        payloads = new CapturedPayloadStore(plain);
        jdbc.update(
                "INSERT INTO cluster (id, name, system) VALUES (:id, :name, 'artemis')",
                Map.of("id", clusterId, "name", "rr-store-" + clusterId));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM cluster WHERE id = :id", Map.of("id", clusterId));
    }

    /** A flow {@code ageDays} old, with one event that carries a captured body. */
    private void flow(int ageDays) {
        TestPartitions.ensure(plain, "rr_event", LocalDate.now(ZoneOffset.UTC).minusDays(ageDays));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rr_flow (id, requested_at, request_destination, reply_kind, state, cluster_id)
                VALUES (:id, now() - make_interval(days => :age), 'rr.store', 'SHARED', 'COMPLETED', :c)
                """, Map.of("id", id, "age", ageDays, "c", clusterId));
        jdbc.update("""
                INSERT INTO rr_event (ts, kind, detail, flow_id)
                VALUES (now() - make_interval(days => :age), 'REQUEST_SEEN',
                        '{"bodyPreview":"hello","truncated":true,"policyVersion":1}'::jsonb, :id)
                """, Map.of("id", id, "age", ageDays));
        jdbc.update("""
                INSERT INTO rr_event (ts, kind, detail, flow_id)
                VALUES (now() - make_interval(days => :age), 'CLOCK_SKEW', '{"side":"request"}'::jsonb, :id)
                """, Map.of("id", id, "age", ageDays));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Map.of("c", clusterId), Integer.class);
    }

    private int flowsLeft() {
        return count("SELECT count(*) FROM rr_flow WHERE cluster_id = :c");
    }

    private int eventsLeft() {
        return count("SELECT count(*) FROM rr_event e JOIN rr_flow f ON f.id = e.flow_id WHERE f.cluster_id = :c");
    }

    private int bodiesLeft() {
        return count("""
                SELECT count(*) FROM rr_event e JOIN rr_flow f ON f.id = e.flow_id
                WHERE f.cluster_id = :c AND jsonb_exists(e.detail, 'bodyPreview')""");
    }

    @Test
    void flowPreviewCountsOldFlowsAndDeletesNothing() {
        flow(30);
        flow(8);
        flow(1);

        PurgeEstimate estimate = flows.preview(Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(estimate.rows()).isGreaterThanOrEqualTo(2);
        assertThat(flowsLeft()).isEqualTo(3);
    }

    @Test
    void flowPurgeBatchRemovesOnlyOldFlowsWithTheirEventsAndEndsAtZero() {
        flow(30);
        flow(20);
        flow(8);
        flow(1);
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);

        long total = 0;
        long batch;
        do {
            batch = flows.purgeBatch(cutoff, 1);
            total += batch;
        } while (batch > 0);

        assertThat(total).isGreaterThanOrEqualTo(3);
        assertThat(flowsLeft()).isEqualTo(1);
        assertThat(eventsLeft()).isEqualTo(2);
        assertThat(flows.purgeBatch(cutoff, 1)).isZero();
    }

    @Test
    void payloadPreviewCountsOldBodiesAndChangesNothing() {
        flow(5);
        flow(1);

        PurgeEstimate estimate = payloads.preview(Instant.now().minus(3, ChronoUnit.DAYS));

        assertThat(estimate.rows()).isGreaterThanOrEqualTo(1);
        assertThat(estimate.bytes()).isPositive();
        assertThat(bodiesLeft()).isEqualTo(2);
        assertThat(payloads.usage().rows()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void payloadPurgeBatchStripsOnlyOldBodiesKeepsEventsAndEndsAtZero() {
        flow(6);
        flow(5);
        flow(4);
        flow(1);
        Instant cutoff = Instant.now().minus(3, ChronoUnit.DAYS);

        long total = 0;
        long batch;
        do {
            batch = payloads.purgeBatch(cutoff, 1);
            assertThat(batch).isLessThanOrEqualTo(1);
            total += batch;
        } while (batch > 0);

        assertThat(total).isGreaterThanOrEqualTo(3);
        assertThat(eventsLeft()).isEqualTo(8);
        assertThat(bodiesLeft()).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM rr_event e JOIN rr_flow f ON f.id = e.flow_id
                WHERE f.cluster_id = :c AND e.kind = 'REQUEST_SEEN' AND e.detail IS NULL""")).isEqualTo(3);
        assertThat(count("""
                SELECT count(*) FROM rr_event e JOIN rr_flow f ON f.id = e.flow_id
                WHERE f.cluster_id = :c AND e.kind = 'CLOCK_SKEW' AND e.detail IS NOT NULL""")).isEqualTo(4);
        assertThat(payloads.purgeBatch(cutoff, 1)).isZero();
    }
}
