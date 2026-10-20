package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.kernel.security.TableSealedStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The sealed originals of governed messages, for key rotation (ADR-0132). */
@Component
class MessageIndexSealedStore extends TableSealedStore {

    MessageIndexSealedStore(JdbcTemplate jdbc) {
        super(jdbc, "message_index", "node_id", "subscription", "message_id", "observed_at");
    }
}
