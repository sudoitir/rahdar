package io.github.sudoitir.rahdar.feature.brokerconfig;

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
 * The broker configuration history under the data lifecycle (ADR-0134): old applies, then the old
 * revisions nothing depends on any more.
 *
 * <ul>
 *   <li>An apply is kept while a node state names it ({@code basis_ref} of a verified or drifted
 *       node; an adopted node's {@code basis_ref} is a revision number, not an apply).
 *   <li>A revision is kept while it is a declaration's current one, an apply that is kept still
 *       points at it (that foreign key would refuse the delete), an owned item was last written by
 *       it, or a node state verified it. Applies go first, so a revision frees up in a later batch
 *       of the same run.
 * </ul>
 */
@Component
@RequiredArgsConstructor
class BrokerConfigHistoryStore implements HousekeepingContributor, ManagedStore {

    /** A node state that a verified or drifted node names as the apply that wrote it. */
    private static String namedByNodeState(String apply) {
        return "EXISTS (SELECT 1 FROM artemis_config_node_state ns WHERE ns.basis_ref = " + apply
                + ".id AND ns.basis IS DISTINCT FROM 'ADOPTED')";
    }

    private static final String APPLY_TABLE = "artemis_config_apply";
    private static final String REVISION_TABLE = "artemis_config_revision";
    private static final String APPLIES = "started_at < ? AND NOT " + namedByNodeState(APPLY_TABLE);

    /**
     * The cutoff is bound once, as {@code k.at}, because an apply that would itself be purged must
     * not keep its revision: the preview then counts what the whole run removes.
     */
    private static final String REVISIONS = "EXISTS (SELECT 1 FROM (SELECT ?::timestamptz AS at) k"
            + " WHERE artemis_config_revision.created_at < k.at"
            + " AND NOT EXISTS (SELECT 1 FROM artemis_config_declaration d"
            + " WHERE d.current_revision_id = artemis_config_revision.id)"
            + " AND NOT EXISTS (SELECT 1 FROM artemis_config_apply a WHERE a.revision_id = artemis_config_revision.id"
            + " AND (a.started_at >= k.at OR " + namedByNodeState("a") + "))"
            + " AND NOT EXISTS (SELECT 1 FROM artemis_config_owned_item o"
            + " WHERE o.revision_id = artemis_config_revision.id)"
            + " AND NOT EXISTS (SELECT 1 FROM artemis_config_node_state ns"
            + " WHERE ns.cluster_id = artemis_config_revision.cluster_id"
            + " AND ns.verified_revision = artemis_config_revision.revision))";

    private static final StoreDef DEF = new StoreDef(
            "broker-config-history",
            "Broker configuration history",
            List.of(REVISION_TABLE, APPLY_TABLE),
            StoreDef.QuotaUnit.ROWS,
            Duration.ofDays(365),
            Duration.ofDays(7),
            null);

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
        PurgeEstimate applies = LifecycleSql.estimate(jdbc, APPLY_TABLE, APPLIES, cutoff);
        PurgeEstimate revisions = LifecycleSql.estimate(jdbc, REVISION_TABLE, REVISIONS, cutoff);
        return new PurgeEstimate(applies.rows() + revisions.rows(), applies.bytes() + revisions.bytes());
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        long applies = LifecycleSql.deleteBatch(jdbc, APPLY_TABLE, APPLIES, cutoff, limit);
        return applies > 0 ? applies : LifecycleSql.deleteBatch(jdbc, REVISION_TABLE, REVISIONS, cutoff, limit);
    }
}
