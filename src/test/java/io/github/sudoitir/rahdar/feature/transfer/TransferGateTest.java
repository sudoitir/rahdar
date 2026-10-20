package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.transfer.TransferService.Reach;
import io.github.sudoitir.rahdar.feature.transfer.TransferService.TransferExecuteParams;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunEntity;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunRepository;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.SelectionKind;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferExecuteRequest;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferSelection;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.gate.OperationHeldException;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.security.GrantLoader;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.OperatorFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * A transfer through the approval gate as {@code transfer.execute} (ADR-0179), on a previewed run stored as the preview
 * leaves it: validated before it is gated, held without starting anything, kept while it is held, started on approval,
 * and estimated from its plan. The gate is a stand-in; the engine's own tests prove what it does with each answer.
 */
class TransferGateTest extends PostgresIntegrationTest {

    @MockitoBean
    OperationGate gate;

    @MockitoBean
    TransferRunner runner;

    @Autowired
    TransferService transfers;

    @Autowired
    GatedOperation<TransferExecuteParams> transferExecuteOperation;

    @Autowired
    TransferRunRepository runs;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    GrantLoader grants;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    private UUID source;
    private UUID target;
    private TransferRunEntity run;

    @BeforeEach
    void previewedRun() {
        OperatorFixture.signIn(users, roles, rolePermissions, userRoles, grants);
        source = clusters.save(new ClusterEntity("s-" + UUID.randomUUID(), null, null))
                .getId();
        target = clusters.save(new ClusterEntity("t-" + UUID.randomUUID(), null, null))
                .getId();
        Instant now = Instant.now();
        run = runs.save(TransferRunEntity.builder()
                .mode(TransferMode.MOVE)
                .sourceClusterId(source)
                .sourceNodeId(UUID.randomUUID())
                .sourceNodeName("a")
                .sourceNativeNodeId("node-a")
                .sourceQueue("orders.dlq")
                .sourceAddress("orders.dlq")
                .sourceRoutingType("ANYCAST")
                .targetClusterId(target)
                .targetNodeId(UUID.randomUUID())
                .targetNodeName("b")
                .targetNativeNodeId("node-b")
                .targetQueue("orders")
                .targetAddress("orders")
                .targetRoutingType("ANYCAST")
                .selection(json.writeValueAsString(new TransferSelection(SelectionKind.ALL, null, null)))
                .findings("[]")
                .t0(now)
                .planHash("plan-1")
                .estimate(40L)
                .username("test-operator")
                .createdAt(now)
                .expiresAt(now.plus(Duration.ofSeconds(30)))
                .build());
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(source);
        clusters.deleteById(target);
        SecurityContextHolder.clearContext();
    }

    private TransferExecuteRequest request() {
        return new TransferExecuteRequest("plan-1", false, null, "orders.dlq");
    }

    private void holds() {
        Mockito.doThrow(new OperationHeldException(
                        UUID.randomUUID(), "Move messages", Instant.now().plus(Duration.ofHours(2))))
                .when(gate)
                .run(any(), any());
    }

    @Test
    void aHeldTransferStartsNothingAndKeepsItsPreviewUntilTheHoldEnds() {
        holds();
        UUID runId = run.getId();
        TransferExecuteRequest request = request();

        assertThatThrownBy(() -> transfers.execute(source, runId, request)).isInstanceOf(OperationHeldException.class);

        assertThat(runs.findById(run.getId()).orElseThrow().getState()).isEqualTo(TransferState.PREVIEWED);
        verify(runner, never()).start(any(), any());
        Instant keptUntil =
                jdbc.queryForObject("SELECT expires_at FROM transfer_run WHERE id = ?", Instant.class, run.getId());
        assertThat(keptUntil).isAfter(Instant.now().plus(Duration.ofHours(2)));
        ArgumentCaptor<Operation> operation = ArgumentCaptor.forClass(Operation.class);
        verify(gate).run(operation.capture(), any());
        assertThat(operation.getValue().params())
                .isEqualTo(new TransferExecuteParams(
                        source,
                        run.getId(),
                        "plan-1",
                        TransferMode.MOVE,
                        "orders.dlq",
                        target,
                        "orders",
                        false,
                        List.of(),
                        "orders.dlq"));
    }

    @Test
    void aTransferThatIsNotTheOnePreviewedIsRefusedBeforeItIsGated() {
        UUID runId = run.getId();
        TransferExecuteRequest request = new TransferExecuteRequest("not-the-plan", false, null, "orders.dlq");

        assertThatThrownBy(() -> transfers.execute(source, runId, request)).isInstanceOf(ConflictException.class);

        verify(gate, never()).run(any(), any());
    }

    @Test
    void anApprovedTransferStartsOnce() {
        when(gate.run(any(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());

        transfers.execute(source, run.getId(), request());

        assertThat(runs.findById(run.getId()).orElseThrow().getState()).isEqualTo(TransferState.RUNNING);
        verify(runner).start(any(), any());
        verify(gate).run(any(), any());
    }

    @Test
    void aTransferIsEstimatedFromItsPlanAndPinnedToThePlansHash() {
        assertThat(transfers.reach(source, run.getId())).isEqualTo(new Reach(40L, "plan-1"));

        Effect effect = transferExecuteOperation.estimate(new TransferExecuteParams(
                source,
                run.getId(),
                "plan-1",
                TransferMode.MOVE,
                "orders.dlq",
                target,
                "orders",
                false,
                List.of(),
                null));

        assertThat(effect).isEqualTo(new Effect(40, "messages", "plan-1", null));
    }

    @Test
    void aTransferIsDestructiveAndNamesBothEnds() {
        var params = new TransferExecuteParams(
                source,
                run.getId(),
                "plan-1",
                TransferMode.COPY,
                "orders.dlq",
                target,
                "orders",
                false,
                List.of(),
                null);

        assertThat(transferExecuteOperation.traits(params)).isEqualTo(Set.of(Trait.DESTRUCTIVE));
        assertThat(transferExecuteOperation.summary(params))
                .startsWith("Copy messages from queue orders.dlq on s-")
                .contains("to queue orders on t-");
    }
}
