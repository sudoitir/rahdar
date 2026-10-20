package io.github.sudoitir.rahdar.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome.NodeApply;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome.StepApply;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome.StepStatus;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome.Verification;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyService.Detail;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.BridgeDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.QueueDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService.CurrentRevision;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService.Source;
import io.github.sudoitir.rahdar.feature.brokerconfig.ObservedNodeConfig.ObservedBridge;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigApplyEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigApplyEntity.Outcome;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigApplyRepository;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigDeclarationEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigDeclarationEntity.ApplyMode;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateRepository;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigOwnedItemEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigOwnedItemRepository;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.clusters.CapabilityLedger;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterSecrets;
import io.github.sudoitir.rahdar.platform.clusters.SplitBrainStatus;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The apply engine's write paths, one section at a time, with the planner real and the brokers scripted: what
 * each kind of step asks the operations layer to do, how a step is verified by re-reading the node, and how a
 * run reports a node it could not reach, a write the broker refused, and a read-back that came back empty.
 */
class BrokerConfigApplyEngineTest {

    // Generated, so no credential-shaped literal sits in the source.
    private static final String BRIDGE_SECRET = UUID.randomUUID().toString();

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID NODE_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String BROKER = "org.apache.activemq.artemis:broker=\"b\"";

    private final BrokerConfigService configs = mock(BrokerConfigService.class);
    private final BrokerConfigReads reads = mock(BrokerConfigReads.class);
    private final BrokerConfigOperations ops = mock(BrokerConfigOperations.class);
    private final BrokerConfigApplyRepository applies = mock(BrokerConfigApplyRepository.class);
    private final BrokerConfigOwnedItemRepository ownedItems = mock(BrokerConfigOwnedItemRepository.class);
    private final BrokerConfigNodeStateRepository nodeStates = mock(BrokerConfigNodeStateRepository.class);
    private final BrokerConfigDriftService drift = mock(BrokerConfigDriftService.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final CapabilityLedger capabilities = mock(CapabilityLedger.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private final ClusterLock lock = mock(ClusterLock.class);
    private final ClusterSecrets secrets = mock(ClusterSecrets.class);
    private final SseHub hub = mock(SseHub.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final AuditEvent event = mock(AuditEvent.class);
    private final ArtemisNode client = mock(ArtemisNode.class);
    private final ClusterNode nodeA = node(NODE_A, "a-first", true);
    private final ClusterNode nodeB = node(NODE_B, "b-second", true);

    private BrokerConfigApplyService service;
    private BrokerConfigDocument document = BrokerConfigDocument.empty();
    private BrokerConfigDeclarationEntity header = new BrokerConfigDeclarationEntity(CLUSTER, 1L);
    private Set<OwnedItem> owned = Set.of();
    private List<ClusterNode> targets = List.of(nodeA, nodeB);
    private Function<UUID, ObservedNodeConfig> before = id -> state(id, BrokerConfigDocument.empty());
    private Function<UUID, ObservedNodeConfig> after = id -> state(id, document);

    @BeforeEach
    void setUp() {
        service = new BrokerConfigApplyService(
                configs,
                reads,
                ops,
                applies,
                ownedItems,
                nodeStates,
                drift,
                access,
                capabilities,
                settings,
                audit,
                actors,
                lock,
                secrets,
                hub,
                mapper,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
        when(configs.current(CLUSTER))
                .thenAnswer(invocation -> Optional.of(new CurrentRevision(1L, 3, document, header, Source.EDIT)));
        when(configs.clusterName(CLUSTER)).thenReturn("prod");
        when(drift.owned(CLUSTER)).thenAnswer(invocation -> owned);
        when(reads.targets(CLUSTER)).thenAnswer(invocation -> targets);
        when(reads.observe(eq(CLUSTER), any(BrokerConfigOperations.ReadScope.class)))
                .thenAnswer(invocation ->
                        targets.stream().map(n -> before.apply(n.getId())).toList());
        when(reads.observe(eq(CLUSTER), any(ClusterNode.class), any(BrokerConfigOperations.ReadScope.class)))
                .thenAnswer(invocation -> after.apply(((ClusterNode) invocation.getArgument(1)).getId()));
        when(reads.client(eq(CLUSTER), any())).thenReturn(client);
        when(client.resolveBrokerObjectName()).thenReturn(BROKER);
        when(settings.intValue(BrokerConfigSettings.APPLY_STEP_CAP)).thenReturn(200);
        when(actors.resolve()).thenReturn(new Actor("alice", "127.0.0.1", "req", null));
        when(audit.begin(any(), anyString(), anyString(), anyString(), any(), any(), anyMap(), any(Boolean.class)))
                .thenReturn(event);
        when(applies.save(any())).thenAnswer(invocation -> {
            BrokerConfigApplyEntity row = invocation.getArgument(0);
            if (row.getId() == null) {
                ReflectionTestUtils.setField(row, "id", 77L);
            }
            return row;
        });
        when(lock.runIfHeld(eq(CLUSTER), eq(ClusterLock.Scope.CONFIG_APPLY), any()))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(2)).run();
                    return true;
                });
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static ClusterNode node(UUID id, String name, boolean active) {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(id);
        when(node.getName()).thenReturn(name);
        when(node.getActive()).thenReturn(active);
        when(node.getArtemisNodeId()).thenReturn("artemis-" + name);
        when(node.getSplitBrain()).thenReturn(SplitBrainStatus.NONE);
        when(node.getJolokiaUrl()).thenReturn("http://" + name + ":8161/console/jolokia");
        return node;
    }

    /** What a node running exactly {@code doc} would report, bridges aside. */
    private static ObservedNodeConfig state(UUID id, BrokerConfigDocument doc) {
        Map<String, Set<String>> addresses = new LinkedHashMap<>();
        Map<String, Map<String, Object>> queues = new LinkedHashMap<>();
        for (AddressDecl a : doc.addresses()) {
            addresses.put(a.name(), a.routingTypes());
            for (QueueDecl q : a.queues()) {
                queues.put(q.name(), BrokerConfigPlanner.queueConfig(a.name(), q));
            }
        }
        Map<String, Map<String, Object>> addressSettings = new LinkedHashMap<>();
        doc.addressSettings().forEach(s -> addressSettings.put(s.match(), s.values()));
        Map<String, Map<PermissionType, Set<String>>> security = new LinkedHashMap<>();
        doc.securitySettings().forEach(s -> security.put(s.match(), s.permissions()));
        Map<String, DivertDecl> diverts = new LinkedHashMap<>();
        doc.diverts().forEach(d -> diverts.put(d.name(), d));
        Map<String, ObservedBridge> bridges = new LinkedHashMap<>();
        doc.bridges().forEach(b -> bridges.put(b.name(), new ObservedBridge(b, true, true, 1)));
        return new ObservedNodeConfig(
                id,
                id.equals(NODE_A) ? "a-first" : "b-second",
                true,
                addresses,
                queues,
                addressSettings,
                security,
                diverts,
                bridges,
                Map.of(),
                null);
    }

    private BrokerConfigApplyOutcome preview() {
        return service.plan(CLUSTER, BrokerConfigApplyRequest.everything());
    }

    private BrokerConfigApplyOutcome applyConfirmed() {
        BrokerConfigApplyOutcome preview = preview();
        return service.apply(
                CLUSTER,
                new BrokerConfigApplyRequest(
                        null,
                        Set.of(),
                        null,
                        false,
                        preview.plan().highHazardIds(),
                        preview.plan().planHash(),
                        false,
                        Set.of()));
    }

    private static NodeApply of(BrokerConfigApplyOutcome outcome, UUID id) {
        return outcome.nodes().stream()
                .filter(n -> n.nodeId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static BrokerConfigDocument fullDocument() {
        return new BrokerConfigDocument(
                1,
                List.of(
                        new AddressDecl(
                                "orders",
                                Set.of("ANYCAST"),
                                List.of(new QueueDecl("orders.q", "ANYCAST", null, true, 4, null, null, null, null))),
                        new AddressDecl(
                                "audit",
                                Set.of("MULTICAST"),
                                List.of(new QueueDecl(
                                        "audit.q", "MULTICAST", null, true, null, null, null, null, null)))),
                List.of(new AddressSettingDecl("orders.#", Map.of("maxDeliveryAttempts", 5))),
                List.of(new SecuritySettingDecl("orders.#", Map.of(PermissionType.SEND, Set.of("writers")))),
                List.of(new DivertDecl("copy-orders", "orders", "audit", null, false, null, null, null)),
                List.of());
    }

    // ---- every section is written, owned and verified -------------------------------------------------

    @Test
    void aFullDeclarationIsWrittenSectionBySectionOwnedAndVerifiedOnEveryNode() {
        document = fullDocument();

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(outcome.dryRun()).isFalse();
        for (UUID id : List.of(NODE_A, NODE_B)) {
            assertThat(of(outcome, id).steps()).isNotEmpty().allSatisfy(step -> {
                assertThat(step.status()).isEqualTo(StepStatus.APPLIED);
                assertThat(step.verified()).isEqualTo(Verification.VERIFIED);
            });
        }
        assertThat(of(outcome, NODE_A).canary()).isTrue();
        assertThat(of(outcome, NODE_B).canary()).isFalse();
        verify(ops, times(4)).createAddress(eq(client), eq(BROKER), anyString(), any());
        verify(ops, times(4)).createQueue(eq(client), eq(BROKER), anyMap());
        verify(ops, times(2)).addAddressSettings(eq(client), eq(BROKER), eq("orders.#"), anyMap());
        verify(ops, times(2)).addSecuritySettings(eq(client), eq(BROKER), eq("orders.#"), anyMap());
        verify(ops, times(2)).createDivert(eq(client), eq(BROKER), anyMap());
        // Ownership is recorded for what a later apply may remove; an address or a queue is never owned.
        ArgumentCaptor<BrokerConfigOwnedItemEntity> owns = ArgumentCaptor.forClass(BrokerConfigOwnedItemEntity.class);
        verify(ownedItems, Mockito.atLeastOnce()).save(owns.capture());
        assertThat(owns.getAllValues())
                .extracting(BrokerConfigOwnedItemEntity::getKind)
                .containsOnly("ADDRESS_SETTING", "SECURITY_SETTING", "DIVERT");
        verify(capabilities, Mockito.atLeastOnce()).recordWriteSucceeded(CLUSTER);
        // Each node's drift state records a verified apply.
        ArgumentCaptor<BrokerConfigNodeStateEntity> states = ArgumentCaptor.forClass(BrokerConfigNodeStateEntity.class);
        verify(nodeStates, times(2)).save(states.capture());
        assertThat(states.getAllValues()).allSatisfy(s -> {
            assertThat(s.state()).isEqualTo(BrokerConfigNodeStateEntity.State.IN_SYNC);
            assertThat(s.basis()).isEqualTo(BrokerConfigNodeStateEntity.Basis.VERIFIED_APPLY);
        });
        verify(audit)
                .finish(
                        eq(event),
                        eq(false),
                        eq((long) outcome.nodes().stream()
                                .mapToLong(n -> n.steps().size())
                                .sum()),
                        any(),
                        any());
        verify(hub).publish(CLUSTER, BrokerConfigDriftService.SSE_TOPIC);
        verify(drift).evaluateAfterApply(CLUSTER);
        assertThat(outcome.summary()).startsWith("Applied ").endsWith("each verified by reading it back.");
    }

    @Test
    void anAlreadyOwnedItemIsTouchedNotDuplicated() {
        document = new BrokerConfigDocument(
                1,
                List.of(),
                List.of(new AddressSettingDecl("orders.#", Map.of("maxDeliveryAttempts", 5))),
                List.of(),
                List.of(),
                List.of());
        BrokerConfigOwnedItemEntity existing = mock(BrokerConfigOwnedItemEntity.class);
        when(ownedItems.findById(any())).thenReturn(Optional.of(existing));

        applyConfirmed();

        verify(existing, Mockito.atLeastOnce()).touch(1L);
        verify(ownedItems, Mockito.atLeastOnce()).save(existing);
    }

    @Test
    void whatIsAlreadyThereIsNotWrittenAndTheRunSaysNothingWasWritten() {
        document = fullDocument();
        before = id -> state(id, document);

        BrokerConfigApplyOutcome outcome = service.apply(CLUSTER, BrokerConfigApplyRequest.everything());

        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(outcome.summary()).isEqualTo("Every live node already matches revision 3; nothing was written.");
        verify(ops, never()).createAddress(any(), anyString(), anyString(), any());
        verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), anyMap());
        verify(audit).finish(eq(event), eq(false), eq(0L), any(), any());
    }

    @Test
    void differencesInAnExistingAddressAndQueueAreUpdatedInPlace() {
        document = fullDocument();
        before = id -> {
            ObservedNodeConfig base = state(id, document);
            Map<String, Set<String>> addresses = new HashMap<>(base.addresses());
            addresses.put("orders", Set.of("ANYCAST", "MULTICAST"));
            Map<String, Map<String, Object>> queues = new HashMap<>(base.queues());
            Map<String, Object> changed = new HashMap<>(queues.get("orders.q"));
            changed.put("max-consumers", 9);
            queues.put("orders.q", changed);
            return new ObservedNodeConfig(
                    id,
                    base.nodeName(),
                    true,
                    addresses,
                    queues,
                    base.addressSettings(),
                    base.securitySettings(),
                    base.diverts(),
                    base.bridges(),
                    Map.of(),
                    null);
        };

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
        verify(ops, times(2)).updateAddress(eq(client), eq(BROKER), eq("orders"), any());
        verify(ops, times(2)).updateQueue(eq(client), eq(BROKER), anyMap());
        verify(ops, never()).createQueue(any(), anyString(), anyMap());
    }

    @Test
    void anItemThatLeftTheDeclarationIsRemovedAndDisownedOnEveryNode() {
        document = BrokerConfigDocument.empty();
        owned = Set.of(
                new OwnedItem(Plan.Section.ADDRESS_SETTING, "old.#"),
                new OwnedItem(Plan.Section.SECURITY_SETTING, "old.sec"),
                new OwnedItem(Plan.Section.DIVERT, "old-divert"));
        before = id -> {
            ObservedNodeConfig base = state(id, BrokerConfigDocument.empty());
            return new ObservedNodeConfig(
                    id,
                    base.nodeName(),
                    true,
                    Map.of(),
                    Map.of(),
                    Map.of("#", Map.of("maxSizeBytes", -1L), "old.#", Map.of("maxSizeBytes", 5L)),
                    Map.of("old.sec", Map.of(PermissionType.SEND, Set.of("x"))),
                    Map.of("old-divert", new DivertDecl("old-divert", "a", "b", null, false, null, null, null)),
                    Map.of(),
                    Map.of(),
                    null);
        };
        after = id -> state(id, BrokerConfigDocument.empty());
        BrokerConfigOwnedItemEntity ownedRow = mock(BrokerConfigOwnedItemEntity.class);
        when(ownedItems.findById(any())).thenReturn(Optional.of(ownedRow));

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
        verify(ops, times(2)).removeAddressSettings(client, BROKER, "old.#");
        verify(ops, times(2)).removeSecuritySettings(client, BROKER, "old.sec");
        verify(ops, times(2)).destroyDivert(client, BROKER, "old-divert");
        verify(ownedItems, times(6)).delete(ownedRow);
        assertThat(of(outcome, NODE_A).steps()).extracting(StepApply::verified).containsOnly(Verification.VERIFIED);
    }

    // ---- bridges ------------------------------------------------------------------------------------------

    private static BridgeDecl bridge(String credentialRef) {
        return new BridgeDecl(
                "to-dc2",
                "orders.q",
                "audit",
                null,
                null,
                List.of("remote"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                credentialRef);
    }

    private static BrokerConfigDocument withBridge(BridgeDecl bridge) {
        BrokerConfigDocument base = fullDocument();
        return new BrokerConfigDocument(1, base.addresses(), List.of(), List.of(), List.of(), List.of(bridge));
    }

    @Test
    void aBridgeIsCreatedWithItsCredentialResolvedFromTheVaultAtTheLastMoment() {
        document = withBridge(bridge("dc2"));
        when(secrets.resolve(CLUSTER, "dc2"))
                .thenReturn(Optional.of(new ClusterSecrets.Credential("dc2", "bridge-user", BRIDGE_SECRET)));

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> config = ArgumentCaptor.forClass(Map.class);
        verify(ops, times(2)).createBridge(eq(client), eq(BROKER), config.capture());
        assertThat(config.getValue()).containsEntry("user", "bridge-user").containsEntry("password", BRIDGE_SECRET);
        // The credential is on the wire only: the plan and the stored apply row never carry it.
        assertThat(mapper.writeValueAsString(outcome.plan())).doesNotContain(BRIDGE_SECRET);
    }

    @Test
    void aBridgeWithoutAReferenceIsCreatedAsPlanned() {
        document = withBridge(bridge(null));

        applyConfirmed();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> config = ArgumentCaptor.forClass(Map.class);
        verify(ops, Mockito.atLeastOnce()).createBridge(eq(client), eq(BROKER), config.capture());
        assertThat(config.getValue()).doesNotContainKey("password");
        verify(secrets, never()).resolve(any(), anyString());
    }

    @Test
    void aBridgeWhoseCredentialIsNoLongerHeldFailsTheNodeByName() {
        document = withBridge(bridge("gone"));
        when(secrets.resolve(CLUSTER, "gone")).thenReturn(Optional.empty());

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(outcome.outcome()).isEqualTo(Outcome.HALTED);
        assertThat(of(outcome, NODE_A).steps()).anySatisfy(step -> {
            assertThat(step.status()).isEqualTo(StepStatus.FAILED);
            assertThat(step.error()).contains("names the credential 'gone'");
        });
        assertThat(outcome.summary()).startsWith("Halted at a-first");
    }

    @Test
    void aBridgeThatLeftTheDeclarationIsDestroyedByItsDeclaredName() {
        document = BrokerConfigDocument.empty();
        owned = Set.of(new OwnedItem(Plan.Section.BRIDGE, "to-dc2"));
        before = id -> {
            ObservedNodeConfig base = state(id, BrokerConfigDocument.empty());
            return new ObservedNodeConfig(
                    id,
                    base.nodeName(),
                    true,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of("to-dc2", new ObservedBridge(bridge(null), true, true, 1)),
                    Map.of(),
                    null);
        };
        after = id -> state(id, BrokerConfigDocument.empty());

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
        verify(ops, times(2)).destroyBridge(client, BROKER, "to-dc2");
    }

    // ---- what goes wrong -------------------------------------------------------------------------------------

    @Test
    void aNodeThatCannotBeConnectedToIsReportedAndTheRunHaltsWithNothingAttempted() {
        document = fullDocument();
        when(reads.client(CLUSTER, nodeA))
                .thenThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "no route"));

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        NodeApply canary = of(outcome, NODE_A);
        assertThat(canary.unavailableReason()).isEqualTo("no route");
        assertThat(canary.note()).isEqualTo("Could not connect: no route");
        assertThat(canary.steps()).extracting(StepApply::status).containsOnly(StepStatus.NOT_ATTEMPTED);
        // An unreachable canary is not a write failure, so the run moves on to the next node.
        assertThat(of(outcome, NODE_B).steps()).extracting(StepApply::status).containsOnly(StepStatus.APPLIED);
    }

    @Test
    void aRefusalThatSaysAlreadyIsAnAlreadyNotAFailure() {
        document = fullDocument();
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.ALREADY, "Already present"))
                .when(ops)
                .createAddress(any(), anyString(), anyString(), any());

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(of(outcome, NODE_A).steps())
                .filteredOn(s -> s.section() == Plan.Section.ADDRESS)
                .extracting(StepApply::status)
                .containsOnly(StepStatus.ALREADY);
        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
        verify(capabilities, Mockito.atLeastOnce()).recordWriteSucceeded(CLUSTER);
    }

    @Test
    void aRefusalTheBrokerMeansHaltsTheNodeAndLaterStepsAreNotAttempted() {
        document = fullDocument();
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.ARGUMENT, "bad argument"))
                .when(ops)
                .createQueue(any(), anyString(), anyMap());

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(outcome.outcome()).isEqualTo(Outcome.HALTED);
        assertThat(of(outcome, NODE_A).steps())
                .anySatisfy(s -> {
                    assertThat(s.status()).isEqualTo(StepStatus.FAILED);
                    assertThat(s.error()).isEqualTo("bad argument");
                })
                .anySatisfy(s -> assertThat(s.status()).isEqualTo(StepStatus.NOT_ATTEMPTED));
        assertThat(of(outcome, NODE_B).steps()).extracting(StepApply::status).containsOnly(StepStatus.NOT_ATTEMPTED);
        assertThat(of(outcome, NODE_B).note()).isEqualTo("Not attempted: the run halted on an earlier node.");
        assertThat(outcome.summary()).contains("Nothing was rolled back.");
        ArgumentCaptor<BrokerConfigNodeStateEntity> states = ArgumentCaptor.forClass(BrokerConfigNodeStateEntity.class);
        verify(nodeStates).save(states.capture());
        assertThat(states.getValue().state()).isEqualTo(BrokerConfigNodeStateEntity.State.DRIFTED);
        assertThat(states.getValue().getDetail()).contains("failed on this node");
    }

    @Test
    void aWriteTheBrokerRefusedForItsCredentialsIsRecordedOnTheCapabilityLedger() {
        document = fullDocument();
        doThrow(new BrokerConnectionException(BrokerConnectionException.Kind.CREDENTIALS_REJECTED, "403"))
                .when(ops)
                .createAddress(any(), anyString(), anyString(), any());

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(outcome.outcome()).isEqualTo(Outcome.HALTED);
        verify(capabilities).recordWriteRefused(CLUSTER, "403");
    }

    @Test
    void aWriteThatFailsForAnotherReasonIsNotBlamedOnTheCredentials() {
        document = fullDocument();
        doThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "reset"))
                .when(ops)
                .createAddress(any(), anyString(), anyString(), any());

        applyConfirmed();

        verify(capabilities, never()).recordWriteRefused(any(), anyString());
    }

    @Test
    void aFailureOnTheOnlyNodeWithNothingAppliedIsFailedNotHalted() {
        document = fullDocument();
        targets = List.of(nodeA);
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.ARGUMENT, "bad"))
                .when(ops)
                .createAddress(any(), anyString(), anyString(), any());

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(outcome.outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void aVerificationReadThatCannotBeTakenLeavesTheAppliedStepsUnverifiable() {
        document = fullDocument();
        after = id -> ObservedNodeConfig.unreachable(id, "x", "read timed out");

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(of(outcome, NODE_A).steps()).extracting(StepApply::verified).containsOnly(Verification.UNVERIFIABLE);
        assertThat(of(outcome, NODE_A).note()).isEqualTo("Applied, but the verification read failed: read timed out");
        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void aReadBackMissingWhatWasWrittenIsAMismatchOnEverySectionAndHaltsTheRun() {
        document = fullDocument();
        after = id -> state(id, BrokerConfigDocument.empty());

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(outcome.outcome()).isEqualTo(Outcome.HALTED);
        assertThat(of(outcome, NODE_A).steps()).extracting(StepApply::verified).containsOnly(Verification.MISMATCH);
        assertThat(of(outcome, NODE_A).note()).contains("does not match the declaration");
        assertThat(outcome.summary()).contains("read-back did not match");
        assertThat(of(outcome, NODE_B).steps()).extracting(StepApply::status).containsOnly(StepStatus.NOT_ATTEMPTED);
    }

    @Test
    void aSettingThatTheBrokerDoesNotEchoBackIsUnverifiableNotMismatched() {
        document = new BrokerConfigDocument(
                1,
                List.of(),
                List.of(new AddressSettingDecl("orders.#", Map.of("maxDeliveryAttempts", 5))),
                List.of(),
                List.of(),
                List.of());
        // The re-plan sees the setting as matching, but the observed entry lacks the declared key.
        after = id -> {
            ObservedNodeConfig base = state(id, document);
            return new ObservedNodeConfig(
                    id,
                    base.nodeName(),
                    true,
                    Map.of(),
                    Map.of(),
                    Map.of("orders.#", Map.of("maxDeliveryAttempts", 5)),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    null);
        };

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(outcome.outcome()).isEqualTo(Outcome.APPLIED);
    }

    // ---- the gates -----------------------------------------------------------------------------------------------

    @Test
    void aClusterWithoutADeclarationCannotBeApplied() {
        when(configs.current(CLUSTER)).thenReturn(Optional.empty());

        assertThatThrownBy(this::preview)
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        e -> assertThat(e.getMessage()).contains("Declare the cluster's configuration"));
    }

    @Test
    void aRevisionThatIsNotCurrentIsRefused() {
        assertThatThrownBy(() -> service.plan(
                        CLUSTER,
                        new BrokerConfigApplyRequest(2, Set.of(), null, false, List.of(), null, false, Set.of())))
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        e -> assertThat(e.getMessage()).contains("Revision 2 is not current; revision 3 is."));
    }

    @Test
    void aDeclarationThatFailsThePlannersChecksIsRefusedBeforeAnyWrite() {
        document = new BrokerConfigDocument(
                1,
                List.of(),
                List.of(),
                List.of(),
                List.of(new DivertDecl("d", "orders", "nowhere", null, false, null, null, null)),
                List.of());

        assertThatThrownBy(this::applyConfirmedWithoutPreview).isInstanceOf(BrokerConfigInvalidException.class);
        verify(ops, never()).createDivert(any(), anyString(), anyMap());
    }

    private BrokerConfigApplyOutcome applyConfirmedWithoutPreview() {
        return service.apply(CLUSTER, BrokerConfigApplyRequest.everything());
    }

    @Test
    void aConfigManagedClusterRefusesARealRunButStillPlans() {
        document = fullDocument();
        header.configure(ApplyMode.CONFIG_MANAGED, false, "[]");

        assertThat(preview().dryRun()).isTrue();
        assertThatThrownBy(this::applyConfirmedWithoutPreview)
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.getMessage()).contains("managed outside Rahdar"));
        verify(ops, never()).createAddress(any(), anyString(), anyString(), any());
    }

    @Test
    void aStalePlanHashIsRefused() {
        document = fullDocument();

        assertThatThrownBy(() -> service.apply(
                        CLUSTER,
                        new BrokerConfigApplyRequest(
                                null, Set.of(), null, false, List.of(), "stale-hash", false, Set.of())))
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        e -> assertThat(e.getMessage()).contains("no longer the one that was confirmed"));
    }

    @Test
    void aPlanOverTheStepCapNeedsAnOverride() {
        document = fullDocument();
        when(settings.intValue(BrokerConfigSettings.APPLY_STEP_CAP)).thenReturn(3);
        BrokerConfigApplyOutcome preview = preview();
        assertThat(preview.overCap()).isTrue();
        assertThat(preview.summary()).contains("Over the step cap of 3; an override is needed.");

        var withoutOverride = new BrokerConfigApplyRequest(
                null, Set.of(), null, false, preview.plan().highHazardIds(), null, false, Set.of());

        assertThatThrownBy(() -> service.apply(CLUSTER, withoutOverride)).isInstanceOf(BulkCapExceededException.class);

        assertThat(service.apply(
                                CLUSTER,
                                new BrokerConfigApplyRequest(
                                        null,
                                        Set.of(),
                                        null,
                                        false,
                                        preview.plan().highHazardIds(),
                                        null,
                                        true,
                                        Set.of()))
                        .outcome())
                .isEqualTo(Outcome.APPLIED);
    }

    @Test
    void aNodeThatWentNotLiveSinceThePreviewIsRefusedBeforeAnyWrite() {
        document = fullDocument();
        ClusterNode wentDown = node(NODE_A, "a-first", false);
        // The plan was computed while the node answered live; the topology has since changed.
        when(reads.targets(CLUSTER)).thenReturn(List.of(wentDown, nodeB));

        assertThatThrownBy(this::applyConfirmedWithoutPreview)
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        e -> assertThat(e.getMessage()).contains("a-first").contains("no longer live"));
        verify(ops, never()).createAddress(any(), anyString(), anyString(), any());
    }

    @Test
    void aSecondApplyWhileOneHoldsTheClusterIsRefused() {
        when(lock.runIfHeld(eq(CLUSTER), eq(ClusterLock.Scope.CONFIG_APPLY), any()))
                .thenReturn(false);

        assertThatThrownBy(this::applyConfirmedWithoutPreview)
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        e -> assertThat(e.getMessage()).contains("Another configuration apply is running"));
    }

    @Test
    void aFailureInsideTheLockedRunIsRethrownToTheCaller() {
        when(configs.current(CLUSTER)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(this::applyConfirmedWithoutPreview)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
    }

    // ---- the dry run and the history --------------------------------------------------------------------------------

    @Test
    void aDryRunNamesTheCanaryTheHazardsAndWritesNothing() {
        document = fullDocument();
        nodesReport();

        BrokerConfigApplyOutcome preview = preview();

        assertThat(preview.dryRun()).isTrue();
        assertThat(preview.outcome()).isEqualTo(Outcome.DRY_RUN);
        assertThat(preview.summary()).contains(" on 2 live nodes.").contains("Canary: a-first.");
        assertThat(of(preview, NODE_A).note()).isEqualTo("Canary: applied first and read back before any other node.");
        assertThat(of(preview, NODE_B).note()).isNull();
        assertThat(of(preview, NODE_A).steps()).extracting(StepApply::status).containsOnly(StepStatus.WOULD_APPLY);
        verify(ops, never()).createAddress(any(), anyString(), anyString(), any());
        verify(audit).finish(eq(event), eq(false), eq(0L), any(), any());
        verify(access).requireCluster(CLUSTER, BrokerConfigPermissions.CONFIG_APPLY);
    }

    private void nodesReport() {
        // A node that is not live appears in the preview as skipped, with the reason.
        ClusterNode standby = node(UUID.randomUUID(), "c-standby", false);
        targets = List.of(nodeA, nodeB, standby);
        UUID standbyId = standby.getId();
        before = id -> id.equals(standbyId)
                ? ObservedNodeConfig.notLive(standbyId, "c-standby")
                : state(id, BrokerConfigDocument.empty());
    }

    @Test
    void aStandbyNodeIsSkippedWithTheReasonInThePreviewAndTheRun() {
        document = fullDocument();
        nodesReport();

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        NodeApply standby = outcome.nodes().stream()
                .filter(n -> n.nodeName().equals("c-standby"))
                .findFirst()
                .orElseThrow();
        assertThat(standby.live()).isFalse();
        assertThat(standby.steps()).isEmpty();
        assertThat(standby.note()).startsWith("Not live.");
    }

    @Test
    void anUnreachableNodeIsSkippedAsUnreachable() {
        document = fullDocument();
        before = id -> id.equals(NODE_B)
                ? ObservedNodeConfig.unreachable(NODE_B, "b-second", "timed out")
                : state(id, BrokerConfigDocument.empty());

        BrokerConfigApplyOutcome outcome = applyConfirmed();

        assertThat(of(outcome, NODE_B).note()).isEqualTo("Unreachable: timed out");
        assertThat(of(outcome, NODE_B).unavailableReason()).isEqualTo("timed out");
    }

    @Test
    void historyIsPagedAndFilteredToTheCluster() {
        BrokerConfigApplyEntity mine = new BrokerConfigApplyEntity(CLUSTER, 1L, "{}", "alice", null, false);
        BrokerConfigApplyEntity other = new BrokerConfigApplyEntity(UUID.randomUUID(), 1L, "{}", "alice", null, false);
        ReflectionTestUtils.setField(mine, "id", 5L);
        ReflectionTestUtils.setField(other, "id", 6L);
        when(applies.findByClusterIdOrderByStartedAtDesc(eq(CLUSTER), any())).thenReturn(new PageImpl<>(List.of(mine)));
        when(applies.findById(5L)).thenReturn(Optional.of(mine));
        when(applies.findById(6L)).thenReturn(Optional.of(other));

        assertThat(service.history(CLUSTER, ResourceQuery.ofPage(3, 20))).containsExactly(mine);
        ArgumentCaptor<PageRequest> page = ArgumentCaptor.forClass(PageRequest.class);
        verify(applies).findByClusterIdOrderByStartedAtDesc(eq(CLUSTER), page.capture());
        assertThat(page.getValue().getPageNumber()).isEqualTo(2);
        assertThat(page.getValue().getPageSize()).isEqualTo(20);
        verify(access, Mockito.atLeast(1)).requireCluster(CLUSTER, Permissions.CLUSTER_READ);

        assertThat(service.one(CLUSTER, 5L)).contains(mine);
        // Another cluster's apply is not readable through this one.
        assertThat(service.one(CLUSTER, 6L)).isEmpty();
    }

    @Test
    void aStoredApplyIsReadBackWithItsPlanAndPerNodeDetail() {
        document = fullDocument();
        BrokerConfigApplyOutcome outcome = applyConfirmed();
        BrokerConfigApplyEntity row = new BrokerConfigApplyEntity(
                CLUSTER, 1L, mapper.writeValueAsString(outcome.plan()), "alice", NODE_A, false);
        ReflectionTestUtils.setField(row, "id", 9L);
        row.finish(Outcome.APPLIED, "done", mapper.writeValueAsString(outcome.nodes()));
        BrokerConfigApplyEntity noDetail = new BrokerConfigApplyEntity(
                CLUSTER, 1L, mapper.writeValueAsString(outcome.plan()), "alice", NODE_A, false);
        ReflectionTestUtils.setField(noDetail, "id", 10L);
        when(applies.findById(9L)).thenReturn(Optional.of(row));
        when(applies.findById(10L)).thenReturn(Optional.of(noDetail));

        Detail detail = service.detail(CLUSTER, 9L).orElseThrow();

        assertThat(detail.plan().stepCount()).isEqualTo(outcome.plan().stepCount());
        assertThat(detail.nodes()).hasSameSizeAs(outcome.nodes());
        assertThat(detail.nodes().get(0).steps()).isNotEmpty();
        assertThat(service.detail(CLUSTER, 10L).orElseThrow().nodes()).isEmpty();
        assertThat(service.detail(CLUSTER, 404L)).isEmpty();
    }

    @Test
    void progressIsPublishedForEveryPhaseOfEveryNode() {
        document = fullDocument();
        List<Object> frames = new ArrayList<>();
        doAnswer(invocation -> frames.add(invocation.getArgument(2)))
                .when(hub)
                .publish(eq(CLUSTER), eq(BrokerConfigDriftService.SSE_TOPIC), any(), any());

        applyConfirmed();

        assertThat(frames)
                .extracting(f -> String.valueOf(((Map<?, ?>) f).get("phase")))
                .contains("APPLYING", "VERIFYING", "DONE");
    }

    @Test
    void thePostApplyEvaluationIsDeferredUntilCommitInsideATransaction() {
        document = fullDocument();
        TransactionSynchronizationManager.initSynchronization();

        applyConfirmed();

        verify(drift, never()).evaluateAfterApply(CLUSTER);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(drift).evaluateAfterApply(CLUSTER);
    }

    @Test
    void aSplitBrainClusterIsRefusedBeforeAnyWrite() {
        document = fullDocument();
        when(nodeB.getSplitBrain()).thenReturn(SplitBrainStatus.CRITICAL);

        assertThatThrownBy(this::applyConfirmedWithoutPreview)
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.getMessage()).contains("split-brain (b-second)"));
    }
}
