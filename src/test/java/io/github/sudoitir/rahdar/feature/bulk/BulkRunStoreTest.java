package io.github.sudoitir.rahdar.feature.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Finished bulk runs are purged with their items once old; expired previews go whatever the retention. */
class BulkRunStoreTest extends PostgresIntegrationTest {

    @Autowired
    BulkRunStore store;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    JdbcTemplate jdbc;

    UUID clusterId;
    UUID oldFinished;
    UUID oldInterrupted;
    UUID recentFinished;
    UUID expiredPreview;
    UUID livePreview;
    UUID running;

    @BeforeEach
    void runs() {
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "c-" + UUID.randomUUID(), null, null))
                .getId();
        oldFinished = run("SUCCEEDED", "100 days", "100 days", "-1 minute");
        oldInterrupted = run("INTERRUPTED", "100 days", "100 days", "-1 minute");
        recentFinished = run("FAILED", "10 days", "10 days", "-1 minute");
        expiredPreview = run("PREVIEWED", "1 hour", null, "-1 minute");
        livePreview = run("PREVIEWED", "1 minute", null, "10 minutes");
        running = run("RUNNING", "100 days", null, "-1 minute");
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    private UUID run(String status, String createdAgo, String finishedAgo, String expiresIn) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bulk_run (id, cluster_id, operation, status, username, plan_hash, selection, options,
                                      total_items, estimate_complete, created_at, finished_at, expires_at)
                VALUES (?, ?, 'PAUSE', ?, 'test', 'h', '{}'::jsonb, '{}'::jsonb, 1, true,
                        now() - ?::interval, now() - ?::interval, now() + ?::interval)""", id, clusterId, status, createdAgo, finishedAgo, expiresIn);
        jdbc.update(
                "INSERT INTO bulk_run_item (id, run_id, ordinal, subscription, status, estimate)"
                        + " VALUES (?, ?, 0, 'q', 'SUCCEEDED', '{}'::jsonb)",
                UUID.randomUUID(),
                id);
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM bulk_run WHERE id = ?", Long.class, id) == 1;
    }

    private long items(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM bulk_run_item WHERE run_id = ?", Long.class, id);
    }

    @Test
    void previewCountsOldRunsAndDeletesNothing() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(90));

        assertThat(store.preview(cutoff).rows()).isGreaterThanOrEqualTo(3);

        assertThat(exists(oldFinished)).isTrue();
        assertThat(exists(expiredPreview)).isTrue();
    }

    @Test
    void batchesRemoveOnlyWhatQualifiesAndFinallyReturnZero() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(90));
        long qualifying = store.preview(cutoff).rows();

        long purged = 0;
        long batch;
        do {
            batch = store.purgeBatch(cutoff, 2);
            assertThat(batch).isLessThanOrEqualTo(2);
            purged += batch;
        } while (batch > 0);

        assertThat(purged).isEqualTo(qualifying);
        assertThat(exists(oldFinished)).isFalse();
        assertThat(items(oldFinished)).isZero();
        assertThat(exists(oldInterrupted)).isFalse();
        assertThat(exists(expiredPreview)).isFalse();
        assertThat(items(expiredPreview)).isZero();
        assertThat(exists(recentFinished)).isTrue();
        assertThat(items(recentFinished)).isEqualTo(1);
        assertThat(exists(livePreview)).isTrue();
        assertThat(exists(running)).isTrue();
    }

    @Test
    void aShortRetentionStillNeverTouchesALivePreviewOrARunningRun() {
        Instant cutoff = Instant.now().plus(Duration.ofDays(1));

        while (store.purgeBatch(cutoff, 10) > 0) {
            // drain
        }

        assertThat(exists(recentFinished)).isFalse();
        assertThat(exists(livePreview)).isTrue();
        assertThat(exists(running)).isTrue();
    }
}
