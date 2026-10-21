package com.acme.notes;

import io.github.sudoitir.rahdar.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.rahdar.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.rahdar.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreDef;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Puts the notes under Rahdar's data lifecycle: they appear on Administration → Data, where an
 * operator sets how long they are kept, previews a purge and sets a quota. Rahdar runs the purge on
 * one instance, in small batches, and audits it. Never write your own pruning job for a table that
 * grows with use; contribute a store like this one.
 */
@Component
class NotesStore implements HousekeepingContributor, ManagedStore {

    private static final String TABLE = "note";
    private static final String OLDER = "created_at < ?";
    private static final StoreDef DEF = new StoreDef(
            "notes",
            "Subscription notes",
            List.of(TABLE),
            StoreDef.QuotaUnit.ROWS,
            Duration.ofDays(365),
            Duration.ofDays(30),
            null);

    private final JdbcTemplate jdbc;

    NotesStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public List<ManagedStore> stores() {
        return List.of(this);
    }

    @Override
    public StoreDef def() {
        return DEF;
    }

    @Override
    public StoreUsage usage() {
        return LifecycleSql.usage(jdbc, DEF.tables());
    }

    @Override
    public PurgeEstimate preview(Instant cutoff) {
        return LifecycleSql.estimate(jdbc, TABLE, OLDER, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, TABLE, OLDER, cutoff, limit);
    }
}
