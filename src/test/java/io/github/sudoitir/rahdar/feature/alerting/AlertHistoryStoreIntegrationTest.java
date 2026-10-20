package io.github.sudoitir.rahdar.feature.alerting;

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

/** The alert-history store against a real Postgres (ADR-0134). */
class AlertHistoryStoreIntegrationTest extends PostgresIntegrationTest {

    private static final String OLD = "now() - interval '100 days'";

    @Autowired
    AlertHistoryStore store;

    @Autowired
    JdbcTemplate jdbc;

    private UUID clusterId;
    private UUID ruleId;
    private UUID channelId;

    @BeforeEach
    void seed() {
        clusterId = jdbc.queryForObject(
                "INSERT INTO cluster (name, system) VALUES (?, 'artemis') RETURNING id",
                UUID.class,
                "hist-" + UUID.randomUUID());
        ruleId = jdbc.queryForObject(
                "INSERT INTO alert_rule (name, kind, state_condition, cluster_id)"
                        + " VALUES ('r', 'STATE', 'NODE_DOWN', ?) RETURNING id",
                UUID.class,
                clusterId);
        channelId = jdbc.queryForObject(
                "INSERT INTO notification_channel (name, kind, config) VALUES (?, 'WEBHOOK', '{}') RETURNING id",
                UUID.class,
                "hist-" + UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            firing("old-" + i, OLD, OLD);
        }
        firing("old-open", OLD, null);
        firing("recent", "now()", "now()");
        for (String state : new String[] {"SENT", "SENT", "SENT", "DEAD"}) {
            delivery(state, OLD);
        }
        delivery("PENDING", OLD);
        delivery("SENT", "now()");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM cluster WHERE id = ?", clusterId);
        jdbc.update("DELETE FROM notification_channel WHERE id = ?", channelId);
    }

    private void firing(String subject, String startedAt, String resolvedAt) {
        jdbc.update(
                "INSERT INTO alert_firing (started_at, resolved_at, subject_key, severity, rule_id, cluster_id)"
                        + " VALUES (" + startedAt + ", " + resolvedAt + ", ?, 'WARNING', ?, ?)",
                subject,
                ruleId,
                clusterId);
    }

    private void delivery(String state, String createdAt) {
        jdbc.update(
                "INSERT INTO alert_delivery (created_at, state, payload, rule_id, channel_id)" + " VALUES (" + createdAt
                        + ", ?, '{}', ?, ?)",
                state,
                ruleId,
                channelId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE rule_id = ?", Long.class, ruleId);
    }

    @Test
    void previewCountsWhatQualifiesAndDeletesNothing() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));

        assertThat(store.preview(cutoff).rows()).isEqualTo(9);

        assertThat(count("alert_firing")).isEqualTo(7);
        assertThat(count("alert_delivery")).isEqualTo(6);
    }

    @Test
    void purgesResolvedFiringsAndFinishedDeliveriesInBatchesAndKeepsTheRest() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));

        long total = 0;
        long batch;
        int batches = 0;
        while ((batch = store.purgeBatch(cutoff, 2)) > 0) {
            assertThat(batch).isLessThanOrEqualTo(2);
            total += batch;
            batches++;
        }

        assertThat(total).isEqualTo(9);
        assertThat(batches).isEqualTo(5);
        assertThat(store.purgeBatch(cutoff, 2)).isZero();
        assertThat(count("alert_firing")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM alert_firing WHERE rule_id = ? AND resolved_at IS NULL",
                        Long.class,
                        ruleId))
                .isEqualTo(1);
        assertThat(count("alert_delivery")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM alert_delivery WHERE rule_id = ? AND state = 'PENDING'",
                        Long.class,
                        ruleId))
                .isEqualTo(1);
    }
}
