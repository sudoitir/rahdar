package io.github.sudoitir.rahdar.feature.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkExecuteRequest;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkItemView;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkRunDetailView;
import io.github.sudoitir.rahdar.feature.messages.MessageService;
import io.github.sudoitir.rahdar.feature.resources.QueueLifecycleService;
import io.github.sudoitir.rahdar.kernel.audit.AuditScope;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.jobs.BackgroundRuns;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.support.OperatorFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The run engine (ADR-0093 D4–D6): one queue at a time through the single-queue commands, which are
 * mocked here — their own tests prove what each does to a broker. What is under test is the order,
 * the failure policy, stop, the identity the worker acts as, the audit linkage and recovery.
 */
class BulkRunTest extends BulkTestSupport {

    @MockitoBean
    QueueLifecycleService queues;

    @MockitoBean
    MessageService messages;

    @Autowired
    ActorResolver actors;

    @Autowired
    BulkRecovery recovery;

    @Autowired
    ReplicaRegistry replicas;

    @Autowired
    BackgroundRuns background;

    private final List<String> seen = new CopyOnWriteArrayList<>();

    private void fourQueues() {
        for (String q : List.of("orders.1", "orders.2", "orders.3", "orders.4")) {
            queue(nodeA, q, 1, 0, false);
        }
    }

    private void pauseAnswers(String failing) {
        when(queues.setPaused(eq(clusterId), anyString(), eq(true), eq(false))).thenAnswer(call -> {
            String q = call.getArgument(1);
            seen.add(q);
            return ok(q.equals(failing) ? NodeStatus.FAILED : NodeStatus.APPLIED);
        });
    }

    private Attempt<LifecycleOutcome> ok(NodeStatus... statuses) {
        List<NodeOutcome> nodes = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            UUID node = i == 0 ? nodeA : nodeB;
            nodes.add(new NodeOutcome(
                    node, i == 0 ? "a" : "b", statuses[i], null, statuses[i] == NodeStatus.FAILED ? "refused" : null));
        }
        return new Attempt.Ok<>(new LifecycleOutcome(false, 1000, false, nodes));
    }

    private BulkRunDetailView execute(BulkRunDetailView preview, boolean continueOnFailure) {
        bulk.execute(
                clusterId,
                preview.run().id(),
                new BulkExecuteRequest(preview.run().planHash(), false, continueOnFailure));
        return awaitFinished(preview.run().id());
    }

    private BulkRunDetailView awaitFinished(UUID runId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            BulkRunDetailView run = bulk.get(clusterId, runId);
            if (run.run().finishedAt() != null) {
                return run;
            }
            LockSupport.parkNanos(20_000_000);
        }
        throw new AssertionError("The run did not finish");
    }

    private static List<BulkItemStatus> statuses(BulkRunDetailView run) {
        return run.items().stream().map(BulkItemView::status).toList();
    }

    /**
     * The run's audit outcome, once written. The runner commits the run's terminal status and then the
     * audit outcome, each in its own transaction (ADR-0078), so a reader that has just seen the run
     * finish can still find the audit event pending for a moment.
     */
    private String auditOutcome(Long id) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        String outcome;
        do {
            outcome = jdbc.queryForObject("SELECT outcome FROM audit_event WHERE id = ?", String.class, id);
            if (!"PENDING".equals(outcome)) {
                return outcome;
            }
            LockSupport.parkNanos(20_000_000);
        } while (Instant.now().isBefore(deadline));
        return outcome;
    }

    @Test
    void theRunStopsAtTheFirstFailureByDefault() {
        fourQueues();
        pauseAnswers("orders.2");

        BulkRunDetailView run = execute(preview(BulkOperation.PAUSE, "orders"), false);

        assertThat(statuses(run))
                .containsExactly(
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.FAILED,
                        BulkItemStatus.SKIPPED,
                        BulkItemStatus.SKIPPED);
        assertThat(run.items().get(1).error()).contains("refused");
        assertThat(run.run().status()).isEqualTo(BulkRunStatus.PARTIAL);
        assertThat(run.run().succeeded()).isEqualTo(1);
        assertThat(run.run().failed()).isEqualTo(1);
        assertThat(run.run().skipped()).isEqualTo(2);
        assertThat(seen).containsExactly("orders.1", "orders.2");
        assertThat(auditOutcome(run.run().auditEventId())).isEqualTo("FAILURE");
    }

    @Test
    void continuingPastFailuresActsOnEveryQueue() {
        fourQueues();
        pauseAnswers("orders.2");

        BulkRunDetailView run = execute(preview(BulkOperation.PAUSE, "orders"), true);

        assertThat(statuses(run))
                .containsExactly(
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.FAILED,
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.SUCCEEDED);
        assertThat(run.run().status()).isEqualTo(BulkRunStatus.PARTIAL);
    }

    @Test
    void aQueueAppliedOnSomeNodesIsPartialWithEachNodesOutcome() {
        queue(nodeA, "orders.1", 1, 0, false);
        queue(nodeB, "orders.1", 1, 0, false);
        when(queues.deleteQueue(clusterId, "orders.1", false, false, false))
                .thenReturn(ok(NodeStatus.APPLIED, NodeStatus.FAILED));

        BulkRunDetailView run = execute(preview(BulkOperation.DELETE, "orders"), false);

        BulkItemView item = run.items().getFirst();
        assertThat(item.status()).isEqualTo(BulkItemStatus.PARTIAL);
        assertThat(item.outcome())
                .extracting(NodeOutcome::status)
                .containsExactly(NodeStatus.APPLIED, NodeStatus.FAILED);
        assertThat(run.run().status()).isEqualTo(BulkRunStatus.FAILED);
    }

    @Test
    void aPurgeActsOnceOnEveryHostingNode() {
        queue(nodeA, "orders.1", 3, 0, false);
        queue(nodeB, "orders.1", 4, 0, false);
        when(messages.purge(clusterId, "orders.1", nodeA, false, false))
                .thenReturn(new Attempt.Ok<>(new MessageService.Outcome.Affected(3, nodeA)));
        when(messages.purge(clusterId, "orders.1", nodeB, false, false))
                .thenReturn(new Attempt.Ok<>(new MessageService.Outcome.Affected(4, nodeB)));

        BulkRunDetailView run = execute(preview(BulkOperation.PURGE, "orders"), false);

        assertThat(run.items().getFirst().status()).isEqualTo(BulkItemStatus.SUCCEEDED);
        assertThat(run.items().getFirst().affected()).isEqualTo(7L);
        assertThat(run.run().status()).isEqualTo(BulkRunStatus.SUCCEEDED);
    }

    @Test
    void stoppingLetsTheCurrentQueueFinishAndCancelsTheRest() {
        fourQueues();
        UUID[] runId = new UUID[1];
        when(queues.setPaused(eq(clusterId), anyString(), eq(true), eq(false))).thenAnswer(call -> {
            String q = call.getArgument(1);
            seen.add(q);
            if (q.equals("orders.2")) {
                bulk.stop(clusterId, runId[0]);
            }
            return ok(NodeStatus.APPLIED);
        });
        BulkRunDetailView preview = preview(BulkOperation.PAUSE, "orders");
        runId[0] = preview.run().id();

        BulkRunDetailView run = execute(preview, false);

        assertThat(statuses(run))
                .containsExactly(
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.CANCELLED,
                        BulkItemStatus.CANCELLED);
        assertThat(run.run().status()).isEqualTo(BulkRunStatus.STOPPED);
    }

    @Test
    void aWithdrawnPermissionFailsEveryQueueNotYetActedOn() {
        fourQueues();
        when(queues.setPaused(eq(clusterId), anyString(), eq(true), eq(false))).thenAnswer(call -> {
            seen.add(call.getArgument(1));
            OperatorFixture.revokeAll(userRoles, accessChanges, userId);
            return ok(NodeStatus.APPLIED);
        });

        BulkRunDetailView run = execute(preview(BulkOperation.PAUSE, "orders"), true);

        assertThat(statuses(run))
                .containsExactly(
                        BulkItemStatus.SUCCEEDED, BulkItemStatus.FAILED, BulkItemStatus.FAILED, BulkItemStatus.FAILED);
        assertThat(run.items().get(3).error()).contains("subscription:pause").contains("no longer");
        assertThat(seen).containsExactly("orders.1");
    }

    @Test
    void eachQueueIsActedOnAsTheOperatorUnderTheRunsAuditEvent() {
        queue(nodeA, "orders.1", 1, 0, false);
        Long[] parent = new Long[1];
        UUID[] actor = new UUID[1];
        when(queues.setPaused(clusterId, "orders.1", true, false)).thenAnswer(call -> {
            parent[0] = AuditScope.PARENT.get();
            actor[0] = actors.resolve().userId();
            return ok(NodeStatus.APPLIED);
        });

        BulkRunDetailView run = execute(preview(BulkOperation.PAUSE, "orders"), false);

        assertThat(parent[0]).isEqualTo(run.run().auditEventId());
        assertThat(actor[0]).isEqualTo(userId);
        assertThat(run.run().status()).isEqualTo(BulkRunStatus.SUCCEEDED);
        assertThat(auditOutcome(run.run().auditEventId())).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject(
                        "SELECT action FROM audit_event WHERE id = ?",
                        String.class,
                        run.run().auditEventId()))
                .isEqualTo("bulk.pause");
    }

    @Test
    void executingRefusesAChangedPlanAnExpiredPreviewAndARunThatStarted() {
        queue(nodeA, "orders.1", 1, 0, false);
        when(queues.setPaused(clusterId, "orders.1", true, false)).thenReturn(ok(NodeStatus.APPLIED));
        BulkRunDetailView preview = preview(BulkOperation.PAUSE, "orders");
        UUID id = preview.run().id();

        var changedPlan = new BulkExecuteRequest("not-the-hash", false, false);
        assertThatThrownBy(() -> bulk.execute(clusterId, id, changedPlan)).isInstanceOf(ConflictException.class);

        jdbc.update("UPDATE bulk_run SET expires_at = now() - interval '1 second' WHERE id = ?", id);
        assertThatThrownBy(() -> execute(preview, false))
                .isInstanceOf(BulkRefusedException.class)
                .hasMessageContaining("expired");

        BulkRunDetailView fresh = preview(BulkOperation.PAUSE, "orders");
        execute(fresh, false);
        assertThatThrownBy(() -> execute(fresh, false)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aSecondRunOnTheClusterIsRefusedNamingTheRunInProgress() {
        queue(nodeA, "orders.1", 1, 0, false);
        BulkRunDetailView first = preview(BulkOperation.PAUSE, "orders");
        BulkRunDetailView second = preview(BulkOperation.RESUME, "orders");
        jdbc.update(
                "UPDATE bulk_run SET status = 'RUNNING' WHERE id = ?",
                first.run().id());

        assertThatThrownBy(() -> execute(second, false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(first.run().id().toString());
        verify(queues, never()).setPaused(eq(clusterId), anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    void overTheMessageCapNeedsTheOverride() {
        settings.put(BrokerSettings.BULK_CAP, "2");
        queue(nodeA, "orders.1", 5, 0, false);
        BulkRunDetailView preview = preview(BulkOperation.PURGE, "orders");

        assertThat(preview.run().overCap()).isTrue();
        assertThatThrownBy(() -> execute(preview, false)).isInstanceOf(BulkCapExceededException.class);
        verify(messages, never()).purge(eq(clusterId), anyString(), eq(nodeA), anyBoolean(), anyBoolean());
    }

    @Test
    void aRestartMidRunLeavesAnHonestRecordAndResumesNothing() {
        fourQueues();
        when(queues.setPaused(eq(clusterId), anyString(), eq(true), eq(false))).thenReturn(ok(NodeStatus.APPLIED));
        BulkRunDetailView done = execute(preview(BulkOperation.PAUSE, "orders"), false);
        UUID id = done.run().id();
        // Rewind it to what a crash while the second queue was in flight leaves behind.
        jdbc.update(
                "UPDATE bulk_run SET status = 'RUNNING', finished_at = NULL, replica_id = ? WHERE id = ?",
                UUID.randomUUID(),
                id);
        jdbc.update("UPDATE bulk_run_item SET status = 'RUNNING' WHERE run_id = ? AND ordinal = 1", id);
        jdbc.update("UPDATE bulk_run_item SET status = 'PENDING' WHERE run_id = ? AND ordinal > 1", id);
        reopenAudit(done.run().auditEventId());

        recovery.recover();

        BulkRunDetailView run = bulk.get(clusterId, id);
        assertThat(run.run().status()).isEqualTo(BulkRunStatus.INTERRUPTED);
        assertThat(statuses(run))
                .containsExactly(
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.UNKNOWN,
                        BulkItemStatus.CANCELLED,
                        BulkItemStatus.CANCELLED);
        assertThat(run.items().get(1).error()).contains("check the broker");
        assertThat(auditOutcome(done.run().auditEventId())).isEqualTo("FAILURE");
    }

    /** What a crash leaves: the record still pending. The audit guard refuses reopening one, so it is off for the rewind. */
    private void reopenAudit(Long auditEventId) {
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER tg_audit_event_guard");
        try {
            jdbc.update("UPDATE audit_event SET outcome = 'PENDING' WHERE id = ?", auditEventId);
        } finally {
            jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER tg_audit_event_guard");
        }
    }

    @Test
    void recoveryLeavesALiveReplicasRunAloneAndInterruptsAGoneReplicasRun() {
        fourQueues();
        when(queues.setPaused(eq(clusterId), anyString(), eq(true), eq(false))).thenReturn(ok(NodeStatus.APPLIED));
        BulkRunDetailView done = execute(preview(BulkOperation.PAUSE, "orders"), false);
        UUID id = done.run().id();
        assertThat(jdbc.queryForObject("SELECT replica_id FROM bulk_run WHERE id = ?", UUID.class, id))
                .as("the run records the replica that executed it")
                .isEqualTo(replicas.id());
        jdbc.update("UPDATE bulk_run SET status = 'RUNNING', finished_at = NULL WHERE id = ?", id);
        reopenAudit(done.run().auditEventId());

        recovery.recover();
        assertThat(bulk.get(clusterId, id).run().status()).isEqualTo(BulkRunStatus.RUNNING);

        jdbc.update("UPDATE bulk_run SET replica_id = ? WHERE id = ?", UUID.randomUUID(), id);
        recovery.recover();
        assertThat(bulk.get(clusterId, id).run().status()).isEqualTo(BulkRunStatus.INTERRUPTED);
    }

    @Test
    void aRunInterruptedByRecoveryWhileItExecutesIsNotOverwrittenAndStops() {
        fourQueues();
        UUID[] runId = new UUID[1];
        when(queues.setPaused(eq(clusterId), anyString(), eq(true), eq(false))).thenAnswer(call -> {
            String q = call.getArgument(1);
            seen.add(q);
            if (q.equals("orders.2")) {
                // Another replica's recovery finds this run orphaned while its second queue is in flight.
                jdbc.update("UPDATE bulk_run SET replica_id = ? WHERE id = ?", UUID.randomUUID(), runId[0]);
                recovery.recover();
            }
            return ok(NodeStatus.APPLIED);
        });
        BulkRunDetailView preview = preview(BulkOperation.PAUSE, "orders");
        runId[0] = preview.run().id();

        bulk.execute(clusterId, runId[0], new BulkExecuteRequest(preview.run().planHash(), false, false));
        await(() -> !background.isActive(runId[0]));

        BulkRunDetailView run = bulk.get(clusterId, runId[0]);
        assertThat(run.run().status()).isEqualTo(BulkRunStatus.INTERRUPTED);
        assertThat(run.run().error()).as("what recovery wrote").contains("It was not resumed");
        assertThat(statuses(run))
                .containsExactly(
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.UNKNOWN,
                        BulkItemStatus.CANCELLED,
                        BulkItemStatus.CANCELLED);
        assertThat(seen).as("no queue is touched after the takeover").containsExactly("orders.1", "orders.2");
        assertThat(jdbc.queryForObject(
                        "SELECT outcome_detail IS NULL FROM audit_event WHERE id = ?",
                        Boolean.class,
                        run.run().auditEventId()))
                .as("the audit event is left as recovery wrote it")
                .isTrue();
    }

    /** Waits for what another thread has yet to do, such as a signal travelling through the database. */
    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("The condition was not met in time");
            }
            LockSupport.parkNanos(10_000_000);
        }
    }

    @Test
    void aStopSignalFromAnotherReplicaReachesTheExecutingRun() {
        fourQueues();
        UUID[] runId = new UUID[1];
        when(queues.setPaused(eq(clusterId), anyString(), eq(true), eq(false))).thenAnswer(call -> {
            String q = call.getArgument(1);
            seen.add(q);
            if (q.equals("orders.2")) {
                // What a stop request on a replica that is not executing the run does: tell every replica.
                background.signalStop(runId[0]);
                await(() -> background.stopRequested(runId[0]));
            }
            return ok(NodeStatus.APPLIED);
        });
        BulkRunDetailView preview = preview(BulkOperation.PAUSE, "orders");
        runId[0] = preview.run().id();

        BulkRunDetailView run = execute(preview, false);

        assertThat(statuses(run))
                .containsExactly(
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.CANCELLED,
                        BulkItemStatus.CANCELLED);
        assertThat(run.run().status()).isEqualTo(BulkRunStatus.STOPPED);
    }

    @Test
    void aStopWhoseSignalWasLostStillStopsTheRun() {
        fourQueues();
        UUID[] runId = new UUID[1];
        when(queues.setPaused(eq(clusterId), anyString(), eq(true), eq(false))).thenAnswer(call -> {
            String q = call.getArgument(1);
            seen.add(q);
            if (q.equals("orders.2")) {
                // What a stop on another replica leaves when its signal never arrives here: the row alone.
                jdbc.update("UPDATE bulk_run SET stop_requested_at = now() WHERE id = ?", runId[0]);
            }
            return ok(NodeStatus.APPLIED);
        });
        BulkRunDetailView preview = preview(BulkOperation.PAUSE, "orders");
        runId[0] = preview.run().id();

        BulkRunDetailView run = execute(preview, false);

        assertThat(statuses(run))
                .containsExactly(
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.CANCELLED,
                        BulkItemStatus.CANCELLED);
        assertThat(run.run().status()).isEqualTo(BulkRunStatus.STOPPED);
    }

    @Test
    void stoppingARunThatExecutesElsewhereIsAcceptedAndOneThatIsNotRunningIsRefused() {
        queue(nodeA, "orders.1", 1, 0, false);
        UUID running = preview(BulkOperation.PAUSE, "orders").run().id();
        jdbc.update("UPDATE bulk_run SET status = 'RUNNING', replica_id = ? WHERE id = ?", UUID.randomUUID(), running);
        UUID previewed = preview(BulkOperation.RESUME, "orders").run().id();

        assertThat(bulk.stop(clusterId, running).status()).isEqualTo(BulkRunStatus.RUNNING);
        assertThat(jdbc.queryForObject(
                        "SELECT stop_requested_at IS NOT NULL FROM bulk_run WHERE id = ?", Boolean.class, running))
                .as("the stop is on the run, not only in a signal")
                .isTrue();
        assertThatThrownBy(() -> bulk.stop(clusterId, previewed)).isInstanceOf(ConflictException.class);
    }

    @Test
    void shutdownStopsARunThatOutlastsTheGraceAndRecordsItInterruptedWithItsProgress() {
        fourQueues();
        UUID[] runId = new UUID[1];
        when(queues.setPaused(eq(clusterId), anyString(), eq(true), eq(false))).thenAnswer(call -> {
            String q = call.getArgument(1);
            seen.add(q);
            if (q.equals("orders.2")) {
                await(() -> background.stoppedForShutdown(runId[0]));
            }
            return ok(NodeStatus.APPLIED);
        });
        BulkRunDetailView preview = preview(BulkOperation.PAUSE, "orders");
        runId[0] = preview.run().id();
        bulk.execute(clusterId, runId[0], new BulkExecuteRequest(preview.run().planHash(), false, false));
        await(() -> seen.contains("orders.2"));

        background.stopForShutdown(Duration.ZERO);

        BulkRunDetailView run = awaitFinished(runId[0]);
        assertThat(statuses(run))
                .containsExactly(
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.SUCCEEDED,
                        BulkItemStatus.CANCELLED,
                        BulkItemStatus.CANCELLED);
        assertThat(run.run().status()).isEqualTo(BulkRunStatus.INTERRUPTED);
        assertThat(run.run().succeeded()).isEqualTo(2);
        assertThat(run.run().error()).contains("Rahdar shut down");
        assertThat(auditOutcome(run.run().auditEventId())).isEqualTo("FAILURE");
    }
}
