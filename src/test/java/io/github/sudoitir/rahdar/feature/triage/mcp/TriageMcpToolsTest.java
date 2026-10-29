package io.github.sudoitir.rahdar.feature.triage.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.alerting.AlertPermissions;
import io.github.sudoitir.rahdar.feature.alerting.AlertService;
import io.github.sudoitir.rahdar.feature.alerting.web.AlertViews.AlertFiringView;
import io.github.sudoitir.rahdar.feature.events.BrokerEventQuery;
import io.github.sudoitir.rahdar.feature.events.BrokerEventService;
import io.github.sudoitir.rahdar.feature.events.web.EventViews.BrokerEventPageView;
import io.github.sudoitir.rahdar.feature.events.web.EventViews.BrokerEventView;
import io.github.sudoitir.rahdar.feature.triage.ConsumerHealth;
import io.github.sudoitir.rahdar.feature.triage.ConsumerHealth.Source;
import io.github.sudoitir.rahdar.feature.triage.ConsumerHealth.Verdict;
import io.github.sudoitir.rahdar.feature.triage.ConsumerHealthService;
import io.github.sudoitir.rahdar.kernel.audit.AuditQuery;
import io.github.sudoitir.rahdar.kernel.audit.AuditQueryService;
import io.github.sudoitir.rahdar.kernel.audit.web.AuditViews.AuditEventView;
import io.github.sudoitir.rahdar.kernel.core.HistoryPage;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerVersion;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetRegistry.ClockOffset;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService.Assessment;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService.NodeSkew;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.HealthView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.LogicalNodeView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.NodeEndpointView;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.TopologyView;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpProperties;
import io.github.sudoitir.rahdar.platform.mcp.McpResourcesFixture;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The triage tools are thin adapters over services the REST layer already uses, so what
 * matters here is what they add: the honest "alerts not visible" flag, the queue
 * findings, and the dry-run marker an audit row carries.
 */
class TriageMcpToolsTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-09-30T10:00:00Z");

    private ClusterService clusters;
    private ConsumerHealthService consumerHealth;
    private BrokerEventService brokerEvents;
    private AuditQueryService auditLog;
    private ClusterDirectory clusterRepo;
    private ObjectProvider<AlertService> alerts;
    private PermissionResolver perm;
    private ClockOffsetService clocks;
    private AlertService alertService;
    private TriageMcpTools tools;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        clusters = mock(ClusterService.class);
        consumerHealth = mock(ConsumerHealthService.class);
        brokerEvents = mock(BrokerEventService.class);
        auditLog = mock(AuditQueryService.class);
        clusterRepo = mock(ClusterDirectory.class);
        alerts = mock(ObjectProvider.class);
        perm = mock(PermissionResolver.class);
        clocks = mock(ClockOffsetService.class);
        alertService = mock(AlertService.class);
        tools = new TriageMcpTools(
                new McpProperties(25, 100),
                clusters,
                consumerHealth,
                brokerEvents,
                auditLog,
                clusterRepo,
                alerts,
                perm,
                clocks,
                McpResourcesFixture.resolving());

        RegisteredCluster registered = mock(RegisteredCluster.class);
        when(registered.getName()).thenReturn("prod");
        when(clusterRepo.cluster(CLUSTER)).thenReturn(Optional.of(registered));
        when(clusters.topology(CLUSTER))
                .thenReturn(new TopologyView(
                        CLUSTER,
                        List.of(new LogicalNodeView(
                                "n1",
                                "NONE",
                                false,
                                List.of(new NodeEndpointView(
                                        UUID.randomUUID(),
                                        "broker-1",
                                        "n1",
                                        null,
                                        null,
                                        "PRIMARY",
                                        "UP",
                                        true,
                                        null,
                                        null,
                                        BrokerVersion.Support.UNKNOWN,
                                        null,
                                        null,
                                        AT,
                                        null,
                                        null,
                                        false,
                                        true))))));
        when(clusters.health(CLUSTER))
                .thenReturn(new HealthView(
                        CLUSTER, "HEALTHY", List.of("broker-1"), "NONE", false, List.of("all good"), List.of()));
        when(clocks.assessmentFor(CLUSTER)).thenReturn(Assessment.UNKNOWN);
        when(alerts.getIfAvailable()).thenReturn(alertService);
        when(perm.can(CLUSTER, AlertPermissions.ALERT_READ)).thenReturn(true);
        when(alertService.firingNow(CLUSTER)).thenReturn(List.of());
    }

    private static McpViews.ClusterHealth clusterHealth(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isNotEqualTo(true);
        return (McpViews.ClusterHealth) result.structuredContent();
    }

    private static ConsumerHealth health(
            String address, long scheduled, boolean stale, int present, int total, Duration eta, Duration span) {
        return new ConsumerHealth(
                address,
                "ORDERS",
                Verdict.STALLED,
                "no acks",
                Source.DERIVED,
                null,
                10,
                1,
                0,
                scheduled,
                false,
                0.5,
                1.0,
                0.0,
                -1.0,
                0.0,
                eta,
                AT,
                span,
                stale,
                present,
                total);
    }

    // ---- diagnose: cluster --------------------------------------------------

    @Test
    void diagnoseWithoutAQueueAnswersClusterHealthFromTheScrapeCache() {
        McpViews.ClusterHealth h = clusterHealth(tools.diagnose(CLUSTER.toString(), null));

        assertThat(h.cluster()).isEqualTo("prod");
        assertThat(h.level()).isEqualTo("HEALTHY");
        assertThat(h.liveNodes()).containsExactly("broker-1");
        assertThat(h.nodes()).singleElement().satisfies(n -> {
            assertThat(n.node()).isEqualTo("broker-1");
            assertThat(n.haRole()).isEqualTo("PRIMARY");
            assertThat(n.live()).isTrue();
            assertThat(n.manageable()).isTrue();
        });
        assertThat(h.alertsVisible()).isTrue();
        assertThat(h.firingAlerts()).isEmpty();
        assertThat(h.clock().verdict()).isEqualTo("UNKNOWN");
        assertThat(h.clock().worstOffsetMs()).isNull();
        assertThat(h.clock().skewedNodes()).isEmpty();
    }

    @Test
    void aBlankQueueMeansClusterScope() {
        McpViews.ClusterHealth h = clusterHealth(tools.diagnose(CLUSTER.toString(), "  "));

        assertThat(h.cluster()).isEqualTo("prod");
    }

    @Test
    void firingAlertsAreListedWhenTheCallerMayReadThem() {
        when(alertService.firingNow(CLUSTER))
                .thenReturn(List.of(new AlertFiringView(
                        1, UUID.randomUUID(), CLUSTER, "Backlog", "ORDERS", "CRITICAL", 42.0, AT, null)));

        McpViews.ClusterHealth h = clusterHealth(tools.diagnose(CLUSTER.toString(), null));

        assertThat(h.firingAlerts()).singleElement().satisfies(a -> {
            assertThat(a.rule()).isEqualTo("Backlog");
            assertThat(a.subject()).isEqualTo("ORDERS");
            assertThat(a.severity()).isEqualTo("CRITICAL");
            assertThat(a.value()).isEqualTo(42.0);
            assertThat(a.since()).isEqualTo(AT);
        });
    }

    @Test
    void withoutTheAlertReadPermissionAlertsAreReportedAsNotVisibleRatherThanEmpty() {
        when(perm.can(CLUSTER, AlertPermissions.ALERT_READ)).thenReturn(false);

        McpViews.ClusterHealth h = clusterHealth(tools.diagnose(CLUSTER.toString(), null));

        assertThat(h.alertsVisible()).isFalse();
        assertThat(h.firingAlerts()).isEmpty();
        Mockito.verifyNoInteractions(alertService);
    }

    @Test
    void withAlertingDisabledAlertsAreNotVisibleEither() {
        when(alerts.getIfAvailable()).thenReturn(null);

        McpViews.ClusterHealth h = clusterHealth(tools.diagnose(CLUSTER.toString(), null));

        assertThat(h.alertsVisible()).isFalse();
    }

    @Test
    void theClockVerdictNamesTheSkewedNodesAndTheWorstOffset() {
        NodeSkew skew = new NodeSkew(UUID.randomUUID(), "broker-2", new ClockOffset(900, 5, 10, 3, AT));
        when(clocks.assessmentFor(CLUSTER))
                .thenReturn(new Assessment(ClockOffsetService.Verdict.BROKER_SKEWED, List.of(skew), List.of(skew), AT));

        McpViews.ClockVerdict clock =
                clusterHealth(tools.diagnose(CLUSTER.toString(), null)).clock();

        assertThat(clock.verdict()).isEqualTo("BROKER_SKEWED");
        assertThat(clock.worstOffsetMs()).isEqualTo(900L);
        assertThat(clock.uncertaintyMs()).isEqualTo(5L);
        assertThat(clock.skewedNodes()).containsExactly("broker-2");
        assertThat(clock.measuredAt()).isEqualTo(AT);
    }

    @Test
    void aClusterThatIsNotRegisteredIsTheHiddenDenialNotAnError() {
        when(clusterRepo.cluster(CLUSTER)).thenReturn(Optional.empty());

        McpSchema.CallToolResult result = tools.diagnose(CLUSTER.toString(), null);

        assertThat(result.isError()).isTrue();
        assertThat(result.content().toString()).contains(McpErrors.CLUSTER_DENIED);
    }

    @Test
    void aMalformedClusterIdIsAProtocolError() {
        assertThatThrownBy(() -> tools.diagnose("not-a-uuid", null))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("clusterId must be a UUID");
        assertThatThrownBy(() -> tools.diagnose(null, null)).isInstanceOf(McpError.class);
    }

    // ---- diagnose: queue ----------------------------------------------------

    @Test
    void diagnoseOfAQueueReadsTheSharedVerdictAndCarriesItsFindings() {
        when(consumerHealth.forSubscription(CLUSTER, "ORDERS.DLQ"))
                .thenReturn(
                        Optional.of(health("orders", 3, true, 1, 2, Duration.ofSeconds(90), Duration.ofSeconds(30))));
        when(brokerEvents.page(eq(CLUSTER), any()))
                .thenReturn(new BrokerEventPageView(
                        List.of(new BrokerEventView(
                                1,
                                AT,
                                AT,
                                "CONSUMER_CREATED",
                                "orders",
                                null,
                                "worker-1",
                                null,
                                null,
                                null,
                                "svc",
                                null,
                                null)),
                        1,
                        10,
                        1L,
                        false,
                        null,
                        false,
                        0,
                        null));

        McpViews.SubscriptionDiagnosis d = (McpViews.SubscriptionDiagnosis)
                tools.diagnose(CLUSTER.toString(), "artemis.queue/ORDERS.DLQ").structuredContent();

        assertThat(d.subscription()).isEqualTo("ORDERS.DLQ");
        assertThat(d.verdict()).isEqualTo("STALLED");
        assertThat(d.severity()).isEqualTo(4);
        assertThat(d.source()).isEqualTo("DERIVED");
        assertThat(d.drainEtaSeconds()).isEqualTo(90L);
        assertThat(d.sampleSpanSeconds()).isEqualTo(30L);
        assertThat(d.dlq()).contains("dead-letter queue by name");
        assertThat(d.findings())
                .hasSize(3)
                .anyMatch(f -> f.contains("1 of 2 nodes"))
                .anyMatch(f -> f.contains("stale"))
                .anyMatch(f -> f.contains("3 messages are scheduled"));
        assertThat(d.recentEvents()).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo("CONSUMER_CREATED");
            assertThat(r.actor()).isEqualTo("svc");
            assertThat(r.subject()).isEqualTo("orders");
            assertThat(r.outcome()).isEqualTo("observed");
            assertThat(r.detail()).isEqualTo("consumer=worker-1");
        });
        ArgumentCaptor<BrokerEventQuery> query = ArgumentCaptor.forClass(BrokerEventQuery.class);
        verify(brokerEvents).page(eq(CLUSTER), query.capture());
        assertThat(query.getValue().destination()).isEqualTo("orders");
        assertThat(query.getValue().size()).isEqualTo(10);
    }

    @Test
    void aHealthyFullyReportedQueueHasNoFindingsAndNoDeadLetterHint() {
        when(consumerHealth.forSubscription(CLUSTER, "ORDERS"))
                .thenReturn(Optional.of(health("orders", 0, false, 2, 2, null, null)));
        when(brokerEvents.page(eq(CLUSTER), any()))
                .thenReturn(new BrokerEventPageView(List.of(), 1, 10, 0L, false, null, false, 0, null));

        McpViews.SubscriptionDiagnosis d = (McpViews.SubscriptionDiagnosis)
                tools.diagnose(CLUSTER.toString(), "artemis.queue/ORDERS").structuredContent();

        assertThat(d.findings()).isEmpty();
        assertThat(d.dlq()).isNull();
        assertThat(d.drainEtaSeconds()).isNull();
        assertThat(d.sampleSpanSeconds()).isNull();
        assertThat(d.recentEvents()).isEmpty();
    }

    @Test
    void queueNamesLookedLikeDeadLetterQueuesAreRecognisedByPrefixInfixAndSuffix() {
        when(brokerEvents.page(eq(CLUSTER), any()))
                .thenReturn(new BrokerEventPageView(List.of(), 1, 10, 0L, false, null, false, 0, null));
        for (String name : List.of("DLQ", "orders.DLQ", "orders.dlq")) {
            when(consumerHealth.forSubscription(CLUSTER, name))
                    .thenReturn(Optional.of(health("a", 0, false, 1, 1, null, null)));

            McpViews.SubscriptionDiagnosis d = (McpViews.SubscriptionDiagnosis)
                    tools.diagnose(CLUSTER.toString(), "artemis.queue/" + name).structuredContent();

            assertThat(d.dlq()).as(name).isNotNull();
        }
    }

    @Test
    void anUnknownQueueIsTheHiddenDenial() {
        when(consumerHealth.forSubscription(CLUSTER, "nope")).thenReturn(Optional.empty());

        McpSchema.CallToolResult result = tools.diagnose(CLUSTER.toString(), "artemis.queue/nope");

        assertThat(result.isError()).isTrue();
    }

    // ---- activity_log -------------------------------------------------------

    private static BrokerEventView event(String address, String routingName, String consumer) {
        return new BrokerEventView(1, AT, AT, "T", address, routingName, consumer, null, null, null, "u", null, null);
    }

    @SuppressWarnings("unchecked")
    private static McpViews.Page<McpViews.ActivityRow> page(McpSchema.CallToolResult result) {
        return (McpViews.Page<McpViews.ActivityRow>) result.structuredContent();
    }

    @Test
    void aDeliveredRowIsReadAsACountOverAnIntervalNotOneDelivery() {
        BrokerEventView summary = new BrokerEventView(
                1,
                AT,
                AT,
                "openbroker.message.delivered",
                "ORDERS",
                "inbound",
                null,
                null,
                null,
                null,
                null,
                null,
                java.util.Map.of("count", 42, "from", "2026-10-29T01:00:00Z", "to", "2026-10-29T01:00:05Z"));
        when(brokerEvents.page(eq(CLUSTER), any()))
                .thenReturn(new BrokerEventPageView(List.of(summary), 1, 26, 1L, false, null, false, 0, null));

        McpViews.Page<McpViews.ActivityRow> page = page(tools.activityLog(CLUSTER.toString(), null, null, null));

        assertThat(page.items())
                .extracting(McpViews.ActivityRow::detail)
                .containsExactly(
                        "42 messages delivered on ORDERS/inbound between 2026-10-29T01:00:00Z and 2026-10-29T01:00:05Z");
    }

    @Test
    void brokerEventsAreTheDefaultSourceAndFallBackToTheRoutingName() {
        when(brokerEvents.page(eq(CLUSTER), any()))
                .thenReturn(new BrokerEventPageView(
                        List.of(event("addr", null, null), event(null, "route", null)),
                        1,
                        26,
                        2L,
                        false,
                        null,
                        false,
                        0,
                        null));

        McpViews.Page<McpViews.ActivityRow> page = page(tools.activityLog(CLUSTER.toString(), null, "addr", null));

        assertThat(page.items()).extracting(McpViews.ActivityRow::subject).containsExactly("addr", "route");
        assertThat(page.items()).extracting(McpViews.ActivityRow::detail).containsOnlyNulls();
        assertThat(page.truncated()).isFalse();
        assertThat(page.orderedBy()).isEqualTo("most recent first");
        ArgumentCaptor<BrokerEventQuery> query = ArgumentCaptor.forClass(BrokerEventQuery.class);
        verify(brokerEvents).page(eq(CLUSTER), query.capture());
        // one more than the default limit, so truncation can be reported
        assertThat(query.getValue().size()).isEqualTo(26);
        assertThat(query.getValue().destination()).isEqualTo("addr");
    }

    @Test
    void theLimitIsClampedAndAnExtraRowMarksThePageTruncated() {
        when(brokerEvents.page(eq(CLUSTER), any()))
                .thenReturn(new BrokerEventPageView(
                        List.of(event("a", null, null), event("b", null, null)),
                        1,
                        2,
                        2L,
                        false,
                        null,
                        false,
                        0,
                        null));

        McpViews.Page<McpViews.ActivityRow> page =
                page(tools.activityLog(CLUSTER.toString(), "broker_events", null, 1));

        assertThat(page.truncated()).isTrue();
        assertThat(page.returned()).isEqualTo(1);
    }

    @Test
    void auditRowsMarkADryRunAndPreferTheErrorOverTheAffectedCount() {
        when(auditLog.page(eq(CLUSTER), any()))
                .thenReturn(new HistoryPage<>(
                        List.of(
                                audit("purge", "SUCCESS", true, null, 5L),
                                audit("purge", "FAILURE", false, "boom", 5L),
                                audit("move", "SUCCESS", false, null, 7L),
                                audit("move", "SUCCESS", false, null, null)),
                        1,
                        26,
                        4L,
                        false,
                        null,
                        false));

        McpViews.Page<McpViews.ActivityRow> page = page(tools.activityLog(CLUSTER.toString(), "audit", "purge", null));

        assertThat(page.items())
                .extracting(McpViews.ActivityRow::outcome)
                .containsExactly("SUCCESS (dry run)", "FAILURE", "SUCCESS", "SUCCESS");
        assertThat(page.items())
                .extracting(McpViews.ActivityRow::detail)
                .containsExactly("affected=5", "boom", "affected=7", null);
        assertThat(page.items().getFirst().actor()).isEqualTo("alice");
        assertThat(page.items().getFirst().subject()).isEqualTo("ORDERS");
        ArgumentCaptor<AuditQuery> query = ArgumentCaptor.forClass(AuditQuery.class);
        verify(auditLog).page(eq(CLUSTER), query.capture());
        assertThat(query.getValue().action()).isEqualTo("purge");
    }

    @Test
    void anUnknownSourceIsAProtocolError() {
        String clusterId = CLUSTER.toString();

        assertThatThrownBy(() -> tools.activityLog(clusterId, "syslog", null, null))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("source must be one of");
    }

    private static AuditEventView audit(String action, String outcome, boolean dryRun, String error, Long affected) {
        return new AuditEventView(
                1, null, AT, "alice", null, null, action, "queue", "ORDERS", affected, outcome, dryRun, null, error,
                null, null);
    }
}
