package io.github.sudoitir.rahdar.platform.governance;

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

/** The classification-findings store against a real Postgres (ADR-0134). */
class ClassificationFindingsStoreIntegrationTest extends PostgresIntegrationTest {

    private static final String OLD = "now() - interval '100 days'";

    @Autowired
    ClassificationFindingsStore store;

    @Autowired
    JdbcTemplate jdbc;

    private String address;

    /** Four findings not seen for 100 days, two of them decided by an operator, and one seen now. */
    @BeforeEach
    void seed() {
        address = "orders-" + UUID.randomUUID();
        finding("a", "OPEN", OLD);
        finding("b", "OPEN", OLD);
        finding("c", "CONFIRMED", OLD);
        finding("d", "DISMISSED", OLD);
        finding("e", "OPEN", "now()");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM classification_finding WHERE destination = ?", address);
    }

    private void finding(String path, String status, String lastSeenAt) {
        jdbc.update(
                "INSERT INTO classification_finding (first_seen_at, last_seen_at, hit_count, destination, location,"
                        + " field_path, data_class, status) VALUES (" + OLD + ", " + lastSeenAt
                        + ", 1, ?, 'BODY', ?, 'PAN', ?)",
                address,
                path,
                status);
    }

    private long count() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM classification_finding WHERE destination = ?", Long.class, address);
    }

    @Test
    void previewCountsStaleFindingsAndDeletesNothing() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));

        assertThat(store.preview(cutoff).rows()).isEqualTo(2);

        assertThat(count()).isEqualTo(5);
    }

    @Test
    void purgesStaleOpenFindingsInBatchesAndKeepsDecidedAndRecentOnes() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));

        long total = 0;
        long batch;
        while ((batch = store.purgeBatch(cutoff, 1)) > 0) {
            assertThat(batch).isLessThanOrEqualTo(1);
            total += batch;
        }

        assertThat(total).isEqualTo(2);
        assertThat(store.purgeBatch(cutoff, 3)).isZero();
        assertThat(count()).isEqualTo(3);
    }
}
