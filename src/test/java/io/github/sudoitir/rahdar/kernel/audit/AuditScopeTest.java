package io.github.sudoitir.rahdar.kernel.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** An event begun while {@link AuditScope#PARENT} is bound names that parent; one begun outside names none. */
class AuditScopeTest extends PostgresIntegrationTest {

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aBoundParentLinksTheChild() {
        AuditEvent parent = begin("bulk.delete");

        AuditEvent child = ScopedValue.where(AuditScope.PARENT, parent.getId()).call(() -> begin("DELETE_QUEUE"));

        assertThat(parentOf(child)).isEqualTo(parent.getId());
    }

    @Test
    void anUnboundScopeLeavesTheParentNull() {
        assertThat(parentOf(begin("DELETE_QUEUE"))).isNull();
    }

    private AuditEvent begin(String action) {
        return audit.begin(Actor.ofSystem(), action, "QUEUE", "orders", null, null, Map.of(), false);
    }

    private Long parentOf(AuditEvent event) {
        return jdbc.queryForObject("SELECT parent_id FROM audit_event WHERE id = ?", Long.class, event.getId());
    }
}
