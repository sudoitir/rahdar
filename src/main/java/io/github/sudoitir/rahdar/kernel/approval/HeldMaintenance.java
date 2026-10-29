package io.github.sudoitir.rahdar.kernel.approval;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.HeldState;
import io.github.sudoitir.rahdar.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.rahdar.kernel.plugin.PluginStatusChanged;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ends held requests nobody will finish (ADR-0180): those past their expiry or run deadline, those whose provider was
 * removed, those of a cluster that was deleted, and those whose running replica disappeared, which become {@code OUTCOME_UNKNOWN} and never run again. Each
 * batch is one transaction that skips rows another writer holds, and every time check is the database's. Expiry is
 * exact even when the job is late, because the vote and the claim check the time themselves.
 */
@Component
@Slf4j
class HeldMaintenance {

    static final int BATCH = 100;

    private static final String DESIRED_ACTIVE = PluginInstallStatus.desiredActiveDbValues().stream()
            .map(status -> "'" + status + "'")
            .collect(Collectors.joining(", "));

    /** Open and not yet running, of a provider or a plugin that is no longer meant to run. */
    private static final String ORPHANED = "state IN ('HELD', 'APPROVED') AND (provider_id NOT IN (SELECT id FROM"
            + " plugin_install WHERE approval_provider AND status IN (" + DESIRED_ACTIVE + ")) OR (position(':' IN"
            + " type) > 0 AND split_part(type, ':', 1) NOT IN (SELECT id FROM plugin_install WHERE status IN ("
            + DESIRED_ACTIVE + "))))";

    /** Open and not yet running, of a cluster that has been deleted. */
    private static final String CLUSTER_GONE =
            "state IN ('HELD', 'APPROVED') AND cluster_id IS NOT NULL AND cluster_id NOT IN (SELECT id FROM cluster)";

    static final String CLUSTER_DELETED = "The cluster was deleted, so this request was cancelled.";

    static final String PROVIDER_REMOVED = "The approval provider was removed, so this request was cancelled.";

    private final HeldStore store;
    private final ApprovalNotices notices;
    private final AuditService audit;
    private final ReplicaRegistry replicas;
    private final GateBounds bounds;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    HeldMaintenance(
            HeldStore store,
            ApprovalNotices notices,
            AuditService audit,
            ReplicaRegistry replicas,
            GateBounds bounds,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.notices = notices;
        this.audit = audit;
        this.replicas = replicas;
        this.bounds = bounds;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
    }

    /** The {@code approval-expiry} job: expiry, run deadlines, lost runs and orphans, each until none is left. */
    void sweep() {
        drain("state = 'HELD' AND expires_at <= now()", List.of(), HeldState.EXPIRED, "No one decided it in time.");
        drain(
                "state = 'APPROVED' AND run_deadline <= now()",
                List.of(),
                HeldState.EXPIRED,
                "It was approved but did not run before its run window closed.");
        String alive = replicas.aliveIds().stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        drain(
                "state = 'EXECUTING' AND claimed_at < now() - make_interval(secs => ?)"
                        + " AND NOT (claimed_by = ANY (?::uuid[]))",
                List.of(bounds.runLease().toSeconds(), alive),
                HeldState.OUTCOME_UNKNOWN,
                "The Rahdar instance running it stopped before recording the outcome. It will not run again; check"
                        + " the audit log, then request it again if needed.");
        drain(ORPHANED, List.of(), HeldState.CANCELLED, PROVIDER_REMOVED);
        drain(CLUSTER_GONE, List.of(), HeldState.CANCELLED, CLUSTER_DELETED);
    }

    /** A cluster was deleted on any replica, once its transaction committed: its open requests end now. */
    @EventListener(condition = "#signal.kind() == 'cluster-deleted'")
    void onClusterDeleted(ReplicaSignal signal) {
        drain(CLUSTER_GONE, List.of(), HeldState.CANCELLED, CLUSTER_DELETED);
    }

    private void drain(String where, List<Object> args, HeldState to, String detail) {
        int ended;
        do {
            ended = tx.execute(status -> endAll(where, args, to, detail).size());
        } while (ended == BATCH);
    }

    /** Ends one batch in the caller's transaction, with each one's event and notices. */
    private List<HeldRow> endAll(String where, List<Object> args, HeldState to, String detail) {
        List<HeldRow> rows = store.endBatch(where, args, to, detail, BATCH);
        for (HeldRow row : rows) {
            store.event(row.id(), Executions.kindOf(to), null, null, detail);
        }
        notices.ended(rows);
        if (!rows.isEmpty()) {
            log.info("approval-gate ended {} request(s) as {}: {}", rows.size(), to, detail);
        }
        return rows;
    }

    /**
     * A plugin left the statuses where it is meant to run. Inside the transaction that removes it, every open request
     * its provider held, and every request for its own operations, is cancelled; if it was the provider, its removal is
     * audited. A request already running finishes; the sweep catches what a locked row made this miss.
     */
    @EventListener
    void onPluginStatusChanged(PluginStatusChanged changed) {
        if (changed.to().desiredActive()) {
            return;
        }
        String plugin = changed.pluginId();
        List<HeldRow> cancelled = new ArrayList<>();
        List<HeldRow> batch;
        do {
            batch = endAll(
                    "state IN ('HELD', 'APPROVED') AND (provider_id = ? OR type LIKE ?)",
                    List.of(
                            plugin,
                            plugin.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + ":%"),
                    HeldState.CANCELLED,
                    PROVIDER_REMOVED);
            cancelled.addAll(batch);
        } while (batch.size() == BATCH);
        Boolean provider = jdbc
                .queryForList("SELECT approval_provider FROM plugin_install WHERE id = ?", Boolean.class, plugin)
                .stream()
                .findFirst()
                .orElse(false);
        if (Boolean.TRUE.equals(provider) || !cancelled.isEmpty()) {
            AuditEvent event = audit.begin(
                    new Actor(changed.actor(), null, null, null),
                    "GATE_PROVIDER_REMOVED",
                    "PLUGIN",
                    plugin,
                    null,
                    null,
                    Map.of(
                            "from", changed.from().dbValue(),
                            "to", changed.to().dbValue(),
                            "cancelled", cancelled.size()),
                    false);
            audit.succeed(event, cancelled.size());
            log.warn(
                    "approval-gate provider-removed plugin={} to={} by={} cancelled={}",
                    plugin,
                    changed.to().dbValue(),
                    changed.actor(),
                    cancelled.size());
        }
    }
}
