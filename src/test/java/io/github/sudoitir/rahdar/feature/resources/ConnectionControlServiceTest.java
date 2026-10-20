package io.github.sudoitir.rahdar.feature.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.resources.ConnectionControlService.CloseResult;
import io.github.sudoitir.rahdar.feature.resources.ConnectionOperations.ConnectionSnapshot;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The close contract (ADR-0057): what is read before a close, what a vanished
 * target reports, and what the audit row carries afterwards.
 *
 * <p>The broker layer is mocked. What is under test is the service — the
 * pre-close read, the already-gone verdict, the cap on the address-scoped fan-out
 * and the audit row — not Jolokia, which {@code ConnectionOperations} owns.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class ConnectionControlServiceTest extends PostgresIntegrationTest {

    private static final String LIVE_URL = "http://live:8161/console/jolokia";
    private static final String DEAD_URL = "http://dead:8161/console/jolokia";
    private static final String CONNECTION = "a3f1c9de";
    private static final String BROKER_MBEAN = "org.apache.activemq.artemis:broker=\"b\"";

    @Autowired
    ConnectionControlService control;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    SettingsService settings;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    ArtemisNodes artemisNodes;

    @MockitoBean
    ConnectionOperations ops;

    private UUID clusterId;
    private UUID liveId;
    private UUID deadId;
    private ArtemisNode client;

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        liveId = node("live", LIVE_URL, true);
        deadId = node("dead", DEAD_URL, false);

        client = mock(ArtemisNode.class);
        when(client.resolveBrokerObjectName()).thenReturn(BROKER_MBEAN);
        when(artemisNodes.on(eq(clusterId), any(UUID.class))).thenReturn(client);
    }

    private UUID node(String name, String url, boolean active) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                clusterId, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachSeedUrl(url);
        n.applyHaState(new HaObservation(active, "STARTED", "PRIMARY", null, "2.56.0", null), 1L, Instant.now());
        return nodes.save(n).getId();
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private static ConnectionSnapshot snapshot() {
        return new ConnectionSnapshot(CONNECTION, "orders-worker-7", "10.4.2.9:53160", "apps", "CORE", 2, 3, 41L);
    }

    // ---- close by id -----------------------------------------------------

    @Test
    void closesTheConnectionOnTheNamedNodeAndReportsItApplied() {
        when(ops.readConnection(client, CONNECTION)).thenReturn(snapshot());
        when(ops.closeConnection(client, BROKER_MBEAN, CONNECTION)).thenReturn(true);

        CloseResult result = ok(control.closeConnection(clusterId, liveId, CONNECTION, false));

        assertThat(result.outcome().nodes()).singleElement().satisfies(n -> {
            assertThat(n.nodeId()).isEqualTo(liveId);
            assertThat(n.status()).isEqualTo(NodeStatus.APPLIED);
        });
        assertThat(result.target().clientId()).isEqualTo("orders-worker-7");
        // A close by id is not a fan-out: the other node is never asked.
        verify(artemisNodes, never()).on(clusterId, deadId);
    }

    @Test
    void aPreviewReadsTheTargetAndClosesNothing() {
        when(ops.readConnection(client, CONNECTION)).thenReturn(snapshot());

        CloseResult result = ok(control.closeConnection(clusterId, liveId, CONNECTION, true));

        assertThat(result.outcome().dryRun()).isTrue();
        assertThat(result.outcome().nodes().get(0).status()).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(result.target().messagesInTransit()).isEqualTo(41L);
        verify(ops, never()).closeConnection(any(), anyString(), anyString());
    }

    /** D2: the requested state — that connection is not open — holds. Not an error. */
    @Test
    void aConnectionThatHasAlreadyGoneIsASuccess() {
        when(ops.readConnection(client, CONNECTION)).thenReturn(null);

        CloseResult result = ok(control.closeConnection(clusterId, liveId, CONNECTION, false));

        assertThat(result.outcome().nodes().get(0).status()).isEqualTo(NodeStatus.ALREADY);
        assertThat(result.outcome().anyFailed()).isFalse();
        assertThat(result.target()).isNull();
        assertThat(latestAudit().getOutcome()).isEqualTo("SUCCESS");
        verify(ops, never()).closeConnection(any(), anyString(), anyString());
    }

    /**
     * The broker answers {@code false} for an id it no longer has, without an
     * error — so a connection that vanished between the read and the close is
     * still reported as already gone rather than as a failure.
     */
    @Test
    void aConnectionThatVanishesBetweenTheReadAndTheCloseIsAlreadyGone() {
        when(ops.readConnection(client, CONNECTION)).thenReturn(snapshot());
        when(ops.closeConnection(client, BROKER_MBEAN, CONNECTION)).thenReturn(false);

        CloseResult result = ok(control.closeConnection(clusterId, liveId, CONNECTION, false));

        assertThat(result.outcome().nodes().get(0).status()).isEqualTo(NodeStatus.ALREADY);
        assertThat(result.outcome().anyFailed()).isFalse();
    }

    /** D3: after the close the id resolves to nothing, so the row has to name the application. */
    @Test
    void theAuditRowCarriesTheIdentityReadImmediatelyBeforeTheClose() {
        when(ops.readConnection(client, CONNECTION)).thenReturn(snapshot());
        when(ops.closeConnection(client, BROKER_MBEAN, CONNECTION)).thenReturn(true);

        control.closeConnection(clusterId, liveId, CONNECTION, false);

        AuditEventEntity event = latestAudit();
        assertThat(event.getAction()).isEqualTo("CLOSE_CONNECTION");
        assertThat(event.getNodeId()).isEqualTo(liveId);
        assertThat(event.getOutcomeDetail())
                .contains("orders-worker-7")
                .contains("10.4.2.9:53160")
                .contains("apps");
    }

    /** A session is closed through the connection the broker says it belongs to, never a client-supplied one. */
    @Test
    void closingASessionUsesTheConnectionTheBrokerReportsForIt() {
        when(ops.readSession(client, "sess-1"))
                .thenReturn(new ConnectionSnapshot(CONNECTION, "orders-worker-7", null, "apps", null, 1, 2, 7L));
        when(ops.closeSession(client, BROKER_MBEAN, CONNECTION, "sess-1")).thenReturn(true);

        CloseResult result = ok(control.closeSession(clusterId, liveId, "sess-1", false));

        assertThat(result.outcome().nodes().get(0).status()).isEqualTo(NodeStatus.APPLIED);
        verify(ops).closeSession(client, BROKER_MBEAN, CONNECTION, "sess-1");
    }

    /** A consumer row names its session, so the connection is walked to, not guessed. */
    @Test
    void closingAConsumerWalksToItsConnectionAndClosesThat() {
        when(ops.sessionIdOfConsumer(client, "1849")).thenReturn("sess-1");
        when(ops.connectionIdOfSession(client, "sess-1")).thenReturn(CONNECTION);
        when(ops.readConnection(client, CONNECTION)).thenReturn(snapshot());
        when(ops.closeConnection(client, BROKER_MBEAN, CONNECTION)).thenReturn(true);

        CloseResult result = ok(control.closeConsumerConnection(clusterId, liveId, "1849", false));

        assertThat(result.outcome().nodes().get(0).status()).isEqualTo(NodeStatus.APPLIED);
        verify(ops).closeConnection(client, BROKER_MBEAN, CONNECTION);
    }

    /**
     * A preview of a target that has already gone must not also claim it "would
     * apply": the two statements reach the same screen, and the MCP result carries
     * both in one object.
     */
    @Test
    void aPreviewOfAnAlreadyGoneTargetReportsItGoneRatherThanWouldApply() {
        when(ops.readConnection(client, CONNECTION)).thenReturn(null);

        CloseResult result = ok(control.closeConnection(clusterId, liveId, CONNECTION, true));

        assertThat(result.outcome().dryRun()).isTrue();
        assertThat(result.outcome().nodes().get(0).status()).isEqualTo(NodeStatus.ALREADY);
    }

    @Test
    void aNodeThatIsNotPartOfTheClusterIsNotFound() {
        UUID unknownNode = UUID.randomUUID();
        assertThatThrownBy(() -> control.closeConnection(clusterId, unknownNode, CONNECTION, false))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * A session row carries no remote address and, for a CORE client, usually a
     * blank client id — so the identity has to come from its connection. Without
     * it the confirmation token falls through to the opaque connection id, which
     * ADR-0057 D3 exists to prevent.
     */
    @Test
    void aSessionCloseNeverConfirmsAgainstTheOpaqueConnectionId() {
        when(ops.readSession(client, "sess-1"))
                .thenReturn(new ConnectionSnapshot(CONNECTION, "", "10.4.2.9:53160", "apps", "CORE", 1, 2, 7L));

        CloseResult result = ok(control.closeSession(clusterId, liveId, "sess-1", true));

        assertThat(result.target().label()).isEqualTo("10.4.2.9:53160").isNotEqualTo(CONNECTION);
    }

    // ---- address-scoped close --------------------------------------------

    @Test
    void anAddressPreviewCountsPerNodeAndClosesNothing() {
        when(ops.countConsumersForAddress(client, "orders")).thenReturn(4L);

        CloseResult result = ok(control.closeAddressConsumers(clusterId, "orders", true, false));

        assertThat(result.outcome().dryRun()).isTrue();
        assertThat(result.outcome().totalAffected()).isEqualTo(4);
        assertThat(statusOf(result, liveId)).isEqualTo(NodeStatus.WOULD_APPLY);
        // The node that is not live never received the command, so it is skipped.
        assertThat(statusOf(result, deadId)).isEqualTo(NodeStatus.SKIPPED_NOT_LIVE);
        verify(ops, never()).closeConsumerConnectionsForAddress(any(), anyString(), anyString());
    }

    @Test
    void anAddressCloseOverTheCapIsRefusedUntilOverridden() {
        long cap = settings.intValue(BrokerSettings.BULK_CAP);
        when(ops.countConsumersForAddress(client, "orders")).thenReturn(cap + 1);

        assertThatThrownBy(() -> control.closeAddressConsumers(clusterId, "orders", false, false))
                .isInstanceOf(BulkCapExceededException.class);
        verify(ops, never()).closeConsumerConnectionsForAddress(any(), anyString(), anyString());

        when(ops.closeConsumerConnectionsForAddress(client, BROKER_MBEAN, "orders"))
                .thenReturn(true);
        CloseResult result = ok(control.closeAddressConsumers(clusterId, "orders", false, true));
        assertThat(statusOf(result, liveId)).isEqualTo(NodeStatus.APPLIED);
    }

    @Test
    void anAddressWithNoConsumersOnANodeReportsThatNodeAsAlready() {
        when(ops.countConsumersForAddress(client, "orders")).thenReturn(0L);
        when(ops.closeConsumerConnectionsForAddress(client, BROKER_MBEAN, "orders"))
                .thenReturn(false);

        CloseResult result = ok(control.closeAddressConsumers(clusterId, "orders", false, false));

        assertThat(statusOf(result, liveId)).isEqualTo(NodeStatus.ALREADY);
        assertThat(result.outcome().anyFailed()).isFalse();
    }

    /**
     * A node that could not be counted makes the total a floor, not a figure.
     * Passing the cap on the nodes that happened to answer would wave through
     * exactly the close whose blast radius is unknown.
     */
    @Test
    void anEstimateNoNodeCouldCompleteStillDemandsTheOverride() {
        ArtemisNode mute = mock(ArtemisNode.class);
        when(mute.resolveBrokerObjectName()).thenReturn(BROKER_MBEAN);
        when(artemisNodes.on(eq(clusterId), any(UUID.class))).thenReturn(mute);
        when(ops.countConsumersForAddress(mute, "orders"))
                .thenThrow(new BrokerConnectionException(
                        BrokerConnectionException.Kind.UNREACHABLE, "node did not answer"));

        // Well under the cap on the nodes that answered — which is nothing at all here.
        CloseResult preview = ok(control.closeAddressConsumers(clusterId, "orders", true, false));
        assertThat(preview.outcome().overCap()).isTrue();
        assertThat(preview.outcome().nodes().stream().map(NodeOutcome::error))
                .anySatisfy(e -> assertThat(e).contains("consumer count is unknown"));

        assertThatThrownBy(() -> control.closeAddressConsumers(clusterId, "orders", false, false))
                .isInstanceOf(BulkCapExceededException.class);
        verify(ops, never()).closeConsumerConnectionsForAddress(any(), anyString(), anyString());
    }

    // ---- helpers ---------------------------------------------------------

    private static CloseResult ok(Attempt<CloseResult> attempt) {
        return ((Attempt.Ok<CloseResult>) attempt).value();
    }

    private static NodeStatus statusOf(CloseResult result, UUID nodeId) {
        return result.outcome().nodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow()
                .status();
    }

    private AuditEventEntity latestAudit() {
        return auditEvents.findAll().stream()
                .filter(e -> clusterId.equals(e.getClusterId()))
                .max(Comparator.comparing(AuditEventEntity::getId))
                .orElseThrow();
    }
}
