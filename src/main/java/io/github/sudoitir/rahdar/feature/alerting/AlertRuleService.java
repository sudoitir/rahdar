package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleChannelEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleChannelRepository;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleEntity.Condition;
import io.github.sudoitir.rahdar.feature.alerting.internal.persistence.AlertRuleRepository;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.AlertRuleRequest;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.AlertRuleView;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.PluginMetricView;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Alert rule CRUD. Every mutation is audited in-transaction, following
 * {@code ClusterService}'s pattern (ADR-0023) — rule/channel changes are
 * operator actions; the firings a rule later produces are not (alerting spec).
 */
@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private static final String AUDIT_TARGET = "ALERT_RULE";
    private static final Set<String> STATE_CONDITIONS = Set.of(
            "SPLIT_BRAIN",
            "NODE_DOWN",
            "REPLICATION_BEHIND",
            "CLUSTER_DEGRADED",
            "CLOCK_SKEW",
            "CONFIG_DRIFT",
            "SETUP_RISK");

    /** What an installation-scoped rule may watch (ADR-0135); a cluster's rules never may, and vice versa. */
    private static final Set<String> INSTALLATION_CONDITIONS =
            Set.of("STORAGE_QUOTA", "STORAGE_HEALTH", "AUDIT_DELIVERY");

    private static final Set<String> COMPARATORS = Set.of("GT", "GTE", "LT", "LTE", "EQ", "NE");

    private final AlertRuleRepository rules;
    private final AlertRuleChannelRepository ruleChannels;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final AlertViewMapper mapper;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver perm;
    private final PluginMetricCondition pluginMetrics;
    private final ObjectMapper json;

    /** Every rule kind's predicate, so validation accepts exactly what evaluation can run. */
    private final List<AlertCondition> conditions;

    /**
     * The cluster's rules and, for a caller with the global grant, the installation's (ADR-0135),
     * which every cluster's alerts view shows.
     */
    @Transactional(readOnly = true)
    public List<AlertRuleView> list(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_READ);
        return rules.findVisible(clusterId, perm.can(AlertPermissions.ALERT_READ)).stream()
                .filter(r -> seen(clusterId, r))
                .map(r -> view(r, channelIds(r.getId())))
                .toList();
    }

    /** The metrics of running plugins a threshold rule may watch (ADR-0113). */
    public List<PluginMetricView> pluginMetrics(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_READ);
        return pluginMetrics.declared().stream()
                .map(d -> new PluginMetricView(
                        d.metric().name(),
                        d.plugin(),
                        d.metric().description(),
                        d.metric().unit(),
                        d.metric().subject()))
                .sorted(Comparator.comparing(PluginMetricView::metric))
                .toList();
    }

    @Transactional
    public AlertRuleView create(UUID clusterId, AlertRuleRequest request) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_WRITE);
        AlertRuleEntity rule = validated(request, false);
        requireMayWatch(clusterId, rule);
        rule.setClusterId(clusterId);
        rules.save(rule);
        bindChannels(rule.getId(), request.channelIds());

        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "CREATE_ALERT_RULE",
                AUDIT_TARGET,
                rule.getName(),
                clusterId,
                null,
                Map.of("kind", rule.getKind()),
                false);
        audit.succeed(event, 1);
        return view(rule, channelIds(rule.getId()));
    }

    @Transactional
    public AlertRuleView update(UUID clusterId, UUID ruleId, AlertRuleRequest request) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_WRITE);
        AlertRuleEntity existing = requireRule(clusterId, ruleId);
        AlertRuleEntity updated = validated(request, existing.getClusterId() == null);
        requireMayWatch(clusterId, updated);

        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "UPDATE_ALERT_RULE",
                AUDIT_TARGET,
                existing.getName(),
                existing.getClusterId(),
                null,
                Map.of("kind", updated.getKind()),
                false);

        existing.setName(updated.getName());
        existing.setKind(updated.getKind());
        existing.setMetric(updated.getMetric());
        existing.setComparator(updated.getComparator());
        existing.setThreshold(updated.getThreshold());
        existing.setStateCondition(updated.getStateCondition());
        existing.setForSeconds(updated.getForSeconds());
        existing.setSeverity(updated.getSeverity());
        existing.setScope(updated.getScope());
        existing.setEnabled(request.enabled());
        rules.save(existing);

        ruleChannels.deleteByRuleId(ruleId);
        bindChannels(ruleId, request.channelIds());

        audit.succeed(event, 1);
        return view(existing, channelIds(ruleId));
    }

    @Transactional
    public void delete(UUID clusterId, UUID ruleId) {
        clusterAccess.requireCluster(clusterId, AlertPermissions.ALERT_WRITE);
        AlertRuleEntity rule = requireRule(clusterId, ruleId);
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                "DELETE_ALERT_RULE",
                AUDIT_TARGET,
                rule.getName(),
                rule.getClusterId(),
                null,
                Map.of(),
                false);
        rules.delete(rule); // cascades alert_state / alert_firing / alert_delivery / alert_rule_channel
        audit.succeed(event, 1);
    }

    // ---- what a caller may see of a rule -------------------------------------------------

    /**
     * Alert permissions are held on the cluster, through a grant, and never through a team. A threshold rule
     * watches queues, which it names by a pattern, so a rule is seen and changed by a caller who may read
     * every queue that pattern can match, and a state rule or the installation's, which watch no queue, by
     * any holder of the permission.
     */
    public boolean seen(UUID clusterId, AlertRuleEntity rule) {
        return rule.getClusterId() == null
                || !rule.isThreshold()
                || clusterAccess.mayOnAll(
                        clusterId,
                        ResourceType.SUBSCRIPTION,
                        AlertScope.parse(rule.getScope(), json).subscriptionNames(),
                        Permissions.SUBSCRIPTION_READ);
    }

    /** A rule is created or changed only to watch queues the caller may read: it would report on the rest. */
    private void requireMayWatch(UUID clusterId, AlertRuleEntity rule) {
        if (rule.isThreshold()) {
            clusterAccess.requireOnAll(
                    clusterId,
                    ResourceType.SUBSCRIPTION,
                    AlertScope.parse(rule.getScope(), json).subscriptionNames(),
                    Permissions.SUBSCRIPTION_READ);
        }
    }

    // ---- helpers ------------------------------------------------------------

    private AlertRuleEntity validated(AlertRuleRequest r, boolean installation) {
        if (installation && !"STATE".equals(r.kind())) {
            throw new IllegalArgumentException("an installation rule must be a state rule");
        }
        if ("METRIC_THRESHOLD".equals(r.kind())) {
            return validatedThreshold(r);
        }
        if ("STATE".equals(r.kind())) {
            return validatedState(r, installation);
        }
        throw new IllegalArgumentException("unknown rule kind: " + r.kind());
    }

    private AlertRuleEntity validatedThreshold(AlertRuleRequest r) {
        if (r.metric() == null || r.comparator() == null || r.threshold() == null) {
            throw new IllegalArgumentException("metric, comparator, and threshold are required for a threshold rule");
        }
        if (!COMPARATORS.contains(r.comparator())) {
            throw new IllegalArgumentException("unknown comparator: " + r.comparator());
        }
        // Ask the conditions themselves what they can evaluate, rather than naming two
        // of them here. The hard-coded pair rejected every derived metric — including
        // ackRatePerConsumer, which ADR-0044 ships a template for and which therefore
        // could not be saved through this API at all (ADR-0089).
        AlertRuleSpec probe = new AlertRuleSpec(null, true, r.metric(), r.comparator(), r.threshold(), r.scope(), null);
        if (conditions.stream().noneMatch(c -> c.supports(probe))) {
            throw new IllegalArgumentException("unknown metric: " + r.metric());
        }
        if (r.stateCondition() != null) {
            throw new IllegalArgumentException("a threshold rule must not set stateCondition");
        }
        return AlertRuleEntity.threshold(
                null,
                r.name(),
                new Condition(r.metric(), r.comparator(), r.threshold()),
                r.forSeconds(),
                r.severity(),
                r.scope());
    }

    private AlertRuleEntity validatedState(AlertRuleRequest r, boolean installation) {
        Set<String> allowed = installation ? INSTALLATION_CONDITIONS : STATE_CONDITIONS;
        if (r.stateCondition() == null || !allowed.contains(r.stateCondition())) {
            throw new IllegalArgumentException("unknown stateCondition: " + r.stateCondition());
        }
        if (r.metric() != null || r.comparator() != null || r.threshold() != null) {
            throw new IllegalArgumentException("a state rule must not set metric/comparator/threshold");
        }
        return AlertRuleEntity.state(null, r.name(), r.stateCondition(), r.forSeconds(), r.severity());
    }

    /** A rule on a plugin metric whose plugin is not running has no source (ADR-0113). */
    private AlertRuleView view(AlertRuleEntity rule, List<UUID> channelIds) {
        return mapper.rule(rule, channelIds, !rule.isThreshold() || pluginMetrics.available(rule.getMetric()));
    }

    private void bindChannels(UUID ruleId, List<UUID> channelIds) {
        if (channelIds == null) {
            return;
        }
        for (UUID channelId : channelIds) {
            ruleChannels.save(new AlertRuleChannelEntity(ruleId, channelId));
        }
    }

    private List<UUID> channelIds(UUID ruleId) {
        return ruleChannels.findByRuleId(ruleId).stream()
                .map(AlertRuleChannelEntity::getChannelId)
                .toList();
    }

    /**
     * The cluster's rule, or an installation rule when the caller holds the write grant globally:
     * an operator scoped to one cluster cannot silence the installation's alerts, and is told the
     * rule does not exist.
     */
    private AlertRuleEntity requireRule(UUID clusterId, UUID ruleId) {
        AlertRuleEntity rule = rules.findById(ruleId).orElseThrow(() -> new NotFoundException("AlertRule", ruleId));
        boolean visible = rule.getClusterId() == null
                ? perm.can(AlertPermissions.ALERT_WRITE)
                : clusterId.equals(rule.getClusterId()) && seen(clusterId, rule);
        if (!visible) {
            throw new NotFoundException("AlertRule", ruleId);
        }
        return rule;
    }
}
