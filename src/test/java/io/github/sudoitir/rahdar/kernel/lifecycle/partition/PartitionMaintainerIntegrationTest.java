package io.github.sudoitir.rahdar.kernel.lifecycle.partition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The partition maintainer and the drops against a real Postgres (ADR-0193). */
class PartitionMaintainerIntegrationTest extends PostgresIntegrationTest {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    PartitionMaintainer maintainer;

    @Autowired
    PartitionDrops drops;

    @Autowired
    PartitionsHealthIndicator health;

    @Autowired
    List<PartitionSpec> specs;

    @Autowired
    JdbcTemplate jdbc;

    private PartitionSpec spec(String table) {
        return specs.stream().filter(s -> s.table().equals(table)).findFirst().orElseThrow();
    }

    private List<String> partitions(String table) {
        return jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                 WHERE i.inhparent = to_regclass(?) ORDER BY c.relname
                """, String.class, table);
    }

    private static String day(String table, int offset) {
        return table + "_p" + DAY.format(LocalDate.now(ZoneOffset.UTC).plusDays(offset));
    }

    private void insertSample(Instant ts, UUID clusterId) {
        jdbc.update("""
                INSERT INTO metric_sample (ts, value, subject_kind, subject_path, metric, cluster_id)
                VALUES (?, 1.0, 'QUEUE', 'Q', 'openbroker.backlog', ?)
                """, Timestamp.from(ts), clusterId);
    }

    @Test
    void everyTableOwnerContributesASpec() {
        assertThat(specs)
                .extracting(PartitionSpec::table)
                .containsExactlyInAnyOrder("metric_sample", "message_index", "broker_event", "rr_event", "audit_event");
    }

    @Test
    void startUpAndTheHourlyRunKeepThePeriodsAheadWithoutCreatingTwice() {
        maintainer.ensureAhead();
        List<String> before = partitions("metric_sample");

        maintainer.ensureAhead();

        assertThat(partitions("metric_sample")).isEqualTo(before);
        for (int i = 0; i <= 7; i++) {
            assertThat(before).contains(day("metric_sample", i));
        }
        assertThat(partitions("audit_event")).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void aPartitionCarriesTheSpecsStorageParametersAndTheParentCarriesNone() {
        maintainer.ensureAhead();

        String partition = day("metric_sample", 1);
        assertThat(jdbc.queryForObject(
                        "SELECT reloptions::text FROM pg_class WHERE oid = to_regclass(?)", String.class, partition))
                .contains("fillfactor=100")
                .contains("autovacuum_vacuum_insert_threshold=10000");
        assertThat(jdbc.queryForObject(
                        "SELECT reloptions::text FROM pg_class WHERE oid = to_regclass('metric_sample')", String.class))
                .isNull();
    }

    @Test
    void aWriteWithoutAPartitionFailsAndIsRetriedOnceAfterTheyAreCreated() {
        maintainer.ensureAhead();
        UUID cluster = UUID.randomUUID();
        Instant tomorrow = Instant.now().plus(Duration.ofDays(1));
        jdbc.execute("ALTER TABLE metric_sample DETACH PARTITION " + day("metric_sample", 1));
        jdbc.execute("DROP TABLE " + day("metric_sample", 1));
        try {
            assertThatThrownBy(() -> insertSample(tomorrow, cluster))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("no partition of relation");

            maintainer.retryingNoPartition(() -> {
                insertSample(tomorrow, cluster);
                return null;
            });

            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM metric_sample WHERE cluster_id = ?", Long.class, cluster))
                    .isEqualTo(1);
        } finally {
            maintainer.ensureAhead();
            jdbc.update("DELETE FROM metric_sample WHERE cluster_id = ?", cluster);
        }
    }

    @Test
    void anotherCheckViolationIsNotRetried() {
        int[] attempts = {0};

        assertThatThrownBy(() -> maintainer.retryingNoPartition(() -> {
                    attempts[0]++;
                    jdbc.update("INSERT INTO broker_node (name, ha_designation, cluster_id)"
                            + " VALUES ('n', 'NOPE', gen_random_uuid())");
                    return null;
                }))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(attempts[0]).isEqualTo(1);
    }

    @Test
    void theHealthCheckIsDownWhileTheCurrentPeriodHasNoPartitionAndNamesTheTable() {
        maintainer.ensureAhead();
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        String today = day("rr_event", 0);
        jdbc.execute("ALTER TABLE rr_event DETACH PARTITION " + today);
        jdbc.execute("DROP TABLE " + today);
        try {
            Health down = health.health();

            assertThat(down.getStatus()).isEqualTo(Status.DOWN);
            assertThat(down.getDetails()).containsEntry("missing", List.of("rr_event"));
        } finally {
            maintainer.ensureAhead();
        }
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aClosedPartitionIsFrozenOnceAndTheParentsAreAnalyzed() {
        TestPartitions.ensure(
                jdbc, "broker_event", LocalDate.now(ZoneOffset.UTC).minusDays(5));
        String closed = day("broker_event", -5);
        String open = day("broker_event", 0);

        maintainer.maintain();

        assertThat(comment(closed)).isEqualTo("frozen");
        assertThat(comment(open)).isNull();
        maintainer.maintain();
        assertThat(comment(closed)).as("a frozen partition stays marked").isEqualTo("frozen");
        assertThat(jdbc.queryForObject(
                        "SELECT last_analyze FROM pg_stat_all_tables WHERE relid = to_regclass('broker_event')",
                        Timestamp.class))
                .isNotNull();
    }

    @Test
    void dropOldestDetachesAndDropsOnlyPartitionsWhollyBeforeTheCutoff() {
        PartitionSpec spec = spec("rr_event");
        TestPartitions.ensure(jdbc, "rr_event", LocalDate.now(ZoneOffset.UTC).minusDays(9));
        TestPartitions.ensure(jdbc, "rr_event", LocalDate.now(ZoneOffset.UTC).minusDays(8));
        Instant cutoff = Instant.now().minus(Duration.ofDays(7));

        assertThat(drops.expired(spec, cutoff)).contains(day("rr_event", -9), day("rr_event", -8));
        assertThat(drops.preview(spec, cutoff).bytes()).isPositive();
        long dropped = 0;
        long batch;
        do {
            batch = drops.dropOldest(spec, cutoff);
            dropped += batch;
        } while (batch > 0);

        assertThat(dropped).isGreaterThanOrEqualTo(2);
        assertThat(drops.expired(spec, cutoff)).isEmpty();
        assertThat(partitions("rr_event")).contains(day("rr_event", 0)).doesNotContain(day("rr_event", -9));
    }

    private String comment(String partition) {
        return jdbc.queryForObject("SELECT obj_description(to_regclass(?), 'pg_class')", String.class, partition);
    }
}
