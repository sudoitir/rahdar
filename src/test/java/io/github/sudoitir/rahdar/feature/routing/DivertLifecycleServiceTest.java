package io.github.sudoitir.rahdar.feature.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.DivertSettings;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Targets;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Divert creation and deletion across a cluster: what is checked on each node before anything is sent, what
 * is sent, and what the audit row keeps. The broker layer is mocked at the SDK's seam; the real-broker test
 * covers the wire.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class DivertLifecycleServiceTest extends PostgresIntegrationTest {

    private static final String LIVE_URL = "http://live:8161/console/jolokia";
    private static final String DEAD_URL = "http://dead:8161/console/jolokia";

    @Autowired
    DivertLifecycleService lifecycle;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    AuditEventRepository auditEvents;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    ArtemisNodes artemisNodes;

    @MockitoBean
    BrokerGateway gateway;

    @MockitoBean
    SystemExtensions extensions;

    private final DivertSettings settings = mock(DivertSettings.class);

    private BrokerTarget node;
    private Operations ops;
    private final List<Resource> held = new ArrayList<>();

    private UUID clusterId;
    private UUID liveId;

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        liveId = node("live", LIVE_URL, true);
        node("dead", DEAD_URL, false);

        when(extensions.require(any(), eq(DivertSettings.class))).thenReturn(settings);
        when(settings.addressAvailable(any(), eq("dst"))).thenReturn(true);
        node = Targets.target();
        ops = Targets.operations(node);
        when(gateway.node(any(UUID.class), any(UUID.class))).thenReturn(node);
        // The forwarding address is there, unless a test says otherwise.
        held.add(Targets.address("dst"));
        Targets.holding(node, held.toArray(Resource[]::new));
    }

    private UUID node(String name, String url, boolean active) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                clusterId, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachSeedUrl(url);
        n.applyHaState(new HaObservation(active, "STARTED", "PRIMARY", null, "2.44.0", null), 1L, Instant.now());
        return nodes.save(n).getId();
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private void nodeHolds(Resource... resources) {
        held.clear();
        held.addAll(List.of(resources));
        Targets.holding(node, held.toArray(Resource[]::new));
    }

    private static CreateDivertRequest request(String name, boolean exclusive, boolean acknowledge) {
        return new CreateDivertRequest(name, null, "src", "dst", exclusive, null, null, acknowledge);
    }

    private static Resource captureTap(String address) {
        return Targets.divert(RahdarNames.CAPTURE_PREFIX + "tap", address, "capture.sink");
    }

    private static ResourceId divertId(String name) {
        return Targets.id("artemis.divert", name);
    }

    @Test
    void aValidDivertIsCreatedAndAuditedWithTheShadowingAcknowledgement() {
        LifecycleOutcome outcome = ok(lifecycle.createDivert(clusterId, request("feed", false, false), false));

        assertThat(status(outcome)).isEqualTo(NodeStatus.APPLIED);
        ArgumentCaptor<Operation> sent = ArgumentCaptor.forClass(Operation.class);
        verify(ops).execute(eq(divertId("feed")), sent.capture(), any(Scope.class));
        assertThat(((Operation.Create) sent.getValue()).attributes().asMap())
                .containsEntry("artemis.routing_name", "feed")
                .containsEntry("artemis.exclusive", false)
                .containsKeys("openbroker.route.source", "openbroker.route.target");
        AuditEventEntity audit = realAudit("CREATE_ROUTE");
        assertThat(audit.getParams()).contains("acknowledgeCaptureShadowing").contains("routing-name");
    }

    @Test
    void anIdenticalDivertAlreadyThereIsAlreadyInTheRequestedState() {
        when(ops.execute(eq(divertId("feed")), any(Operation.Create.class), any(Scope.class)))
                .thenReturn(Targets.already());

        assertThat(status(ok(lifecycle.createDivert(clusterId, request("feed", false, false), false))))
                .isEqualTo(NodeStatus.ALREADY);
    }

    @Test
    void aDivertThatWouldCompleteACycleIsRefusedNamingIt() {
        nodeHolds(Targets.address("dst"), Targets.divert("back", "dst", "src"));

        NodeOutcome preview =
                ok(lifecycle.createDivert(clusterId, request("feed", false, false), true)).nodes().stream()
                        .filter(n -> n.nodeId().equals(liveId))
                        .findFirst()
                        .orElseThrow();

        assertThat(preview.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(preview.error()).contains("would complete a cycle of diverts");
        verify(ops, never()).execute(any(), any(), any());
    }

    @Test
    void aDivertToAnAddressTheNodeLacksIsRefusedWithTheDeclarationThatWouldFixIt() {
        when(settings.addressAvailable(any(), eq("dst"))).thenReturn(false);

        NodeOutcome preview = preview(request("feed", false, false));

        assertThat(preview.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(preview.error())
                .contains("'dst' does not exist on this node and is not created automatically")
                .contains("producers to 'src' would fail")
                .contains("<address name=\"dst\">");
    }

    @Test
    void anExclusiveDivertOnACapturedAddressIsRefusedUnlessTheShadowingIsAcknowledged() {
        nodeHolds(Targets.address("dst"), captureTap("src"));

        NodeOutcome refused = preview(request("feed", true, false));
        assertThat(refused.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(refused.error()).contains("is being captured").contains("acknowledgeCaptureShadowing");

        NodeOutcome warned = preview(request("feed", true, true));
        assertThat(warned.status()).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(warned.error()).contains("is being captured").doesNotContain("Set acknowledgeCaptureShadowing");
    }

    @Test
    void theCaptureCheckIgnoresNonExclusiveDivertsAndCapturesOfOtherAddresses() {
        nodeHolds(
                Targets.address("dst"),
                captureTap("src"),
                captureTap("elsewhere"),
                Targets.divert("plain", "src", "x"));
        assertThat(preview(request("feed", false, false)).error()).isNull();

        nodeHolds(Targets.address("dst"), captureTap("elsewhere"), Targets.divert("plain", "src", "x"));
        assertThat(preview(request("feed", true, false)).error()).isNull();
    }

    @Test
    void aDivertRequestThatFailsValidationIsRejectedWithEveryViolation() {
        var invalid = new CreateDivertRequest("bad name", null, "src", "src", null, null, "NOPE", null);

        assertThatThrownBy(() -> lifecycle.createDivert(clusterId, invalid, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name:")
                .hasMessageContaining("routingType:");
    }

    @Test
    void divertNamesInTheNamespacesRahdarReservesAreRefused() {
        var captureRequest = request(RahdarNames.CAPTURE_PREFIX + "x", false, false);
        var tapRequest = request(RahdarNames.PLUGIN_TAP_PREFIX + "x", false, false);

        assertThatThrownBy(() -> lifecycle.createDivert(clusterId, captureRequest, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved for message capture");
        assertThatThrownBy(() -> lifecycle.createDivert(clusterId, tapRequest, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved for plugins' message taps");
        assertThatThrownBy(() -> lifecycle.deleteDivert(clusterId, RahdarNames.CAPTURE_PREFIX + "x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycle.deleteDivert(clusterId, RahdarNames.PLUGIN_TAP_PREFIX + "x", false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ops, never()).execute(any(), any(), any());
    }

    @Test
    void anOperatorsDivertIsDeletedOnTheLiveNode() {
        LifecycleOutcome outcome = ok(lifecycle.deleteDivert(clusterId, "feed", false));

        assertThat(status(outcome)).isEqualTo(NodeStatus.APPLIED);
        verify(ops).execute(eq(divertId("feed")), eq(Operation.delete()), any(Scope.class));
    }

    // ---- helpers ----------------------------------------------------------

    private NodeOutcome preview(CreateDivertRequest request) {
        return ok(lifecycle.createDivert(clusterId, request, true)).nodes().stream()
                .filter(n -> n.nodeId().equals(liveId))
                .findFirst()
                .orElseThrow();
    }

    /** The one real (not previewed) row for an action on this test's cluster. */
    private AuditEventEntity realAudit(String action) {
        List<AuditEventEntity> real = auditEvents.findAll().stream()
                .filter(e -> action.equals(e.getAction()) && clusterId.equals(e.getClusterId()) && !e.isDryRun())
                .toList();
        assertThat(real).hasSize(1);
        return real.get(0);
    }

    private static LifecycleOutcome ok(Attempt<LifecycleOutcome> attempt) {
        return ((Attempt.Ok<LifecycleOutcome>) attempt).value();
    }

    private NodeStatus status(LifecycleOutcome outcome) {
        return outcome.nodes().stream()
                .filter(n -> n.nodeId().equals(liveId))
                .findFirst()
                .orElseThrow()
                .status();
    }
}
