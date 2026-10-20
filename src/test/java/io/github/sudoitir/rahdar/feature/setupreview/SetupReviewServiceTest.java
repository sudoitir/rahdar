package io.github.sudoitir.rahdar.feature.setupreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.alerting.AlertPermissions;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.FindingKey;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.SetupFindingAcceptanceEntity;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.SetupFindingAcceptanceRepository;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.SetupFindingEntity;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.SetupFindingRepository;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.SetupReviewEntity;
import io.github.sudoitir.rahdar.feature.setupreview.internal.persistence.SetupReviewRepository;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.AcceptRiskRequest;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.SetupFindingView;
import io.github.sudoitir.rahdar.feature.setupreview.web.SetupReviewViews.SetupReviewView;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The review's orchestration and the acceptance ledger, with every collaborator stubbed. */
class SetupReviewServiceTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private final ArtemisNodes artemisNodes = mock(ArtemisNodes.class);
    private final ClusterDirectory clusters = mock(ClusterDirectory.class);
    private final BrokerConnections connections = mock(BrokerConnections.class);
    private final SetupReader reader = mock(SetupReader.class);
    private final SetupReviewStore store = mock(SetupReviewStore.class);
    private final SetupReviewRepository reviews = mock(SetupReviewRepository.class);
    private final SetupFindingRepository findings = mock(SetupFindingRepository.class);
    private final SetupFindingAcceptanceRepository acceptances = mock(SetupFindingAcceptanceRepository.class);
    private final ClusterLock lock = mock(ClusterLock.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final SseHub hub = mock(SseHub.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final AuditService audit = mock(AuditService.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();

    private SetupReviewService service;

    @BeforeEach
    void setUp() {
        service = new SetupReviewService(
                clusters,
                connections,
                reader,
                store,
                reviews,
                findings,
                acceptances,
                lock,
                settings,
                hub,
                access,
                audit,
                actors,
                mapper,
                artemisNodes);
        when(settings.duration(SetupReviewSettings.MIN_INTERVAL)).thenReturn(Duration.ofMinutes(1));
        when(clusters.nodes(CLUSTER)).thenReturn(List.of());
        when(reviews.findById(CLUSTER)).thenReturn(Optional.empty());
        when(findings.findByClusterId(CLUSTER)).thenReturn(List.of());
        when(acceptances.findByClusterId(CLUSTER)).thenReturn(List.of());
        when(actors.resolve()).thenReturn(new Actor("alice", "127.0.0.1", "req", null));
        AuditEvent event = mock(AuditEvent.class);
        when(audit.begin(any(), anyString(), anyString(), anyString(), any(), any(), any(), eq(false)))
                .thenReturn(event);
    }

    private static ClusterNode node(UUID id, String name, String jolokiaUrl, Boolean active) {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(id);
        when(node.getName()).thenReturn(name);
        when(node.getManagementUrl()).thenReturn(jolokiaUrl);
        when(node.getNativeNodeId()).thenReturn("artemis-" + name);
        when(node.getActive()).thenReturn(active);
        return node;
    }

    private static Finding finding(String code, Category category, Severity severity, String subject) {
        return new Finding(
                code,
                category,
                severity,
                subject,
                "title " + code,
                "impact",
                List.of(new Finding.Evidence("node-a", "key", "value")),
                "recommendation",
                "<snippet/>",
                List.of("a caveat"),
                true);
    }

    private SetupFindingEntity stored(Finding f, Instant firstSeen, Instant lastSeen) {
        SetupFindingEntity row = new SetupFindingEntity(CLUSTER, f.code(), f.subject(), firstSeen);
        row.seen(f.severity().name(), f.category().name(), mapper.writeValueAsString(f), lastSeen);
        return row;
    }

    private SetupReviewEntity reviewedAt(Instant at, String nodesJson, String notAssessedJson) {
        SetupReviewEntity review = new SetupReviewEntity(CLUSTER);
        review.recordReview(at, 250, 2, 1, nodesJson, notAssessedJson, true);
        return review;
    }

    // ---- reading ----------------------------------------------------------

    @Test
    void viewOfANeverReviewedClusterIsEmptyAndNeverRunsAReview() {
        SetupReviewView view = service.view(CLUSTER);

        verify(access).requireCluster(CLUSTER, Permissions.CLUSTER_READ);
        assertThat(view.reviewedAt()).isNull();
        assertThat(view.durationMs()).isZero();
        assertThat(view.nodesTotal()).isZero();
        assertThat(view.nodesReviewed()).isZero();
        assertThat(view.clusterEvaluated()).isFalse();
        assertThat(view.nodes()).isEmpty();
        assertThat(view.findings()).isEmpty();
        assertThat(view.notAssessed()).isEmpty();
        assertThat(view.accepted()).isZero();
        assertThat(view.rulesInCatalogue()).isEqualTo(SetupRules.CODES.size());
        assertThat(view.notice()).isNull();
        verify(store, never()).persist(any(), any(), any(), any(), anyLong());
    }

    private final Instant reviewed = Instant.now().minusSeconds(60);
    private final UUID staleNode = UUID.randomUUID();

    private SetupReviewView mixedView() {
        Finding critical = finding("A_CRITICAL", Category.DURABILITY, Severity.CRITICAL, "node:" + NODE);
        Finding warning = finding("B_WARNING", Category.CLUSTERING, Severity.WARNING, SetupRules.CLUSTER);
        Finding acceptedWarning = finding("C_WARNING", Category.SECURITY, Severity.WARNING, "node:" + NODE);
        Finding expiredAcceptance = finding("D_INFO", Category.SECURITY, Severity.INFO, "node:" + staleNode);
        ClusterNode nodeA = node(NODE, "node-a", "http://a", true);
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(nodeA));
        when(reviews.findById(CLUSTER))
                .thenReturn(Optional.of(reviewedAt(
                        reviewed,
                        "[{\"nodeId\":\"" + NODE + "\",\"nodeName\":\"node-a\",\"live\":true,\"reviewed\":true,"
                                + "\"reason\":null},{\"nodeId\":\"" + staleNode
                                + "\",\"nodeName\":\"node-b\",\"live\":false,\"reviewed\":false,\"reason\":\"down\"}]",
                        "[{\"code\":\"*\",\"subject\":\"node:" + staleNode + "\",\"reason\":\"down\"}]")));
        when(findings.findByClusterId(CLUSTER))
                .thenReturn(List.of(
                        stored(expiredAcceptance, reviewed.minusSeconds(500), reviewed.minusSeconds(100)),
                        stored(acceptedWarning, reviewed.minusSeconds(500), reviewed),
                        stored(warning, reviewed.minusSeconds(500), reviewed),
                        stored(critical, reviewed.minusSeconds(500), reviewed)));
        when(acceptances.findByClusterId(CLUSTER))
                .thenReturn(List.of(
                        new SetupFindingAcceptanceEntity(
                                CLUSTER, "C_WARNING", "node:" + NODE, "dev only", "bob", reviewed, null),
                        new SetupFindingAcceptanceEntity(
                                CLUSTER,
                                "D_INFO",
                                "node:" + staleNode,
                                "was ok",
                                "bob",
                                reviewed,
                                Instant.now().minusSeconds(5))));

        return service.view(CLUSTER);
    }

    @Test
    void viewSortsFindingsBySeverityAndCountsOnlyUnacceptedOnesAsOpen() {
        SetupReviewView view = mixedView();

        assertThat(view.reviewedAt()).isEqualTo(reviewed);
        assertThat(view.durationMs()).isEqualTo(250);
        assertThat(view.nodesTotal()).isEqualTo(2);
        assertThat(view.nodesReviewed()).isEqualTo(1);
        assertThat(view.clusterEvaluated()).isTrue();
        assertThat(view.findings())
                .extracting(SetupFindingView::code)
                .containsExactly("A_CRITICAL", "B_WARNING", "C_WARNING", "D_INFO");
        // Open counts: the accepted warning is set aside, the expired acceptance no longer hides its finding.
        assertThat(view.open().critical()).isEqualTo(1);
        assertThat(view.open().warning()).isEqualTo(1);
        assertThat(view.open().info()).isEqualTo(1);
        assertThat(view.accepted()).isEqualTo(1);
    }

    @Test
    void viewCarriesTheAcceptanceAndLabelOfEachFinding() {
        SetupReviewView view = mixedView();

        SetupFindingView accepted = view.findings().get(2);
        assertThat(accepted.acceptance().active()).isTrue();
        assertThat(accepted.acceptance().acceptedBy()).isEqualTo("bob");
        assertThat(accepted.acceptance().reason()).isEqualTo("dev only");
        assertThat(accepted.acceptance().expiresAt()).isNull();
        assertThat(accepted.subjectLabel()).isEqualTo("node-a");
        assertThat(accepted.stale()).isFalse();
        assertThat(accepted.evidence()).singleElement().satisfies(e -> {
            assertThat(e.key()).isEqualTo("key");
            assertThat(e.value()).isEqualTo("value");
        });
        assertThat(accepted.caveats()).containsExactly("a caveat");

        assertThat(view.findings().get(1).subjectLabel()).isEqualTo("cluster");
        assertThat(view.findings().get(1).acceptance()).isNull();

        SetupFindingView expired = view.findings().get(3);
        assertThat(expired.acceptance().active()).isFalse();
        // Last seen before the latest review: this node did not answer, so the finding is a remembered one.
        assertThat(expired.stale()).isTrue();
        assertThat(expired.subjectLabel()).isEqualTo("node:" + staleNode);
    }

    @Test
    void viewListsEachNodeAndWhatWasNotAssessed() {
        SetupReviewView view = mixedView();

        assertThat(view.nodes()).hasSize(2);
        assertThat(view.nodes().get(0).reviewed()).isTrue();
        assertThat(view.nodes().get(0).reason()).isNull();
        assertThat(view.nodes().get(1).live()).isFalse();
        assertThat(view.nodes().get(1).reason()).isEqualTo("down");
        assertThat(view.notAssessed()).singleElement().satisfies(na -> {
            assertThat(na.subject()).isEqualTo("node:" + staleNode);
            assertThat(na.subjectLabel()).isEqualTo("node:" + staleNode);
            assertThat(na.reason()).isEqualTo("down");
        });
    }

    // ---- running ------------------------------------------------------------

    @Test
    void runningAnUnknownClusterIs404() {
        when(clusters.cluster(CLUSTER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run(CLUSTER)).isInstanceOf(NotFoundException.class);
        verify(access).requireCluster(CLUSTER, Permissions.CLUSTER_READ);
    }

    @Test
    void aRunTooSoonAfterTheLastReturnsTheLastWithANotice() {
        when(clusters.cluster(CLUSTER)).thenReturn(Optional.of(mock(RegisteredCluster.class)));
        when(reviews.findById(CLUSTER))
                .thenReturn(Optional.of(reviewedAt(Instant.now().minusSeconds(20), "[]", "[]")));

        SetupReviewView view = service.run(CLUSTER);

        assertThat(view.notice())
                .startsWith("Reviewed 20s ago; the next review can run in ")
                .endsWith("Showing the last review.");
        verify(lock, never()).runIfHeld(any(), any(ClusterLock.Scope.class), any());
    }

    @Test
    void aRunWhileAnotherHoldsTheLockSaysSo() {
        when(clusters.cluster(CLUSTER)).thenReturn(Optional.of(mock(RegisteredCluster.class)));
        when(lock.runIfHeld(eq(CLUSTER), eq(ClusterLock.Scope.SETUP_REVIEW), any()))
                .thenReturn(false);

        SetupReviewView view = service.run(CLUSTER);

        assertThat(view.notice()).contains("already running");
        verify(store, never()).persist(any(), any(), any(), any(), anyLong());
    }

    @Test
    void aRunWhoseLockWasHeldButWhoseWorkDidNotRunSaysSo() {
        when(clusters.cluster(CLUSTER)).thenReturn(Optional.of(mock(RegisteredCluster.class)));
        when(lock.runIfHeld(eq(CLUSTER), eq(ClusterLock.Scope.SETUP_REVIEW), any()))
                .thenReturn(true);

        assertThat(service.run(CLUSTER).notice()).contains("already running");
    }

    @Test
    void aRunAfterTheSpacingReviewsEveryNodeAndPersistsOnce() {
        when(clusters.cluster(CLUSTER)).thenReturn(Optional.of(mock(RegisteredCluster.class)));
        when(reviews.findById(CLUSTER))
                .thenReturn(Optional.of(reviewedAt(Instant.now().minus(1, ChronoUnit.HOURS), "[]", "[]")));
        ClusterNode noUrl = node(UUID.randomUUID(), "node-a", null, null);
        ClusterNode readable = node(NODE, "node-b", "http://b", true);
        when(clusters.nodes(CLUSTER)).thenReturn(List.of(noUrl, readable));
        NodeRead read = NodeRead.unreadable(NODE, "node-b", "artemis-node-b", true, true, "boom");
        when(reader.read(any(), eq(readable))).thenReturn(read);
        doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(2)).run();
                    return true;
                })
                .when(lock)
                .runIfHeld(eq(CLUSTER), eq(ClusterLock.Scope.SETUP_REVIEW), any());

        SetupReviewView view = service.run(CLUSTER);

        assertThat(view.notice()).isNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NodeRead>> reads = ArgumentCaptor.forClass(List.class);
        verify(store)
                .persist(eq(CLUSTER), reads.capture(), any(SetupRules.Result.class), any(Instant.class), anyLong());
        assertThat(reads.getValue()).hasSize(2);
        // A node with no management URL is reported unreadable, never passed silently.
        assertThat(reads.getValue().get(0).unavailableReason()).contains("no management URL");
        assertThat(reads.getValue().get(0).live()).isFalse();
        assertThat(reads.getValue().get(1)).isSameAs(read);
        verify(hub).publish(CLUSTER, SetupReviewService.TOPIC);
    }

    @Test
    void reviewAllVisitsEveryClusterAndSurvivesOneFailing() {
        UUID other = UUID.randomUUID();
        RegisteredCluster first = mock(RegisteredCluster.class);
        when(first.getId()).thenReturn(CLUSTER);
        RegisteredCluster second = mock(RegisteredCluster.class);
        when(second.getId()).thenReturn(other);
        when(clusters.owned()).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("lock db down"))
                .when(lock)
                .runIfHeld(eq(CLUSTER), eq(ClusterLock.Scope.SETUP_REVIEW), any());
        when(lock.runIfHeld(eq(other), eq(ClusterLock.Scope.SETUP_REVIEW), any()))
                .thenReturn(true);

        service.reviewAll();

        verify(lock).runIfHeld(eq(other), eq(ClusterLock.Scope.SETUP_REVIEW), any());
    }

    // ---- risk acceptance ------------------------------------------------------

    private void findingExists(String code, String subject) {
        Finding f = finding(code, Category.DURABILITY, Severity.WARNING, subject);
        when(findings.findById(new FindingKey(CLUSTER, code, subject)))
                .thenReturn(Optional.of(stored(f, Instant.now(), Instant.now())));
    }

    @Test
    void acceptingAnUnknownFindingIs404() {
        when(findings.findById(any())).thenReturn(Optional.empty());

        var request = new AcceptRiskRequest("NOPE", "cluster", "why", null);

        assertThatThrownBy(() -> service.accept(CLUSTER, request)).isInstanceOf(NotFoundException.class);
        verify(access).requireCluster(CLUSTER, AlertPermissions.ALERT_WRITE);
        verify(acceptances, never()).save(any());
    }

    @Test
    void anAcceptanceThatExpiresInThePastIsRefused() {
        findingExists("CODE", "cluster");

        var request =
                new AcceptRiskRequest("CODE", "cluster", "why", Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.accept(CLUSTER, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiresAt");
        verify(acceptances, never()).save(any());
    }

    @Test
    void acceptingRecordsTheTrimmedReasonTheActorAndAuditsIt() {
        findingExists("CODE", "cluster");
        AuditEvent event = mock(AuditEvent.class);
        when(audit.begin(
                        any(),
                        eq("ACCEPT_SETUP_RISK"),
                        eq("SETUP_FINDING"),
                        eq("CODE on cluster"),
                        eq(CLUSTER),
                        any(),
                        any(),
                        eq(false)))
                .thenReturn(event);

        service.accept(CLUSTER, new AcceptRiskRequest("CODE", "cluster", "  dev only  ", null));

        ArgumentCaptor<SetupFindingAcceptanceEntity> saved =
                ArgumentCaptor.forClass(SetupFindingAcceptanceEntity.class);
        verify(acceptances).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo("dev only");
        assertThat(saved.getValue().getAcceptedBy()).isEqualTo("alice");
        assertThat(saved.getValue().getExpiresAt()).isNull();
        verify(audit).succeed(event, 1);
        verify(hub).publish(CLUSTER, SetupReviewService.TOPIC);
    }

    @Test
    void anAcceptanceWithAnExpiryPutsItInTheAuditParams() {
        findingExists("CODE", "cluster");
        Instant expires = Instant.now().plus(1, ChronoUnit.DAYS);

        service.accept(CLUSTER, new AcceptRiskRequest("CODE", "cluster", "until the upgrade", expires));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .begin(
                        any(),
                        eq("ACCEPT_SETUP_RISK"),
                        anyString(),
                        anyString(),
                        eq(CLUSTER),
                        any(),
                        params.capture(),
                        eq(false));
        assertThat(params.getValue())
                .containsEntry("reason", "until the upgrade")
                .containsEntry("expiresAt", expires.toString());
    }

    @Test
    void revokingAnUnknownAcceptanceIs404() {
        when(acceptances.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(CLUSTER, "CODE", "cluster")).isInstanceOf(NotFoundException.class);
        verify(acceptances, never()).delete(any());
    }

    @Test
    void revokingDeletesTheAcceptanceAndAuditsWhoAcceptedIt() {
        SetupFindingAcceptanceEntity acceptance =
                new SetupFindingAcceptanceEntity(CLUSTER, "CODE", "cluster", "why", "bob", Instant.now(), null);
        when(acceptances.findById(new FindingKey(CLUSTER, "CODE", "cluster"))).thenReturn(Optional.of(acceptance));
        AuditEvent event = mock(AuditEvent.class);
        when(audit.begin(any(), eq("REVOKE_SETUP_RISK"), anyString(), anyString(), any(), any(), any(), eq(false)))
                .thenReturn(event);

        service.revoke(CLUSTER, "CODE", "cluster");

        verify(acceptances).delete(acceptance);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .begin(
                        any(),
                        eq("REVOKE_SETUP_RISK"),
                        eq("SETUP_FINDING"),
                        eq("CODE on cluster"),
                        eq(CLUSTER),
                        any(),
                        params.capture(),
                        eq(false));
        assertThat(params.getValue()).containsEntry("acceptedBy", "bob");
        verify(audit).succeed(event, 1);
        verify(hub).publish(CLUSTER, SetupReviewService.TOPIC);
    }
}
