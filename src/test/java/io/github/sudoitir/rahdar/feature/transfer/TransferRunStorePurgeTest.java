package io.github.sudoitir.rahdar.feature.transfer;

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

/**
 * Runs that are over for good are purged with their copy ledger once old; expired previews go whatever
 * the retention; active and resumable runs are never touched.
 */
class TransferRunStorePurgeTest extends PostgresIntegrationTest {

    @Autowired
    TransferRunStore store;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    JdbcTemplate jdbc;

    UUID clusterId;
    UUID oldSucceeded;
    UUID oldReturned;
    UUID recentSucceeded;
    UUID expiredPreview;
    UUID livePreview;
    UUID oldRunning;
    UUID oldWaiting;
    UUID oldReturning;
    UUID oldStopped;
    UUID oldFailed;

    private int queue;

    @BeforeEach
    void runs() {
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "c-" + UUID.randomUUID(), null, null))
                .getId();
        oldSucceeded = run("SUCCEEDED", "100 days", "100 days", "-1 minute");
        oldReturned = run("RETURNED", "100 days", "100 days", "-1 minute");
        recentSucceeded = run("SUCCEEDED", "10 days", "10 days", "-1 minute");
        expiredPreview = run("PREVIEWED", "1 hour", null, "-1 minute");
        livePreview = run("PREVIEWED", "1 minute", null, "10 minutes");
        oldRunning = run("RUNNING", "100 days", null, "-1 minute");
        oldWaiting = run("WAITING_FOR_CAPACITY", "100 days", null, "-1 minute");
        oldReturning = run("RETURNING", "100 days", null, "-1 minute");
        oldStopped = run("STOPPED", "100 days", "100 days", "-1 minute");
        oldFailed = run("FAILED", "100 days", "100 days", "-1 minute");
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    private UUID run(String state, String createdAgo, String finishedAgo, String expiresIn) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO transfer_run (id, t0, created_at, finished_at, expires_at, mode, state,
                    source_subscription, source_destination, source_node_name, source_native_node_id,
                    target_subscription, target_destination, target_node_name, target_native_node_id,
                    plan_hash, username, selection, findings, source_cluster_id, source_node_id,
                    target_cluster_id, target_node_id, same_node)
                VALUES (?, now(), now() - ?::interval, now() - ?::interval, now() + ?::interval, 'MOVE', ?,
                    ?, 'a', 'n', 'x', 'q2', 'a2', 'n', 'x',
                    'h', 'test', '{}'::jsonb, '[]'::jsonb, ?, ?, ?, ?, false)""",
                id,
                createdAgo,
                finishedAgo,
                expiresIn,
                state,
                "q" + queue++,
                clusterId,
                UUID.randomUUID(),
                clusterId,
                UUID.randomUUID());
        jdbc.update("INSERT INTO transfer_copied (run_id, message_id) VALUES (?, 1)", id);
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM transfer_run WHERE id = ?", Long.class, id) == 1;
    }

    private long copied(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM transfer_copied WHERE run_id = ?", Long.class, id);
    }

    @Test
    void previewCountsOldRunsAndDeletesNothing() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(90));

        assertThat(store.preview(cutoff).rows()).isGreaterThanOrEqualTo(3);

        assertThat(exists(oldSucceeded)).isTrue();
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
        assertThat(exists(oldSucceeded)).isFalse();
        assertThat(copied(oldSucceeded)).isZero();
        assertThat(exists(oldReturned)).isFalse();
        assertThat(exists(expiredPreview)).isFalse();
        assertThat(copied(expiredPreview)).isZero();
        assertThat(exists(recentSucceeded)).isTrue();
        assertThat(copied(recentSucceeded)).isEqualTo(1);
        assertThat(exists(livePreview)).isTrue();
        for (UUID kept : new UUID[] {oldRunning, oldWaiting, oldReturning, oldStopped, oldFailed}) {
            assertThat(exists(kept)).isTrue();
        }
    }
}
