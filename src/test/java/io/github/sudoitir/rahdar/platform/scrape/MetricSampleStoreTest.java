package io.github.sudoitir.rahdar.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.TestPartitions;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link MetricSampleStore} against a real Postgres: a preview counts what is past the cutoff and
 * deletes nothing, and repeated purges drop the expired daily partitions and nothing newer, until a
 * call returns 0.
 */
class MetricSampleStoreTest extends PostgresIntegrationTest {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    MetricSampleStore store;

    @Autowired
    JdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private final Instant cutoff = Instant.now().minus(Duration.ofDays(7));

    @BeforeEach
    void emptyWhatIsExpired() {
        while (store.purgeBatch(cutoff, 1000) > 0) {
            // drain leftovers of other tests, so the counts below are exact
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM metric_sample WHERE cluster_id = ?", clusterId);
    }

    private void partition(int daysAgo) {
        TestPartitions.ensure(
                jdbc, "metric_sample", LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo));
    }

    private void samples(int daysAgo, int count) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO metric_sample (ts, value, subject_kind, subject_path, metric, cluster_id, node_id)
                    VALUES (?, 1.0, 'QUEUE', 'Q', 'openbroker.backlog', ?, ?)
                    """, Timestamp.from(Instant.now().minus(Duration.ofDays(daysAgo))), clusterId, nodeId);
        }
    }

    private static String name(int daysAgo) {
        return "metric_sample_p"
                + LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo).format(SUFFIX);
    }

    private long mine() {
        return jdbc.queryForObject("SELECT count(*) FROM metric_sample WHERE cluster_id = ?", Long.class, clusterId);
    }

    private List<String> partitions() {
        return jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                 WHERE i.inhparent = 'metric_sample'::regclass AND c.relname ~ '^metric_sample_p[0-9]{8}$'
                """, String.class);
    }

    @Test
    void previewCountsWhatIsPastTheCutoffAndDeletesNothing() {
        partition(30);
        samples(30, 3); // in the expired partition
        samples(2, 2); // recent

        assertThat(store.preview(cutoff).rows()).isPositive();
        assertThat(mine()).isEqualTo(5);
        assertThat(partitions()).contains(name(30));
    }

    @Test
    void repeatedBatchesRemoveOnlyWhatIsPastTheCutoffAndEndAtZero() {
        partition(30);
        partition(10);
        samples(30, 3);
        samples(10, 2);
        samples(2, 2);

        long calls = 0;
        long removed;
        do {
            removed = store.purgeBatch(cutoff, 2);
            calls++;
        } while (removed > 0 && calls < 20);

        assertThat(removed).isZero();
        assertThat(mine()).isEqualTo(2);
        assertThat(partitions()).doesNotContain(name(30), name(10));
        // two partitions dropped, then the call that finds nothing left
        assertThat(calls).isEqualTo(3);
        assertThat(store.preview(cutoff).rows()).isZero();
        assertThat(store.usage().bytes()).isPositive();
    }
}
