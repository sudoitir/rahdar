package io.github.sudoitir.rahdar.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome.NodeApply;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome.StepApply;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome.StepStatus;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome.Verification;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService.Source;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigApplyEntity.Outcome;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigDeclarationEntity.ApplyMode;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity.Basis;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity.State;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateRepository;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.SplitBrainStatus;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The apply contract (ADR-0067 D3, D4, D7, D12): canary first, verified by a
 * read-back, halted on the first failure with the rest not attempted, hazards
 * acknowledged before a write, and the plan the operator saw being the plan that
 * runs.
 *
 * <p>The broker layer is a simulated one: {@link BrokerConfigOperations} is mocked so
 * that a write mutates an in-memory per-node state and a read returns it, which is
 * exactly what verification needs to be meaningful. A test that wants the read-back
 * to disagree with the write simply makes the write a no-op.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class BrokerConfigApplyServiceTest extends PostgresIntegrationTest {

    private static final String MATCH = "orders.#";

    @Autowired
    BrokerConfigApplyService apply;

    @Autowired
    BrokerConfigService config;

    @Autowired
    BrokerConfigDriftService drift;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    ClusterLock lock;

    @Autowired
    BrokerConfigNodeStateRepository nodeStates;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    ArtemisNodes artemisNodes;

    @MockitoBean
    BrokerConfigOperations ops;

    @MockitoSpyBean
    SseHub sseHub;

    private UUID clusterId;
    private UUID firstId;
    private UUID secondId;
    private UUID deadId;

    /** What each simulated broker currently holds: node → match → values. */
    private final Map<UUID, Map<String, Map<String, Object>>> broker = new HashMap<>();

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "c-" + UUID.randomUUID(), null, null))
                .getId();
        firstId = node("a-first", true);
        secondId = node("b-second", true);
        deadId = node("c-dead", false);

        // One client per node, so a write can be attributed to the node it went to.
        for (UUID id : List.of(firstId, secondId, deadId)) {
            ArtemisNode client = mock(ArtemisNode.class);
            when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
            when(artemisNodes.on(clusterId, id)).thenReturn(client);
            clientToNode.put(client, id);
        }

        when(ops.read(any(), any(), anyString(), any())).thenAnswer(inv -> {
            UUID nodeId = inv.getArgument(1);
            String name = inv.getArgument(2);
            return new ObservedNodeConfig(
                    nodeId,
                    name,
                    true,
                    Map.of(),
                    Map.of(),
                    broker.getOrDefault(nodeId, Map.of()),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    null);
        });
        doAnswer(inv -> {
                    UUID nodeId = nodeFor(inv.getArgument(0));
                    broker.computeIfAbsent(nodeId, k -> new HashMap<>()).put(inv.getArgument(2), inv.getArgument(3));
                    return null;
                })
                .when(ops)
                .addAddressSettings(any(), anyString(), anyString(), any());
    }

    private final Map<ArtemisNode, UUID> clientToNode = new HashMap<>();

    private UUID nodeFor(Object client) {
        return clientToNode.get(client);
    }

    private UUID node(String name, boolean active) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                clusterId, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachSeedUrl("http://" + name + ":8161/console/jolokia");
        n.applyHaState(new HaObservation(active, "STARTED", "PRIMARY", null, "2.44.0", null), 1L, Instant.now());
        return nodes.save(n).getId();
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private void declare(String match, Map<String, Object> values) {
        BrokerConfigDocument doc = new BrokerConfigDocument(
                1, List.of(), List.of(new AddressSettingDecl(match, values)), List.of(), List.of(), List.of());
        config.save(clusterId, doc, null, "test", Source.EDIT);
    }

    private BrokerConfigApplyRequest confirmed(BrokerConfigApplyOutcome preview) {
        return new BrokerConfigApplyRequest(
                null,
                Set.of(),
                null,
                false,
                preview.plan().highHazardIds(),
                preview.plan().planHash(),
                false,
                Set.of());
    }

    private static NodeApply node(BrokerConfigApplyOutcome o, UUID id) {
        return o.nodes().stream().filter(n -> n.nodeId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void anApplyScopedToOneItemWritesThatItemOnEveryNodeAndLeavesTheRestAlone() {
        // The screen's per-row "Apply this" (ADR-0087 D2): one declared item, every
        // live node, and the confirmation covers exactly what runs.
        BrokerConfigDocument doc = new BrokerConfigDocument(
                1,
                List.of(),
                List.of(
                        new AddressSettingDecl(MATCH, Map.of("maxSizeBytes", 10_485_760L)),
                        new AddressSettingDecl("payments.#", Map.of("maxDeliveryAttempts", 7))),
                List.of(),
                List.of(),
                List.of());
        config.save(clusterId, doc, null, "test", Source.EDIT);
        Set<String> onlyPayments = Set.of("ADDRESS_SETTING:payments.#:ADD", "ADDRESS_SETTING:payments.#:REPLACE");

        BrokerConfigApplyOutcome whole = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        assertThat(whole.plan().stepCount()).isEqualTo(4);

        BrokerConfigApplyOutcome preview = apply.plan(
                clusterId,
                new BrokerConfigApplyRequest(null, Set.of(), null, false, List.of(), null, false, onlyPayments));
        assertThat(preview.plan().stepCount()).isEqualTo(2);
        assertThat(preview.plan().planHash()).isNotEqualTo(whole.plan().planHash());

        BrokerConfigApplyOutcome outcome = apply.apply(
                clusterId,
                new BrokerConfigApplyRequest(
                        null,
                        Set.of(),
                        null,
                        false,
                        preview.plan().highHazardIds(),
                        preview.plan().planHash(),
                        false,
                        onlyPayments));

        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(broker.get(firstId)).containsKey("payments.#").doesNotContainKey(MATCH);
        assertThat(broker.get(secondId)).containsKey("payments.#").doesNotContainKey(MATCH);
        // The item that was not in scope is still pending, and says so.
        assertThat(apply.plan(clusterId, BrokerConfigApplyRequest.everything())
                        .plan()
                        .stepCount())
                .isEqualTo(2);
    }

    // ---- canary, verify, continue ------------------------------------------

    @Test
    void appliesToTheCanaryVerifiesItThenContinuesToTheNextLiveNode() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));

        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        assertThat(preview.dryRun()).isTrue();
        assertThat(preview.plan().canaryNodeId()).isEqualTo(firstId);
        assertThat(preview.plan().stepCount()).isEqualTo(2);
        verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), any());

        BrokerConfigApplyOutcome outcome = apply.apply(clusterId, confirmed(preview));

        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(node(outcome, firstId).canary()).isTrue();
        assertThat(only(node(outcome, firstId)).status()).isEqualTo(StepStatus.APPLIED);
        assertThat(only(node(outcome, firstId)).verified()).isEqualTo(Verification.VERIFIED);
        assertThat(only(node(outcome, secondId)).status()).isEqualTo(StepStatus.APPLIED);
        assertThat(only(node(outcome, secondId)).verified()).isEqualTo(Verification.VERIFIED);
        assertThat(node(outcome, deadId).steps()).isEmpty();
        assertThat(node(outcome, deadId).live()).isFalse();
        assertThat(broker.get(firstId)).containsKey(MATCH);
        assertThat(broker.get(secondId)).containsKey(MATCH);

        // Re-running converges: everything is ALREADY and no write is issued.
        BrokerConfigApplyOutcome again = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        assertThat(again.plan().stepCount()).isZero();
    }

    /**
     * An apply across a pair is a sequence worth watching — the canary is written
     * and read back before anything else is touched. Without progress frames the
     * whole sequence is one spinner, and a halt on the canary is indistinguishable
     * from a slow success.
     */
    @Test
    void theRunReportsWhereEachNodeHasGotToWhileItIsStillRunning() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());

        List<Map<String, Object>> frames = new ArrayList<>();
        doAnswer(call -> {
                    Object data = call.getArgument(2);
                    if (data instanceof Map<?, ?> map && "apply-progress".equals(map.get("kind"))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> frame = (Map<String, Object>) map;
                        frames.add(Map.copyOf(frame));
                    }
                    return null;
                })
                .when(sseHub)
                .publish(any(), anyString(), any(), any());

        apply.apply(clusterId, confirmed(preview));

        // The canary reports first, and reaches DONE before the second node starts:
        // that ordering is the whole promise of a canary-first apply.
        assertThat(frames).isNotEmpty();
        assertThat(frames.getFirst())
                .containsEntry("nodeId", firstId.toString())
                .containsEntry("canary", true);
        int canaryDone = frames.indexOf(frames.stream()
                .filter(f -> firstId.toString().equals(f.get("nodeId")) && "DONE".equals(f.get("phase")))
                .findFirst()
                .orElseThrow());
        int secondFirst = frames.indexOf(frames.stream()
                .filter(f -> secondId.toString().equals(f.get("nodeId")))
                .findFirst()
                .orElseThrow());
        assertThat(canaryDone).isLessThan(secondFirst);
        // A frame is for one apply, so a screen can tell this run from the last one.
        assertThat(frames).anyMatch(f -> "VERIFYING".equals(f.get("phase"))).allMatch(f -> f.get("applyId") != null);
    }

    @Test
    void aCanaryFailureHaltsTheRunAndTheRestIsNotAttempted() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.ARGUMENT, "Error while parsing MetaData"))
                .when(ops)
                .addAddressSettings(any(), anyString(), anyString(), any());

        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        BrokerConfigApplyOutcome outcome = apply.apply(clusterId, confirmed(preview));

        assertThat(outcome.outcome()).isEqualTo(Outcome.HALTED);
        assertThat(only(node(outcome, firstId)).status()).isEqualTo(StepStatus.FAILED);
        assertThat(only(node(outcome, firstId)).error()).contains("parsing");
        assertThat(only(node(outcome, secondId)).status()).isEqualTo(StepStatus.NOT_ATTEMPTED);
        assertThat(outcome.summary())
                .contains("Halted")
                .contains("not attempted")
                .contains("Nothing was rolled back")
                .contains("converges");
        assertThat(broker).doesNotContainKey(secondId);
    }

    @Test
    void aReadBackThatDisagreesWithTheWriteIsAMismatchAndHalts() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        // The write "succeeds" but the broker does not reflect it.
        doAnswer(inv -> null).when(ops).addAddressSettings(any(), anyString(), anyString(), any());

        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        BrokerConfigApplyOutcome outcome = apply.apply(clusterId, confirmed(preview));

        assertThat(outcome.outcome()).isEqualTo(Outcome.HALTED);
        assertThat(only(node(outcome, firstId)).status()).isEqualTo(StepStatus.APPLIED);
        assertThat(only(node(outcome, firstId)).verified()).isEqualTo(Verification.MISMATCH);
        assertThat(node(outcome, firstId).note()).contains("does not match");
        assertThat(only(node(outcome, secondId)).status()).isEqualTo(StepStatus.NOT_ATTEMPTED);
    }

    // ---- the gates ------------------------------------------------------------

    @Test
    void aHighHazardMustBeAcknowledgedBeforeAnythingIsWritten() {
        // A catch-all match is a High hazard on every node it touches.
        declare("#", Map.of("maxSizeBytes", 10_485_760L));

        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        List<String> high = preview.plan().highHazardIds();
        assertThat(high).isNotEmpty();
        assertThat(preview.plan().hazards())
                .anyMatch(h -> h.kind() == Plan.HazardKind.BROAD_MATCH
                        && h.hazardClass().name().equals("HIGH"));

        BrokerConfigApplyRequest unacknowledged = new BrokerConfigApplyRequest(
                null, Set.of(), null, false, List.of(), preview.plan().planHash(), false, Set.of());
        assertThatThrownBy(() -> apply.apply(clusterId, unacknowledged))
                .isInstanceOf(HazardNotAcknowledgedException.class)
                .hasMessageContaining(high.get(0));
        verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), any());

        BrokerConfigApplyOutcome outcome = apply.apply(clusterId, confirmed(preview));
        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void aPlanThatChangedSinceThePreviewIsRefused() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));

        BrokerConfigApplyRequest stale =
                new BrokerConfigApplyRequest(null, Set.of(), null, false, List.of(), "not-the-hash", false, Set.of());
        assertThatThrownBy(() -> apply.apply(clusterId, stale))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("plan");
        verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), any());
    }

    @Test
    void aSecondApplyWhileOneHoldsTheClusterIsRefusedNotQueued() throws Exception {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());

        // Another instance is mid-apply: it holds the cluster's apply lock on its own
        // connection until told to let go.
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = new Thread(() -> lock.runIfHeld(clusterId, ClusterLock.Scope.CONFIG_APPLY, () -> {
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }));
        other.start();
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            BrokerConfigApplyRequest request = confirmed(preview);
            assertThatThrownBy(() -> apply.apply(clusterId, request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("apply");
            verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), any());
        } finally {
            release.countDown();
            other.join(10_000);
        }
    }

    // ---- the audit row ------------------------------------------------------

    @Test
    void oneAuditEventPerApplyCarriesTheNodeByStepDetail() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));

        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        apply.apply(clusterId, confirmed(preview));

        List<AuditEventEntity> events = auditEvents.findAll().stream()
                .filter(e -> clusterId.equals(e.getClusterId()))
                .filter(e -> BrokerConfigApplyService.AUDIT_APPLY.equals(e.getAction()))
                .toList();
        // The dry run is audited too, flagged as such.
        assertThat(events).hasSize(2);
        AuditEventEntity real =
                events.stream().filter(e -> !e.isDryRun()).findFirst().orElseThrow();
        assertThat(real.getTargetType()).isEqualTo("cluster");
        assertThat(real.getOutcome()).isEqualTo("SUCCESS");
        assertThat(real.getOutcomeDetail())
                .contains("a-first")
                .contains("b-second")
                .contains("APPLIED");
        assertThat(events.stream()
                        .filter(AuditEventEntity::isDryRun)
                        .findFirst()
                        .orElseThrow()
                        .getOutcome())
                .isEqualTo("SUCCESS");
    }

    // ---- drift ----------------------------------------------------------------

    @Test
    void driftReportsInSyncAfterAnApplyAndDriftedOnceANodeDiverges() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        apply.apply(clusterId, confirmed(apply.plan(clusterId, BrokerConfigApplyRequest.everything())));

        BrokerConfigDriftService.Report report = drift.evaluate(clusterId);
        assertThat(state(report, firstId)).isEqualTo(State.IN_SYNC);
        assertThat(state(report, secondId)).isEqualTo(State.IN_SYNC);
        assertThat(state(report, deadId)).isEqualTo(State.NOT_EVALUATED);

        // Someone changes the second node behind Rahdar's back.
        broker.get(secondId).put(MATCH, Map.of("maxSizeBytes", 1L));
        report = drift.evaluate(clusterId);
        assertThat(state(report, firstId)).isEqualTo(State.IN_SYNC);
        assertThat(state(report, secondId)).isEqualTo(State.DRIFTED);
        assertThat(report.nodes().stream()
                        .filter(n -> n.nodeId().equals(secondId))
                        .findFirst()
                        .orElseThrow()
                        .findings())
                .anyMatch(f -> f.kind() == Plan.FindingKind.DIVERGENT && MATCH.equals(f.key()));
    }

    @Test
    void anInSyncNodeRecordsWhyItAgrees() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        apply.apply(clusterId, confirmed(apply.plan(clusterId, BrokerConfigApplyRequest.everything())));

        // The apply itself is the strongest evidence, and it survives later evaluations.
        assertThat(basis(drift.evaluate(clusterId), firstId)).isEqualTo(Basis.VERIFIED_APPLY);
        assertThat(basis(drift.evaluate(clusterId), firstId)).isEqualTo(Basis.VERIFIED_APPLY);
        assertThat(report(drift.evaluate(clusterId), firstId).detail()).contains("read it back");
    }

    @Test
    void aDeclarationThatMatchesWithoutAnApplyIsObservedNotVerified() {
        // The broker already holds it; the declaration is written to agree with it.
        broker.computeIfAbsent(firstId, k -> new HashMap<>()).put(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        broker.computeIfAbsent(secondId, k -> new HashMap<>()).put(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));

        BrokerConfigDriftService.Report report = drift.evaluate(clusterId);
        assertThat(state(report, firstId)).isEqualTo(State.IN_SYNC);
        assertThat(basis(report, firstId)).isEqualTo(Basis.OBSERVED_MATCH);
        assertThat(report(report, firstId).detail()).contains("has not written");
    }

    @Test
    void anAdoptedDeclarationSaysSoRatherThanClaimingAVerifiedApply() {
        broker.computeIfAbsent(firstId, k -> new HashMap<>()).put(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        broker.computeIfAbsent(secondId, k -> new HashMap<>()).put(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        BrokerConfigDocument doc = new BrokerConfigDocument(
                1,
                List.of(),
                List.of(new AddressSettingDecl(MATCH, Map.of("maxSizeBytes", 10_485_760L))),
                List.of(),
                List.of(),
                List.of());
        config.save(clusterId, doc, null, "adopted", Source.ADOPT);

        BrokerConfigDriftService.Report report = drift.evaluate(clusterId);
        assertThat(basis(report, firstId)).isEqualTo(Basis.ADOPTED);
        assertThat(report(report, firstId).detail()).contains("no broker was written");
    }

    @Test
    void aVerificationMismatchMarksTheNodeDriftedWithoutWaitingForAnEvaluation() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        // The write reports success and changes nothing, so the read-back disagrees.
        doAnswer(inv -> null).when(ops).addAddressSettings(any(), anyString(), anyString(), any());

        BrokerConfigApplyOutcome outcome =
                apply.apply(clusterId, confirmed(apply.plan(clusterId, BrokerConfigApplyRequest.everything())));

        assertThat(only(node(outcome, firstId)).verified()).isEqualTo(Verification.MISMATCH);
        // Read the stored row, not a fresh evaluation: the alert condition reads this
        // table, and before this the row was only ever written by the evaluation that
        // follows an apply — so a mismatch went unrecorded whenever that was skipped or
        // threw. The apply writes it now; the evaluation afterwards refines the detail
        // with the findings it computed, which is why only the state is asserted here.
        assertThat(storedState(firstId)).isEqualTo(State.DRIFTED);
        assertThat(storedRow(firstId).basis()).isNull();
    }

    @Test
    void adoptingOverOpenDriftIsRefusedWithoutTheClusterName() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        apply.apply(clusterId, confirmed(apply.plan(clusterId, BrokerConfigApplyRequest.everything())));
        broker.get(secondId).put(MATCH, Map.of("maxSizeBytes", 1L));
        drift.evaluate(clusterId);

        BrokerConfigDocument asRunning = new BrokerConfigDocument(
                1,
                List.of(),
                List.of(new AddressSettingDecl(MATCH, Map.of("maxSizeBytes", 1L))),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> config.save(clusterId, asRunning, null, "adopt", Source.ADOPT, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("without writing to any broker");

        // Named, it goes through: the operator has said the cluster is what is right.
        String name = clusters.findById(clusterId).orElseThrow().getName();
        assertThat(config.save(clusterId, asRunning, null, "adopt", Source.ADOPT, name)
                        .source())
                .isEqualTo(Source.ADOPT);
    }

    @Test
    void anEditIsNotHeldToTheAdoptionConfirmation() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        apply.apply(clusterId, confirmed(apply.plan(clusterId, BrokerConfigApplyRequest.everything())));
        broker.get(secondId).put(MATCH, Map.of("maxSizeBytes", 1L));
        drift.evaluate(clusterId);

        // An ordinary edit may still change the declaration while drift is open; only
        // adoption — which closes findings by copying the cluster — needs the name.
        BrokerConfigDocument edited = new BrokerConfigDocument(
                1,
                List.of(),
                List.of(new AddressSettingDecl(MATCH, Map.of("maxSizeBytes", 20_971_520L))),
                List.of(),
                List.of(),
                List.of());
        assertThat(config.save(clusterId, edited, null, "edit", Source.EDIT).revision())
                .isEqualTo(2);
    }

    @Test
    void adoptionPreviewNamesTheFindingsItWouldClose() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        apply.apply(clusterId, confirmed(apply.plan(clusterId, BrokerConfigApplyRequest.everything())));
        broker.get(secondId).put(MATCH, Map.of("maxSizeBytes", 1L));
        drift.evaluate(clusterId);

        when(ops.readForAdoption(any(), any(), anyString())).thenAnswer(inv -> {
            UUID nodeId = inv.getArgument(1);
            return new ObservedNodeConfig(
                    nodeId,
                    inv.getArgument(2),
                    true,
                    Map.of(),
                    Map.of(),
                    broker.getOrDefault(nodeId, Map.of()),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    null);
        });

        BrokerConfigService.Adoption adoption = config.adopt(clusterId);

        assertThat(adoption.closes()).isNotEmpty();
        assertThat(adoption.closes()).allMatch(c -> c.nodeId().equals(secondId));
        assertThat(adoption.notes()).anyMatch(n -> n.contains("no broker will be written"));
    }

    // ---- refusals the UI must not be the only one making ----------------------

    @Test
    void aConfigManagedClusterIsRefusedByTheEngineNotOnlyByTheScreen() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        config.configure(clusterId, ApplyMode.CONFIG_MANAGED, false, List.of());

        // A dry run still works: a plan is a comparison, and a config-managed cluster
        // is exactly the one whose drift an operator needs to read.
        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());
        assertThat(preview.plan().stepCount()).isEqualTo(2);

        BrokerConfigApplyRequest request = confirmed(preview);
        assertThatThrownBy(() -> apply.apply(clusterId, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("managed outside Rahdar");
        verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), any());
    }

    @Test
    void aSplitBrainClusterIsRefusedBeforeAnythingIsWritten() {
        declare(MATCH, Map.of("maxSizeBytes", 10_485_760L));
        BrokerConfigApplyOutcome preview = apply.plan(clusterId, BrokerConfigApplyRequest.everything());

        BrokerNodeEntity first = nodes.findById(firstId).orElseThrow();
        first.recordSplitBrain(SplitBrainStatus.CRITICAL);
        nodes.save(first);
        BrokerConfigApplyRequest request = confirmed(preview);
        assertThatThrownBy(() -> apply.apply(clusterId, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("split-brain");
        verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), any());
    }

    // ---- helpers ----------------------------------------------------------

    private static StepApply only(NodeApply n) {
        assertThat(n.steps()).hasSize(1);
        return n.steps().get(0);
    }

    private static State state(BrokerConfigDriftService.Report r, UUID nodeId) {
        return report(r, nodeId).state();
    }

    private static Basis basis(BrokerConfigDriftService.Report r, UUID nodeId) {
        return report(r, nodeId).basis();
    }

    private static BrokerConfigDriftService.NodeReport report(BrokerConfigDriftService.Report r, UUID nodeId) {
        return r.nodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow();
    }

    /** What the drift table holds, which is what the CONFIG_DRIFT condition reads. */
    private State storedState(UUID nodeId) {
        return storedRow(nodeId).state();
    }

    private BrokerConfigNodeStateEntity storedRow(UUID nodeId) {
        return nodeStates.findByClusterId(clusterId).stream()
                .filter(s -> s.getNodeId().equals(nodeId))
                .findFirst()
                .orElseThrow();
    }
}
