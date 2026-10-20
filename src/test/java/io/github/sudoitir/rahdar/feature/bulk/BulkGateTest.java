package io.github.sudoitir.rahdar.feature.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.feature.bulk.BulkService.BulkExecuteParams;
import io.github.sudoitir.rahdar.feature.bulk.BulkService.Reach;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkExecuteRequest;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkRunDetailView;
import io.github.sudoitir.rahdar.feature.messages.MessageService;
import io.github.sudoitir.rahdar.feature.resources.QueueLifecycleService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.gate.GateContext;
import io.github.sudoitir.rahdar.kernel.gate.GateLease;
import io.github.sudoitir.rahdar.kernel.gate.GateLeases;
import io.github.sudoitir.rahdar.kernel.gate.GateScope;
import io.github.sudoitir.rahdar.kernel.gate.GateTicket;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.gate.OperationHeldException;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff.Operator;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * A bulk run through the approval gate as {@code bulk.execute} (ADR-0179): validated before it is gated, held without
 * starting anything, kept while it is held, started once on approval with its queues covered by that approval, and
 * estimated from its plan. The gate is a stand-in that holds or lets the action through, as the engine's own tests
 * prove what it does with each.
 */
class BulkGateTest extends BulkTestSupport {

    @MockitoBean
    OperationGate gate;

    @MockitoBean
    QueueLifecycleService queues;

    @MockitoBean
    MessageService messages;

    @MockitoSpyBean
    OperatorHandoff handoff;

    @MockitoSpyBean
    GateLeases leases;

    @Autowired
    WebApplicationContext webContext;

    private final List<String> paused = new CopyOnWriteArrayList<>();
    private final List<Operator> operators = new CopyOnWriteArrayList<>();

    @BeforeEach
    void pausesAnswer() {
        queue(nodeA, "orders.1", 5, 0, false);
        queue(nodeA, "orders.2", 7, 0, false);
        when(queues.setPaused(eq(clusterId), anyString(), eq(true), eq(false))).thenAnswer(call -> {
            paused.add(call.getArgument(1));
            return new Attempt.Ok<>(new LifecycleOutcome(
                    false, 1000, false, List.of(new NodeOutcome(nodeA, "a", NodeStatus.APPLIED, null, null))));
        });
    }

    /** Lets the gate through again, so the shared clean-up can reset settings, which pass the gate too. */
    @AfterEach
    @SuppressWarnings("unchecked")
    void openTheGate() {
        Mockito.reset(gate);
        when(gate.run(any(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
    }

    private void holds(Duration forHowLong) {
        when(gate.run(any(), any()))
                .thenThrow(new OperationHeldException(
                        UUID.randomUUID(), "Pause 2 queues", Instant.now().plus(forHowLong)));
    }

    @SuppressWarnings("unchecked")
    private void allowsUnder(GateTicket ticket) {
        when(gate.run(any(), any())).thenAnswer(call -> {
            Supplier<Object> action = call.getArgument(1);
            return ScopedValue.where(GateScope.COVERED, ticket).call(action::get);
        });
    }

    private BulkRunDetailView pausePreview() {
        return preview(BulkOperation.PAUSE, List.of("orders.1", "orders.2"));
    }

    private BulkExecuteRequest request(BulkRunDetailView preview) {
        return new BulkExecuteRequest(preview.run().planHash(), false, false);
    }

    /** On this thread: the run is read as the operator who signed in here. */
    private void awaitFinished(UUID runId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (bulk.get(clusterId, runId).run().finishedAt() == null) {
            assertThat(Instant.now()).isBefore(deadline);
            LockSupport.parkNanos(20_000_000);
        }
    }

    @Test
    void aPreviewIsNeverGated() {
        pausePreview();

        verify(gate, never()).run(any(), any());
    }

    @Test
    void aHeldRunStartsNothingAndKeepsItsPreviewUntilTheHoldEnds() {
        BulkRunDetailView preview = pausePreview();
        UUID runId = preview.run().id();
        jdbc.update("UPDATE bulk_run SET expires_at = now() + interval '30 seconds' WHERE id = ?", runId);
        holds(Duration.ofHours(2));
        BulkExecuteRequest request = request(preview);

        assertThatThrownBy(() -> bulk.execute(clusterId, runId, request)).isInstanceOf(OperationHeldException.class);

        assertThat(bulk.get(clusterId, runId).run().status()).isEqualTo(BulkRunStatus.PREVIEWED);
        assertThat(paused).isEmpty();
        Instant keptUntil = jdbc.queryForObject("SELECT expires_at FROM bulk_run WHERE id = ?", Instant.class, runId);
        assertThat(keptUntil).isAfter(Instant.now().plus(Duration.ofHours(2)));
        ArgumentCaptor<Operation> operation = ArgumentCaptor.forClass(Operation.class);
        verify(gate).run(operation.capture(), any());
        assertThat(operation.getValue().params())
                .isEqualTo(new BulkExecuteParams(
                        clusterId, runId, preview.run().planHash(), BulkOperation.PAUSE, 2, false, false));
    }

    @Test
    void aRunThatIsNotTheOnePreviewedIsRefusedBeforeItIsGated() {
        BulkRunDetailView preview = pausePreview();

        UUID runId = preview.run().id();
        BulkExecuteRequest request = new BulkExecuteRequest("not-the-plan", false, false);

        assertThatThrownBy(() -> bulk.execute(clusterId, runId, request)).isInstanceOf(ConflictException.class);

        verify(gate, never()).run(any(), any());
    }

    @Test
    void anApprovedRunStartsOnceAndItsQueuesRunCoveredByTheApproval() {
        BulkRunDetailView preview = pausePreview();
        GateTicket ticket = new GateTicket(null, "bulk.execute", null);
        allowsUnder(ticket);
        Mockito.doAnswer(call -> Optional.of(new GateLease(ticket, () -> {})))
                .when(leases)
                .retain(ticket);
        Mockito.doAnswer(call -> {
                    operators.add(call.getArgument(0));
                    return call.callRealMethod();
                })
                .when(handoff)
                .runAs(any(), any());

        bulk.execute(clusterId, preview.run().id(), request(preview));

        awaitFinished(preview.run().id());
        assertThat(paused).containsExactlyInAnyOrder("orders.1", "orders.2");
        assertThat(operators)
                .isNotEmpty()
                .allSatisfy(operator -> assertThat(operator.covered().ticket()).isSameAs(ticket));
        verify(gate).run(any(), any());
    }

    @Test
    void aRunIsEstimatedFromItsPlanAndAChangedPlanHasAnotherStateKey() {
        BulkRunDetailView both = pausePreview();
        BulkRunDetailView one = preview(BulkOperation.PAUSE, List.of("orders.1"));

        Reach reach = bulk.reach(clusterId, both.run().id());

        assertThat(reach)
                .isEqualTo(new Reach(BulkOperation.PAUSE, 2, 0, true, both.run().planHash()));
        assertThat(bulk.reach(clusterId, one.run().id()).planHash()).isNotEqualTo(reach.planHash());
    }

    @Test
    void aDestructiveRunIsEstimatedInMessages() {
        BulkRunDetailView purge = preview(BulkOperation.PURGE, List.of("orders.1", "orders.2"));

        assertThat(bulk.reach(clusterId, purge.run().id()).messages()).isEqualTo(12);
    }

    @Test
    void aHeldRunIsA202NamingTheHeldRequest() throws Exception {
        BulkRunDetailView preview = pausePreview();
        holds(Duration.ofHours(1));
        MockMvc mvc = webAppContextSetup(webContext).build();

        mvc.perform(post(
                                "/api/v1/clusters/{c}/bulk/runs/{r}/execute",
                                clusterId,
                                preview.run().id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planHash\":\"%s\",\"override\":false,\"continueOnFailure\":false}"
                                .formatted(preview.run().planHash())))
                .andExpect(status().isAccepted())
                .andExpect(header().exists(GateContext.HELD_HEADER))
                .andExpect(jsonPath("$.outcome").value("held"))
                .andExpect(jsonPath("$.heldOperation.summary").value("Pause 2 queues"));
    }
}
