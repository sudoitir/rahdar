package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferLedger;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunEntity;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunRepository;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/**
 * The run's bookkeeping against the real schema, and the API's refusals over HTTP with the payloads the
 * frontend sends (ADR-0096). Nothing here reaches a broker: every request is refused before the run
 * would start, and the end-to-end runs are exercised against real brokers elsewhere.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class TransferRunStoreTest extends PostgresIntegrationTest {

    private static final String NO_WARNINGS = "[]";

    @Autowired
    TransferRunRepository runs;

    @Autowired
    TransferLedger ledger;

    @Autowired
    TransferRecovery recovery;

    @Autowired
    ReplicaRegistry replicas;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    SettingsService settings;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WebApplicationContext webContext;

    MockMvc mvc;
    UUID source;
    UUID target;

    @BeforeEach
    void clusters() {
        mvc = webAppContextSetup(webContext).build();
        source = clusters.save(new ClusterEntity("src-" + UUID.randomUUID(), null, null))
                .getId();
        target = clusters.save(new ClusterEntity("dst-" + UUID.randomUUID(), null, null))
                .getId();
    }

    @AfterEach
    void cleanUp() {
        settings.reset(TransferSettings.MAX_CONCURRENT_RUNS);
        clusters.deleteById(source);
        clusters.deleteById(target);
    }

    private TransferRunEntity preview(TransferMode mode, String queue, String findings, Instant expiresAt) {
        Instant now = Instant.now();
        return runs.save(TransferRunEntity.builder()
                .mode(mode)
                .sourceClusterId(source)
                .sourceNodeId(UUID.randomUUID())
                .sourceNodeName("a")
                .sourceNativeNodeId("node-a")
                .sourceQueue(queue)
                .sourceAddress(queue)
                .sourceRoutingType("ANYCAST")
                .targetClusterId(target)
                .targetNodeId(UUID.randomUUID())
                .targetNodeName("c")
                .targetNativeNodeId("node-c")
                .targetQueue(queue)
                .targetAddress(queue)
                .targetRoutingType("ANYCAST")
                .sameNode(false)
                .selection("{\"kind\":\"ALL\",\"ids\":null,\"filter\":null}")
                .findings(findings)
                .t0(now)
                .planHash("hash")
                .estimate(10L)
                .estimateBytes(1000L)
                .username("test-admin")
                .createdAt(now)
                .expiresAt(expiresAt)
                .build());
    }

    private TransferRunEntity preview(TransferMode mode, String queue) {
        return preview(mode, queue, NO_WARNINGS, Instant.now().plus(Duration.ofMinutes(10)));
    }

    private void state(TransferRunEntity run, TransferState state) {
        jdbc.update("UPDATE transfer_run SET state = ? WHERE id = ?", state.name(), run.getId());
    }

    // ---- store ---------------------------------------------------------------------

    @Test
    void theHistoryIsPaged() throws Exception {
        for (String queue : new String[] {"a", "b", "c"}) {
            state(preview(TransferMode.MOVE, queue), TransferState.SUCCEEDED);
        }

        mvc.perform(get("/api/v1/clusters/{c}/transfers/runs", source).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/v1/clusters/{c}/transfers/runs", source)
                        .param("size", "2")
                        .param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void theLedgerRecordsEachCopiedIdOnce() {
        UUID runId = preview(TransferMode.COPY, "orders").getId();

        ledger.recordCopied(runId, List.of(1L, 2L, 3L));
        ledger.recordCopied(runId, List.of(3L, 4L));

        assertThat(ledger.count(runId)).isEqualTo(4);
        assertThat(ledger.known(runId, List.of(2L, 4L, 9L))).isEqualTo(Set.of(2L, 4L));
        ledger.forget(runId);
        assertThat(ledger.count(runId)).isZero();
    }

    @Test
    void aRunIsClaimedOnce() {
        TransferRunEntity run = preview(TransferMode.MOVE, "orders");

        assertThat(runs.transition(run.getId(), Set.of(TransferState.PREVIEWED), TransferState.RUNNING))
                .isEqualTo(1);
        assertThat(runs.transition(run.getId(), Set.of(TransferState.PREVIEWED), TransferState.RUNNING))
                .isZero();
    }

    @Test
    void onlyOneRunIsActiveOnASourceQueue() {
        TransferRunEntity first = preview(TransferMode.MOVE, "orders");
        TransferRunEntity second = preview(TransferMode.COPY, "orders");
        TransferRunEntity elsewhere = preview(TransferMode.MOVE, "payments");
        runs.transition(first.getId(), Set.of(TransferState.PREVIEWED), TransferState.WAITING_FOR_CAPACITY);

        UUID secondId = second.getId();
        Set<TransferState> previewed = Set.of(TransferState.PREVIEWED);
        assertThatThrownBy(() -> runs.transition(secondId, previewed, TransferState.RUNNING))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(runs.transition(elsewhere.getId(), Set.of(TransferState.PREVIEWED), TransferState.RUNNING))
                .isEqualTo(1);
    }

    @Test
    void aRunCutOffByAStopIsInterruptedAndResumable() {
        TransferRunEntity run = preview(TransferMode.MOVE, "orders");
        state(run, TransferState.WAITING_FOR_CAPACITY);

        recovery.recover();

        TransferRunEntity after = runs.findById(run.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(TransferState.INTERRUPTED);
        assertThat(after.getState().resumable()).isTrue();
        assertThat(after.getLastError()).isEqualTo(TransferRecovery.INTERRUPTED);
    }

    @Test
    void aRunOfALiveReplicaIsLeftAloneAndOneOfAGoneReplicaIsInterrupted() {
        TransferRunEntity live = preview(TransferMode.MOVE, "live");
        TransferRunEntity gone = preview(TransferMode.MOVE, "gone");
        state(live, TransferState.RUNNING);
        state(gone, TransferState.RUNNING);
        jdbc.update("UPDATE transfer_run SET replica_id = ? WHERE id = ?", replicas.id(), live.getId());
        jdbc.update("UPDATE transfer_run SET replica_id = ? WHERE id = ?", UUID.randomUUID(), gone.getId());

        recovery.recover();

        assertThat(runs.findById(live.getId()).orElseThrow().getState()).isEqualTo(TransferState.RUNNING);
        assertThat(runs.findById(gone.getId()).orElseThrow().getState()).isEqualTo(TransferState.INTERRUPTED);
    }

    @Test
    void stoppingARunThatExecutesElsewhereRecordsTheRequestOnTheRunUntilItIsResumed() throws Exception {
        TransferRunEntity run = preview(TransferMode.MOVE, "elsewhere");
        state(run, TransferState.RUNNING);
        jdbc.update("UPDATE transfer_run SET replica_id = ? WHERE id = ?", UUID.randomUUID(), run.getId());

        mvc.perform(post("/api/v1/clusters/{c}/transfers/runs/{r}/stop", source, run.getId()))
                .andExpect(status().isOk());

        assertThat(runs.existsByIdAndStopRequestedAtIsNotNull(run.getId()))
                .as("a stop whose signal is lost is still on the row")
                .isTrue();
        state(run, TransferState.INTERRUPTED);
        assertThat(runs.transition(run.getId(), TransferState.RESUMABLE, TransferState.RUNNING))
                .isOne();
        assertThat(runs.existsByIdAndStopRequestedAtIsNotNull(run.getId()))
                .as("the resumed segment starts without it")
                .isFalse();
    }

    // ---- API refusals --------------------------------------------------------------

    private ResultActions execute(UUID cluster, TransferRunEntity run, String body) throws Exception {
        return mvc.perform(post("/api/v1/clusters/{c}/transfers/runs/{r}/execute", cluster, run.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void refusesAnExecuteMissingAPrimitive() throws Exception {
        execute(source, preview(TransferMode.COPY, "orders"), """
                        {"planHash":"hash","acknowledged":[],"confirmQueue":null}
                        """).andExpect(status().isBadRequest());
    }

    @Test
    void refusesAPlanThatWasNotPreviewed() throws Exception {
        execute(source, preview(TransferMode.COPY, "orders"), """
                        {"planHash":"other","override":false,"acknowledged":[],"confirmQueue":null}
                        """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("transfer-plan-mismatch")));
    }

    @Test
    void refusesAnExpiredPreview() throws Exception {
        TransferRunEntity run =
                preview(TransferMode.COPY, "orders", NO_WARNINGS, Instant.now().minusSeconds(1));

        execute(source, run, """
                        {"planHash":"hash","override":false,"acknowledged":[],"confirmQueue":null}
                        """)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value(endsWith("transfer-preview-expired")));
    }

    @Test
    void refusesWhatThePreviewRefused() throws Exception {
        TransferRunEntity run = preview(
                TransferMode.COPY,
                "orders",
                "[{\"kind\":\"REFUSE\",\"code\":\"address-full-drop\",\"words\":\"Drops.\",\"snippet\":null}]",
                Instant.now().plusSeconds(600));

        execute(source, run, """
                        {"planHash":"hash","override":false,"acknowledged":[],"confirmQueue":null}
                        """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(endsWith("transfer-refused")));
    }

    @Test
    void refusesAnUnacknowledgedWarning() throws Exception {
        TransferRunEntity run = preview(
                TransferMode.COPY,
                "orders",
                "[{\"kind\":\"WARN\",\"code\":\"last-value\",\"words\":\"Collapses.\",\"snippet\":null},"
                        + "{\"kind\":\"UNKNOWN\",\"code\":\"disk-unknown\",\"words\":\"?\",\"snippet\":null}]",
                Instant.now().plusSeconds(600));

        execute(source, run, """
                        {"planHash":"hash","override":false,"acknowledged":[],"confirmQueue":null}
                        """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(endsWith("transfer-unacknowledged")))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("last-value")));
    }

    @Test
    void refusesAnUnconfirmedMove() throws Exception {
        execute(source, preview(TransferMode.MOVE, "orders"), """
                        {"planHash":"hash","override":false,"acknowledged":[],"confirmQueue":"order"}
                        """)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(endsWith("transfer-confirmation-mismatch")));
    }

    @Test
    void refusesARunOverTheConcurrencyLimit() throws Exception {
        settings.put(TransferSettings.MAX_CONCURRENT_RUNS, "1");
        runs.transition(
                preview(TransferMode.COPY, "busy").getId(), Set.of(TransferState.PREVIEWED), TransferState.RUNNING);

        execute(source, preview(TransferMode.MOVE, "orders"), """
                        {"planHash":"hash","override":false,"acknowledged":[],"confirmQueue":"orders"}
                        """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("transfer-concurrency-limit")));
    }

    @Test
    void refusesToStopARunThatIsNotRunning() throws Exception {
        mvc.perform(post(
                        "/api/v1/clusters/{c}/transfers/runs/{r}/stop",
                        source,
                        preview(TransferMode.COPY, "orders").getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("transfer-run-not-running")));
    }

    @Test
    void acceptsTheStopOfARunThatExecutesOnAnotherReplica() throws Exception {
        TransferRunEntity run = preview(TransferMode.COPY, "elsewhere");
        state(run, TransferState.RUNNING);
        jdbc.update("UPDATE transfer_run SET replica_id = ? WHERE id = ?", UUID.randomUUID(), run.getId());

        mvc.perform(post("/api/v1/clusters/{c}/transfers/runs/{r}/stop", source, run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"));
    }

    @Test
    void refusesToResumeAPreview() throws Exception {
        mvc.perform(post(
                        "/api/v1/clusters/{c}/transfers/runs/{r}/resume",
                        source,
                        preview(TransferMode.COPY, "orders").getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("transfer-run-not-resumable")));
    }

    @Test
    void refusesToReturnACopy() throws Exception {
        TransferRunEntity run = preview(TransferMode.COPY, "orders");
        state(run, TransferState.STOPPED);

        mvc.perform(post("/api/v1/clusters/{c}/transfers/runs/{r}/return", source, run.getId()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(endsWith("transfer-not-returnable")));
    }

    @Test
    void aRunIsListedOnBothClustersAndHiddenElsewhere() throws Exception {
        TransferRunEntity run = preview(TransferMode.MOVE, "orders");
        state(run, TransferState.STOPPED);
        UUID other = clusters.save(new ClusterEntity("x-" + UUID.randomUUID(), null, null))
                .getId();
        try {
            for (UUID cluster : List.of(source, target)) {
                mvc.perform(get("/api/v1/clusters/{c}/transfers/runs", cluster))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data[?(@.id == '%s')].state".formatted(run.getId()))
                                .value("STOPPED"));
            }
            mvc.perform(get("/api/v1/clusters/{c}/transfers/runs/{r}", target, run.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resumable").value(true))
                    .andExpect(jsonPath("$.returnable").value(true))
                    .andExpect(jsonPath("$.stagingQueue").value("rahdar.transfer." + run.getId()));
            mvc.perform(get("/api/v1/clusters/{c}/transfers/runs/{r}", other, run.getId()))
                    .andExpect(status().isNotFound());
        } finally {
            clusters.deleteById(other);
        }
    }
}
