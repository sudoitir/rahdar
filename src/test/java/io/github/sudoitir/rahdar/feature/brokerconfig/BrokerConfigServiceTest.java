package io.github.sudoitir.rahdar.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService.Adoption;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService.Declaration;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService.NodeConnectors;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService.Source;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigDeclarationEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigDeclarationEntity.ApplyMode;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigDeclarationRepository;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity.Basis;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity.State;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateRepository;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigRevisionEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigRevisionRepository;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterSecrets;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import io.github.sudoitir.rahdar.platform.clusters.RegistrationAdoption;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import io.github.sudoitir.rahdar.system.artemis.BrokerConnectors;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The declaration's lifecycle and adoption, with persistence and the brokers stubbed. */
class BrokerConfigServiceTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE_A = UUID.randomUUID();
    private static final UUID NODE_B = UUID.randomUUID();

    private final BrokerConfigDeclarationRepository declarations = mock(BrokerConfigDeclarationRepository.class);
    private final BrokerConfigRevisionRepository revisions = mock(BrokerConfigRevisionRepository.class);
    private final BrokerConfigNodeStateRepository nodeStates = mock(BrokerConfigNodeStateRepository.class);
    private final ClusterDirectory clusters = mock(ClusterDirectory.class);
    private final BrokerConnectors brokerConnectors = mock(BrokerConnectors.class);
    private final ClusterSecrets secrets = mock(ClusterSecrets.class);
    private final QueueSnapshots snapshots = mock(QueueSnapshots.class);
    private final BrokerConfigReads reads = mock(BrokerConfigReads.class);
    private final BrokerConfigOperations ops = mock(BrokerConfigOperations.class);
    private final ClusterLock lock = mock(ClusterLock.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final RegisteredCluster cluster = mock(RegisteredCluster.class);
    private final AuditEvent event = mock(AuditEvent.class);

    private final ArtemisNodes artemisNodes = mock(ArtemisNodes.class);

    private BrokerConfigService service;

    /** A target the mocked {@link ArtemisNodes} resolves to this node. */
    private BrokerTarget target(ArtemisNode node) {
        BrokerTarget target = mock(BrokerTarget.class);
        when(artemisNodes.on(target)).thenReturn(node);
        return target;
    }

    @BeforeEach
    void setUp() {
        service = new BrokerConfigService(
                declarations,
                revisions,
                nodeStates,
                clusters,
                brokerConnectors,
                secrets,
                snapshots,
                reads,
                ops,
                lock,
                access,
                settings,
                audit,
                actors,
                mapper,
                artemisNodes);
        when(cluster.getName()).thenReturn("prod");
        when(clusters.cluster(CLUSTER)).thenReturn(Optional.of(cluster));
        when(settings.duration(BrokerConfigSettings.DRIFT_INTERVAL)).thenReturn(Duration.ofMillis(200));
        when(reads.targets(CLUSTER)).thenReturn(List.of());
        when(declarations.findById(CLUSTER)).thenReturn(Optional.empty());
        when(nodeStates.findByClusterId(CLUSTER)).thenReturn(List.of());
        when(actors.resolve()).thenReturn(new Actor("alice", "127.0.0.1", "req", null));
        when(audit.begin(any(), anyString(), anyString(), anyString(), any(), any(), any(), eq(false)))
                .thenReturn(event);
        when(revisions.save(any())).thenAnswer(invocation -> {
            BrokerConfigRevisionEntity saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L + saved.getRevision());
            return saved;
        });
    }

    private static ClusterNode node(UUID id, String name, boolean active) {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(id);
        when(node.getName()).thenReturn(name);
        when(node.getActive()).thenReturn(active);
        return node;
    }

    private BrokerConfigRevisionEntity revisionEntity(int number, long id, BrokerConfigDocument doc, Source source) {
        BrokerConfigRevisionEntity entity = new BrokerConfigRevisionEntity(
                CLUSTER, number, mapper.writeValueAsString(doc), source.name(), "a note", "alice");
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private static BrokerConfigDocument document(String... addresses) {
        return new BrokerConfigDocument(
                1,
                Arrays.stream(addresses)
                        .map(a -> new AddressDecl(a, Set.of("ANYCAST"), List.of()))
                        .toList(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private void declared(int number, long revisionId, BrokerConfigDocument doc, Source source) {
        BrokerConfigDeclarationEntity header = new BrokerConfigDeclarationEntity(CLUSTER, revisionId);
        when(declarations.findById(CLUSTER)).thenReturn(Optional.of(header));
        when(revisions.findById(revisionId)).thenReturn(Optional.of(revisionEntity(number, revisionId, doc, source)));
    }

    private BrokerConfigNodeStateEntity driftedState(UUID nodeId) {
        BrokerConfigNodeStateEntity state = new BrokerConfigNodeStateEntity(CLUSTER, nodeId);
        state.recordState(
                State.DRIFTED,
                "1 difference",
                3,
                mapper.writeValueAsString(List.of(new BrokerConfigDriftService.DriftFinding(
                        Plan.FindingKind.MISSING, Plan.Section.ADDRESS, "orders", "missing", Map.of(), Map.of()))),
                Basis.VERIFIED_APPLY,
                7L);
        return state;
    }

    // ---- read -----------------------------------------------------------------

    @Test
    void anUnknownClusterIs404() {
        when(clusters.cluster(CLUSTER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(CLUSTER)).isInstanceOf(NotFoundException.class);
        verify(access).requireCluster(CLUSTER, Permissions.CLUSTER_READ);
    }

    @Test
    void anUndeclaredClusterReadsAsEmptyWithItsNodesNotEvaluated() {
        ClusterNode a = node(NODE_A, "node-a", true);
        ClusterNode b = node(NODE_B, "node-b", false);
        when(reads.targets(CLUSTER)).thenReturn(List.of(a, b));

        Declaration declaration = service.get(CLUSTER);

        assertThat(declaration.declared()).isFalse();
        assertThat(declaration.clusterName()).isEqualTo("prod");
        assertThat(declaration.revision()).isZero();
        assertThat(declaration.document()).isEqualTo(BrokerConfigDocument.empty());
        assertThat(declaration.applyMode()).isEqualTo(ApplyMode.RAHDAR_MANAGED);
        assertThat(declaration.source()).isNull();
        // A sub-second interval is still shown as one second, never as "0s".
        assertThat(declaration.driftIntervalSeconds()).isEqualTo(1);
        assertThat(declaration.nodes())
                .extracting(
                        BrokerConfigService.NodeState::nodeName,
                        BrokerConfigService.NodeState::live,
                        BrokerConfigService.NodeState::state)
                .containsExactly(
                        Assertions.tuple("node-a", true, State.NOT_EVALUATED),
                        Assertions.tuple("node-b", false, State.NOT_EVALUATED));
    }

    @Test
    void aDeclaredClusterReadsItsCurrentRevisionModeAndStoredNodeStates() {
        declared(3, 12L, document("orders"), Source.ADOPT);
        BrokerConfigDeclarationEntity header = declarations.findById(CLUSTER).orElseThrow();
        header.configure(ApplyMode.CONFIG_MANAGED, true, "[\"tmp.#\"]");
        ClusterNode a = node(NODE_A, "node-a", true);
        when(reads.targets(CLUSTER)).thenReturn(List.of(a));
        when(nodeStates.findByClusterId(CLUSTER)).thenReturn(List.of(driftedState(NODE_A)));
        when(settings.duration(BrokerConfigSettings.DRIFT_INTERVAL)).thenReturn(Duration.ofMinutes(5));

        Declaration declaration = service.get(CLUSTER);

        assertThat(declaration.declared()).isTrue();
        assertThat(declaration.revision()).isEqualTo(3);
        assertThat(declaration.revisionId()).isEqualTo(12L);
        assertThat(declaration.document().addresses())
                .extracting(AddressDecl::name)
                .containsExactly("orders");
        assertThat(declaration.applyMode()).isEqualTo(ApplyMode.CONFIG_MANAGED);
        assertThat(declaration.reportUndeclared()).isTrue();
        assertThat(declaration.undeclaredExclusions()).containsExactly("tmp.#");
        assertThat(declaration.source()).isEqualTo(Source.ADOPT);
        assertThat(declaration.note()).isEqualTo("a note");
        assertThat(declaration.updatedBy()).isEqualTo("alice");
        assertThat(declaration.driftIntervalSeconds()).isEqualTo(300);
        assertThat(declaration.nodes()).singleElement().satisfies(state -> {
            assertThat(state.state()).isEqualTo(State.DRIFTED);
            assertThat(state.detail()).isEqualTo("1 difference");
            assertThat(state.verifiedRevision()).isEqualTo(3);
            assertThat(state.basis()).isEqualTo(Basis.VERIFIED_APPLY);
            assertThat(state.basisRef()).isEqualTo(7L);
            assertThat(state.findings())
                    .singleElement()
                    .satisfies(f -> assertThat(f.key()).isEqualTo("orders"));
        });
    }

    @Test
    void aDeclarationPointingAtAMissingRevisionIsAnInvariantBreak() {
        declarations.findById(CLUSTER);
        when(declarations.findById(CLUSTER)).thenReturn(Optional.of(new BrokerConfigDeclarationEntity(CLUSTER, 9L)));
        when(revisions.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(CLUSTER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing revision");
    }

    @Test
    void revisionsAreListedAndLookedUpByNumber() {
        BrokerConfigRevisionEntity two = revisionEntity(2, 12L, document("b"), Source.EDIT);
        BrokerConfigRevisionEntity one = revisionEntity(1, 11L, document("a"), Source.IMPORT_XML);
        when(revisions.findByClusterIdOrderByRevisionDesc(CLUSTER)).thenReturn(List.of(two, one));
        when(revisions.findByClusterIdAndRevision(CLUSTER, 1)).thenReturn(Optional.of(one));
        when(revisions.findByClusterIdAndRevision(CLUSTER, 9)).thenReturn(Optional.empty());

        assertThat(service.revisions(CLUSTER))
                .extracting(BrokerConfigService.Revision::revision, BrokerConfigService.Revision::source)
                .containsExactly(Assertions.tuple(2, Source.EDIT), Assertions.tuple(1, Source.IMPORT_XML));
        assertThat(service.revision(CLUSTER, 1).document().addresses()).hasSize(1);
        assertThatThrownBy(() -> service.revision(CLUSTER, 9)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void currentIsEmptyForAnUndeclaredClusterAndCarriesTheHeaderOtherwise() {
        assertThat(service.current(CLUSTER)).isEmpty();

        declared(4, 14L, document("orders"), Source.MCP);

        assertThat(service.current(CLUSTER)).hasValueSatisfying(current -> {
            assertThat(current.revision()).isEqualTo(4);
            assertThat(current.id()).isEqualTo(14L);
            assertThat(current.source()).isEqualTo(Source.MCP);
            assertThat(current.header().getClusterId()).isEqualTo(CLUSTER);
        });
    }

    // ---- save ---------------------------------------------------------------------

    @Test
    void aFirstSaveCreatesRevisionOneAndTheDeclaration() {
        Declaration saved;
        when(revisions.findById(101L))
                .thenAnswer(invocation -> Optional.of(revisionEntity(1, 101L, document("orders"), Source.EDIT)));
        when(declarations.findById(CLUSTER))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new BrokerConfigDeclarationEntity(CLUSTER, 101L)));

        saved = service.save(CLUSTER, document("orders"), null, "  first  ", Source.EDIT);

        ArgumentCaptor<BrokerConfigRevisionEntity> revision = ArgumentCaptor.forClass(BrokerConfigRevisionEntity.class);
        verify(revisions).save(revision.capture());
        assertThat(revision.getValue().getRevision()).isEqualTo(1);
        assertThat(revision.getValue().getNote()).isEqualTo("first");
        assertThat(revision.getValue().getCreatedBy()).isEqualTo("alice");
        assertThat(revision.getValue().getSource()).isEqualTo("EDIT");
        ArgumentCaptor<BrokerConfigDeclarationEntity> header =
                ArgumentCaptor.forClass(BrokerConfigDeclarationEntity.class);
        verify(declarations).save(header.capture());
        assertThat(header.getValue().getCurrentRevisionId()).isEqualTo(101L);
        verify(audit).succeed(event, 1);
        assertThat(saved.declared()).isTrue();
    }

    @Test
    void aLaterSaveAdvancesTheNumberAndRepointsTheDeclaration() {
        declared(2, 12L, document("a"), Source.EDIT);
        when(revisions.findById(103L))
                .thenReturn(Optional.of(revisionEntity(3, 103L, document("a", "b"), Source.EDIT)));

        service.save(CLUSTER, document("a", "b"), 2, "   ", Source.EDIT);

        ArgumentCaptor<BrokerConfigRevisionEntity> revision = ArgumentCaptor.forClass(BrokerConfigRevisionEntity.class);
        verify(revisions).save(revision.capture());
        assertThat(revision.getValue().getRevision()).isEqualTo(3);
        // A blank note is no note.
        assertThat(revision.getValue().getNote()).isNull();
        ArgumentCaptor<BrokerConfigDeclarationEntity> header =
                ArgumentCaptor.forClass(BrokerConfigDeclarationEntity.class);
        verify(declarations).save(header.capture());
        assertThat(header.getValue().getCurrentRevisionId()).isEqualTo(103L);
    }

    @Test
    void aSaveOfAStaleRevisionIsRefused() {
        declared(5, 15L, document("a"), Source.EDIT);

        assertThatThrownBy(() -> service.save(CLUSTER, document("a"), 4, null, Source.EDIT))
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        e -> assertThat(e.getMessage())
                                .contains("Revision 5 was saved while you were editing revision 4"));
        verify(revisions, never()).save(any());
    }

    @Test
    void aSaveOfAnInvalidDocumentIsRefusedBeforeAnythingIsWritten() {
        var duplicated = document("dup", "dup");

        assertThatThrownBy(() -> service.save(CLUSTER, duplicated, null, null, Source.EDIT))
                .isInstanceOf(BrokerConfigInvalidException.class);
        verify(revisions, never()).save(any());
        verify(audit, never()).begin(any(), anyString(), anyString(), anyString(), any(), any(), any(), eq(false));
    }

    @Test
    void anAdoptionThatClosesDriftNeedsTheClusterNameTypedBack() {
        ClusterNode a = node(NODE_A, "node-a", true);
        when(reads.targets(CLUSTER)).thenReturn(List.of(a));
        when(nodeStates.findByClusterId(CLUSTER)).thenReturn(List.of(driftedState(NODE_A)));

        assertThatThrownBy(() -> service.save(CLUSTER, document("a"), null, null, Source.ADOPT, "wrong"))
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        e -> assertThat(e.getMessage()).contains("closes 1 open drift finding"));
        verify(revisions, never()).save(any());
    }

    @Test
    void aConfirmedAdoptionIsStillRefusedWhileAnApplyHoldsTheCluster() {
        ClusterNode a = node(NODE_A, "node-a", true);
        when(reads.targets(CLUSTER)).thenReturn(List.of(a));
        when(nodeStates.findByClusterId(CLUSTER)).thenReturn(List.of(driftedState(NODE_A)));
        when(lock.isHeld(CLUSTER, ClusterLock.Scope.CONFIG_APPLY)).thenReturn(true);

        assertThatThrownBy(() -> service.save(CLUSTER, document("a"), null, null, Source.ADOPT, "prod"))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.getMessage()).contains("apply is running"));
        verify(revisions, never()).save(any());
    }

    @Test
    void anAdoptionWithNothingToCloseSavesWithoutConfirmation() {
        when(lock.isHeld(CLUSTER, ClusterLock.Scope.CONFIG_APPLY)).thenReturn(false);
        when(revisions.findById(101L)).thenReturn(Optional.of(revisionEntity(1, 101L, document("a"), Source.ADOPT)));
        when(declarations.findById(CLUSTER))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new BrokerConfigDeclarationEntity(CLUSTER, 101L)));

        service.save(CLUSTER, document("a"), null, null, Source.ADOPT, null);

        verify(revisions).save(any());
    }

    // ---- configure ------------------------------------------------------------------

    @Test
    void configuringAnUndeclaredClusterIsRefused() {
        assertThatThrownBy(() -> service.configure(CLUSTER, ApplyMode.CONFIG_MANAGED, true, List.of()))
                .isInstanceOfSatisfying(
                        ConflictException.class,
                        e -> assertThat(e.getMessage()).contains("Declare the cluster's configuration first"));
    }

    @Test
    void configuringStoresTheModeAndCleanedExclusions() {
        declared(1, 11L, document("a"), Source.EDIT);

        service.configure(CLUSTER, ApplyMode.CONFIG_MANAGED, true, List.of(" a.# ", "", "a.#", "b.#"));

        BrokerConfigDeclarationEntity header = declarations.findById(CLUSTER).orElseThrow();
        assertThat(header.mode()).isEqualTo(ApplyMode.CONFIG_MANAGED);
        assertThat(header.isReportUndeclared()).isTrue();
        assertThat(header.getUndeclaredExclusions()).isEqualTo("[\"a.#\",\"b.#\"]");
        verify(declarations).save(header);
        verify(audit).succeed(event, 1);
    }

    @Test
    void configuringWithNoExclusionsStoresAnEmptyList() {
        declared(1, 11L, document("a"), Source.EDIT);

        service.configure(CLUSTER, ApplyMode.RAHDAR_MANAGED, false, null);

        assertThat(declarations.findById(CLUSTER).orElseThrow().getUndeclaredExclusions())
                .isEqualTo("[]");
    }

    // ---- connectors and credentials -----------------------------------------------------

    @Test
    void connectorsAreReadPerLiveNodeAndAnUnreadableOneIsUnknownNotEmpty() {
        ClusterNode idle = node(NODE_A, "backup", false);
        ClusterNode good = node(UUID.randomUUID(), "good", true);
        ClusterNode bad = node(UUID.randomUUID(), "bad", true);
        ArtemisNode goodClient = mock(ArtemisNode.class);
        ArtemisNode badClient = mock(ArtemisNode.class);
        when(reads.targets(CLUSTER)).thenReturn(List.of(idle, good, bad));
        when(reads.client(CLUSTER, good)).thenReturn(goodClient);
        when(reads.client(CLUSTER, bad)).thenReturn(badClient);
        when(brokerConnectors.read(goodClient))
                .thenReturn(new BrokerConnectors.Connectors(List.of("netty"), true, null));
        when(brokerConnectors.read(badClient)).thenThrow(new IllegalStateException("timeout"));

        List<NodeConnectors> out = service.connectors(CLUSTER);

        assertThat(out).hasSize(3);
        assertThat(out.get(0).known()).isFalse();
        assertThat(out.get(0).reason()).contains("Not live");
        assertThat(out.get(1).names()).containsExactly("netty");
        assertThat(out.get(1).known()).isTrue();
        assertThat(out.get(2).known()).isFalse();
        assertThat(out.get(2).names()).isEmpty();
        assertThat(out.get(2).reason()).contains("Could not reach this node: timeout");
    }

    @Test
    void bridgeCredentialsAreListedStoredAndForgottenWithAnAuditThatNeverHoldsTheSecret() {
        when(secrets.list(CLUSTER)).thenReturn(List.of(new ClusterSecrets.Credential("dc2", "bridge", null)));

        assertThat(service.bridgeCredentials(CLUSTER))
                .extracting(ClusterSecrets.Credential::ref)
                .containsExactly("dc2");

        service.setBridgeCredential(CLUSTER, "dc2", null, "s3cret");
        service.setBridgeCredential(CLUSTER, "dc3", "bridge", "s3cret");
        service.forgetBridgeCredential(CLUSTER, "dc2");

        verify(secrets).store(CLUSTER, "dc2", null, "s3cret");
        verify(secrets).store(CLUSTER, "dc3", "bridge", "s3cret");
        verify(secrets).forget(CLUSTER, "dc2");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(audit, times(3))
                .begin(
                        any(),
                        eq(BrokerConfigService.AUDIT_CREDENTIAL),
                        anyString(),
                        anyString(),
                        eq(CLUSTER),
                        any(),
                        params.capture(),
                        eq(false));
        assertThat(params.getAllValues().get(0)).containsEntry("username", "");
        assertThat(params.getAllValues().get(1)).containsEntry("username", "bridge");
        assertThat(params.getAllValues().get(2)).containsEntry("forgotten", true);
        assertThat(params.getAllValues().toString()).doesNotContain("s3cret");
    }

    // ---- XML -----------------------------------------------------------------------------

    @Test
    void importParsesWithoutSavingAndExportWritesTheCurrentOrANumberedRevision() {
        BrokerXmlCodec.ParseResult parsed = service.importXml(CLUSTER, "<configuration/>");
        assertThat(parsed).isNotNull();
        verify(revisions, never()).save(any());

        declared(2, 12L, document("current"), Source.EDIT);
        when(revisions.findByClusterIdAndRevision(CLUSTER, 1))
                .thenReturn(Optional.of(revisionEntity(1, 11L, document("first"), Source.EDIT)));

        assertThat(service.exportXml(CLUSTER, null)).contains("current").doesNotContain("first");
        assertThat(service.exportXml(CLUSTER, 1)).contains("first").doesNotContain("current");
    }

    // ---- adoption --------------------------------------------------------------------------

    private ObservedNodeConfig observed(
            UUID id,
            String name,
            Map<String, Set<String>> addresses,
            Map<String, Map<String, Object>> settings,
            Map<String, Map<PermissionType, Set<String>>> security,
            Map<String, DivertDecl> diverts) {
        return new ObservedNodeConfig(
                id, name, true, addresses, Map.of(), settings, security, diverts, Map.of(), Map.of(), null);
    }

    private static DivertDecl divert(String name, String forwarding) {
        return new DivertDecl(name, "orders", forwarding, null, false, null, null, null);
    }

    @Test
    void adoptingWithNoLiveNodeIsRefused() {
        ClusterNode idle = node(NODE_A, "node-a", false);
        when(reads.targets(CLUSTER)).thenReturn(List.of(idle));

        assertThatThrownBy(() -> service.adopt(CLUSTER))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.getMessage()).contains("nothing to adopt"));
    }

    @Test
    void anUnreadableNodeIsNotedAndTheRestAreStillAdopted() {
        ClusterNode a = node(NODE_A, "node-a", true);
        ClusterNode b = node(NODE_B, "node-b", true);
        ArtemisNode ca = mock(ArtemisNode.class);
        ArtemisNode cb = mock(ArtemisNode.class);
        when(reads.targets(CLUSTER)).thenReturn(List.of(a, b));
        when(reads.client(CLUSTER, a)).thenReturn(ca);
        when(reads.client(CLUSTER, b)).thenReturn(cb);
        when(ops.readForAdoption(ca, NODE_A, "node-a"))
                .thenReturn(
                        observed(NODE_A, "node-a", Map.of("orders", Set.of("ANYCAST")), Map.of(), Map.of(), Map.of()));
        when(ops.readForAdoption(cb, NODE_B, "node-b")).thenThrow(new IllegalStateException("refused"));

        Adoption adoption = service.adopt(CLUSTER);

        assertThat(adoption.notes())
                .anyMatch(n -> n.contains("node-b could not be read and contributed nothing: refused"));
        assertThat(adoption.document().addresses())
                .extracting(AddressDecl::name)
                .containsExactly("orders");
        assertThat(adoption.document().bridges()).isEmpty();
        assertThat(adoption.closes()).isEmpty();
    }

    @Test
    void adoptionMergesNodesAndKeepsTheFirstNodesValuesListingEveryDisagreement() {
        ClusterNode a = node(NODE_A, "node-a", true);
        ClusterNode b = node(NODE_B, "node-b", true);
        ArtemisNode ca = mock(ArtemisNode.class);
        ArtemisNode cb = mock(ArtemisNode.class);
        when(reads.targets(CLUSTER)).thenReturn(List.of(a, b));
        when(reads.client(CLUSTER, a)).thenReturn(ca);
        when(reads.client(CLUSTER, b)).thenReturn(cb);
        when(nodeStates.findByClusterId(CLUSTER)).thenReturn(List.of(driftedState(NODE_A)));

        Map<String, Object> base = Map.of("max-size-bytes", 100, "address-full-policy", "PAGE");
        Map<String, Object> ordersSettings = Map.of("max-size-bytes", 100.0, "address-full-policy", "block");
        Map<String, Object> ordersOther = Map.of("max-size-bytes", 5, "address-full-policy", "PAGE");
        Map<PermissionType, Set<String>> baseRoles = Map.of(PermissionType.SEND, Set.of("admins"));
        Map<PermissionType, Set<String>> ordersRoles = Map.of(PermissionType.SEND, Set.of("orders-writers"));
        when(ops.readForAdoption(ca, NODE_A, "node-a"))
                .thenReturn(observed(
                        NODE_A,
                        "node-a",
                        Map.of("orders", Set.of("ANYCAST"), "audit", Set.of()),
                        Map.of("#", base, "orders", ordersSettings, "audit", base),
                        Map.of("#", baseRoles, "orders", ordersRoles, "audit", baseRoles),
                        Map.of("d1", divert("d1", "audit"), "d2", divert("d2", "audit"))));
        when(ops.readForAdoption(cb, NODE_B, "node-b"))
                .thenReturn(observed(
                        NODE_B,
                        "node-b",
                        Map.of("orders", Set.of("MULTICAST")),
                        Map.of("#", base, "orders", ordersOther),
                        Map.of(),
                        Map.of("d1", divert("d1", "other"))));
        when(snapshots.forCluster(CLUSTER))
                .thenReturn(List.of(
                        snapshot("orders.q", "orders", "anycast", true),
                        snapshot("orders.q", "orders", "anycast", true),
                        snapshot("extra.q", "extra", "multicast", false),
                        snapshot("dlq", "activemq.notifications", "anycast", true),
                        snapshot("sys", "$sys.x", "anycast", true),
                        snapshot("no-address", null, "anycast", true)));

        Adoption adoption = service.adopt(CLUSTER);

        BrokerConfigDocument doc = adoption.document();
        // Addresses are the union across nodes, and a type-less one defaults to ANYCAST.
        assertThat(doc.addresses()).extracting(AddressDecl::name).containsExactly("audit", "extra", "orders");
        AddressDecl orders = doc.addresses().stream()
                .filter(x -> x.name().equals("orders"))
                .findFirst()
                .orElseThrow();
        assertThat(orders.routingTypes()).containsExactlyInAnyOrder("ANYCAST", "MULTICAST");
        assertThat(orders.queues())
                .extracting(BrokerConfigDocument.QueueDecl::name)
                .containsExactly("orders.q");
        assertThat(doc.addresses().get(0).routingTypes()).containsExactly("ANYCAST");
        AddressDecl extra = doc.addresses().stream()
                .filter(x -> x.name().equals("extra"))
                .findFirst()
                .orElseThrow();
        assertThat(extra.queues().get(0).durable()).isFalse();
        // Address settings: the catch-all in full, then only what an address resolves differently.
        assertThat(doc.addressSettings()).extracting(AddressSettingDecl::match).containsExactly("#", "orders");
        assertThat(doc.addressSettings().get(1).values()).containsOnlyKeys("address-full-policy");
        // Security settings: the catch-all and the addresses whose roles differ from it.
        assertThat(doc.securitySettings())
                .extracting(BrokerConfigDocument.SecuritySettingDecl::match)
                .containsExactly("#", "orders");
        assertThat(doc.diverts()).extracting(DivertDecl::name).containsExactly("d1", "d2");
        assertThat(adoption.disagreements())
                .anyMatch(d -> d.startsWith("Address settings for orders differ between node-a and node-b"))
                .anyMatch(d -> d.startsWith("Divert d1 differs between node-a and node-b"))
                .anyMatch(d -> d.equals("Divert d2 is missing on node-b."));
        assertThat(adoption.closes()).singleElement().satisfies(closed -> {
            assertThat(closed.nodeName()).isEqualTo("node-a");
            assertThat(closed.finding().key()).isEqualTo("orders");
        });
        assertThat(adoption.notes()).anyMatch(n -> n.contains("1 open drift finding(s) will be closed"));
    }

    private static final Map<String, Object> BASE_SETTINGS =
            Map.of("max-size-bytes", 100, "address-full-policy", "PAGE");

    private ObservedNodeConfig sameAs(UUID id, String name, Map<String, Object> ordersSettings) {
        return observed(
                id,
                name,
                Map.of("orders", Set.of("ANYCAST")),
                Map.of("#", BASE_SETTINGS, "orders", ordersSettings),
                Map.of(),
                Map.of());
    }

    @Test
    void aPreviewBeforeRegistrationDeclaresWhatNodesThatAgreeRunWithoutQueuesOrFindings() {
        ArtemisNode ca = mock(ArtemisNode.class);
        ArtemisNode cb = mock(ArtemisNode.class);
        Map<String, Object> orders = Map.of("max-size-bytes", 100, "address-full-policy", "BLOCK");
        when(ops.readForAdoption(eq(ca), any(), eq("node-a"))).thenReturn(sameAs(NODE_A, "node-a", orders));
        when(ops.readForAdoption(eq(cb), any(), eq("node-b"))).thenReturn(sameAs(NODE_B, "node-b", orders));

        Adoption adoption = service.previewAdoption(List.of(
                        new RegistrationAdoption.LiveNode("node-a", target(ca)),
                        new RegistrationAdoption.LiveNode("node-b", target(cb))))
                .orElseThrow();

        assertThat(adoption.disagreements()).isEmpty();
        assertThat(adoption.closes()).isEmpty();
        assertThat(adoption.document().addresses())
                .extracting(AddressDecl::name)
                .containsExactly("orders");
        assertThat(adoption.document().addresses().get(0).queues()).isEmpty();
        assertThat(adoption.document().addressSettings())
                .extracting(AddressSettingDecl::match)
                .containsExactly("#", "orders");
        // The cluster does not exist yet, so nothing is looked up for it.
        verifyNoInteractions(snapshots, nodeStates);
    }

    @Test
    void aPreviewBeforeRegistrationListsWhereTheNodesDisagree() {
        ArtemisNode ca = mock(ArtemisNode.class);
        ArtemisNode cb = mock(ArtemisNode.class);
        when(ops.readForAdoption(eq(ca), any(), eq("node-a")))
                .thenReturn(sameAs(NODE_A, "node-a", Map.of("max-size-bytes", 100, "address-full-policy", "BLOCK")));
        when(ops.readForAdoption(eq(cb), any(), eq("node-b")))
                .thenReturn(sameAs(NODE_B, "node-b", Map.of("max-size-bytes", 5, "address-full-policy", "PAGE")));

        Adoption adoption = service.previewAdoption(List.of(
                        new RegistrationAdoption.LiveNode("node-a", target(ca)),
                        new RegistrationAdoption.LiveNode("node-b", target(cb))))
                .orElseThrow();

        assertThat(adoption.disagreements())
                .singleElement()
                .asString()
                .startsWith("Address settings for orders differ between node-a and node-b");
    }

    @Test
    void aPreviewOfNodesNoneOfWhichCouldBeReadOffersNothing() {
        ArtemisNode dead = mock(ArtemisNode.class);
        when(ops.readForAdoption(eq(dead), any(), eq("node-a"))).thenThrow(new IllegalStateException("down"));

        assertThat(service.previewAdoption(List.of(new RegistrationAdoption.LiveNode("node-a", target(dead)))))
                .isEmpty();
    }

    @Test
    void adoptionWithoutCatchAllSettingsOrRolesStillProducesADocument() {
        ClusterNode a = node(NODE_A, "node-a", true);
        ArtemisNode ca = mock(ArtemisNode.class);
        when(reads.targets(CLUSTER)).thenReturn(List.of(a));
        when(reads.client(CLUSTER, a)).thenReturn(ca);
        when(ops.readForAdoption(ca, NODE_A, "node-a"))
                .thenReturn(observed(
                        NODE_A,
                        "node-a",
                        Map.of("#.#", Set.of("ANYCAST")),
                        Map.of(),
                        Map.of("#.#", Map.of(PermissionType.SEND, Set.of("x"))),
                        Map.of()));

        Adoption adoption = service.adopt(CLUSTER);

        assertThat(adoption.document().addressSettings())
                .singleElement()
                .satisfies(s -> assertThat(s.match()).isEqualTo("#"));
        // A catch-all spelled as an address is not re-declared as a per-address role set.
        assertThat(adoption.document().securitySettings()).isEmpty();
        assertThat(adoption.disagreements()).isEmpty();
    }

    private static QueueSnapshot snapshot(String queue, String address, String routingType, boolean durable) {
        return new QueueSnapshot(
                CLUSTER, NODE_A, queue, address, routingType, durable, false, Instant.now(), 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void aFailingAuditPropagatesAndLeavesTheDeclarationUntouched() {
        declared(1, 11L, document("a"), Source.EDIT);
        doThrow(new IllegalStateException("audit down"))
                .when(audit)
                .begin(any(), anyString(), anyString(), anyString(), any(), any(), any(), eq(false));

        assertThatThrownBy(() -> service.configure(CLUSTER, ApplyMode.CONFIG_MANAGED, false, List.of()))
                .isInstanceOf(IllegalStateException.class);
        verify(declarations, never()).save(any());
    }
}
