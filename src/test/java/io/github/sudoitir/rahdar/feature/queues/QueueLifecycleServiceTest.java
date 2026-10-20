package io.github.sudoitir.rahdar.feature.queues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.queues.LifecycleRequests.CreateQueueRequest;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Targets;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import io.github.sudoitir.rahdar.system.artemis.DivertOperations;
import io.github.sudoitir.rahdar.system.artemis.DivertRow;
import io.github.sudoitir.rahdar.system.artemis.ManagementResponse;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The fan-out contract (ADR-0049): which nodes are targeted, what each reports,
 * and what the audit row says afterwards.
 *
 * <p>The broker layer is mocked. What is under test is the service's own
 * behaviour — target selection from live topology, per-node outcome collection,
 * the cap check, and the single audit row — not Jolokia, which
 * {@code QueueLifecycleOperations} owns and the live groundwork already verified.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class QueueLifecycleServiceTest extends PostgresIntegrationTest {

    private static final String LIVE_URL = "http://live:8161/console/jolokia";
    private static final String DEAD_URL = "http://dead:8161/console/jolokia";
    private static final String QUEUE = "orders";

    @Autowired
    QueueLifecycleService lifecycle;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    QueueSnapshots snapshots;

    @Autowired
    AuditEventRepository auditEvents;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    ArtemisNodes artemisNodes;

    @MockitoBean
    BrokerGateway gateway;

    @MockitoBean
    QueueLifecycleOperations ops;

    @MockitoBean
    DivertOperations divertOps;

    @MockitoBean
    DeclaredDiverts declaredDiverts;

    @MockitoBean
    CaptureTaps captureTaps;

    private ArtemisNode client;

    private UUID clusterId;
    private UUID liveId;
    private UUID deadId;

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        liveId = node("live", LIVE_URL, true);
        deadId = node("dead", DEAD_URL, false);

        client = mock(ArtemisNode.class);
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
        when(artemisNodes.on(eq(clusterId), any(UUID.class))).thenReturn(client);
        when(artemisNodes.on(any(BrokerTarget.class))).thenReturn(client);
        BrokerTarget anyNode = Targets.target();
        when(gateway.node(any(UUID.class), any(UUID.class))).thenReturn(anyNode);
    }

    /** Each node is its own logical node, so both are considered as targets. */
    private UUID node(String name, String url, boolean active) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                clusterId, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachSeedUrl(url);
        n.applyHaState(new HaObservation(active, "STARTED", "PRIMARY", null, "2.44.0", null), 1L, Instant.now());
        return nodes.save(n).getId();
    }

    private void seedQueue() {
        upsert.upsertBatch(List.of(new QueueRow(
                clusterId,
                liveId,
                "orders.addr",
                QUEUE,
                "ANYCAST",
                true,
                5,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                "artemis.queue")));
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private CreateQueueRequest createRequest() {
        return new CreateQueueRequest("orders.addr", QUEUE, "ANYCAST", true, null, null, null, null, null, null, null);
    }

    // ---- fan-out targeting ------------------------------------------------

    @Test
    void appliesToLiveNodesAndSkipsTheRestWithoutFailingTheCommand() {
        Attempt<LifecycleOutcome> attempt = lifecycle.createQueue(clusterId, createRequest(), false);

        LifecycleOutcome outcome = ((Attempt.Ok<LifecycleOutcome>) attempt).value();
        assertThat(outcome.nodes()).hasSize(2);
        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        // Never reached the command, so it is skipped — not a failure.
        assertThat(status(outcome, deadId)).isEqualTo(NodeStatus.SKIPPED_NOT_LIVE);
        assertThat(outcome.anyFailed()).isFalse();

        // The node that was not live must not have been called at all.
        verify(artemisNodes, never()).on(clusterId, deadId);
    }

    @Test
    void aQueueThatAlreadyExistsWithTheSameConfigurationIsNotAnError() {
        when(ops.createQueue(any(), anyString(), any()))
                .thenThrow(new ManagementRefusal(ManagementRefusal.Kind.ALREADY, "AMQ229019: already exists"));
        when(ops.readQueueConfig(any(), anyString()))
                .thenReturn(Map.of("address", "orders.addr", "routing-type", "ANYCAST", "durable", true));

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, createRequest(), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.ALREADY);
        assertThat(outcome.anyFailed()).isFalse();
    }

    @Test
    void aQueueThatExistsWithADifferentConfigurationFailsAndNamesTheDifference() {
        when(ops.createQueue(any(), anyString(), any()))
                .thenThrow(new ManagementRefusal(ManagementRefusal.Kind.ALREADY, "AMQ229019: already exists"));
        // Same name, different address — a genuinely different queue.
        when(ops.readQueueConfig(any(), anyString()))
                .thenReturn(Map.of("address", "somewhere.else", "routing-type", "ANYCAST", "durable", true));

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, createRequest(), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.FAILED);
        assertThat(error(outcome, liveId)).contains("somewhere.else").contains("orders.addr");
        assertThat(outcome.anyFailed()).isTrue();
    }

    // ---- dry run ----------------------------------------------------------

    @Test
    void aDryRunMakesNoMutatingCall() {
        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, createRequest(), true));

        assertThat(outcome.dryRun()).isTrue();
        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(status(outcome, deadId)).isEqualTo(NodeStatus.SKIPPED_NOT_LIVE);
        verify(ops, never()).createQueue(any(), anyString(), any());
    }

    @Test
    void aDeleteDryRunReportsWhatWouldBeDestroyedPerNodeAndDestroysNothing() {
        seedQueue();
        onTheNode(0, QUEUE);
        when(ops.messageCount(any(), anyString())).thenReturn(42L);

        LifecycleOutcome outcome = ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false));

        assertThat(outcome.totalAffected()).isEqualTo(42L);
        assertThat(outcome.nodes().stream()
                        .filter(n -> n.nodeId().equals(liveId))
                        .findFirst()
                        .orElseThrow()
                        .affected())
                .isEqualTo(42L);
        verify(ops, never()).destroyQueue(any(), anyString(), anyString(), anyBoolean());
    }

    // ---- the bulk cap (D6) ------------------------------------------------

    @Test
    void aDeleteOverTheCapIsRefusedWithoutAnOverride() {
        seedQueue();
        onTheNode(0, QUEUE);
        when(ops.messageCount(any(), anyString())).thenReturn(Long.MAX_VALUE / 2);

        assertThatThrownBy(() -> lifecycle.deleteQueue(clusterId, QUEUE, false, false, false))
                .isInstanceOf(BulkCapExceededException.class);
        verify(ops, never()).destroyQueue(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void aDeleteOverTheCapProceedsWithAnExplicitOverride() {
        seedQueue();
        onTheNode(0, QUEUE);
        when(ops.messageCount(any(), anyString())).thenReturn(Long.MAX_VALUE / 2);

        LifecycleOutcome outcome = ok(lifecycle.deleteQueue(clusterId, QUEUE, false, true, false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        verify(ops).destroyQueue(any(), anyString(), eq(QUEUE), eq(false));
    }

    @Test
    void aDeletedQueueLeavesTheListingAtOnce() {
        // The snapshot is a cache of what the brokers run. Left until the next sweep, the
        // deleted queue stays on screen — the delete reads as though it did nothing, and
        // every action on the row that is left fails against an MBean that is gone.
        seedQueue();
        onTheNode(0, QUEUE);

        LifecycleOutcome outcome = ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        assertThat(snapshots.forCluster(clusterId))
                .extracting(s -> s.queueName())
                .doesNotContain(QUEUE);
    }

    @Test
    void aPreviewLeavesTheListingAlone() {
        seedQueue();
        onTheNode(0, QUEUE);

        ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false));

        assertThat(snapshots.forCluster(clusterId))
                .extracting(s -> s.queueName())
                .contains(QUEUE);
    }

    // ---- partial failure (D3, D4) -----------------------------------------

    @Test
    void aPartialFailureIsReportedPerNodeAndRecordedAsFailed() {
        // Two live nodes, so one can succeed while the other refuses.
        UUID secondLive = node("live-2", "http://live2:8161/console/jolokia", true);
        doThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "connection refused"))
                .when(ops)
                .createQueue(any(), anyString(), any());

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, createRequest(), false));

        assertThat(outcome.anyFailed()).isTrue();
        assertThat(status(outcome, secondLive)).isEqualTo(NodeStatus.FAILED);

        // One row for the whole command, recorded as failed, carrying every node.
        List<AuditEventEntity> events = auditFor("CREATE_QUEUE");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOutcome()).isEqualTo("FAILURE");
        assertThat(events.get(0).getOutcomeDetail()).contains("FAILED").contains("SKIPPED_NOT_LIVE");
    }

    @Test
    void aSuccessfulFanOutIsOneAuditRowCarryingEveryNode() {
        ok(lifecycle.createQueue(clusterId, createRequest(), false));

        List<AuditEventEntity> events = auditFor("CREATE_QUEUE");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOutcome()).isEqualTo("SUCCESS");
        assertThat(events.get(0).getOutcomeDetail()).contains("APPLIED").contains("SKIPPED_NOT_LIVE");
    }

    // ---- delete: consumers and dependent diverts (ADR-0084) ----------------

    private static final String ADDRESS = "orders.addr";

    /** What the delete preflight reads on the live node: the queue's consumers and the address's queues. */
    private void onTheNode(long consumers, String... addressQueues) {
        when(ops.deleteState(any(), anyString(), eq(ADDRESS), eq(QUEUE), anyString()))
                .thenReturn(new QueueLifecycleOperations.DeleteState(
                        List.of(addressQueues).contains(QUEUE), consumers, List.of(addressQueues)));
    }

    private void diverts(DivertRow... rows) {
        when(divertOps.listDiverts(any(), any(), any())).thenReturn(List.of(rows));
    }

    private static DivertRow divert(String name, String from, String to) {
        return new DivertRow(null, null, name, name, from, to, null, "STRIP", null, Map.of(), false, false);
    }

    private NodeOutcome live(LifecycleOutcome outcome) {
        return outcome.nodes().stream()
                .filter(n -> n.nodeId().equals(liveId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aQueueWithConsumersIsRefusedInThePreviewNamingTheFlagAndIsNotDestroyed() {
        seedQueue();
        onTheNode(2, QUEUE);

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));
        assertThat(preview.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(preview.error()).contains("2 consumers").contains("disconnectConsumers");

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));
        assertThat(real.status()).isEqualTo(NodeStatus.FAILED);
        verify(ops, never()).destroyQueue(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void disconnectingConsumersIsWarnedInThePreviewAndSentForReal() {
        seedQueue();
        onTheNode(2, QUEUE);

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, true)));
        assertThat(preview.status()).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(preview.error()).contains("2 consumers").contains("disconnected");

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, true)));
        assertThat(real.status()).isEqualTo(NodeStatus.APPLIED);
        verify(ops).destroyQueue(any(), anyString(), eq(QUEUE), eq(true));
        assertThat(realAudit("DELETE_QUEUE").getParams()).contains("disconnectConsumers");
    }

    @Test
    void aDivertIntoTheQueuesLastAddressIsNamedInThePreviewAndRemovedBeforeTheQueue() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));
        assertThat(preview.status()).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(preview.error())
                .contains("feed")
                .contains("incoming → " + ADDRESS)
                .contains("broker.xml");
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));
        assertThat(real.status()).isEqualTo(NodeStatus.APPLIED);
        assertThat(real.error()).contains("feed");
        InOrder order = inOrder(divertOps, ops);
        order.verify(divertOps).destroyDivert(any(), anyString(), eq("feed"));
        order.verify(ops).destroyQueue(any(), anyString(), eq(QUEUE), eq(false));
        // Enough in the audit row to recreate the divert exactly.
        assertThat(realAudit("DELETE_QUEUE").getOutcomeDetail())
                .contains("feed")
                .contains("incoming")
                .contains("STRIP");
    }

    @Test
    void aDivertIntoAnAddressThatKeepsAnotherQueueIsLeftAlone() {
        seedQueue();
        onTheNode(0, QUEUE, "orders.audit");
        diverts(divert("feed", "incoming", ADDRESS));

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.APPLIED);
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());
    }

    @Test
    void aDivertFromTheQueuesAddressIsKeptAndTheReasonGiven() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("fan-out", ADDRESS, "elsewhere"));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));
        assertThat(preview.error()).contains("fan-out").contains("kept");

        ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false));
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());
    }

    @Test
    void anIncomingDivertIsKeptWhileADivertFromTheAddressKeepsItBound() {
        // incoming → ADDRESS → elsewhere: the address outlives its last queue through fan-out,
        // so removing feed would cut a chain the delete does not otherwise touch.
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS), divert("fan-out", ADDRESS, "elsewhere"));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));
        assertThat(preview.error()).contains("'feed'").contains("fan-out").contains("kept");
        assertThat(preview.error()).doesNotContain("Removed with the queue");

        ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false));
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());
    }

    @Test
    void aCaptureTapIsNeverRemovedByAQueueDelete() {
        seedQueue();
        onTheNode(0, QUEUE);
        String tap = DivertOperations.CAPTURE_PREFIX + "x";
        diverts(divert(tap, ADDRESS, tap));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));
        assertThat(preview.error()).contains(tap).contains("removed by its capture subscription");

        ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false));
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());
    }

    @Test
    void aCaptureTapStillCoveringAnotherQueueOnTheAddressIsNamedAsKept() {
        seedQueue();
        onTheNode(0, QUEUE, "orders.audit");
        String tap = DivertOperations.CAPTURE_PREFIX + "x";
        diverts(divert(tap, ADDRESS, tap));
        when(captureTaps.coversWithout(clusterId, tap, ADDRESS, QUEUE)).thenReturn(true);

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));

        assertThat(preview.error()).contains(tap).contains("kept").doesNotContain("removed by its capture");
    }

    @Test
    void aDeclaredDependentDivertIsNamedAsComingBack() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));
        when(declaredDiverts.names(clusterId)).thenReturn(Set.of("feed"));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));

        assertThat(preview.error()).contains("declared configuration");
    }

    @Test
    void aDivertThatCannotBeRemovedKeepsTheQueue() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));
        doThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "connection refused"))
                .when(divertOps)
                .destroyDivert(any(), anyString(), eq("feed"));

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(real.error()).contains("feed").contains("not deleted");
        verify(ops, never()).destroyQueue(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void aQueueDeleteThatFailsAfterItsDivertsNamesTheDivertsAlreadyRemoved() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.HAS_CONSUMERS, "A consumer attached."))
                .when(ops)
                .destroyQueue(any(), anyString(), eq(QUEUE), anyBoolean());

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(real.error()).contains("A consumer attached.").contains("feed");
    }

    @Test
    void aNodeWithoutTheQueueRemovesNothingAndIsAlreadyThere() {
        seedQueue();
        onTheNode(0, "someone.else");
        diverts(divert("feed", "incoming", ADDRESS));
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.ALREADY, "Already absent"))
                .when(ops)
                .destroyQueue(any(), anyString(), eq(QUEUE), anyBoolean());

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.ALREADY);
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());
    }

    // ---- queue resolution -------------------------------------------------

    @Test
    void aQueueTheScrapeHasNotReachedIsFoundOnTheLiveNode() {
        String mbean = "org.apache.activemq.artemis:broker=\"b\",component=addresses,address=\"" + ADDRESS
                + "\",subcomponent=queues,routing-type=\"anycast\",queue=\"" + QUEUE + "\"";
        tools.jackson.databind.node.ObjectNode value = new tools.jackson.databind.json.JsonMapper().createObjectNode();
        value.putObject(mbean).put("MessageCount", 0);
        when(client.single(ArgumentMatchers.argThat(
                        r -> r != null && "read".equals(r.type()) && r.mbean().contains("queue=\"" + QUEUE + "\""))))
                .thenReturn(new ManagementResponse(200, value, null, null, null));
        onTheNode(0, QUEUE);

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));

        assertThat(preview.status()).isEqualTo(NodeStatus.WOULD_APPLY);
    }

    @Test
    void aQueueNoNodeHasIsNotFound() {
        // What a broker answers a pattern read that matches nothing.
        when(client.single(ArgumentMatchers.any()))
                .thenReturn(new ManagementResponse(
                        404, null, "No MBean with pattern found", "javax.management.InstanceNotFoundException", null));
        assertThatThrownBy(() -> lifecycle.deleteQueue(clusterId, QUEUE, true, false, false))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- create queue: the ALREADY comparison ------------------------------

    private CreateQueueRequest fullRequest() {
        return new CreateQueueRequest(ADDRESS, QUEUE, "anycast", true, "color='red'", 5, true, false, true, 100L, null);
    }

    private static Map<String, Object> fullConfig() {
        return Map.of(
                "address",
                ADDRESS,
                "routing-type",
                "ANYCAST",
                "durable",
                true,
                "filter-string",
                "color='red'",
                "max-consumers",
                5,
                "purge-on-no-consumers",
                true,
                "exclusive",
                false,
                "non-destructive",
                true,
                "ring-size",
                100);
    }

    private void alreadyExists(Map<String, Object> existing) {
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.ALREADY, "AMQ229019: already exists"))
                .when(ops)
                .createQueue(any(), anyString(), any());
        doReturn(existing).when(ops).readQueueConfig(any(), anyString());
    }

    @Test
    void anExistingQueueMatchingEveryRequestedAttributeIsAlreadyThere() {
        alreadyExists(fullConfig());

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, fullRequest(), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.ALREADY);
    }

    @Test
    void anExistingQueueDifferingInAnyRequestedAttributeFails() {
        for (String key : List.of(
                "filter-string",
                "max-consumers",
                "purge-on-no-consumers",
                "exclusive",
                "non-destructive",
                "ring-size",
                "durable")) {
            HashMap<String, Object> existing = new HashMap<>(fullConfig());
            existing.put(
                    key,
                    switch (key) {
                        case "filter-string" -> "color='blue'";
                        case "max-consumers", "ring-size" -> 999;
                        default -> !(Boolean) fullConfig().get(key);
                    });
            alreadyExists(existing);

            LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, fullRequest(), false));

            assertThat(status(outcome, liveId)).as(key).isEqualTo(NodeStatus.FAILED);
        }
    }

    @Test
    void anExistingQueueMissingARequestedAttributeDiffersAndItIsNamedAsUnset() {
        alreadyExists(Map.of("routing-type", "MULTICAST", "filter-string", "other"));

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, fullRequest(), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.FAILED);
        assertThat(error(outcome, liveId))
                .contains("address is unset, requested orders.addr")
                .contains("routing type is MULTICAST, requested anycast")
                .contains("filter is other, requested color='red'");
    }

    @Test
    void aDifferenceInAnAttributeThatIsNotDescribedStillFailsWithAGenericReason() {
        HashMap<String, Object> existing = new HashMap<>(fullConfig());
        existing.put("ring-size", 1);
        alreadyExists(existing);

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, fullRequest(), false));

        assertThat(error(outcome, liveId)).contains("the existing queue differs from the request");
    }

    @Test
    void aBlankFilterIsNotSentAndIsNotADifference() {
        alreadyExists(Map.of("address", ADDRESS, "routing-type", "ANYCAST", "durable", true));
        CreateQueueRequest blankFilter =
                new CreateQueueRequest(ADDRESS, QUEUE, "ANYCAST", true, "  ", null, null, null, null, null, null);

        assertThat(status(ok(lifecycle.createQueue(clusterId, blankFilter, false)), liveId))
                .isEqualTo(NodeStatus.ALREADY);
    }

    @Test
    void aRefusalOtherThanAlreadyFailsTheNodeWithTheBrokersReason() {
        when(ops.createQueue(any(), anyString(), any()))
                .thenThrow(new ManagementRefusal(ManagementRefusal.Kind.ARGUMENT, "bad filter"));

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, fullRequest(), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.FAILED);
        assertThat(error(outcome, liveId)).contains("bad filter");
        verify(ops, never()).readQueueConfig(any(), anyString());
    }

    @Test
    void theRequestedConfigurationIsSentToTheBrokerWithOnlyWhatWasSet() {
        lifecycle.createQueue(clusterId, fullRequest(), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> config = ArgumentCaptor.forClass(Map.class);
        verify(ops).createQueue(any(), anyString(), config.capture());
        assertThat(config.getValue())
                .containsEntry("name", QUEUE)
                .containsEntry("address", ADDRESS)
                .containsEntry("routing-type", "ANYCAST")
                .containsEntry("durable", true)
                .containsEntry("auto-create-address", true)
                .containsEntry("filter-string", "color='red'")
                .containsEntry("max-consumers", 5)
                .containsEntry("purge-on-no-consumers", true)
                .containsEntry("exclusive", false)
                .containsEntry("non-destructive", true)
                .containsEntry("ring-size", 100L);

        Mockito.clearInvocations(ops);
        lifecycle.createQueue(clusterId, createRequest(), false);
        verify(ops).createQueue(any(), anyString(), config.capture());
        assertThat(config.getValue()).doesNotContainKeys("filter-string", "max-consumers", "ring-size");
    }

    // ---- update, pause, resume, reset -------------------------------------------

    @Test
    void anUpdateSendsOnlyTheFieldsThatWereGiven() {
        seedQueue();

        LifecycleOutcome outcome = ok(lifecycle.updateQueue(
                clusterId, QUEUE, new LifecycleRequests.UpdateQueueRequest("", 3, false, true, false, 50L), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(ops).updateQueue(any(), anyString(), anyString(), patch.capture());
        assertThat(patch.getValue())
                .containsEntry("filter-string", "")
                .containsEntry("max-consumers", 3)
                .containsEntry("purge-on-no-consumers", false)
                .containsEntry("exclusive", true)
                .containsEntry("non-destructive", false)
                .containsEntry("ring-size", 50L);
    }

    @Test
    void anUpdateThatChangesNothingIsRejectedBeforeTouchingAnyNode() {
        seedQueue();

        var request = new LifecycleRequests.UpdateQueueRequest(null, null, null, null, null, null);

        assertThatThrownBy(() -> lifecycle.updateQueue(clusterId, QUEUE, request, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one field");
        verify(ops, never()).updateQueue(any(), anyString(), anyString(), any());
    }

    @Test
    void anUpdateOfAQueueNoNodeHasIsNotFound() {
        when(client.single(any()))
                .thenReturn(new ManagementResponse(
                        404, null, "No MBean", "javax.management.InstanceNotFoundException", null));

        var update = new LifecycleRequests.UpdateQueueRequest(null, 1, null, null, null, null);

        assertThatThrownBy(() -> lifecycle.updateQueue(clusterId, "ghost", update, false))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void pausingAQueueThatIsRunningPausesItAndOneAlreadyPausedIsLeftAlone() {
        seedQueue();

        assertThat(status(ok(lifecycle.setPaused(clusterId, QUEUE, true, false)), liveId))
                .isEqualTo(NodeStatus.APPLIED);
        verify(ops).pause(any(), anyString());

        when(ops.isPaused(any(), anyString())).thenReturn(true);
        assertThat(status(ok(lifecycle.setPaused(clusterId, QUEUE, true, false)), liveId))
                .isEqualTo(NodeStatus.ALREADY);
        verify(ops, times(1)).pause(any(), anyString());
    }

    @Test
    void resumingAPausedQueueResumesItAndOneAlreadyRunningIsLeftAlone() {
        seedQueue();
        when(ops.isPaused(any(), anyString())).thenReturn(true);

        assertThat(status(ok(lifecycle.setPaused(clusterId, QUEUE, false, false)), liveId))
                .isEqualTo(NodeStatus.APPLIED);
        verify(ops).resume(any(), anyString());

        when(ops.isPaused(any(), anyString())).thenReturn(false);
        assertThat(status(ok(lifecycle.setPaused(clusterId, QUEUE, false, false)), liveId))
                .isEqualTo(NodeStatus.ALREADY);
        verify(ops, times(1)).resume(any(), anyString());
        assertThat(auditFor("RESUME_QUEUE")).isNotEmpty();
    }

    @Test
    void resettingTheCounterIsAppliedOnTheLiveNode() {
        seedQueue();

        LifecycleOutcome outcome = ok(lifecycle.resetCounter(clusterId, QUEUE, false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        verify(ops).resetMessageCounter(any(), anyString());
    }

    // ---- addresses --------------------------------------------------------------

    @Test
    void anAddressIsCreatedWithItsRoutingTypesUpperCased() {
        LifecycleOutcome outcome = ok(lifecycle.createAddress(
                clusterId, new LifecycleRequests.CreateAddressRequest("a.b", "anycast,multicast"), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        verify(ops).createAddress(any(), anyString(), eq("a.b"), eq("ANYCAST,MULTICAST"));
    }

    @Test
    void deletingAnAddressWithBoundQueuesNamesThemAndOtherRefusalsPassThrough() {
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.BOUND_QUEUES, "AMQ: has bindings"))
                .when(ops)
                .deleteAddress(any(), anyString(), eq("a.b"));
        when(ops.boundQueues(any(), anyString())).thenReturn(List.of("q1", "q2"));

        LifecycleOutcome named = ok(lifecycle.deleteAddress(clusterId, "a.b", false));
        assertThat(status(named, liveId)).isEqualTo(NodeStatus.FAILED);
        assertThat(error(named, liveId))
                .contains("2 queue(s) bound to it: q1, q2")
                .contains("Delete them first");

        when(ops.boundQueues(any(), anyString())).thenReturn(List.of());
        assertThat(error(ok(lifecycle.deleteAddress(clusterId, "a.b", false)), liveId))
                .isEqualTo("Address 'a.b' still has queues bound to it.");

        doThrow(new ManagementRefusal(ManagementRefusal.Kind.ARGUMENT, "no such address"))
                .when(ops)
                .deleteAddress(any(), anyString(), eq("a.c"));
        assertThat(error(ok(lifecycle.deleteAddress(clusterId, "a.c", false)), liveId))
                .contains("no such address");
    }

    @Test
    void anAddressWithNoQueuesBoundIsDeleted() {
        LifecycleOutcome outcome = ok(lifecycle.deleteAddress(clusterId, "a.b", false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        verify(ops).deleteAddress(any(), anyString(), eq("a.b"));
    }

    // ---- diverts ----------------------------------------------------------------

    private static LifecycleRequests.CreateDivertRequest divertRequest(
            String name, boolean exclusive, boolean acknowledge) {
        return new LifecycleRequests.CreateDivertRequest(name, null, "src", "dst", exclusive, null, null, acknowledge);
    }

    private static DivertRow captureTap(String address) {
        return divert(DivertOperations.CAPTURE_PREFIX + "tap", address, "capture.sink");
    }

    @Test
    void aValidDivertIsCreatedAndAuditedWithTheShadowingAcknowledgement() {
        when(divertOps.addressAvailable(any(), anyString(), eq("dst"))).thenReturn(true);
        when(divertOps.createVerified(any(), anyString(), any())).thenReturn(NodeStatus.APPLIED);

        LifecycleOutcome outcome = ok(lifecycle.createDivert(clusterId, divertRequest("feed", false, false), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        assertThat(realAudit("CREATE_DIVERT").getParams()).contains("acknowledgeCaptureShadowing");
    }

    @Test
    void aDivertThatWouldCompleteACycleIsRefusedNamingIt() {
        when(divertOps.addressAvailable(any(), anyString(), any())).thenReturn(true);
        diverts(divert("back", "dst", "src"));

        NodeOutcome preview = live(ok(lifecycle.createDivert(clusterId, divertRequest("feed", false, false), true)));

        assertThat(preview.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(preview.error()).contains("would complete a cycle of diverts");
        verify(divertOps, never()).createVerified(any(), anyString(), any());
    }

    @Test
    void aDivertToAnAddressTheNodeLacksIsRefusedWithTheDeclarationThatWouldFixIt() {
        when(divertOps.addressAvailable(any(), anyString(), any())).thenReturn(false);

        NodeOutcome preview = live(ok(lifecycle.createDivert(clusterId, divertRequest("feed", false, false), true)));

        assertThat(preview.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(preview.error())
                .contains("'dst' does not exist on this node")
                .contains("producers to 'src' would fail")
                .contains("<address name=\"dst\">");
    }

    @Test
    void anExclusiveDivertOnACapturedAddressIsRefusedUnlessTheShadowingIsAcknowledged() {
        when(divertOps.addressAvailable(any(), anyString(), any())).thenReturn(true);
        when(divertOps.createVerified(any(), anyString(), any())).thenReturn(NodeStatus.APPLIED);
        diverts(captureTap("src"));

        NodeOutcome refused = live(ok(lifecycle.createDivert(clusterId, divertRequest("feed", true, false), true)));
        assertThat(refused.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(refused.error()).contains("is being captured").contains("acknowledgeCaptureShadowing");

        NodeOutcome warned = live(ok(lifecycle.createDivert(clusterId, divertRequest("feed", true, true), true)));
        assertThat(warned.status()).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(warned.error()).contains("is being captured").doesNotContain("Set acknowledgeCaptureShadowing");
    }

    @Test
    void theCaptureCheckIgnoresNonExclusiveDivertsAndCapturesOfOtherAddresses() {
        when(divertOps.addressAvailable(any(), anyString(), any())).thenReturn(true);
        diverts(captureTap("src"), captureTap("elsewhere"), divert("plain", "src", "x"));

        assertThat(live(ok(lifecycle.createDivert(clusterId, divertRequest("feed", false, false), true)))
                        .error())
                .isNull();

        diverts(captureTap("elsewhere"), divert("plain", "src", "x"));
        assertThat(live(ok(lifecycle.createDivert(clusterId, divertRequest("feed", true, false), true)))
                        .error())
                .isNull();
    }

    @Test
    void aDivertRequestThatFailsValidationIsRejectedWithEveryViolation() {
        var request =
                new LifecycleRequests.CreateDivertRequest("bad name", null, "src", "src", null, null, "NOPE", null);

        assertThatThrownBy(() -> lifecycle.createDivert(clusterId, request, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name:")
                .hasMessageContaining("routingType:");
    }

    @Test
    void divertNamesInTheNamespacesRahdarReservesAreRefused() {
        var captureRequest = divertRequest(DivertOperations.CAPTURE_PREFIX + "x", false, false);
        var tapRequest = divertRequest(DivertOperations.PLUGIN_TAP_PREFIX + "x", false, false);

        assertThatThrownBy(() -> lifecycle.createDivert(clusterId, captureRequest, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved for message capture");
        assertThatThrownBy(() -> lifecycle.createDivert(clusterId, tapRequest, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reserved for plugins' message taps");
        assertThatThrownBy(() -> lifecycle.deleteDivert(clusterId, DivertOperations.CAPTURE_PREFIX + "x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycle.deleteDivert(clusterId, DivertOperations.PLUGIN_TAP_PREFIX + "x", false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(divertOps, never()).destroyDivert(any(), anyString(), anyString());
    }

    @Test
    void anOperatorsDivertIsDeletedOnTheLiveNode() {
        LifecycleOutcome outcome = ok(lifecycle.deleteDivert(clusterId, "feed", false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        verify(divertOps).destroyDivert(any(), anyString(), eq("feed"));
    }

    // ---- delete: what the preview says about each kind of divert ------------------

    @Test
    void aSingleConsumerIsCountedInTheSingular() {
        seedQueue();
        onTheNode(1, QUEUE);

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));

        assertThat(preview.error()).contains("has 1 consumer attached");
    }

    @Test
    void aDependentDivertIsDescribedWithEverythingNeededToRecreateIt() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(
                new DivertRow(
                        null,
                        null,
                        "full",
                        "route",
                        "incoming",
                        ADDRESS,
                        "color='red'",
                        "ANYCAST",
                        "x.Transformer",
                        Map.of(),
                        true,
                        false),
                new DivertRow(null, null, "bare", "bare", "other", ADDRESS, " ", "STRIP", " ", Map.of(), false, false));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));

        assertThat(preview.error())
                .contains("'full' (incoming")
                .contains("routing name route")
                .contains("routing type ANYCAST")
                .contains(", exclusive")
                .contains(", filter color='red'")
                .contains(", transformer x.Transformer")
                .contains("'bare' (other")
                .doesNotContain("filter  ");
    }

    @Test
    void diversAlreadyGoneWhenTheQueueIsDeletedDoNotStopTheDelete() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.ALREADY, "gone"))
                .when(divertOps)
                .destroyDivert(any(), anyString(), eq("feed"));

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.APPLIED);
        verify(ops).destroyQueue(any(), anyString(), eq(QUEUE), anyBoolean());
    }

    @Test
    void aDivertRefusalThatIsNotAlreadyKeepsTheQueueAndSaysWhy() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.ARGUMENT, "in use"))
                .when(divertOps)
                .destroyDivert(any(), anyString(), eq("feed"));

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(real.error())
                .contains("Divert 'feed' could not be removed (in use)")
                .contains("not deleted");
        verify(ops, never()).destroyQueue(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void aConnectionFailureDeletingTheQueueNamesTheDivertsAlreadyRemoved() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));
        doThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "timed out"))
                .when(ops)
                .destroyQueue(any(), anyString(), eq(QUEUE), anyBoolean());

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(real.error()).contains("timed out").contains("Diverts already removed from this node: 'feed'");
    }

    @Test
    void divertsUnrelatedToTheQueuesAddressAndCapturesElsewhereAreNotMentioned() {
        seedQueue();
        onTheNode(0, QUEUE, "another");
        diverts(divert("unrelated", "x", "y"), captureTap("some.other.address"));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));

        assertThat(preview.status()).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(preview.error()).isNull();
    }

    @Test
    void aCaptureTapNoOtherQueueCoversGoesWithItsQueue() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(captureTap(ADDRESS));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));

        assertThat(preview.error()).contains("each removed by its capture subscription");
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Scoped to this test's own cluster. {@code audit_event.cluster_id} is
     * {@code ON DELETE SET NULL}, so rows from earlier tests in this class survive
     * their cluster and would otherwise be counted here.
     */
    private List<AuditEventEntity> auditFor(String action) {
        return auditEvents.findAll().stream()
                .filter(e -> action.equals(e.getAction()) && clusterId.equals(e.getClusterId()))
                .toList();
    }

    /** The one real (not previewed) row for an action. */
    private AuditEventEntity realAudit(String action) {
        List<AuditEventEntity> real =
                auditFor(action).stream().filter(e -> !e.isDryRun()).toList();
        assertThat(real).hasSize(1);
        return real.get(0);
    }

    private static LifecycleOutcome ok(Attempt<LifecycleOutcome> attempt) {
        return ((Attempt.Ok<LifecycleOutcome>) attempt).value();
    }

    private static NodeStatus status(LifecycleOutcome outcome, UUID nodeId) {
        return outcome.nodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outcome for node " + nodeId))
                .status();
    }

    private static String error(LifecycleOutcome outcome, UUID nodeId) {
        return outcome.nodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow()
                .error();
    }
}
