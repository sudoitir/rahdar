package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.CanonicalJson;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.gate.OperationHeldException;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService.ClusterDeleteParams;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService.ClusterUpdateParams;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService.NodeOverrideParams;
import io.github.sudoitir.rahdar.platform.clusters.EnvironmentService.ClusterAssignParams;
import io.github.sudoitir.rahdar.platform.clusters.EnvironmentService.EnvironmentCreateParams;
import io.github.sudoitir.rahdar.platform.clusters.EnvironmentService.EnvironmentDeleteParams;
import io.github.sudoitir.rahdar.platform.clusters.EnvironmentService.EnvironmentUpdateParams;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.EnvironmentRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.AccountUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.NodeOverrideRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.Credentials;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.EnvironmentViews.EnvironmentRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.EnvironmentViews.EnvironmentView;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * The cluster and environment operations through the approval gate (ADR-0179): authorized and validated before they
 * are gated, held without a change, replayed through the same public method, estimated, pinned to the target, and
 * showing their secrets redacted. The gate is a stand-in; the engine's own tests prove what it does with each answer.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class ClusterGateTest extends PostgresIntegrationTest {

    @MockitoBean
    OperationGate gate;

    @Autowired
    EnvironmentService environmentService;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    EnvironmentRepository environments;

    @Autowired
    GatedOperation<EnvironmentCreateParams> environmentCreateOperation;

    @Autowired
    GatedOperation<EnvironmentUpdateParams> environmentUpdateOperation;

    @Autowired
    GatedOperation<EnvironmentDeleteParams> environmentDeleteOperation;

    @Autowired
    GatedOperation<ClusterAssignParams> clusterAssignEnvironmentOperation;

    @Autowired
    GatedOperation<RegisterClusterRequest> clusterRegisterOperation;

    @Autowired
    GatedOperation<ClusterUpdateParams> clusterUpdateOperation;

    @Autowired
    GatedOperation<ClusterDeleteParams> clusterDeleteOperation;

    @Autowired
    GatedOperation<NodeOverrideParams> clusterNodeOverrideOperation;

    @Autowired
    BrokerNodeRepository nodes;

    private String name;
    private UUID clusterId;

    @BeforeEach
    void cluster() {
        name = "env-" + UUID.randomUUID().toString().substring(0, 8);
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "c-" + UUID.randomUUID(), null, null))
                .getId();
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
        environments.findAll().stream()
                .filter(e -> e.getName().startsWith("env-"))
                .forEach(environments::delete);
    }

    @SuppressWarnings("unchecked")
    private void allows() {
        when(gate.run(any(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
    }

    private void holds() {
        doThrow(new OperationHeldException(
                        UUID.randomUUID(), "held", Instant.now().plus(Duration.ofHours(1))))
                .when(gate)
                .run(any(), any());
    }

    private Operation gated() {
        ArgumentCaptor<Operation> operation = ArgumentCaptor.forClass(Operation.class);
        verify(gate).run(operation.capture(), any());
        return operation.getValue();
    }

    private EnvironmentView createdEnvironment() {
        allows();
        EnvironmentView created = environmentService.create(new EnvironmentRequest(name, "teal", 3));
        Mockito.reset(gate);
        return created;
    }

    // ---- environments --------------------------------------------------------------------------------------

    @Test
    void aHeldEnvironmentCreateChangesNothingAndReplaysOnce() {
        holds();
        EnvironmentRequest request = new EnvironmentRequest(name, "teal", 3);

        assertThatThrownBy(() -> environmentService.create(request)).isInstanceOf(OperationHeldException.class);

        assertThat(environments.existsByName(name)).isFalse();
        assertThat(gated().params()).isEqualTo(new EnvironmentCreateParams(name, "teal", 3));
        Mockito.reset(gate);

        allows();
        environmentCreateOperation.replay(new EnvironmentCreateParams(name, "teal", 3));

        assertThat(environments.findAll().stream().filter(e -> e.getName().equals(name)))
                .hasSize(1);
    }

    @Test
    void aNameInUseIsRefusedBeforeTheGate() {
        createdEnvironment();
        EnvironmentRequest request = new EnvironmentRequest(name, null, 0);

        assertThatThrownBy(() -> environmentService.create(request)).isInstanceOf(ConflictException.class);

        verify(gate, never()).run(any(), any());
    }

    @Test
    void aHeldEnvironmentEditAndDeleteChangeNothing() {
        EnvironmentView env = createdEnvironment();
        holds();
        UUID envId = env.id();
        EnvironmentRequest edit = new EnvironmentRequest(name + "-b", "red", 9);

        assertThatThrownBy(() -> environmentService.update(envId, edit)).isInstanceOf(OperationHeldException.class);
        assertThatThrownBy(() -> environmentService.delete(envId)).isInstanceOf(OperationHeldException.class);

        assertThat(environmentService.list()).anySatisfy(e -> {
            assertThat(e.id()).isEqualTo(env.id());
            assertThat(e.name()).isEqualTo(name);
            assertThat(e.colour()).isEqualTo("teal");
        });
    }

    @Test
    void anApprovedEnvironmentEditAndDeleteReplayThroughThePublicMethods() {
        EnvironmentView env = createdEnvironment();
        allows();

        environmentUpdateOperation.replay(new EnvironmentUpdateParams(env.id(), name + "-b", "red", 9));
        assertThat(environmentService.list())
                .anySatisfy(e -> assertThat(e.name()).isEqualTo(name + "-b"));

        environmentDeleteOperation.replay(new EnvironmentDeleteParams(env.id()));
        assertThat(environmentService.list())
                .noneSatisfy(e -> assertThat(e.id()).isEqualTo(env.id()));
    }

    @Test
    void anEnvironmentDeleteCountsTheClustersItWouldLeaveAndIsPinnedToTheEnvironment() {
        EnvironmentView env = createdEnvironment();
        allows();
        environmentService.assignCluster(clusterId, env.id());

        Effect effect = environmentDeleteOperation.estimate(new EnvironmentDeleteParams(env.id()));

        assertThat(effect.count()).isEqualTo(1);
        assertThat(effect.unit()).isEqualTo("clusters");
        assertThat(effect.etag()).isEqualTo(env.id().toString());
        assertThat(environmentDeleteOperation.traits(new EnvironmentDeleteParams(env.id())))
                .isEqualTo(Set.of(Trait.ACCESS_CONTROL, Trait.DESTRUCTIVE));
    }

    @Test
    void anEnvironmentThatIsGoneCannotBeEstimatedSoARequestForItIsRefusedWhenItWouldRun() {
        EnvironmentView env = createdEnvironment();
        allows();
        environmentService.delete(env.id());

        var params = new EnvironmentDeleteParams(env.id());

        assertThatThrownBy(() -> environmentDeleteOperation.estimate(params)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void movingAClusterIsHeldUnchangedAndReplaysOnce() {
        EnvironmentView env = createdEnvironment();
        holds();
        UUID envId = env.id();

        assertThatThrownBy(() -> environmentService.assignCluster(clusterId, envId))
                .isInstanceOf(OperationHeldException.class);

        assertThat(clusters.findById(clusterId).orElseThrow().getEnvironmentId())
                .isNull();
        assertThat(gated().params()).isEqualTo(new ClusterAssignParams(clusterId, env.id()));
        Mockito.reset(gate);

        allows();
        clusterAssignEnvironmentOperation.replay(new ClusterAssignParams(clusterId, env.id()));

        assertThat(clusters.findById(clusterId).orElseThrow().getEnvironmentId())
                .isEqualTo(env.id());
        assertThat(clusterAssignEnvironmentOperation.scope(new ClusterAssignParams(clusterId, env.id())))
                .isEqualTo(OperationScope.cluster(clusterId, env.id()));
    }

    // ---- clusters ------------------------------------------------------------------------------------------

    @Test
    void aHeldClusterDeleteLeavesTheClusterAndAnApprovedOneRemovesIt() {
        holds();

        assertThatThrownBy(() -> clusterService.delete(clusterId)).isInstanceOf(OperationHeldException.class);

        assertThat(clusters.existsById(clusterId)).isTrue();
        assertThat(gated().params()).isEqualTo(new ClusterDeleteParams(clusterId));
        Mockito.reset(gate);

        allows();
        clusterDeleteOperation.replay(new ClusterDeleteParams(clusterId));

        assertThat(clusters.existsById(clusterId)).isFalse();
    }

    @Test
    void aClusterDeleteIsAccessControlAndDestructiveAndPinnedToTheClusterAsRegistered() {
        Effect effect = clusterDeleteOperation.estimate(new ClusterDeleteParams(clusterId));

        assertThat(effect.etag()).startsWith(clusterId + "|");
        assertThat(clusterDeleteOperation.traits(new ClusterDeleteParams(clusterId)))
                .isEqualTo(Set.of(Trait.ACCESS_CONTROL, Trait.DESTRUCTIVE));
        assertThat(clusterDeleteOperation.summary(new ClusterDeleteParams(clusterId)))
                .startsWith("Delete cluster c-");
    }

    @Test
    void aClusterDeletedAfterTheRequestCannotBeEstimatedSoItIsRefusedWhenItWouldRun() {
        clusters.deleteById(clusterId);

        var params = new ClusterDeleteParams(clusterId);

        assertThatThrownBy(() -> clusterDeleteOperation.estimate(params)).isInstanceOf(NotFoundException.class);
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "c-" + UUID.randomUUID(), null, null))
                .getId();
    }

    @Test
    void aHeldNodeOverrideLeavesTheNodeAndAnApprovedOneRepointsItOnce() {
        BrokerNodeEntity seeded = BrokerNodeEntity.fromSeed(
                clusterId, "a", "PRIMARY", UUID.randomUUID().toString());
        seeded.attachSeedUrl("http://a:8161/console/jolokia");
        UUID nodeId = nodes.save(seeded).getId();
        NodeOverrideParams params = new NodeOverrideParams(clusterId, nodeId, null, "tcp://a-core:61616");
        holds();
        NodeOverrideRequest override = new NodeOverrideRequest(null, "tcp://a-core:61616");

        assertThatThrownBy(() -> clusterService.overrideNodeUrl(clusterId, nodeId, override))
                .isInstanceOf(OperationHeldException.class);

        assertThat(nodes.findById(nodeId).orElseThrow().getDataUrl()).isNotEqualTo("tcp://a-core:61616");
        assertThat(gated().params()).isEqualTo(params);
        assertThat(clusterNodeOverrideOperation.traits(params)).isEqualTo(Set.of(Trait.ACCESS_CONTROL));
        assertThat(clusterNodeOverrideOperation.display(params))
                .contains(new DisplayRow("Data URL", seeded.getDataUrl(), "tcp://a-core:61616"));
        String before = clusterNodeOverrideOperation.estimate(params).etag();
        Mockito.reset(gate);

        allows();
        clusterNodeOverrideOperation.replay(params);

        assertThat(nodes.findById(nodeId).orElseThrow().getDataUrl()).isEqualTo("tcp://a-core:61616");
        assertThat(clusterNodeOverrideOperation.estimate(params).etag())
                .as("a node pointed elsewhere since the request is a different target")
                .isNotEqualTo(before);
        verify(gate).run(any(), any());
    }

    @Test
    void aRegistrationShowsItsAccountsWithoutTheirPasswords() {
        var request = new RegisterClusterRequest(
                List.of("http://a:8161/console/jolokia"),
                "prod-eu",
                null,
                new Credentials("admin", "s3cret-management"),
                new Credentials("core", "s3cret-core"),
                null,
                null,
                null,
                true);

        List<DisplayRow> rows = clusterRegisterOperation.display(request);

        assertThat(rows.toString()).contains("admin", "core").doesNotContain("s3cret");
        assertThat(clusterRegisterOperation.redactedPaths())
                .containsExactlyInAnyOrder("/credentials/password", "/coreCredentials/password");
        assertThat(clusterRegisterOperation.summary(request)).isEqualTo("Register cluster prod-eu");
        assertThat(clusterRegisterOperation.traits(request)).isEqualTo(Set.of(Trait.ACCESS_CONTROL));
        assertThat(clusterRegisterOperation.estimate(request).etag()).isEqualTo("seeds:http://a:8161/console/jolokia");
    }

    @Test
    void aHeldRegistrationIsTheRequestItselfAndAnInvalidOneIsRefusedBeforeTheGate() {
        holds();
        var request = new RegisterClusterRequest(
                List.of("http://a:8161/console/jolokia"),
                "prod-eu",
                null,
                null,
                null,
                null,
                null,
                UUID.randomUUID(),
                null);

        assertThatThrownBy(() -> clusterService.register(request)).isInstanceOf(NotFoundException.class);

        verify(gate, never()).run(any(), any());
    }

    @Test
    void aConnectionEditRoundTripsItsClearedCoreAccountAndRedactsBothPasswords() {
        var request = new UpdateClusterRequest(
                "renamed",
                null,
                List.of("http://a:8161/console/jolokia"),
                null,
                null,
                new AccountUpdate("admin", "s3cret"),
                AccountUpdate.CLEAR);
        ClusterUpdateParams params = new ClusterUpdateParams(
                clusterId,
                request.name(),
                request.description(),
                request.seedUrls(),
                request.managementUrlPattern(),
                request.tlsBundle(),
                request.management(),
                null,
                true);

        ClusterUpdateParams back =
                JsonMapper.builder().build().readValue(CanonicalJson.write(params), ClusterUpdateParams.class);

        assertThat(back).isEqualTo(params);
        assertThat(back.toRequest().core()).isSameAs(AccountUpdate.CLEAR);
        assertThat(clusterUpdateOperation.redactedPaths())
                .containsExactlyInAnyOrder("/management/password", "/core/password");
        assertThat(clusterUpdateOperation.display(params).toString())
                .doesNotContain("s3cret")
                .contains("cleared");
        assertThat(clusterUpdateOperation.traits(params)).isEqualTo(Set.of(Trait.ACCESS_CONTROL));
    }
}
