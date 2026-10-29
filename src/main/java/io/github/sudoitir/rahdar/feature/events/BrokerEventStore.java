package io.github.sudoitir.rahdar.feature.events;

import io.github.sudoitir.rahdar.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.rahdar.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.rahdar.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreDef;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreDef.QuotaUnit;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreUsage;
import io.github.sudoitir.rahdar.kernel.lifecycle.partition.Grain;
import io.github.sudoitir.rahdar.kernel.lifecycle.partition.PartitionDrops;
import io.github.sudoitir.rahdar.kernel.lifecycle.partition.PartitionSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The {@code broker_event} history under the data lifecycle (ADR-0028, ADR-0134). Volume follows
 * broker chatter Rahdar does not control, so retention is what bounds it next to the writer's buffer. A batch
 * drops one daily partition older than the cutoff (ADR-0193).
 */
@Component
class BrokerEventStore implements ManagedStore, HousekeepingContributor {

    private static final String TABLE = "broker_event";

    /** Written by the database's clock, never the broker's, so a skewed broker cannot miss a partition. */
    static final PartitionSpec PARTITIONS = new PartitionSpec(
            TABLE,
            "received_at",
            Grain.DAY,
            7,
            "fillfactor=100, autovacuum_vacuum_insert_scale_factor=0, autovacuum_vacuum_insert_threshold=5000");

    private static final StoreDef DEF = new StoreDef(
            "broker-events",
            "Broker events",
            List.of(TABLE),
            QuotaUnit.ROWS,
            Duration.ofHours(72),
            Duration.ofHours(1),
            Duration.ofDays(90));

    private final JdbcTemplate jdbc;
    private final PartitionDrops drops;

    BrokerEventStore(JdbcTemplate jdbc, PartitionDrops drops) {
        this.jdbc = jdbc;
        this.drops = drops;
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
        return drops.preview(PARTITIONS, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return drops.dropOldest(PARTITIONS, cutoff);
    }

    /** Twenty million rows, about what a day of heavy broker chatter on a few clusters holds. */
    @Override
    public int defaultQuota() {
        return 20_000;
    }

    /**
     * Drops the oldest daily partition before today, and when only today's is left deletes its oldest rows, so the
     * partition being written is never dropped.
     */
    @Override
    public long trimOldest(long excessRows, int limit) {
        long dropped = drops.dropOldest(PARTITIONS, Instant.now().truncatedTo(ChronoUnit.DAYS));
        if (dropped > 0) {
            return dropped;
        }
        return jdbc.update(
                "DELETE FROM " + TABLE + " WHERE (seq, received_at) IN (SELECT seq, received_at FROM " + TABLE
                        + " ORDER BY received_at, seq LIMIT ?)",
                Math.min(excessRows, limit));
    }
}
