package io.github.sudoitir.rahdar.kernel.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pending audit row is durable before the broker call it describes, whatever transaction the
 * caller is in (ADR-0078), and a caller that rolls back never leaves a row that looks in flight.
 */
class AuditServiceTransactionTest extends PostgresIntegrationTest {

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void thePendingRowIsCommittedBeforeTheCallerCommits() throws Exception {
        TransactionTemplate caller = new TransactionTemplate(transactions);

        String seenFromAnotherConnection = caller.execute(status -> {
            AuditEvent event = begin();
            // Stands in for the broker call: another connection must already see the row.
            return CompletableFuture.supplyAsync(() -> outcome(event.getId())).join();
        });

        assertThat(seenFromAnotherConnection).isEqualTo("PENDING");
    }

    @Test
    void anOutcomeIsRecordedEvenIfTheCallerRollsBack() {
        TransactionTemplate caller = new TransactionTemplate(transactions);
        Long[] id = new Long[1];

        caller.executeWithoutResult(status -> {
            AuditEvent event = begin();
            id[0] = event.getId();
            audit.succeed(event, 3);
            status.setRollbackOnly();
        });

        assertThat(outcome(id[0])).isEqualTo("SUCCESS");
    }

    @Test
    void aRolledBackCallerWithNoOutcomeLeavesAFailureNotAPendingRow() {
        TransactionTemplate caller = new TransactionTemplate(transactions);
        Long[] id = new Long[1];

        caller.executeWithoutResult(status -> {
            id[0] = begin().getId();
            status.setRollbackOnly();
        });

        assertThat(outcome(id[0])).isEqualTo("FAILURE");
        assertThat(jdbc.queryForObject("SELECT error FROM audit_event WHERE id = ?", String.class, id[0]))
                .isEqualTo(AuditService.ROLLED_BACK);
    }

    @Test
    void aPartialFailureRecordsTheCountAffectedBeforeIt() {
        AuditEvent event = begin();

        audit.failPartial(event, 7, "Stopped after 7 of 10: connection reset");

        assertThat(outcome(event.getId())).isEqualTo("FAILURE");
        assertThat(jdbc.queryForObject(
                        "SELECT affected_count FROM audit_event WHERE id = ?", Long.class, event.getId()))
                .isEqualTo(7L);
    }

    private AuditEvent begin() {
        return audit.begin(Actor.ofSystem(), "DELETE_MESSAGES", "QUEUE", "orders", null, null, Map.of(), false);
    }

    private String outcome(Long id) {
        return jdbc.queryForObject("SELECT outcome FROM audit_event WHERE id = ?", String.class, id);
    }
}
