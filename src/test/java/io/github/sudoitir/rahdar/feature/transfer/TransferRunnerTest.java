package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferLedger;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunEntity;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunRepository;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.SelectionKind;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferProgress;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferSelection;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.jobs.BackgroundRuns;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff.Operator;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerRelay;
import io.github.sudoitir.rahdar.platform.broker.BrokerXmlSnippets;
import io.github.sudoitir.rahdar.platform.broker.BulkResult;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.broker.RelayLink;
import io.github.sudoitir.rahdar.platform.broker.RelayLink.Batch;
import io.github.sudoitir.rahdar.platform.broker.RelayLink.Hooks;
import io.github.sudoitir.rahdar.platform.broker.RelayLink.Outcome;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.AcceptanceFacts;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.AcceptanceFinding;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.BatchVerdict;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.FindingKind;
import io.github.sudoitir.rahdar.platform.broker.TransferStaging;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The runner's decisions, with every collaborator faked: how a segment ends for each kind of run,
 * what a refused batch or a withdrawn grant does, and what the audit event is told. The brokers
 * themselves are exercised by the end-to-end transfer tests.
 */
class TransferRunnerTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID SRC_CLUSTER = UUID.randomUUID();
    private static final UUID TGT_CLUSTER = UUID.randomUUID();
    private static final UUID REPLICA = UUID.randomUUID();
    private static final int BATCH = 2;

    private final JsonMapper json = JsonMapper.builder().build();

    private TransferRunRepository runs;
    private TransferLedger ledger;
    private TransferNodes nodes;
    private TransferFaults faults;
    private BrokerRelay relay;
    private TransferStaging staging;
    private TransferAcceptance acceptance;
    private NodeCallLimiter limiter;
    private OperatorHandoff handoff;
    private AuditService audit;
    private SseHub sse;
    private SettingsService settings;
    private BackgroundRuns background;
    private ReplicaRegistry replicas;
    private ClusterNode sourceNode;
    private ClusterNode targetNode;
    private RelayLink link;
    private TransferRunner runner;
    private final Operator operator = new Operator(null, null, null);

    @BeforeEach
    void setUp() {
        runs = mock(TransferRunRepository.class);
        ledger = mock(TransferLedger.class);
        nodes = mock(TransferNodes.class);
        faults = mock(TransferFaults.class);
        relay = mock(BrokerRelay.class);
        staging = mock(TransferStaging.class);
        acceptance = mock(TransferAcceptance.class);
        limiter = mock(NodeCallLimiter.class);
        handoff = mock(OperatorHandoff.class);
        audit = mock(AuditService.class);
        sse = mock(SseHub.class);
        settings = mock(SettingsService.class);
        background = mock(BackgroundRuns.class);
        replicas = mock(ReplicaRegistry.class);
        sourceNode = mock(ClusterNode.class);
        targetNode = mock(ClusterNode.class);
        link = mock(RelayLink.class);

        when(sourceNode.getManagementUrl()).thenReturn("http://src");
        when(sourceNode.getDataUrl()).thenReturn("core-src");
        when(targetNode.getManagementUrl()).thenReturn("http://tgt");
        when(targetNode.getDataUrl()).thenReturn("core-tgt");
        when(nodes.serving(eq(SRC_CLUSTER), any(), any())).thenReturn(sourceNode);
        when(nodes.serving(eq(TGT_CLUSTER), any(), any())).thenReturn(targetNode);
        when(nodes.staging(any(ClusterNode.class))).thenReturn(staging);
        when(nodes.acceptance(any(ClusterNode.class))).thenReturn(acceptance);
        when(relay.link(any(), any(), any())).thenReturn(link);
        when(acceptance.read(any(), any(), any())).thenReturn(TARGET_FACTS);
        when(acceptance.batch(any(), anyLong(), anyInt(), any(), any())).thenReturn(PROCEED);
        when(handoff.stillHolds(any(), any(), any(), any())).thenReturn(true);
        when(settings.intValue(TransferSettings.BATCH_SIZE)).thenReturn(BATCH);
        when(settings.intValue(TransferSettings.MESSAGES_PER_SECOND)).thenReturn(1_000_000);
        when(settings.intValue(TransferSettings.CAPACITY_THRESHOLD_PERCENT)).thenReturn(90);
        when(settings.duration(TransferSettings.CAPACITY_WAIT)).thenReturn(Duration.ofMinutes(1));
        when(replicas.id()).thenReturn(REPLICA);
        when(replicas.heartbeatFresh()).thenReturn(true);
        when(runs.fence(RUN, REPLICA)).thenReturn(1);
        when(runs.findById(RUN)).thenAnswer(call -> Optional.ofNullable(current));
        doAnswer(call -> {
                    ((Runnable) call.getArgument(2)).run();
                    return null;
                })
                .when(background)
                .start(any(), any(), any());

        runner = new TransferRunner(
                runs,
                ledger,
                nodes,
                faults,
                relay,
                limiter,
                handoff,
                audit,
                sse,
                settings,
                background,
                json,
                replicas,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    private TransferRunEntity current;

    // ---- fixtures ----------------------------------------------------------

    /** What the target node said about the queue; the verdicts below are the system's own, stubbed per test. */
    private static final AcceptanceFacts TARGET_FACTS = new AcceptanceFacts() {
        @Override
        public Long messageCount() {
            return null;
        }

        @Override
        public Long persistentSize() {
            return null;
        }

        @Override
        public int filterCountLimit() {
            return 0;
        }
    };

    private static final BatchVerdict PROCEED = new BatchVerdict(null, null);

    private static BatchVerdict refusing(String code, String words, String snippet) {
        return new BatchVerdict(new AcceptanceFinding(FindingKind.REFUSE, code, words, snippet), null);
    }

    private static BatchVerdict waiting(String reason) {
        return new BatchVerdict(null, reason);
    }

    private TransferRunEntity run(
            TransferMode mode,
            boolean sameNode,
            SelectionKind kind,
            List<Long> ids,
            String filter,
            Long estimate,
            Long estimateBytes) {
        UUID targetCluster = sameNode ? SRC_CLUSTER : TGT_CLUSTER;
        current = TransferRunEntity.builder()
                .mode(mode)
                .sourceClusterId(SRC_CLUSTER)
                .sourceNodeId(UUID.randomUUID())
                .sourceNodeName("src")
                .sourceNativeNodeId("src-id")
                .sourceQueue("SRC.Q")
                .sourceAddress("SRC.A")
                .sourceRoutingType("ANYCAST")
                .targetClusterId(targetCluster)
                .targetNodeId(UUID.randomUUID())
                .targetNodeName("tgt")
                .targetNativeNodeId("tgt-id")
                .targetQueue("TGT.Q")
                .targetAddress("TGT.A")
                .targetRoutingType("ANYCAST")
                .sameNode(sameNode)
                .selection(json.writeValueAsString(new TransferSelection(kind, ids, filter)))
                .findings("[]")
                .t0(Instant.parse("2026-01-01T00:00:00Z"))
                .planHash("hash")
                .estimate(estimate)
                .estimateBytes(estimateBytes)
                .username("alice")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        ReflectionTestUtils.setField(current, "id", RUN);
        return current;
    }

    private TransferRunEntity copyOfAll(Long estimate) {
        return run(TransferMode.COPY, false, SelectionKind.ALL, null, null, estimate, null);
    }

    private TransferRunEntity moveAcross(SelectionKind kind, List<Long> ids) {
        return run(TransferMode.MOVE, false, kind, ids, null, null, null);
    }

    private TransferRunEntity moveOnOneNode(SelectionKind kind, List<Long> ids) {
        return run(TransferMode.MOVE, true, kind, ids, null, null, null);
    }

    private static Batch relayed(int delivered, long bytes, Long... ids) {
        return new Batch(Outcome.RELAYED, delivered, 0, bytes, List.of(ids));
    }

    private static Batch empty() {
        return new Batch(Outcome.EMPTY, 0, 0, 0, List.of());
    }

    private static Batch addressFull() {
        return new Batch(Outcome.ADDRESS_FULL, 0, 0, 0, List.of());
    }

    private void relaying(Batch first, Batch... rest) throws IOException {
        when(link.relay(anyInt(), any())).thenReturn(first, rest);
    }

    private TransferRunEntity execute() {
        runner.start(RUN, operator);
        return current;
    }

    private static BulkResult bulk(long affected, String error, Long... notDone) {
        return new BulkResult(affected, List.of(notDone), error);
    }

    // ---- start and stop ----------------------------------------------------

    @Test
    void aRunThatIsNoLongerStoredEndsWithoutTouchingAnything() {
        current = null;

        runner.start(RUN, operator);

        verify(runs, never()).save(any());
        verify(sse, never()).publishAbout(any(UUID.class), anyString(), anyCollection(), anyCollection());
    }

    @Test
    void stopIsAskedOfTheBackgroundRuns() {
        when(background.requestStop(RUN)).thenReturn(true);

        assertThat(runner.requestStop(RUN)).isTrue();
        assertThat(runner.requestStop(UUID.randomUUID())).isFalse();
    }

    // ---- a run that was taken over --------------------------------------------

    @Test
    void aRunRecoveryInterruptedWhileItExecutedIsNotOverwrittenAndStopsActing() throws Exception {
        copyOfAll(5L).attachAudit(11L, 12L);
        when(audit.byId(11L)).thenReturn(Optional.of(mock(AuditEvent.class)));
        relaying(relayed(3, 300, 1L, 2L, 3L), relayed(2, 200, 4L, 5L), empty());
        // Recovery marked the run interrupted while the first batch was relaying: the fence finds it so.
        when(runs.fence(RUN, REPLICA)).thenReturn(0);

        execute();

        verify(link, times(1)).relay(anyInt(), any());
        verify(runs, never()).save(any());
        verify(audit, never()).finish(any(), anyBoolean(), anyLong(), any(), any());
        verify(sse, never()).publishAbout(any(UUID.class), anyString(), anyCollection(), anyCollection());
    }

    @Test
    void aReplicaWhoseHeartbeatLapsedStopsBeforeTheNextBatch() throws Exception {
        copyOfAll(5L).attachAudit(11L, 12L);
        when(audit.byId(11L)).thenReturn(Optional.of(mock(AuditEvent.class)));
        relaying(relayed(3, 300, 1L, 2L, 3L), empty());
        when(replicas.heartbeatFresh()).thenReturn(false);

        execute();

        verify(link, never()).relay(anyInt(), any());
        verify(runs, never()).save(any());
        verify(audit, never()).finish(any(), anyBoolean(), anyLong(), any(), any());
    }

    @Test
    void aStopOnTheRunsRowStopsItBeforeTheNextBatchWhateverTheSignalDid() throws Exception {
        copyOfAll(5L);
        relaying(relayed(3, 300, 1L, 2L, 3L), empty());
        when(runs.existsByIdAndStopRequestedAtIsNotNull(RUN)).thenReturn(true);

        TransferRunEntity run = execute();

        assertThat(run.getState()).isEqualTo(TransferState.STOPPED);
        assertThat(run.getLastError()).isEqualTo("Stopped by the operator.");
        verify(link, never()).relay(anyInt(), any());
        verify(background).requestStop(RUN);
    }

    // ---- copy ----------------------------------------------------------------

    @Test
    void aCopyRelaysBatchesUntilTheSourceIsEmptyAndRecordsWhatItDelivered() throws Exception {
        copyOfAll(5L);
        relaying(relayed(3, 300, 1L, 2L, 3L), relayed(2, 200, 4L, 5L), empty());
        when(ledger.count(RUN)).thenReturn(5L);

        TransferRunEntity run = execute();

        assertThat(run.getState()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.getDelivered()).isEqualTo(5);
        assertThat(run.getBytes()).isEqualTo(500);
        assertThat(run.getNotTransferred()).isZero();
        assertThat(run.getFinishedAt()).isNotNull();
        verify(ledger).forget(RUN);
        verify(limiter, times(3)).acquire("http://src", 1, "transfer");
        verify(limiter, times(3)).acquire("http://tgt", 1, "transfer");
        ArgumentCaptor<TransferProgress> progress = ArgumentCaptor.forClass(TransferProgress.class);
        verify(sse, Mockito.atLeastOnce())
                .publishAbout(eq(SRC_CLUSTER), eq("transfer"), progress.capture(), anyCollection(), anyCollection());
        assertThat(progress.getAllValues().getLast().delivered()).isEqualTo(5);
        verify(sse).publishAbout(eq(SRC_CLUSTER), eq("queues"), anyCollection(), anyCollection());
        verify(sse).publishAbout(eq(TGT_CLUSTER), eq("queues"), anyCollection(), anyCollection());
    }

    @Test
    void aCopyBrowsesTheSourceWithTheFrozenFilterAndItsHooksUseTheLedger() throws Exception {
        run(TransferMode.COPY, false, SelectionKind.FILTER, null, "color = 'red'", 0L, null);
        ArgumentCaptor<RelayLink.Route> route = ArgumentCaptor.forClass(RelayLink.Route.class);
        ArgumentCaptor<Hooks> hooks = ArgumentCaptor.forClass(Hooks.class);
        relaying(empty());

        execute();

        verify(relay).link(any(), any(), route.capture());
        assertThat(route.getValue().staged()).isFalse();
        assertThat(route.getValue().sourceQueue()).isEqualTo("SRC.Q");
        assertThat(route.getValue().browseFilter()).isEqualTo("color = 'red'");
        assertThat(route.getValue().producedAtOrBefore()).contains(Instant.ofEpochMilli(1767225600000L));
        assertThat(route.getValue().targetQueue()).isEqualTo("TGT.Q");
        assertThat(route.getValue().provenance().staged()).isFalse();
        verify(link).relay(eq(BATCH), hooks.capture());
        when(ledger.known(RUN, List.of(1L, 2L))).thenReturn(Set.of(1L));
        assertThat(hooks.getValue().selected(99L)).isTrue();
        assertThat(hooks.getValue().alreadyRelayed(List.of(1L, 2L))).containsExactly(1L);
        hooks.getValue().afterTargetCommit(List.of(1L, 2L));
        verify(faults).afterTargetCommit(RUN);
        verify(ledger).recordCopied(RUN, List.of(1L, 2L));
    }

    @Test
    void aCopyOfIdsRelaysOnlyThoseIdsAndStopsOnceAllAreInTheLedger() throws Exception {
        run(TransferMode.COPY, false, SelectionKind.IDS, List.of(1L, 2L), null, null, null);
        ArgumentCaptor<Hooks> hooks = ArgumentCaptor.forClass(Hooks.class);
        relaying(relayed(2, 20, 1L, 2L));
        when(ledger.count(RUN)).thenReturn(2L);

        TransferRunEntity run = execute();

        assertThat(run.getState()).isEqualTo(TransferState.SUCCEEDED);
        verify(link, times(1)).relay(anyInt(), hooks.capture());
        assertThat(hooks.getValue().selected(1L)).isTrue();
        assertThat(hooks.getValue().selected(9L)).isFalse();
        ArgumentCaptor<RelayLink.Route> route = ArgumentCaptor.forClass(RelayLink.Route.class);
        verify(relay).link(any(), any(), route.capture());
        assertThat(route.getValue().browseFilter()).isNull();
        assertThat(route.getValue().producedAtOrBefore()).isEmpty();
    }

    @Test
    void aCopyOfIdsThatFoundOnlySomeIsPartial() throws Exception {
        run(TransferMode.COPY, false, SelectionKind.IDS, List.of(1L, 2L, 3L), null, null, null);
        relaying(relayed(1, 10, 1L), empty());
        when(ledger.count(RUN)).thenReturn(1L);

        TransferRunEntity run = execute();

        assertThat(run.getState()).isEqualTo(TransferState.PARTIAL);
        assertThat(run.getNotTransferred()).isEqualTo(2);
    }

    @Test
    void aCopyWithAnEstimateLargerThanWhatWasCopiedIsPartial() throws Exception {
        copyOfAll(10L);
        relaying(relayed(4, 40, 1L, 2L, 3L, 4L), empty());
        when(ledger.count(RUN)).thenReturn(4L);

        TransferRunEntity run = execute();

        assertThat(run.getState()).isEqualTo(TransferState.PARTIAL);
        assertThat(run.getNotTransferred()).isEqualTo(6);
    }

    @Test
    void aCopyWithNoEstimateCountsWhatStillMatchesOnTheSource() throws Exception {
        copyOfAll(null);
        relaying(empty());
        when(ledger.count(RUN)).thenReturn(1L);
        when(staging.countSelected(any(), any(), any(), any(), any())).thenReturn(4L);

        TransferRunEntity run = execute();

        assertThat(run.getState()).isEqualTo(TransferState.PARTIAL);
        assertThat(run.getNotTransferred()).isEqualTo(3);
    }

    @Test
    void aCopyPacesItselfToTheConfiguredRate() throws Exception {
        copyOfAll(0L);
        relaying(relayed(5, 50, 1L, 2L, 3L, 4L, 5L), empty());
        when(settings.intValue(TransferSettings.MESSAGES_PER_SECOND)).thenReturn(50);

        long started = System.nanoTime();
        execute();

        // five messages at fifty a second is a tenth of a second
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(90));
    }

    // ---- the per-batch guard ---------------------------------------------------

    @Test
    void anOperatorStopEndsTheSegmentAsStoppedBeforeAnyBatch() throws Exception {
        copyOfAll(0L);
        when(background.stopRequested(RUN)).thenReturn(true);

        TransferRunEntity run = execute();

        assertThat(run.getState()).isEqualTo(TransferState.STOPPED);
        assertThat(run.getLastError()).isEqualTo("Stopped by the operator.");
        verify(link, never()).relay(anyInt(), any());
    }

    @Test
    void aStopFromRahdarsOwnShutdownEndsTheSegmentAsInterrupted() {
        copyOfAll(0L);
        when(background.stopRequested(RUN)).thenReturn(true);
        when(background.stoppedForShutdown(RUN)).thenReturn(true);

        TransferRunEntity run = execute();

        assertThat(run.getState()).isEqualTo(TransferState.INTERRUPTED);
        assertThat(run.getState().resumable()).isTrue();
        assertThat(run.getLastError()).isEqualTo(TransferRecovery.INTERRUPTED);
    }

    @Test
    void aWithdrawnSourcePermissionStopsTheRunAndNamesIt() {
        TransferRunEntity run = copyOfAll(0L);
        when(handoff.stillHolds(
                        any(),
                        eq(SRC_CLUSTER),
                        eq(ResourceRef.subscription("SRC.Q")),
                        eq(run.getMode().sourcePermission())))
                .thenReturn(false);

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.STOPPED);
        assertThat(run.getLastError())
                .contains(run.getMode().sourcePermission())
                .contains("source queue");
    }

    @Test
    void aWithdrawnTargetPermissionStopsTheRunAndNamesIt() {
        TransferRunEntity run = copyOfAll(0L);
        when(handoff.stillHolds(
                        any(),
                        eq(TGT_CLUSTER),
                        eq(ResourceRef.destination("TGT.A")),
                        eq(MessagePermissions.MESSAGE_SEND)))
                .thenReturn(false);

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.STOPPED);
        assertThat(run.getLastError()).contains(MessagePermissions.MESSAGE_SEND).contains("target address");
    }

    @Test
    void aWithdrawnReadOfTheTargetQueueStopsTheRunAndNamesIt() {
        TransferRunEntity run = copyOfAll(0L);
        when(handoff.stillHolds(
                        any(),
                        eq(TGT_CLUSTER),
                        eq(ResourceRef.subscription(run.getTargetQueue())),
                        eq(Permissions.SUBSCRIPTION_READ)))
                .thenReturn(false);

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.STOPPED);
        assertThat(run.getLastError()).contains(Permissions.SUBSCRIPTION_READ).contains("target queue");
    }

    @Test
    void aTargetThatNowDropsMessagesFailsTheRunWithTheSnippetThatFixesIt() {
        TransferRunEntity run = copyOfAll(0L);
        when(acceptance.batch(any(), anyLong(), anyInt(), any(), any()))
                .thenReturn(refusing(
                        "address-full-drop",
                        "Address TGT.A drops messages silently when it is full.",
                        "<address-setting match=\"TGT.A\"><address-full-policy>FAIL</address-full-policy>"));

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.FAILED);
        assertThat(run.getLastError()).contains("drops messages silently");
        assertThat(run.getErrorSnippet()).contains("address-full-policy").contains("TGT.A");
    }

    @Test
    void aTargetQueueThatNowHasAFilterFailsTheRun() {
        TransferRunEntity run = copyOfAll(0L);
        when(acceptance.batch(any(), anyLong(), anyInt(), any(), any()))
                .thenReturn(refusing("target-filtered", "Queue TGT.Q now has the filter color = 'red'.", null));

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.FAILED);
        assertThat(run.getLastError()).contains("now has the filter");
        assertThat(run.getErrorSnippet()).isNull();
    }

    private static final BatchVerdict NEAR_FULL = waiting("the target is nearly full");

    @Test
    void aTargetNearFullMakesTheRunWaitAndThenCarryOn() throws Exception {
        copyOfAll(0L);
        when(acceptance.batch(any(), anyLong(), anyInt(), any(), any())).thenReturn(NEAR_FULL, PROCEED);
        relaying(empty());

        TransferRunEntity run = execute();

        assertThat(run.getState()).isEqualTo(TransferState.SUCCEEDED);
        verify(acceptance, times(2)).read(any(), any(), any());
        ArgumentCaptor<TransferProgress> progress = ArgumentCaptor.forClass(TransferProgress.class);
        verify(sse, Mockito.atLeastOnce())
                .publishAbout(eq(SRC_CLUSTER), eq("transfer"), progress.capture(), anyCollection(), anyCollection());
        assertThat(progress.getAllValues())
                .extracting(TransferProgress::state)
                .contains(TransferState.WAITING_FOR_CAPACITY, TransferState.RUNNING);
    }

    @Test
    void aTargetThatStaysFullStopsTheRunResumably() {
        TransferRunEntity run = copyOfAll(0L);
        when(acceptance.batch(any(), anyLong(), anyInt(), any(), any())).thenReturn(NEAR_FULL);
        when(settings.duration(TransferSettings.CAPACITY_WAIT)).thenReturn(Duration.ZERO);

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.STOPPED);
        assertThat(run.getLastError()).contains("stayed full").contains("Resume the run");
    }

    @Test
    void anOperatorStopWhileWaitingForRoomEndsTheWait() {
        TransferRunEntity run = copyOfAll(0L);
        when(acceptance.batch(any(), anyLong(), anyInt(), any(), any())).thenReturn(NEAR_FULL);
        when(background.stopRequested(RUN)).thenReturn(false, true);

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.STOPPED);
        assertThat(run.getLastError()).contains("while waiting for the target");
    }

    @Test
    void aTargetThatBecomesUnreadableWhileWaitingKeepsTheRunWaiting() throws Exception {
        TransferRunEntity run = copyOfAll(0L);
        when(acceptance.read(any(), any(), any()))
                .thenReturn(TARGET_FACTS)
                .thenThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "down"))
                .thenReturn(TARGET_FACTS);
        when(acceptance.batch(any(), anyLong(), anyInt(), any(), any())).thenReturn(NEAR_FULL, PROCEED);
        relaying(empty());

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.SUCCEEDED);
        verify(acceptance, times(3)).read(any(), any(), any());
    }

    @Test
    void aTargetThatTurnsIntoADroppingOneWhileWaitingFailsTheRun() {
        TransferRunEntity run = copyOfAll(0L);
        when(acceptance.batch(any(), anyLong(), anyInt(), any(), any()))
                .thenReturn(
                        NEAR_FULL,
                        refusing("address-full-drop", "The address now drops messages silently.", "snippet"));

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.FAILED);
        assertThat(run.getErrorSnippet()).isNotNull();
    }

    @Test
    void aBatchTheTargetRefusedForRoomWaitsAndIsRetried() throws Exception {
        copyOfAll(0L);
        relaying(addressFull(), relayed(1, 10, 1L), empty());

        TransferRunEntity run = execute();

        assertThat(run.getState()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.getDelivered()).isEqualTo(1);
    }

    @Test
    void aBatchesBytesPerMessageFeedTheCapacityCheck() throws Exception {
        // 100 bytes over 10 messages is 10 bytes a message, 20 for a batch of two: 95 + 20 is over the line
        run(TransferMode.COPY, false, SelectionKind.ALL, null, null, 10L, 100L);
        when(acceptance.batch(any(), anyLong(), anyInt(), any(), any())).thenReturn(NEAR_FULL, PROCEED);
        relaying(empty());

        TransferRunEntity run = execute();

        assertThat(run.getState()).isNotEqualTo(TransferState.FAILED);
        verify(acceptance, times(2)).batch(any(), eq(20L), anyInt(), any(), any());
    }

    // ---- failures ---------------------------------------------------------------

    @Test
    void aNodeThatCannotBeReachedFailsACopyAsResumable() {
        TransferRunEntity run = copyOfAll(0L);
        when(nodes.serving(eq(SRC_CLUSTER), any(), any()))
                .thenThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "no endpoint"));

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.FAILED);
        assertThat(run.getLastError())
                .contains("A node failed mid-run: no endpoint")
                .endsWith("Resume once the node answers.");
    }

    @Test
    void aRelayFailureOnACrossNodeMoveSaysTheMessagesAreHeldInStaging() throws Exception {
        TransferRunEntity run = moveAcross(SelectionKind.ALL, null);
        when(staging.moveSelected(any(), any(), any(), any(), any(), anyInt(), anyString()))
                .thenReturn(0L);
        when(link.relay(anyInt(), any())).thenThrow(new IOException("socket closed"));

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.FAILED);
        assertThat(run.getLastError()).contains("socket closed").contains("held in the staging queue");
        verify(link).close();
    }

    @Test
    void anUnexpectedFailureIsRecordedRatherThanLost() {
        TransferRunEntity run = copyOfAll(0L);
        when(acceptance.read(any(), any(), any())).thenThrow(new IllegalStateException("bug"));

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.FAILED);
        assertThat(run.getLastError()).isEqualTo("The run stopped unexpectedly: bug");
    }

    // ---- move on one node ---------------------------------------------------------

    @Test
    void aMoveOnOneNodeLetsTheBrokerMoveBatchesUntilOneComesUpShort() {
        TransferRunEntity run = moveOnOneNode(SelectionKind.ALL, null);
        when(staging.moveSelected(any(), any(), any(), any(), any(), eq(BATCH), eq("TGT.Q")))
                .thenReturn(2L, 1L);
        when(staging.countSelected(any(), any(), any(), any(), any())).thenReturn(0L);

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.getDelivered()).isEqualTo(3);
        verify(sse, times(1)).publishAbout(eq(SRC_CLUSTER), eq("queues"), anyCollection(), anyCollection());
        verify(relay, never()).link(any(), any(), any());
    }

    @Test
    void aMoveOnOneNodeThatLeftMessagesBehindIsPartial() {
        TransferRunEntity run = moveOnOneNode(SelectionKind.ALL, null);
        when(staging.moveSelected(any(), any(), any(), any(), any(), anyInt(), anyString()))
                .thenReturn(1L);
        when(staging.countSelected(any(), any(), any(), any(), any())).thenReturn(4L);

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.PARTIAL);
        assertThat(run.getNotTransferred()).isEqualTo(4);
    }

    @Test
    void aMoveOfIdsOnOneNodeWalksTheIdsInBatches() {
        TransferRunEntity run = moveOnOneNode(SelectionKind.IDS, List.of(1L, 2L, 3L));
        when(staging.moveIds(any(), any(), any(), eq(List.of(1L, 2L)), eq("TGT.Q")))
                .thenReturn(bulk(2, null));
        when(staging.moveIds(any(), any(), any(), eq(List.of(3L)), eq("TGT.Q"))).thenReturn(bulk(0, null));

        execute();

        // the third id was taken but did not move: it is counted, not lost
        assertThat(run.getState()).isEqualTo(TransferState.PARTIAL);
        assertThat(run.getIdCursor()).isEqualTo(3);
        assertThat(run.getDelivered()).isEqualTo(2);
        assertThat(run.getNotTransferred()).isEqualTo(1);
    }

    @Test
    void aMoveOfNoIdsIsCompleteAtOnce() {
        TransferRunEntity run = moveOnOneNode(SelectionKind.IDS, List.of());

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.SUCCEEDED);
        verify(staging, never()).moveIds(any(), any(), any(), anyList(), anyString());
    }

    @Test
    void aMoveOfIdsThatTheBrokerStoppedPartwayFailsWithWhatWasMoved() {
        TransferRunEntity run = moveOnOneNode(SelectionKind.IDS, List.of(1L, 2L));
        when(staging.moveIds(any(), any(), any(), anyList(), anyString())).thenReturn(bulk(1, "broker said no", 2L));

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.FAILED);
        assertThat(run.getDelivered()).isEqualTo(1);
        assertThat(run.getLastError())
                .contains("The move stopped: broker said no")
                .endsWith("Resume once the node answers.");
    }

    // ---- move between two nodes -----------------------------------------------------

    @Test
    void aStagedMoveTakesIntoStagingRelaysAndRemovesStaging() throws Exception {
        TransferRunEntity run = moveAcross(SelectionKind.ALL, null);
        String stagingQueue = RahdarNames.stagingQueue(RUN);
        // fewer than the four asked for: the source is drained
        when(staging.moveSelected(any(), any(), any(), any(), any(), eq(4), eq(stagingQueue)))
                .thenReturn(3L);
        when(staging.stagedCount(any(), any(), anyString())).thenReturn(0L);
        when(staging.countSelected(any(), any(), any(), any(), any())).thenReturn(0L);
        when(staging.destroyIfEmpty(any(), any(), eq(stagingQueue))).thenReturn(true);
        relaying(relayed(2, 20, 1L, 2L), relayed(1, 10, 3L), empty());

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.getStaged()).isEqualTo(3);
        assertThat(run.getDelivered()).isEqualTo(3);
        verify(staging).create(any(), any(), eq(RUN));
        verify(ledger).remove(RUN, List.of(1L, 2L));
        verify(ledger).forget(RUN);
    }

    @Test
    void theInDoubtHooksRecordEachBatchBeforeTheTargetCommitAndUndoARefusedOne() throws Exception {
        moveAcross(SelectionKind.ALL, null);
        when(staging.moveSelected(any(), any(), any(), any(), any(), anyInt(), anyString()))
                .thenReturn(0L);
        when(staging.stagedCount(any(), any(), anyString())).thenReturn(0L);
        when(ledger.known(RUN, List.of(1L, 2L))).thenReturn(Set.of(1L));
        when(staging.destroyIfEmpty(any(), any(), anyString())).thenReturn(true);
        AtomicBoolean first = new AtomicBoolean(true);
        when(link.relay(anyInt(), any())).thenAnswer(call -> {
            Hooks hooks = call.getArgument(1);
            if (first.compareAndSet(true, false)) {
                hooks.beforeTargetCommit(List.of(1L, 2L));
                hooks.afterTargetCommit(List.of(1L, 2L));
                return addressFull();
            }
            return empty();
        });

        execute();

        verify(ledger).recordCopied(RUN, List.of(1L, 2L));
        verify(faults).afterTargetCommit(RUN);
        // only the id that was not already in doubt is taken out again
        verify(ledger).remove(RUN, Set.of(2L));
    }

    @Test
    void aStagingQueueTheSourceBrokerWillNotCreateFailsWithTheSecuritySnippet() {
        TransferRunEntity run = moveAcross(SelectionKind.ALL, null);
        doThrow(new BrokerConnectionException(BrokerConnectionException.Kind.BAD_RESPONSE, "denied"))
                .when(staging)
                .create(any(), any(), any());

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.FAILED);
        assertThat(run.getLastError())
                .contains(RahdarNames.stagingQueue(RUN))
                .contains("denied")
                .contains("rahdar.transfer.#");
        assertThat(run.getErrorSnippet()).isEqualTo(BrokerXmlSnippets.STAGING_SECURITY_SETTING);
    }

    @Test
    void aStagingQueueThatFilledAgainIsKeptAndTheRunStops() throws Exception {
        TransferRunEntity run = moveAcross(SelectionKind.ALL, null);
        when(staging.moveSelected(any(), any(), any(), any(), any(), anyInt(), anyString()))
                .thenReturn(0L);
        when(staging.stagedCount(any(), any(), anyString())).thenReturn(0L);
        when(staging.countSelected(any(), any(), any(), any(), any())).thenReturn(0L);
        when(staging.destroyIfEmpty(any(), any(), anyString())).thenReturn(false);
        relaying(empty());

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.STOPPED);
        assertThat(run.getLastError()).contains("received messages again");
        verify(ledger, never()).forget(RUN);
    }

    @Test
    void messagesThatCannotBeReceivedFromStagingStopTheRunAfterThreeEmptyBatches() throws Exception {
        TransferRunEntity run = moveAcross(SelectionKind.ALL, null);
        // deep enough that no refill is needed, and never received
        when(staging.stagedCount(any(), any(), anyString())).thenReturn(5L);
        relaying(empty());

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.STOPPED);
        assertThat(run.getLastError())
                .contains("5 messages are held in staging queue")
                .contains("scheduled");
        verify(link, times(3)).relay(anyInt(), any());
    }

    @Test
    void aStagedMoveOfIdsTakesThemByIdAndDoesNotCountTheSourceAgain() throws Exception {
        TransferRunEntity run = moveAcross(SelectionKind.IDS, List.of(10L, 20L, 30L));
        String stagingQueue = RahdarNames.stagingQueue(RUN);
        when(staging.moveIds(any(), any(), any(), eq(List.of(10L, 20L, 30L)), eq(stagingQueue)))
                .thenReturn(bulk(3, null));
        when(staging.stagedCount(any(), any(), anyString())).thenReturn(0L);
        when(staging.destroyIfEmpty(any(), any(), anyString())).thenReturn(true);
        relaying(relayed(2, 20, 10L, 20L), relayed(1, 10, 30L), empty());

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.getIdCursor()).isEqualTo(3);
        verify(staging, never()).countSelected(any(), any(), any(), any(), any());
    }

    @Test
    void aStagedMoveOfNoIdsHasNothingToTake() throws Exception {
        TransferRunEntity run = moveAcross(SelectionKind.IDS, List.of());
        when(staging.stagedCount(any(), any(), anyString())).thenReturn(0L);
        when(staging.destroyIfEmpty(any(), any(), anyString())).thenReturn(true);
        relaying(empty());

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.SUCCEEDED);
        verify(staging, never()).moveIds(any(), any(), any(), anyList(), anyString());
    }

    @Test
    void aStagedMoveThatTheBrokerStoppedTakingIntoStagingFailsWithItsReason() {
        TransferRunEntity run = moveAcross(SelectionKind.IDS, List.of(10L, 20L));
        when(staging.stagedCount(any(), any(), anyString())).thenReturn(0L);
        when(staging.moveIds(any(), any(), any(), anyList(), anyString())).thenReturn(bulk(1, "disk full", 20L));

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.FAILED);
        assertThat(run.getLastError()).contains("Taking messages into staging stopped: disk full");
        assertThat(run.getStaged()).isEqualTo(1);
    }

    @Test
    void whatWasStagedButNeitherDeliveredNorReturnedIsCountedAsExpired() throws Exception {
        TransferRunEntity run = moveAcross(SelectionKind.ALL, null);
        when(staging.moveSelected(any(), any(), any(), any(), any(), eq(4), anyString()))
                .thenReturn(3L);
        when(staging.stagedCount(any(), any(), anyString())).thenReturn(0L);
        when(staging.countSelected(any(), any(), any(), any(), any())).thenReturn(0L);
        when(staging.destroyIfEmpty(any(), any(), anyString())).thenReturn(true);
        relaying(relayed(2, 20, 1L, 2L), empty());

        execute();

        assertThat(run.getExpired()).isEqualTo(1);
        assertThat(run.getState()).isEqualTo(TransferState.PARTIAL);
    }

    @Test
    void aBatchTheTargetRefusedForRoomInAStagedMoveWaitsAndCarriesOn() throws Exception {
        TransferRunEntity run = moveAcross(SelectionKind.ALL, null);
        when(staging.moveStaged(any(), any(), anyString(), anyInt(), anyString()))
                .thenReturn(0L);
        when(staging.stagedCount(any(), any(), anyString())).thenReturn(0L);
        when(staging.countSelected(any(), any(), any(), any(), any())).thenReturn(0L);
        when(staging.destroyIfEmpty(any(), any(), anyString())).thenReturn(true);
        relaying(addressFull(), empty());

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.SUCCEEDED);
        verify(acceptance, Mockito.atLeast(2)).read(any(), any(), any());
    }

    // ---- return to source ------------------------------------------------------------

    private TransferRunEntity returning() {
        TransferRunEntity run = moveAcross(SelectionKind.ALL, null);
        run.begin(TransferState.RETURNING, "alice", null, null, Instant.now());
        return run;
    }

    @Test
    void aReturnPutsEverythingStagingHoldsBackInBatchesAndRemovesStaging() {
        TransferRunEntity run = returning();
        when(ledger.all(RUN)).thenReturn(Set.of());
        when(staging.moveStaged(any(), any(), anyString(), eq(BATCH), eq("SRC.Q")))
                .thenReturn(2L, 1L);
        when(staging.destroyIfEmpty(any(), any(), anyString())).thenReturn(true);

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.RETURNED);
        assertThat(run.getReturned()).isEqualTo(3);
        verify(ledger).forget(RUN);
    }

    @Test
    void aReturnThatCannotEmptyStagingKeepsItAndFails() {
        TransferRunEntity run = returning();
        when(ledger.all(RUN)).thenReturn(Set.of());
        when(staging.moveStaged(any(), any(), anyString(), anyInt(), anyString()))
                .thenReturn(0L);
        when(staging.stagedCount(any(), any(), anyString())).thenReturn(7L);
        when(staging.destroyIfEmpty(any(), any(), anyString())).thenReturn(false);

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.FAILED);
        assertThat(run.getLastError())
                .contains("7 messages are still in staging queue")
                .contains("was kept");
    }

    @Test
    void aReturnSettlesInDoubtBatchesWithTheTargetBeforePuttingTheRestBack() throws Exception {
        TransferRunEntity run = returning();
        when(ledger.all(RUN)).thenReturn(Set.of(1L, 2L));
        when(link.relay(anyInt(), any())).thenReturn(relayed(2, 20, 1L, 2L));
        when(staging.moveStaged(any(), any(), anyString(), anyInt(), anyString()))
                .thenReturn(0L);
        when(staging.destroyIfEmpty(any(), any(), anyString())).thenReturn(true);

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.RETURNED);
        // they arrived on the target, so they are settled there and not put back
        verify(ledger).remove(RUN, Set.of(1L, 2L));
        verify(link).close();
    }

    @Test
    void aReturnWhoseSettlingTheTargetCouldNotAnswerKeepsTheInDoubtMessagesInStaging() {
        TransferRunEntity run = returning();
        when(ledger.all(RUN)).thenReturn(Set.of(1L, 2L));
        when(nodes.serving(eq(TGT_CLUSTER), any(), any()))
                .thenThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "target down"));
        when(staging.stagedIds(any(), any(), anyString())).thenReturn(List.of(1L, 2L, 3L));
        when(staging.moveStagedIds(any(), any(), anyString(), eq(List.of(3L)), eq("SRC.Q")))
                .thenReturn(bulk(1, null));

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.FAILED);
        assertThat(run.getReturned()).isEqualTo(1);
        assertThat(run.getLastError()).contains("2 messages in staging queue").contains("may already be on the target");
        verify(staging, never()).destroyIfEmpty(any(), any(), anyString());
    }

    @Test
    void aSettlingRelayThatMakesNoProgressLeavesTheMessagesUnsettled() throws Exception {
        TransferRunEntity run = returning();
        when(ledger.all(RUN)).thenReturn(Set.of(1L));
        when(link.relay(anyInt(), any())).thenReturn(empty());
        when(staging.stagedIds(any(), any(), anyString())).thenReturn(List.of(1L));
        when(staging.moveStagedIds(any(), any(), anyString(), eq(List.of()), anyString()))
                .thenReturn(bulk(0, null));

        execute();

        assertThat(run.getState()).isEqualTo(TransferState.FAILED);
        assertThat(run.getLastError()).contains("1 messages in staging queue");
    }

    // ---- the audit outcome --------------------------------------------------------------

    @Test
    void aSucceededSegmentFinishesItsAuditEventsAsASuccessWithTheCounts() throws Exception {
        TransferRunEntity run = copyOfAll(1L);
        run.attachAudit(11L, 12L);
        AuditEvent source = mock(AuditEvent.class);
        when(audit.byId(11L)).thenReturn(Optional.of(source));
        when(audit.byId(12L)).thenReturn(Optional.empty());
        relaying(relayed(1, 10, 1L), empty());
        when(ledger.count(RUN)).thenReturn(1L);

        execute();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(audit).finish(eq(source), eq(false), eq(1L), eq((String) null), detail.capture());
        assertThat(detail.getValue())
                .containsEntry("runId", RUN.toString())
                .containsEntry("state", "SUCCEEDED")
                .containsEntry("delivered", 1L)
                .containsEntry("notTransferred", 0L);
    }

    @Test
    void aFailedSegmentFinishesItsAuditEventsAsAFailureWithTheError() {
        TransferRunEntity run = copyOfAll(0L);
        run.attachAudit(11L, 12L);
        AuditEvent source = mock(AuditEvent.class);
        AuditEvent target = mock(AuditEvent.class);
        when(audit.byId(11L)).thenReturn(Optional.of(source));
        when(audit.byId(12L)).thenReturn(Optional.of(target));
        when(acceptance.read(any(), any(), any())).thenThrow(new IllegalStateException("bug"));

        execute();

        verify(audit).finish(eq(source), eq(true), eq(0L), eq("The run stopped unexpectedly: bug"), any());
        verify(audit).finish(eq(target), eq(true), eq(0L), eq("The run stopped unexpectedly: bug"), any());
    }

    @Test
    void aPartialSegmentWithoutAnErrorIsSummarisedByItsCounts() throws Exception {
        TransferRunEntity run = copyOfAll(3L);
        run.attachAudit(11L, null);
        AuditEvent source = mock(AuditEvent.class);
        when(audit.byId(11L)).thenReturn(Optional.of(source));
        relaying(relayed(1, 10, 1L), empty());
        when(ledger.count(RUN)).thenReturn(1L);

        execute();

        verify(audit)
                .finish(eq(source), eq(true), eq(1L), eq("PARTIAL: 1 delivered, 2 not transferred, 0 expired."), any());
    }

    @Test
    void aReturnedSegmentReportsWhatWasReturnedAsItsAffectedCount() {
        TransferRunEntity run = returning();
        run.attachAudit(11L, null);
        AuditEvent source = mock(AuditEvent.class);
        when(audit.byId(11L)).thenReturn(Optional.of(source));
        when(ledger.all(RUN)).thenReturn(Set.of());
        when(staging.moveStaged(any(), any(), anyString(), anyInt(), anyString()))
                .thenReturn(1L);
        when(staging.destroyIfEmpty(any(), any(), anyString())).thenReturn(true);

        execute();

        verify(audit).finish(eq(source), eq(false), eq(1L), eq((String) null), any());
    }

    @Test
    void aSegmentWithinOneClusterAnnouncesItsQueuesOnce() {
        moveOnOneNode(SelectionKind.IDS, List.of());

        execute();

        verify(sse, times(1)).publishAbout(eq(SRC_CLUSTER), eq("queues"), anyCollection(), anyCollection());
        verify(sse, never()).publishAbout(eq(TGT_CLUSTER), eq("queues"), anyCollection(), anyCollection());
    }

    @Test
    void aCopyChargesOnePermitOnEachNodePerBatchAndTouchesNoStaging() throws Exception {
        copyOfAll(0L);
        relaying(empty());

        execute();

        verify(limiter).acquire("http://src", 1, "transfer");
        verify(limiter).acquire("http://tgt", 1, "transfer");
        verify(staging, never()).create(any(), any(), any());
        verify(ledger, never()).remove(any(), any());
    }
}
