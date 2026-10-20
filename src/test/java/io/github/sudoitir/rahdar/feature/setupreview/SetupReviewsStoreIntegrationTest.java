package io.github.sudoitir.rahdar.feature.setupreview;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The setup-reviews store against a real Postgres (ADR-0134). */
class SetupReviewsStoreIntegrationTest extends PostgresIntegrationTest {

    private static final String OLD = "now() - interval '100 days'";

    @Autowired
    SetupReviewsStore store;

    @Autowired
    JdbcTemplate jdbc;

    private UUID clusterId;

    /** The cluster's only, and so latest, review is old; three findings went stale, one is current. */
    @BeforeEach
    void seed() {
        clusterId = jdbc.queryForObject(
                "INSERT INTO cluster (name, system) VALUES (?, 'artemis') RETURNING id",
                UUID.class,
                "hist-" + UUID.randomUUID());
        jdbc.update(
                "INSERT INTO artemis_setup_review (reviewed_at, duration_ms, nodes_total, nodes_reviewed, nodes,"
                        + " not_assessed, cluster_id, cluster_evaluated) VALUES (" + OLD
                        + ", 1, 1, 0, '[]', '[]', ?, true)",
                clusterId);
        for (int i = 0; i < 3; i++) {
            finding("stale-" + i, OLD);
        }
        finding("current", "now()");
        jdbc.update(
                "INSERT INTO artemis_setup_finding_acceptance (created_at, code, subject, reason, accepted_by, cluster_id)"
                        + " VALUES (" + OLD + ", 'stale-0', 'node:a', 'known', 'test', ?)",
                clusterId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM cluster WHERE id = ?", clusterId);
    }

    private void finding(String code, String lastSeenAt) {
        jdbc.update(
                "INSERT INTO artemis_setup_finding (first_seen_at, last_seen_at, code, subject, severity, category, finding,"
                        + " cluster_id) VALUES (" + OLD + ", " + lastSeenAt + ", ?, 'node:a', 'INFO', 'HA', '{}', ?)",
                code,
                clusterId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE cluster_id = ?", Long.class, clusterId);
    }

    @Test
    void previewCountsStaleFindingsAndDeletesNothing() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));

        assertThat(store.preview(cutoff).rows()).isEqualTo(3);

        assertThat(count("artemis_setup_finding")).isEqualTo(4);
        assertThat(count("artemis_setup_review")).isEqualTo(1);
    }

    @Test
    void purgesStaleFindingsInBatchesAndKeepsTheLatestReviewAndAcceptances() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));

        long total = 0;
        long batch;
        while ((batch = store.purgeBatch(cutoff, 2)) > 0) {
            assertThat(batch).isLessThanOrEqualTo(2);
            total += batch;
        }

        assertThat(total).isEqualTo(3);
        assertThat(store.purgeBatch(cutoff, 2)).isZero();
        assertThat(count("artemis_setup_finding")).isEqualTo(1);
        assertThat(count("artemis_setup_review")).isEqualTo(1);
        assertThat(count("artemis_setup_finding_acceptance")).isEqualTo(1);
    }
}
