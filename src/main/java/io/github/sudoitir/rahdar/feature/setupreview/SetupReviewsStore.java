package io.github.sudoitir.rahdar.feature.setupreview;

import io.github.sudoitir.rahdar.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.rahdar.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.rahdar.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreDef;
import io.github.sudoitir.rahdar.kernel.lifecycle.StoreUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The setup review under the data lifecycle (ADR-0134). {@code artemis_setup_review} holds one row per
 * cluster, the latest review, rewritten in place, so no review is ever older than the newest one
 * of its cluster and none is purged (a cluster's delete cascades to it). {@code artemis_setup_finding} is
 * the latest finding per subject; the stale ones are those a run could not re-check, whose
 * {@code last_seen_at} stopped advancing, and only those go. {@code artemis_setup_finding_acceptance} is
 * configuration and is not touched.
 */
@Component
@RequiredArgsConstructor
class SetupReviewsStore implements HousekeepingContributor, ManagedStore {

    private static final String FINDING_TABLE = "artemis_setup_finding";
    private static final String STALE_FINDINGS = "last_seen_at < ?";

    private static final StoreDef DEF = new StoreDef(
            "setup-reviews",
            "Setup reviews",
            List.of("artemis_setup_review", FINDING_TABLE),
            StoreDef.QuotaUnit.ROWS,
            Duration.ofDays(90),
            Duration.ofDays(1),
            Duration.ofDays(3650));

    private final JdbcTemplate jdbc;

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
        return LifecycleSql.estimate(jdbc, FINDING_TABLE, STALE_FINDINGS, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, FINDING_TABLE, STALE_FINDINGS, cutoff, limit);
    }
}
