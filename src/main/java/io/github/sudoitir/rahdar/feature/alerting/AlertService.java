package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertFiringEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertFiringRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.AlertFiringView;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.ClusterFiringCountView;
import io.github.sudoitir.rahdar.kernel.core.Cursor;
import io.github.sudoitir.rahdar.kernel.core.HeavyRequests;
import io.github.sudoitir.rahdar.kernel.core.HistoryPage;
import io.github.sudoitir.rahdar.kernel.core.ReadRouting;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.core.Routed;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of alert firings — current and historical (alerting spec). A cluster's view also holds
 * the installation's firings (ADR-0135), for a caller with the global grant.
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertFiringRepository firingRepo;
    private final AlertRuleRepository ruleRepo;
    private final AlertViewMapper mapper;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver perm;
    private final AlertRuleService ruleService;
    private final ActorResolver actors;
    private final HeavyRequests heavy;
    private final ReadRouting routing;

    /** A page of the history: a few at once per user, the page size at most, and the database's 30 s. */
    static final HeavyRequests.Endpoint HISTORY =
            new HeavyRequests.Endpoint("alert history reads", 4, Duration.ofSeconds(30), ResourceQuery.MAX_SIZE);

    @Transactional(readOnly = true)
    public List<AlertFiringView> firingNow(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_READ);
        boolean everything = clusterAccess.holds(clusterId, Permissions.SUBSCRIPTION_READ);
        List<AlertFiringEntity> open = firingRepo.findOpenVisible(
                clusterId, perm.can(AlertPermissions.ALERT_READ), everything, visibleRuleIds(clusterId, everything));
        Map<UUID, String> names = ruleNames(open);
        return open.stream()
                .map(f -> mapper.firing(f, names.get(f.getRuleId())))
                .toList();
    }

    /**
     * The cluster's firings, newest first, resolved and open. By page number for the first
     * {@value HistoryPage#MAX_OFFSET} rows, with a total; by {@code after} beyond that, which costs the same however
     * deep it reads and has no total. Read from the replica when there is one and it is current.
     */
    public HistoryPage<AlertFiringView> history(UUID clusterId, ResourceQuery paging, Cursor after) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_READ);
        if (after == null) {
            HistoryPage.requireReachable(paging);
        }
        boolean everything = clusterAccess.holds(clusterId, Permissions.SUBSCRIPTION_READ);
        boolean installation = perm.can(AlertPermissions.ALERT_READ);
        List<UUID> ruleIds = visibleRuleIds(clusterId, everything);
        Routed<Slice> routed = heavy.run(
                HISTORY,
                actors.resolve().displayName(),
                run -> run.read(
                        routing, client -> slice(client, clusterId, installation, everything, ruleIds, paging, after)));
        Slice slice = routed.value();
        boolean more = slice.rows().size() > paging.size();
        List<AlertFiringView> shown = more ? slice.rows().subList(0, paging.size()) : slice.rows();
        return new HistoryPage<>(
                shown,
                paging.page(),
                paging.size(),
                slice.count(),
                more,
                more ? new Cursor(shown.getLast().seq(), 0).encode() : null,
                routed.fromReplica());
    }

    private record Slice(List<AlertFiringView> rows, Long count) {}

    /** One more row than the page holds, so there is a next page when it comes back; and the total, by page number. */
    private static Slice slice(
            JdbcClient client,
            UUID clusterId,
            boolean installation,
            boolean everything,
            List<UUID> ruleIds,
            ResourceQuery paging,
            Cursor after) {
        StringBuilder visible = new StringBuilder("((f.cluster_id = :cluster");
        visible.append(everything ? "" : " AND f.rule_id IN (:ruleIds)").append(")");
        visible.append(installation ? " OR f.cluster_id IS NULL" : "").append(")");
        JdbcClient.StatementSpec spec = client.sql(
                        "SELECT f.seq, f.rule_id, f.cluster_id, r.name AS rule_name, f.subject_key, f.severity,"
                                + " f.value, f.started_at, f.resolved_at FROM alert_firing f"
                                + " LEFT JOIN alert_rule r ON r.id = f.rule_id WHERE " + visible
                                + (after == null ? "" : " AND f.seq < :afterSeq")
                                + " ORDER BY f.seq DESC LIMIT :limit OFFSET :offset")
                .param("cluster", clusterId)
                .param("limit", paging.size() + 1)
                .param("offset", after == null ? paging.offset() : 0);
        if (!everything) {
            spec = spec.param("ruleIds", ruleIds);
        }
        if (after != null) {
            spec = spec.param("afterSeq", after.major());
        }
        List<AlertFiringView> rows = spec.query(AlertService::row).list();
        if (after != null) {
            return new Slice(rows, null);
        }
        JdbcClient.StatementSpec count = client.sql("SELECT count(*) FROM alert_firing f WHERE " + visible)
                .param("cluster", clusterId);
        if (!everything) {
            count = count.param("ruleIds", ruleIds);
        }
        return new Slice(rows, count.query(Long.class).single());
    }

    private static AlertFiringView row(ResultSet rs, int i) throws SQLException {
        OffsetDateTime resolved = rs.getObject("resolved_at", OffsetDateTime.class);
        return new AlertFiringView(
                rs.getLong("seq"),
                rs.getObject("rule_id", UUID.class),
                rs.getObject("cluster_id", UUID.class),
                rs.getString("rule_name"),
                rs.getString("subject_key"),
                rs.getString("severity"),
                rs.getObject("value", Double.class),
                rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                resolved == null ? null : resolved.toInstant());
    }

    /**
     * Cross-cluster open-firing counts for the shell badge, filtered to what the caller may see.
     * The installation's count has a null cluster, which the filter passes only for the global grant.
     */
    @PostFilter(
            "@perm.can(filterObject.clusterId(), T(io.github.sudoitir.rahdar.feature.alerting.AlertPermissions).ALERT_READ)")
    @Transactional(readOnly = true)
    public List<ClusterFiringCountView> firingCounts() {
        return firingRepo.firingCountsByCluster().stream()
                .map(c -> new ClusterFiringCountView(c.getClusterId(), visibleCount(c.getClusterId(), c.getFiring())))
                .toList();
    }

    /** A cluster's count of firings, less those of rules the caller may not see; the installation's is unchanged. */
    private long visibleCount(UUID clusterId, long firing) {
        if (clusterId == null
                || !perm.can(clusterId, AlertPermissions.ALERT_READ)
                || clusterAccess.holds(clusterId, Permissions.SUBSCRIPTION_READ)) {
            return firing;
        }
        return firingRepo.countByClusterIdAndResolvedAtIsNullAndRuleIdIn(clusterId, visibleRuleIds(clusterId, false));
    }

    /**
     * The cluster's rules the caller may see, whose firings they may see: all of them for a caller who reads
     * every queue. Never empty, as an empty collection cannot be bound.
     */
    private List<UUID> visibleRuleIds(UUID clusterId, boolean everything) {
        if (everything) {
            return List.of(new UUID(0, 0));
        }
        List<UUID> ids = ruleRepo.findByClusterId(clusterId).stream()
                .filter(r -> ruleService.seen(clusterId, r))
                .map(AlertRuleEntity::getId)
                .toList();
        return ids.isEmpty() ? List.of(new UUID(0, 0)) : ids;
    }

    private Map<UUID, String> ruleNames(List<AlertFiringEntity> firings) {
        List<UUID> ruleIds =
                firings.stream().map(AlertFiringEntity::getRuleId).distinct().toList();
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        return ruleRepo.findAllById(ruleIds).stream()
                .collect(Collectors.toMap(AlertRuleEntity::getId, AlertRuleEntity::getName));
    }
}
