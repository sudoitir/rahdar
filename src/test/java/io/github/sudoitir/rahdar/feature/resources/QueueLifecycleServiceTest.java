package io.github.sudoitir.rahdar.feature.resources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.RouteEndpoint;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.openbroker.operation.Direction;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateQueueRequest;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.broker.QueueCounters;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
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
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The fan-out contract (ADR-0049): which nodes are targeted, what each reports,
 * and what the audit row says afterwards.
 *
 * <p>The broker layer is mocked at the SDK's seam: a node's resources and operations. What is under
 * test is the service's own behaviour (target selection from live topology, per-node outcome
 * collection, the cap check, the delete plan and the single audit row), not the driver, whose own
 * tests and the real-broker tests cover the wire.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class QueueLifecycleServiceTest extends PostgresIntegrationTest {

    private static final String LIVE_URL = "http://live:8161/console/jolokia";
    private static final String DEAD_URL = "http://dead:8161/console/jolokia";
    private static final String QUEUE = "orders";
    private static final String ADDRESS = "orders.addr";

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
    QueueCounters counters;

    @MockitoBean
    DeclaredDiverts declaredDiverts;

    @MockitoBean
    CaptureTaps captureTaps;

    private BrokerTarget node;
    private Operations ops;
    private Resources liveResources;

    /** What the node holds: the queues of the queue's address and the diverts of the delete tests. */
    private final List<Resource> held = new ArrayList<>();

    private UUID clusterId;
    private UUID liveId;
    private UUID deadId;

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        liveId = node("live", LIVE_URL, true);
        deadId = node("dead", DEAD_URL, false);

        node = Targets.target();
        ops = Targets.operations(node);
        when(gateway.node(any(UUID.class), any(UUID.class))).thenReturn(node);
        // What the live nodes list for a queue the scrape has not reached: nothing, unless a test says so.
        ClusterConnection live = Mockito.mock(ClusterConnection.class);
        liveResources = Mockito.mock(Resources.class);
        when(gateway.cluster(clusterId)).thenReturn(live);
        when(live.resources()).thenReturn(liveResources);
        when(liveResources.read(any(ResourceQuery.class))).thenReturn(page());
    }

    private static Partial<ResourcePage> page(Resource... items) {
        return new Partial<>(new ResourcePage(List.of(items), Optional.empty()), Map.of(), Map.of());
    }

    /** The queue as a live node lists it. */
    private static Resource liveQueue() {
        Attributes attributes = Attributes.builder()
                .put(StandardAttributes.DESTINATION, Targets.id("artemis.address", ADDRESS))
                .putRaw(QueueRow.ROUTING_TYPE, "ANYCAST")
                .build();
        return new Resource(Targets.id("artemis.queue", QUEUE), attributes, List.of(), List.of(), Instant.now());
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

    // ---- what the node holds ------------------------------------------------

    private static final ResourceId QUEUE_ID = Targets.id("artemis.queue", QUEUE);
    private static final ResourceId ADDRESS_ID = Targets.id("artemis.address", "a.b");

    /** What the delete plan reads on the live node: the queue's consumers and backlog, and the address's queues. */
    private void onTheNode(long consumers, String... addressQueues) {
        queueHolds(consumers, 0, addressQueues);
    }

    private void queueHolds(long consumers, long backlog, String... addressQueues) {
        held.removeIf(r -> "artemis.queue".equals(r.id().kind().value()));
        for (String queue : addressQueues) {
            boolean ours = QUEUE.equals(queue);
            held.add(Targets.queue(queue, ADDRESS, ours ? consumers : 0, ours ? backlog : 0));
        }
        Targets.holding(node, held.toArray(Resource[]::new));
    }

    private void diverts(Resource... diverts) {
        held.removeIf(r -> "artemis.divert".equals(r.id().kind().value()));
        held.addAll(List.of(diverts));
        Targets.holding(node, held.toArray(Resource[]::new));
    }

    private static Resource divert(String name, String from, String to) {
        return Targets.divert(name, from, to);
    }

    private static Operation.Delete deleteQueue(boolean disconnectConsumers) {
        return new Operation.Delete(false, disconnectConsumers);
    }

    private static ResourceId divertId(String name) {
        return Targets.id("artemis.divert", name);
    }

    private void whenExecuted(ResourceId id, Operation operation, RuntimeException failure) {
        when(ops.execute(eq(id), eq(operation), any(Scope.class))).thenThrow(failure);
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
        verify(gateway, never()).node(clusterId, deadId);
    }

    @Test
    void aQueueThatAlreadyExistsWithTheSameConfigurationIsNotAnError() {
        when(ops.execute(eq(QUEUE_ID), any(Operation.Create.class), any(Scope.class)))
                .thenReturn(Targets.already());

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, createRequest(), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.ALREADY);
        assertThat(outcome.anyFailed()).isFalse();
    }

    @Test
    void aQueueThatExistsWithADifferentConfigurationFailsAndNamesTheDifference() {
        // Same name, different address: the driver names the fields that differ.
        when(ops.execute(eq(QUEUE_ID), any(Operation.Create.class), any(Scope.class)))
                .thenThrow(Targets.failure(
                        ErrorReasons.RESOURCE_EXISTS,
                        Map.of("differing", "address"),
                        "Queue orders exists with a different address."));

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, createRequest(), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.FAILED);
        assertThat(error(outcome, liveId)).contains("different address");
        assertThat(outcome.anyFailed()).isTrue();
    }

    // ---- dry run ----------------------------------------------------------

    @Test
    void aDryRunMakesNoMutatingCall() {
        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, createRequest(), true));

        assertThat(outcome.dryRun()).isTrue();
        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(status(outcome, deadId)).isEqualTo(NodeStatus.SKIPPED_NOT_LIVE);
        verify(ops, never()).execute(any(), any(), any());
    }

    @Test
    void aDeleteDryRunReportsWhatWouldBeDestroyedPerNodeAndDestroysNothing() {
        seedQueue();
        queueHolds(0, 42, QUEUE);

        LifecycleOutcome outcome = ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false));

        assertThat(outcome.totalAffected()).isEqualTo(42L);
        assertThat(outcome.nodes().stream()
                        .filter(n -> n.nodeId().equals(liveId))
                        .findFirst()
                        .orElseThrow()
                        .affected())
                .isEqualTo(42L);
        verify(ops, never()).execute(any(), any(), any());
    }

    // ---- the bulk cap (D6) ------------------------------------------------

    @Test
    void aDeleteOverTheCapIsRefusedWithoutAnOverride() {
        seedQueue();
        queueHolds(0, Long.MAX_VALUE / 2, QUEUE);

        assertThatThrownBy(() -> lifecycle.deleteQueue(clusterId, QUEUE, false, false, false))
                .isInstanceOf(BulkCapExceededException.class);
        verify(ops, never()).execute(any(), any(), any());
    }

    @Test
    void aDeleteOverTheCapProceedsWithAnExplicitOverride() {
        seedQueue();
        queueHolds(0, Long.MAX_VALUE / 2, QUEUE);

        LifecycleOutcome outcome = ok(lifecycle.deleteQueue(clusterId, QUEUE, false, true, false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        verify(ops).execute(eq(QUEUE_ID), eq(deleteQueue(false)), any(Scope.class));
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
        // Two live nodes, so the report carries both.
        UUID secondLive = node("live-2", "http://live2:8161/console/jolokia", true);
        when(ops.execute(eq(QUEUE_ID), any(Operation.Create.class), any(Scope.class)))
                .thenThrow(Targets.failure(ErrorReasons.UNREACHABLE, Map.of(), "connection refused"));

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
        verify(ops, never()).execute(any(), any(), any());
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
        verify(ops).execute(eq(QUEUE_ID), eq(deleteQueue(true)), any(Scope.class));
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
        verify(ops, never()).execute(any(), any(), any());

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));
        assertThat(real.status()).isEqualTo(NodeStatus.APPLIED);
        assertThat(real.error()).contains("feed");
        InOrder order = inOrder(ops);
        order.verify(ops).execute(eq(divertId("feed")), eq(Operation.delete()), any(Scope.class));
        order.verify(ops).execute(eq(QUEUE_ID), eq(deleteQueue(false)), any(Scope.class));
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
        verify(ops, never()).execute(eq(divertId("feed")), any(), any());
    }

    @Test
    void aDivertFromTheQueuesAddressIsKeptAndTheReasonGiven() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("fan-out", ADDRESS, "elsewhere"));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));
        assertThat(preview.error()).contains("fan-out").contains("kept");

        ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false));
        verify(ops, never()).execute(eq(divertId("fan-out")), any(), any());
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
        verify(ops, never()).execute(eq(divertId("feed")), any(), any());
    }

    @Test
    void aCaptureTapIsNeverRemovedByAQueueDelete() {
        seedQueue();
        onTheNode(0, QUEUE);
        String tap = RahdarNames.CAPTURE_PREFIX + "x";
        diverts(divert(tap, ADDRESS, tap));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));
        assertThat(preview.error()).contains(tap).contains("removed by its capture subscription");

        ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false));
        verify(ops, never()).execute(eq(divertId(tap)), any(), any());
    }

    @Test
    void aCaptureTapStillCoveringAnotherQueueOnTheAddressIsNamedAsKept() {
        seedQueue();
        onTheNode(0, QUEUE, "orders.audit");
        String tap = RahdarNames.CAPTURE_PREFIX + "x";
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
        whenExecuted(
                divertId("feed"),
                Operation.delete(),
                Targets.failure(ErrorReasons.UNREACHABLE, Map.of(), "connection refused"));

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(real.error()).contains("feed").contains("not deleted");
        verify(ops, never()).execute(eq(QUEUE_ID), any(), any());
    }

    @Test
    void aQueueDeleteThatFailsAfterItsDivertsNamesTheDivertsAlreadyRemoved() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));
        whenExecuted(
                QUEUE_ID,
                deleteQueue(false),
                Targets.failure(ErrorReasons.HAS_CONSUMERS, Map.of(), "A consumer attached."));

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(real.error()).contains("consumers attached").contains("feed");
    }

    @Test
    void aNodeWithoutTheQueueRemovesNothingAndIsAlreadyThere() {
        seedQueue();
        onTheNode(0, "someone.else");
        diverts(divert("feed", "incoming", ADDRESS));
        when(ops.execute(eq(QUEUE_ID), eq(deleteQueue(false)), any(Scope.class)))
                .thenReturn(Targets.already());

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.ALREADY);
        verify(ops, never()).execute(eq(divertId("feed")), any(), any());
    }

    // ---- queue resolution -------------------------------------------------

    @Test
    void aQueueTheScrapeHasNotReachedIsFoundOnTheLiveNode() {
        when(liveResources.read(any(ResourceQuery.class))).thenReturn(page(liveQueue()));
        onTheNode(0, QUEUE);

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));

        assertThat(preview.status()).isEqualTo(NodeStatus.WOULD_APPLY);
    }

    @Test
    void aQueueNoNodeHasIsNotFound() {
        assertThatThrownBy(() -> lifecycle.deleteQueue(clusterId, QUEUE, true, false, false))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- create queue ------------------------------------------------------

    private CreateQueueRequest fullRequest() {
        return new CreateQueueRequest(ADDRESS, QUEUE, "anycast", true, "color='red'", 5, true, false, true, 100L, null);
    }

    @Test
    void aRefusalOtherThanAlreadyFailsTheNodeWithTheBrokersReason() {
        when(ops.execute(eq(QUEUE_ID), any(Operation.Create.class), any(Scope.class)))
                .thenThrow(Targets.failure(ErrorReasons.INVALID_FILTER, Map.of(), "bad filter"));

        LifecycleOutcome outcome = ok(lifecycle.createQueue(clusterId, fullRequest(), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.FAILED);
        assertThat(error(outcome, liveId)).contains("bad filter");
    }

    @Test
    void theRequestedConfigurationIsSentToTheBrokerWithOnlyWhatWasSet() {
        lifecycle.createQueue(clusterId, fullRequest(), false);

        ArgumentCaptor<Operation> sent = ArgumentCaptor.forClass(Operation.class);
        verify(ops).execute(eq(QUEUE_ID), sent.capture(), any(Scope.class));
        assertThat(((Operation.Create) sent.getValue()).attributes().asMap())
                .containsEntry("openbroker.destination", Targets.id("artemis.address", ADDRESS))
                .containsEntry("artemis.routing_type", "ANYCAST")
                .containsEntry("openbroker.durable", true)
                .containsEntry("artemis.auto_create_address", true)
                .containsEntry("openbroker.filter", "color='red'")
                .containsEntry("openbroker.max_consumers", 5L)
                .containsEntry("artemis.purge_on_no_consumers", true)
                .containsEntry("artemis.exclusive", false)
                .containsEntry("artemis.non_destructive", true)
                .containsEntry("artemis.ring_size", 100L);

        Mockito.clearInvocations(ops);
        lifecycle.createQueue(clusterId, createRequest(), false);
        verify(ops).execute(eq(QUEUE_ID), sent.capture(), any(Scope.class));
        assertThat(((Operation.Create) sent.getValue()).attributes().asMap())
                .doesNotContainKeys(
                        "openbroker.filter", "openbroker.max_consumers", "artemis.ring_size", "artemis.exclusive");
    }

    @Test
    void aBlankFilterIsNotSent() {
        CreateQueueRequest blankFilter =
                new CreateQueueRequest(ADDRESS, QUEUE, "ANYCAST", true, "  ", null, null, null, null, null, null);

        lifecycle.createQueue(clusterId, blankFilter, false);

        ArgumentCaptor<Operation> sent = ArgumentCaptor.forClass(Operation.class);
        verify(ops).execute(eq(QUEUE_ID), sent.capture(), any(Scope.class));
        assertThat(((Operation.Create) sent.getValue()).attributes().asMap()).doesNotContainKey("openbroker.filter");
    }

    // ---- update, pause, resume, reset -------------------------------------------

    @Test
    void anUpdateSendsOnlyTheFieldsThatWereGiven() {
        seedQueue();

        LifecycleOutcome outcome = ok(lifecycle.updateQueue(
                clusterId, QUEUE, new LifecycleRequests.UpdateQueueRequest("", 3, false, true, false, 50L), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        ArgumentCaptor<Operation> sent = ArgumentCaptor.forClass(Operation.class);
        verify(ops).execute(eq(QUEUE_ID), sent.capture(), any(Scope.class));
        assertThat(((Operation.Update) sent.getValue()).patch().asMap())
                .containsEntry("openbroker.filter", "")
                .containsEntry("openbroker.max_consumers", 3L)
                .containsEntry("artemis.purge_on_no_consumers", false)
                .containsEntry("artemis.exclusive", true)
                .containsEntry("artemis.non_destructive", false)
                .containsEntry("artemis.ring_size", 50L);
    }

    @Test
    void anUpdateThatChangesNothingIsRejectedBeforeTouchingAnyNode() {
        seedQueue();

        var request = new LifecycleRequests.UpdateQueueRequest(null, null, null, null, null, null);

        assertThatThrownBy(() -> lifecycle.updateQueue(clusterId, QUEUE, request, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one field");
        verify(ops, never()).execute(any(), any(), any());
    }

    @Test
    void anUpdateOfAQueueNoNodeHasIsNotFound() {
        var update = new LifecycleRequests.UpdateQueueRequest(null, 1, null, null, null, null);

        assertThatThrownBy(() -> lifecycle.updateQueue(clusterId, "ghost", update, false))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void pausingAQueueThatIsRunningPausesItAndOneAlreadyPausedIsLeftAlone() {
        seedQueue();
        Operation pause = new Operation.Pause(Direction.DELIVERY);

        assertThat(status(ok(lifecycle.setPaused(clusterId, QUEUE, true, false)), liveId))
                .isEqualTo(NodeStatus.APPLIED);
        verify(ops).execute(eq(QUEUE_ID), eq(pause), any(Scope.class));

        when(ops.execute(eq(QUEUE_ID), eq(pause), any(Scope.class))).thenReturn(Targets.already());
        assertThat(status(ok(lifecycle.setPaused(clusterId, QUEUE, true, false)), liveId))
                .isEqualTo(NodeStatus.ALREADY);
    }

    @Test
    void resumingAPausedQueueResumesItAndOneAlreadyRunningIsLeftAlone() {
        seedQueue();
        Operation resume = new Operation.Resume(Direction.DELIVERY);

        assertThat(status(ok(lifecycle.setPaused(clusterId, QUEUE, false, false)), liveId))
                .isEqualTo(NodeStatus.APPLIED);
        verify(ops).execute(eq(QUEUE_ID), eq(resume), any(Scope.class));

        when(ops.execute(eq(QUEUE_ID), eq(resume), any(Scope.class))).thenReturn(Targets.already());
        assertThat(status(ok(lifecycle.setPaused(clusterId, QUEUE, false, false)), liveId))
                .isEqualTo(NodeStatus.ALREADY);
        assertThat(auditFor("RESUME_QUEUE")).isNotEmpty();
    }

    @Test
    void resettingTheCounterIsAppliedOnTheLiveNode() {
        seedQueue();

        LifecycleOutcome outcome = ok(lifecycle.resetCounter(clusterId, QUEUE, false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        verify(counters).reset(any(), eq(QUEUE));
    }

    // ---- addresses --------------------------------------------------------------

    @Test
    void anAddressIsCreatedWithItsRoutingTypesUpperCased() {
        LifecycleOutcome outcome = ok(lifecycle.createAddress(
                clusterId, new LifecycleRequests.CreateAddressRequest("a.b", "anycast,multicast"), false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        ArgumentCaptor<Operation> sent = ArgumentCaptor.forClass(Operation.class);
        verify(ops).execute(eq(ADDRESS_ID), sent.capture(), any(Scope.class));
        assertThat(((Operation.Create) sent.getValue()).attributes().asMap())
                .containsEntry("artemis.routing_types", "ANYCAST,MULTICAST");
    }

    @Test
    void deletingAnAddressWithBoundQueuesNamesThemAndOtherRefusalsPassThrough() {
        whenExecuted(
                ADDRESS_ID,
                Operation.delete(),
                Targets.failure(ErrorReasons.HAS_CHILDREN, Map.of(), "AMQ: has bindings"));
        held.add(Targets.queue("q1", "a.b", 0, 0));
        held.add(Targets.queue("q2", "a.b", 0, 0));
        Targets.holding(node, held.toArray(Resource[]::new));

        LifecycleOutcome named = ok(lifecycle.deleteAddress(clusterId, "a.b", false));
        assertThat(status(named, liveId)).isEqualTo(NodeStatus.FAILED);
        assertThat(error(named, liveId))
                .contains("2 queue(s) bound to it: q1, q2")
                .contains("Delete them first");

        held.clear();
        Targets.holding(node);
        assertThat(error(ok(lifecycle.deleteAddress(clusterId, "a.b", false)), liveId))
                .isEqualTo("Address 'a.b' still has queues bound to it.");

        whenExecuted(
                Targets.id("artemis.address", "a.c"),
                Operation.delete(),
                Targets.failure(ErrorReasons.INVALID_ARGUMENT, Map.of("artemis.code", "AMQ1"), "no such address"));
        assertThat(error(ok(lifecycle.deleteAddress(clusterId, "a.c", false)), liveId))
                .contains("no such address");
    }

    @Test
    void anAddressWithNoQueuesBoundIsDeleted() {
        LifecycleOutcome outcome = ok(lifecycle.deleteAddress(clusterId, "a.b", false));

        assertThat(status(outcome, liveId)).isEqualTo(NodeStatus.APPLIED);
        verify(ops).execute(eq(ADDRESS_ID), eq(Operation.delete()), any(Scope.class));
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
                Targets.resource(
                        divertId("full"),
                        Attributes.builder()
                                .put(
                                        StandardAttributes.ROUTE_SOURCE,
                                        new RouteEndpoint.Local(Targets.id("artemis.address", "incoming")))
                                .put(
                                        StandardAttributes.ROUTE_TARGET,
                                        new RouteEndpoint.Local(Targets.id("artemis.address", ADDRESS)))
                                .put(StandardAttributes.FILTER, "color='red'")
                                .putRaw("artemis.routing_name", "route")
                                .putRaw("artemis.routing_type", "ANYCAST")
                                .putRaw("artemis.transformer_class", "x.Transformer")
                                .putRaw("artemis.exclusive", true)
                                .build()),
                divert("bare", "other", ADDRESS));

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
        when(ops.execute(eq(divertId("feed")), eq(Operation.delete()), any(Scope.class)))
                .thenReturn(Targets.already());

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.APPLIED);
        verify(ops).execute(eq(QUEUE_ID), eq(deleteQueue(false)), any(Scope.class));
    }

    @Test
    void aDivertRefusalThatIsNotAlreadyKeepsTheQueueAndSaysWhy() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));
        whenExecuted(
                divertId("feed"),
                Operation.delete(),
                Targets.failure(ErrorReasons.INVALID_ARGUMENT, Map.of("artemis.code", "AMQ1"), "in use"));

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(real.error())
                .contains("Divert 'feed' could not be removed (in use)")
                .contains("not deleted");
        verify(ops, never()).execute(eq(QUEUE_ID), any(), any());
    }

    @Test
    void aConnectionFailureDeletingTheQueueNamesTheDivertsAlreadyRemoved() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert("feed", "incoming", ADDRESS));
        whenExecuted(QUEUE_ID, deleteQueue(false), Targets.failure(ErrorReasons.UNREACHABLE, Map.of(), "timed out"));

        NodeOutcome real = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, false, false, false)));

        assertThat(real.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(real.error()).contains("timed out").contains("Diverts already removed from this node: 'feed'");
    }

    @Test
    void divertsUnrelatedToTheQueuesAddressAndCapturesElsewhereAreNotMentioned() {
        seedQueue();
        onTheNode(0, QUEUE, "another");
        diverts(
                divert("unrelated", "x", "y"),
                divert(RahdarNames.CAPTURE_PREFIX + "tap", "some.other.address", "sink"));

        NodeOutcome preview = live(ok(lifecycle.deleteQueue(clusterId, QUEUE, true, false, false)));

        assertThat(preview.status()).isEqualTo(NodeStatus.WOULD_APPLY);
        assertThat(preview.error()).isNull();
    }

    @Test
    void aCaptureTapNoOtherQueueCoversGoesWithItsQueue() {
        seedQueue();
        onTheNode(0, QUEUE);
        diverts(divert(RahdarNames.CAPTURE_PREFIX + "tap", ADDRESS, "capture.sink"));

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
