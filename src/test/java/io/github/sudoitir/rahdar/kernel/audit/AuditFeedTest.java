package io.github.sudoitir.rahdar.kernel.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The audit feed is in commit order and gap-free, and a record's history cannot be rewritten. */
class AuditFeedTest extends PostgresIntegrationTest {

    @Autowired
    AuditFeed feed;

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void aRecordCommittedLaterIsHeldBackUntilTheEarlierTransactionEnds() throws Exception {
        String action = unique();
        AuditPosition from = latest();
        try (Connection early = dataSource.getConnection();
                Connection late = dataSource.getConnection()) {
            early.setAutoCommit(false);
            insert(early, action + "-early");
            insert(late, action + "-late");

            assertThat(actions(from, action)).isEmpty();

            early.commit();

            assertThat(actions(from, action)).containsExactly(action + "-early", action + "-late");
        }
    }

    @Test
    void anOutcomeMovesTheRecordToALaterPosition() {
        AuditEvent event = begin(unique());
        AuditPosition pending = position(event.getId());

        audit.succeed(event, 2);

        AuditRecord done = feed.byId(event.getId() - 1, 1).getFirst();
        assertThat(done.outcome()).isEqualTo("SUCCESS");
        assertThat(done.position()).isGreaterThan(pending);
        assertThat(feed.after(pending, AuditFeed.MAX_LIMIT))
                .extracting(AuditRecord::id)
                .contains(event.getId());
    }

    @Test
    void theGuardRefusesChangingWhatTheRecordSays() {
        AuditEvent event = begin(unique());
        long id = event.getId();

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET action = 'OTHER' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("only its outcome may be changed");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET username = 'x' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theGuardRefusesChangingAFinishedOutcome() {
        AuditEvent event = begin(unique());
        long id = event.getId();
        audit.succeed(event, 1);

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET outcome = 'FAILURE' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("final");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET affected_count = 9 WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void aRefusalCanStillBeRecounted() {
        AuditEvent event = audit.refused(Actor.ofSystem(), unique(), "QUEUE", "orders", null, Map.of(), "no");
        long id = event.getId();

        audit.recount(event, 5);

        assertThat(jdbc.queryForObject(
                        "SELECT affected_count FROM audit_event WHERE id = ?", Long.class, event.getId()))
                .isEqualTo(5L);
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET error = 'other' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    private AuditPosition latest() {
        return jdbc
                .query(
                        "SELECT pos_txid::text, pos_seq FROM audit_event ORDER BY pos_txid DESC, pos_seq DESC LIMIT 1",
                        (rs, i) -> new AuditPosition(Long.parseLong(rs.getString(1)), rs.getLong(2)))
                .stream()
                .findFirst()
                .orElse(AuditPosition.START);
    }

    private List<String> actions(AuditPosition from, String prefix) {
        return feed.after(from, AuditFeed.MAX_LIMIT).stream()
                .map(AuditRecord::action)
                .filter(a -> a.startsWith(prefix))
                .toList();
    }

    private AuditEvent begin(String action) {
        return audit.begin(Actor.ofSystem(), action, "QUEUE", "orders", null, null, Map.of(), false);
    }

    private AuditPosition position(Long id) {
        return jdbc.queryForObject(
                "SELECT pos_txid::text, pos_seq FROM audit_event WHERE id = ?",
                (rs, i) -> new AuditPosition(Long.parseLong(rs.getString(1)), rs.getLong(2)),
                id);
    }

    private static void insert(Connection connection, String action) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO audit_event (action) VALUES ('" + action + "')");
        }
    }

    private static String unique() {
        return "FEED_" + UUID.randomUUID();
    }
}
