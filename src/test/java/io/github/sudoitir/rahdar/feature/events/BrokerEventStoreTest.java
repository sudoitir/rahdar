package io.github.sudoitir.rahdar.feature.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@link BrokerEventStore} against a real Postgres: only rows past the cutoff go, in bounded batches. */
class BrokerEventStoreTest extends PostgresIntegrationTest {

    @Autowired
    BrokerEventStore store;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final UUID clusterId = UUID.randomUUID();

    @BeforeEach
    void seedCluster() {
        jdbc.update(
                "INSERT INTO cluster (id, name, system) VALUES (:id, :name, 'artemis')",
                Map.of("id", clusterId, "name", "events-store-" + clusterId));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM cluster WHERE id = :id", Map.of("id", clusterId));
    }

    private void event(int ageHours) {
        jdbc.update("""
                INSERT INTO broker_event (occurred_at, received_at, type, cluster_id)
                VALUES (now(), now() - make_interval(hours => :age), 'CONSUMER_CREATED', :c)
                """, Map.of("age", ageHours, "c", clusterId));
    }

    private int remaining() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM broker_event WHERE cluster_id = :c", Map.of("c", clusterId), Integer.class);
    }

    @Test
    void previewCountsOldRowsAndDeletesNothing() {
        event(100);
        event(98);
        event(1);
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);

        PurgeEstimate estimate = store.preview(cutoff);

        assertThat(estimate.rows()).isGreaterThanOrEqualTo(2);
        assertThat(remaining()).isEqualTo(3);
    }

    @Test
    void purgeBatchDropsOnlyDaysPastTheCutoffAndEndsAtZero() {
        for (int i = 0; i < 5; i++) {
            event(100 + i);
        }
        event(71);
        event(1);
        Instant cutoff = Instant.now().minus(72, ChronoUnit.HOURS);

        long total = 0;
        long batch;
        do {
            batch = store.purgeBatch(cutoff, 2);
            total += batch;
        } while (batch > 0);

        // a whole expired day goes at once, so what counts is that every expired row is gone
        assertThat(total).isPositive();
        assertThat(remaining()).isEqualTo(2);
        assertThat(store.purgeBatch(cutoff, 2)).isZero();
    }
}
