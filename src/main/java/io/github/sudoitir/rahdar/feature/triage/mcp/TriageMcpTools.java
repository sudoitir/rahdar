package io.github.sudoitir.rahdar.feature.triage.mcp;

import io.github.sudoitir.rahdar.feature.alerting.AlertPermissions;
import io.github.sudoitir.rahdar.feature.alerting.AlertService;
import io.github.sudoitir.rahdar.feature.events.BrokerEventQuery;
import io.github.sudoitir.rahdar.feature.events.BrokerEventService;
import io.github.sudoitir.rahdar.feature.events.web.EventViews;
import io.github.sudoitir.rahdar.feature.triage.ConsumerHealth;
import io.github.sudoitir.rahdar.feature.triage.ConsumerHealthService;
import io.github.sudoitir.rahdar.kernel.audit.AuditQuery;
import io.github.sudoitir.rahdar.kernel.audit.AuditQueryService;
import io.github.sudoitir.rahdar.kernel.audit.web.AuditViews;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpProperties;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The {@code diagnose} and {@code activity_log} MCP tools: triage questions that read across
 * clusters, resources, metrics, events, alerts and the audit trail. *
 * <p>Reads only: nothing here needs the dry-run/confirm contract, which is the review
 * boundary ADR-0045 draws between read and mutating tool classes. Every method is a thin
 * adapter over the services the REST layer already uses — including the queue diagnosis,
 * which reads {@link ConsumerHealthService} rather than deriving a second opinion
 * (ADR-0089).
 */
@Component
@RequiredArgsConstructor
public class TriageMcpTools {

    private final McpProperties props;
    private final ClusterService clusters;
    private final ConsumerHealthService consumerHealth;
    private final BrokerEventService brokerEvents;
    private final AuditQueryService auditLog;
    private final ClusterDirectory clusterRepo;
    private final ObjectProvider<AlertService> alerts;
    private final PermissionResolver perm;
    private final ClockOffsetService clocks;

    /**
     * Cluster health and single-queue diagnosis behind one optional argument
     * (ADR-0054).
     *
     * <p>They merge because they share both conditions the grouping rule requires:
     * one honest posture — neither mutates — and one target family, a cluster and
     * something in it, scoped by an optional name. That is the shape
     * {@code metric_series} already uses for the same reason.
     *
     * <p>It is also the pair a model most often has to choose between blind: "is
     * this cluster healthy" and "why is this queue backing up" are the same triage
     * step at two scopes, and making the scope an argument removes a selection
     * decision rather than adding one.
     */
    @McpTool(
            name = "diagnose",
            description = "Cluster health, or one queue end to end. Start here.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult diagnose(
            @McpToolParam(required = true) String clusterId, @McpToolParam(required = false) String queue) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        if (queue == null || queue.isBlank()) {
            return McpErrors.guard(() -> health(id));
        }
        String name = queue.trim();
        return McpErrors.guard(() -> diagnose(id, name));
    }

    private McpViews.ClusterHealth health(UUID clusterId) {
        // Deliberately not ClusterService.get(): that assembles the full cluster screen
        // and runs a live capability probe against a broker node, which fails the whole
        // call when the cluster is exactly what a triage step is asking about — down.
        // topology() and health() read the scrape cache and answer from what is known.
        ClusterViews.TopologyView topology = clusters.topology(clusterId);
        ClusterViews.HealthView h = clusters.health(clusterId);
        // Both calls above went through ClusterAccessGuard, so by here the caller is
        // known to hold cluster:read on this id and the name is not a disclosure.
        String name = clusterRepo
                .cluster(clusterId)
                .map(RegisteredCluster::getName)
                .orElseThrow(() -> new NotFoundException("cluster", clusterId));

        List<McpViews.NodeHealth> nodes = new ArrayList<>();
        for (ClusterViews.LogicalNodeView logical : topology.nodes()) {
            for (ClusterViews.NodeEndpointView e : logical.endpoints()) {
                nodes.add(new McpViews.NodeHealth(
                        e.name(), e.haRole(), e.active(), e.manageable(), e.artemisNodeId(), e.lastSeenAt()));
            }
        }

        // alert:read is a separate permission from cluster:read, so a key can legally
        // see health and not alerts. Reporting that explicitly beats an empty list a
        // model would read as "nothing is firing" (non-negotiable #5).
        // Alerting is an optional feature; with it disabled there are no alerts to show,
        // and alertsVisible=false says so rather than reporting an empty list as "quiet".
        AlertService alertService = alerts.getIfAvailable();
        boolean alertsVisible = alertService != null && perm.can(clusterId, AlertPermissions.ALERT_READ);
        List<McpViews.FiringAlert> firing = alertsVisible
                ? alertService.firingNow(clusterId).stream()
                        .map(f -> new McpViews.FiringAlert(
                                f.ruleName(), f.subjectKey(), f.severity(), f.value(), f.startedAt()))
                        .toList()
                : List.of();

        return new McpViews.ClusterHealth(
                clusterId,
                name,
                h.level(),
                h.splitBrain(),
                h.replicationBehind(),
                h.liveEndpointNames(),
                nodes,
                h.notes(),
                alertsVisible,
                firing,
                Instant.now(),
                clockVerdict(clusterId));
    }

    private McpViews.ClockVerdict clockVerdict(UUID clusterId) {
        var assessment = clocks.assessmentFor(clusterId);
        return new McpViews.ClockVerdict(
                assessment.verdict().name(),
                assessment.worst().map(w -> w.offset().offsetMs()).orElse(null),
                assessment.worst().map(w -> w.offset().uncertaintyMs()).orElse(null),
                assessment.skewed().stream()
                        .map(ClockOffsetService.NodeSkew::nodeName)
                        .toList(),
                assessment.at());
    }

    private McpViews.SubscriptionDiagnosis diagnose(UUID clusterId, String queue) {
        // The shared verdict (ADR-0089) — the same evaluation the console and the alert
        // condition read. This method used to derive its own trend from two points and its
        // own slow-consumer guess from a ratio, which is how an agent and a screen came to
        // describe one queue differently.
        ConsumerHealth health = consumerHealth
                .forSubscription(clusterId, queue)
                .orElseThrow(() -> new NotFoundException("queue", queue));

        List<String> findings = new ArrayList<>();
        if (health.nodesPresent() < health.nodesTotal()) {
            findings.add("The queue exists on " + health.nodesPresent() + " of " + health.nodesTotal()
                    + " nodes; the numbers here cover only the nodes reporting it.");
        }
        if (health.stale()) {
            findings.add("At least one node's last sweep is stale, so these numbers are the last seen"
                    + " rather than current.");
        }
        if (health.scheduled() > 0) {
            findings.add(health.scheduled() + " messages are scheduled for later delivery "
                    + "and are counted in the depth.");
        }

        // A DLQ relationship is a naming convention, not broker metadata: say what was
        // observed rather than asserting a link the broker never declared.
        String dlq = queue.startsWith("DLQ") || queue.contains(".DLQ") || queue.endsWith(".dlq")
                ? "This queue looks like a dead-letter queue by name."
                : null;

        List<McpViews.ActivityRow> recent = brokerEvents
                .page(clusterId, new BrokerEventQuery(null, null, health.destination(), null, null, 1, 10, null))
                .data()
                .stream()
                .map(TriageMcpTools::toActivity)
                .toList();

        return new McpViews.SubscriptionDiagnosis(
                clusterId,
                queue,
                health.destination(),
                health.verdict().name(),
                health.verdict().severity(),
                health.cause(),
                health.source().name(),
                health.brokerConsumerName(),
                health.depth(),
                health.consumers(),
                health.delivering(),
                health.scheduled(),
                health.paused(),
                health.depthSlopePerSecond(),
                health.addRate(),
                health.ackRate(),
                health.netRate(),
                health.ackRatePerConsumer(),
                health.drainEta() == null ? null : health.drainEta().toSeconds(),
                health.asOf(),
                health.sampleSpan() == null ? null : health.sampleSpan().toSeconds(),
                health.stale(),
                health.nodesPresent(),
                health.nodesTotal(),
                dlq,
                findings,
                recent);
    }

    private enum LogSource {
        BROKER_EVENTS,
        AUDIT
    }

    @McpTool(
            name = "activity_log",
            description = "Recent activity: broker_events from the broker, or audit from Rahdar.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult activityLog(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String source,
            @McpToolParam(required = false) String filter,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        LogSource src = McpArgs.enumOf(LogSource.class, "source", source, LogSource.BROKER_EVENTS);
        int capped = props.clamp(limit);
        return McpErrors.guard(() -> activity(id, src, filter, capped));
    }

    private McpViews.Page<McpViews.ActivityRow> activity(UUID clusterId, LogSource source, String filter, int limit) {
        List<McpViews.ActivityRow> rows =
                switch (source) {
                    case BROKER_EVENTS ->
                        brokerEvents
                                .page(
                                        clusterId,
                                        new BrokerEventQuery(null, null, filter, null, null, 1, limit + 1, null))
                                .data()
                                .stream()
                                .map(TriageMcpTools::toActivity)
                                .toList();
                    case AUDIT ->
                        auditLog
                                .page(
                                        clusterId,
                                        new AuditQuery(null, filter, null, null, null, null, 1, limit + 1, null))
                                .data()
                                .stream()
                                .map(TriageMcpTools::toActivity)
                                .toList();
                };
        return McpViews.Page.of(rows, limit, "most recent first");
    }

    private static McpViews.ActivityRow toActivity(EventViews.BrokerEventView e) {
        return new McpViews.ActivityRow(
                e.occurredAt(),
                e.type(),
                e.username(),
                e.destination() != null ? e.destination() : e.routingName(),
                "observed",
                e.consumerName() != null ? "consumer=" + e.consumerName() : null);
    }

    private static McpViews.ActivityRow toActivity(AuditViews.AuditEventView e) {
        return new McpViews.ActivityRow(
                e.ts(),
                e.action(),
                e.username(),
                e.targetName(),
                // A dry run that "succeeded" changed nothing; saying so keeps a model from
                // reading the audit trail as a record of things that actually happened.
                e.dryRun() ? e.outcome() + " (dry run)" : e.outcome(),
                activityDetail(e));
    }

    private static String activityDetail(AuditViews.AuditEventView e) {
        if (e.error() != null) {
            return e.error();
        }
        return e.affectedCount() != null ? "affected=" + e.affectedCount() : null;
    }
}
