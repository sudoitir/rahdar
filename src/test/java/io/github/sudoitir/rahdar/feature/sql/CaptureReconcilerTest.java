package io.github.sudoitir.rahdar.feature.sql;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageCaptureNodeRepository;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.settings.RahdarInstance;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The converge loop's four obligations (ADR-0062 D4, D5): install what is missing,
 * remove what this instance owns and no longer wants, never touch what belongs to
 * another Rahdar, and do nothing at all when nothing has drifted.
 *
 * <p>The last one is not a performance nicety. A pass that re-asserts every tap would
 * be a management write per tap per interval, forever, against a broker Rahdar is
 * supposed to be gentle with (non-negotiable #1).
 */
class CaptureReconcilerTest {

    private static final String INSTANCE = "abc12345";
    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID SUBSCRIPTION = UUID.randomUUID();
    private static final String WANTED = "rahdar.capture." + INSTANCE + ".ORDER.IN." + SUBSCRIPTION;
    private static final String OTHER_RAHDAR = "rahdar.capture.ff99ff99.ORDER.IN." + UUID.randomUUID();

    private MessageIndexSubscriptionRepository subscriptions;
    private MessageCaptureNodeRepository captureNodes;
    private CaptureTap tap;
    private CaptureConsumer consumers;
    private CaptureReconciler reconciler;
    private ClusterDirectory nodes;
    private AuditService audit;
    private BrokerNodeEntity nodeEntity;
    private BrokerConnections connections;
    private ClusterOwnership ownership;

    private final ArtemisNodes artemisNodes = mock(ArtemisNodes.class);

    @BeforeEach
    void setUp() {
        subscriptions = mock(MessageIndexSubscriptionRepository.class);
        captureNodes = mock(MessageCaptureNodeRepository.class);
        nodes = mock(ClusterDirectory.class);
        connections = mock(BrokerConnections.class);
        ClusterLock lock = mock(ClusterLock.class);
        ownership = mock(ClusterOwnership.class);
        when(ownership.owns(CLUSTER)).thenReturn(true);
        tap = mock(CaptureTap.class);
        consumers = mock(CaptureConsumer.class);
        CaptureBus bus = mock(CaptureBus.class);
        RahdarInstance instance = mock(RahdarInstance.class);
        QueryPlanner planner = mock(QueryPlanner.class);
        audit = mock(AuditService.class);
        AuditEventEntity auditEvent = mock(AuditEventEntity.class);

        when(instance.id()).thenReturn(INSTANCE);
        nodeEntity = node();
        when(nodes.nodes(CLUSTER)).thenReturn(List.of(nodeEntity));
        when(artemisNodes.on(any(UUID.class), any(UUID.class))).thenReturn(mock(ArtemisNode.class));
        MessageIndexSubscriptionEntity subscription = subscription();
        when(subscriptions.findByEnabledTrue()).thenReturn(List.of(subscription));
        QueryPlan plan = plan();
        when(planner.plan(any(), any())).thenReturn(plan);
        CaptureAddresses addresses = new CaptureAddresses(planner, new SqlQueryParser());
        when(audit.begin(any(), anyString(), anyString(), any(), any(), any(), any(), eq(false)))
                .thenReturn(auditEvent);
        when(captureNodes.findBySubscriptionIdAndNodeId(any(), any())).thenReturn(Optional.empty());
        when(subscriptions.findById(any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return true;
                })
                .when(lock)
                .runIfHeld(any(), any());

        reconciler = new CaptureReconciler(
                subscriptions,
                captureNodes,
                nodes,
                connections,
                lock,
                ownership,
                tap,
                consumers,
                bus,
                instance,
                addresses,
                audit,
                mock(CaptureLoss.class),
                artemisNodes);
    }

    @Test
    void installsTheTapThatIsMissing() {
        when(tap.installedNames(any(), eq(INSTANCE))).thenReturn(List.of());

        reconciler.reconcile();

        verify(tap).install(any(), eq(INSTANCE), any());
        verify(consumers).start(any());
    }

    @Test
    void doesNotVisitAClusterThisReplicaDoesNotOwn() {
        when(ownership.owns(CLUSTER)).thenReturn(false);

        reconciler.reconcile();

        verifyNoInteractions(tap);
        verify(consumers).stopCluster(CLUSTER);
        verify(consumers, never()).start(any());
    }

    @Test
    void doesNothingWhenNothingHasDrifted() {
        when(tap.installedNames(any(), eq(INSTANCE))).thenReturn(List.of(WANTED));
        when(consumers.isDraining(NODE, WANTED)).thenReturn(true);
        when(consumers.drainingOn(NODE)).thenReturn(Set.of(WANTED));

        reconciler.reconcile();
        reconciler.reconcile();

        verify(tap, never()).install(any(), any(), any());
        verify(tap, never()).remove(any(), any(), any());
        verify(consumers, never()).start(any());
    }

    @Test
    void removesItsOwnOrphanWhenTheSubscriptionIsGone() {
        when(subscriptions.findByEnabledTrue()).thenReturn(List.of());
        when(tap.installedNames(any(), eq(INSTANCE))).thenReturn(List.of(WANTED));

        // No capturing subscription at all: the pass is not even entered per cluster,
        // so the orphan is removed by the pass that still has one for that cluster.
        reconciler.reconcileCluster(CLUSTER);

        verify(tap).remove(any(), eq(INSTANCE), eq(WANTED));
        verify(consumers).stop(NODE, WANTED);
    }

    @Test
    void aClusterWhoseLastTapWasRemovedIsNotVisitedAgain() {
        when(nodeEntity.getClusterId()).thenReturn(CLUSTER);
        when(nodes.allNodes()).thenReturn(List.of(nodeEntity));
        when(subscriptions.findByEnabledTrue()).thenReturn(List.of());
        when(tap.installedNames(any(), eq(INSTANCE)))
                .thenReturn(List.of(WANTED))
                .thenReturn(List.of());

        // The startup sweep removes the last orphan; the pass after it has nothing to visit.
        reconciler.sweepOnStartup();
        reconciler.reconcile();

        verify(tap).remove(any(), eq(INSTANCE), eq(WANTED));
        verify(tap, times(1)).installedNames(any(), eq(INSTANCE));
    }

    @Test
    void anOrphanThatCouldNotBeRemovedKeepsItsClusterVisited() {
        when(nodeEntity.getClusterId()).thenReturn(CLUSTER);
        when(nodes.allNodes()).thenReturn(List.of(nodeEntity));
        when(subscriptions.findByEnabledTrue()).thenReturn(List.of());
        when(tap.installedNames(any(), eq(INSTANCE))).thenReturn(List.of(WANTED));
        doAnswer(invocation -> {
                    throw new IllegalStateException("broker did not answer");
                })
                .doNothing()
                .when(tap)
                .remove(any(), eq(INSTANCE), eq(WANTED));

        reconciler.sweepOnStartup();
        reconciler.reconcile();

        verify(tap, times(2)).remove(any(), eq(INSTANCE), eq(WANTED));
    }

    @Test
    void anOrphanThatCouldNotBeRemovedKeepsItsClusterVisitedWhenAnotherNodeIsClean() {
        ArtemisNode failing = twoNodesWhereOnlyTheFirstHasAnOrphan();
        doAnswer(invocation -> {
                    throw new IllegalStateException("broker did not answer");
                })
                .doNothing()
                .when(tap)
                .remove(failing, INSTANCE, WANTED);

        // The clean second node must not drop the cluster the first still has an orphan on.
        reconciler.sweepOnStartup();
        reconciler.reconcile();

        verify(tap, times(2)).remove(failing, INSTANCE, WANTED);
    }

    @Test
    void aNodeThatDidNotAnswerKeepsItsClusterVisitedWhenAnotherNodeIsClean() {
        ArtemisNode unanswering = twoNodesWhereOnlyTheFirstHasAnOrphan();
        when(tap.installedNames(unanswering, INSTANCE))
                .thenThrow(new IllegalStateException("broker did not answer"))
                .thenReturn(List.of(WANTED));

        reconciler.sweepOnStartup();
        reconciler.reconcile();

        verify(tap).remove(unanswering, INSTANCE, WANTED);
    }

    /** The first node's client, which reports {@link #WANTED} as an orphan; the second reports nothing. */
    private ArtemisNode twoNodesWhereOnlyTheFirstHasAnOrphan() {
        BrokerNodeEntity second = mock(BrokerNodeEntity.class);
        when(second.getId()).thenReturn(UUID.randomUUID());
        when(second.getName()).thenReturn("second");
        when(second.getManagementUrl()).thenReturn("http://second:8161/console/jolokia");
        when(second.getDataUrl()).thenReturn("tcp://second:61616");
        when(second.getActive()).thenReturn(true);
        when(second.getClusterId()).thenReturn(CLUSTER);
        when(nodeEntity.getClusterId()).thenReturn(CLUSTER);
        when(nodes.nodes(CLUSTER)).thenReturn(List.of(nodeEntity, second));
        when(nodes.allNodes()).thenReturn(List.of(nodeEntity, second));
        when(subscriptions.findByEnabledTrue()).thenReturn(List.of());
        ArtemisNode first = mock(ArtemisNode.class);
        ArtemisNode clean = mock(ArtemisNode.class);
        UUID firstId = nodeEntity.getId();
        UUID secondId = second.getId();
        when(artemisNodes.on(CLUSTER, firstId)).thenReturn(first);
        when(artemisNodes.on(CLUSTER, secondId)).thenReturn(clean);
        when(tap.installedNames(first, INSTANCE)).thenReturn(List.of(WANTED));
        when(tap.installedNames(clean, INSTANCE)).thenReturn(List.of());
        return first;
    }

    @Test
    void neverTouchesAnotherRahdarsTap() {
        // installedNames only ever reports this instance's own names — the filter is in
        // the tap — so a divert belonging to another Rahdar is invisible here. This
        // pins the consequence: nothing in a pass removes a name it did not recognise.
        when(tap.installedNames(any(), eq(INSTANCE))).thenReturn(List.of());

        reconciler.reconcileCluster(CLUSTER);

        verify(tap, never()).remove(any(), any(), eq(OTHER_RAHDAR));
        verify(tap, times(1)).installedNames(any(), eq(INSTANCE));
    }

    @Test
    void aDivertRemovedOutOfBandIsReinstalledEvenWhileItsDrainIsRunning() {
        // The drain is still attached to its queue, but the divert feeding it is gone: capture
        // would report ACTIVE while recording nothing.
        when(tap.installedNames(any(), eq(INSTANCE))).thenReturn(List.of());
        when(consumers.isDraining(NODE, WANTED)).thenReturn(true);

        reconciler.reconcileCluster(CLUSTER);

        verify(consumers).stop(NODE, WANTED);
        verify(tap).install(any(), eq(INSTANCE), any());
    }

    @Test
    void aRefusedTapIsRecordedOnceAndNotRetriedEveryPass() {
        when(tap.installedNames(any(), eq(INSTANCE))).thenReturn(List.of());
        when(tap.install(any(), eq(INSTANCE), any()))
                .thenThrow(new CaptureRefusedException("the filter is not valid selector syntax", null));

        reconciler.reconcileCluster(CLUSTER);
        reconciler.reconcileCluster(CLUSTER);
        reconciler.reconcileCluster(CLUSTER);

        verify(tap, times(1)).install(any(), eq(INSTANCE), any());
        verify(audit, times(1)).begin(any(), eq("INSTALL_CAPTURE"), anyString(), any(), any(), any(), any(), eq(false));
    }

    @Test
    void reinstallRemovesTheSubscriptionsTapsAndInstallsThemAgain() {
        // Installed before the reinstall; gone once it has removed them.
        when(tap.installedNames(any(), eq(INSTANCE)))
                .thenReturn(List.of(WANTED))
                .thenReturn(List.of());

        reconciler.reinstall(CLUSTER, SUBSCRIPTION);

        // The old divert and queue carry the old filter; a divert is never changed in place.
        var order = Mockito.inOrder(consumers, tap);
        order.verify(consumers).stop(NODE, WANTED);
        order.verify(tap).remove(any(), eq(INSTANCE), eq(WANTED));
        order.verify(tap).install(any(), eq(INSTANCE), any());
        verify(tap, times(1)).remove(any(), any(), any());
    }

    @Test
    void aNodeThatStoppedServingKeepsNoDrain() {
        BrokerNodeEntity gone = mock(BrokerNodeEntity.class);
        UUID goneId = UUID.randomUUID();
        when(gone.getId()).thenReturn(goneId);
        when(gone.getName()).thenReturn("old-primary");
        when(gone.getManagementUrl()).thenReturn("http://old:8161/console/jolokia");
        when(gone.getActive()).thenReturn(false);
        when(gone.getLastError()).thenReturn("unreachable");
        when(nodes.nodes(CLUSTER)).thenReturn(List.of(nodeEntity, gone));
        when(tap.installedNames(any(), eq(INSTANCE))).thenReturn(List.of(WANTED));
        when(consumers.isDraining(NODE, WANTED)).thenReturn(true);
        when(consumers.drainingOn(goneId)).thenReturn(Set.of(WANTED));

        reconciler.reconcileCluster(CLUSTER);

        verify(consumers).stop(goneId, WANTED);
    }

    // ---- fixtures --------------------------------------------------------

    /** The entity is deliberately closed to construction; what matters here is what it answers. */
    private static BrokerNodeEntity node() {
        BrokerNodeEntity entity = mock(BrokerNodeEntity.class);
        when(entity.getId()).thenReturn(NODE);
        when(entity.getName()).thenReturn("primary");
        when(entity.getManagementUrl()).thenReturn("http://primary:8161/console/jolokia");
        when(entity.getDataUrl()).thenReturn("tcp://primary:61616");
        when(entity.getActive()).thenReturn(true);
        return entity;
    }

    private static MessageIndexSubscriptionEntity subscription() {
        MessageIndexSubscriptionEntity entity = new MessageIndexSubscriptionEntity();
        entity.setId(SUBSCRIPTION);
        entity.setClusterId(CLUSTER);
        entity.setQueuePattern("ORDER.IN");
        entity.setMode(CaptureMode.CAPTURE);
        entity.setRingSize(10_000);
        entity.setMaxRate(500);
        entity.setBodyCapBytes(262_144);
        entity.setEnabled(true);
        return entity;
    }

    private static QueryPlan plan() {
        QueryPlan.Target target =
                new QueryPlan.Target(NODE, "primary", "ORDER.IN", "ORDER.IN", "ANYCAST", 0, Instant.now(), true);
        return new QueryPlan(
                null,
                QueryAst.Source.BROKER,
                List.of(target),
                null,
                false,
                List.of(),
                List.of(),
                0,
                100,
                false,
                List.of());
    }
}
