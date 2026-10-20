package io.github.sudoitir.rahdar.feature.bulk;

import io.github.sudoitir.rahdar.feature.bulk.BulkRunMapper.ItemEstimate;
import io.github.sudoitir.rahdar.feature.bulk.BulkRunMapper.RunOptions;
import io.github.sudoitir.rahdar.feature.bulk.internal.persistence.BulkRunEntity;
import io.github.sudoitir.rahdar.feature.bulk.internal.persistence.BulkRunItemEntity;
import io.github.sudoitir.rahdar.feature.bulk.internal.persistence.BulkRunItemRepository;
import io.github.sudoitir.rahdar.feature.bulk.internal.persistence.BulkRunRepository;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkProgress;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.NodeFigure;
import io.github.sudoitir.rahdar.feature.messages.MessageService;
import io.github.sudoitir.rahdar.feature.resources.QueueLifecycleService;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditScope;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.jobs.BackgroundRuns;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff.Operator;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes a bulk run as a {@link BackgroundRuns} run (ADR-0093 D4): one queue at a time, in preview
 * order, each through the single-queue command as the operator who executed the run and under the
 * run's audit event. Nothing here re-implements a safety check; the commands carry them.
 *
 * <p>The run's rows are written only while this replica still executes the run (ADR-0152): every write
 * takes the run's row lock first ({@link BulkRunRepository#fence}) in the same transaction, so a run
 * that recovery interrupted, because this replica's heartbeat lapsed, is never overwritten. The runner
 * then stops acting: no further queue is touched and the run's audit event is left as recovery wrote it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BulkRunner {

    static final String TOPIC = "bulk";

    static final String SHUTDOWN = "Rahdar shut down while this run was executing, so the rest of it was not acted on."
            + " What was done is recorded. It was not resumed.";

    private final BulkRunRepository runs;
    private final BulkRunItemRepository items;
    private final QueueLifecycleService queues;
    private final MessageService messages;
    private final OperatorHandoff handoff;
    private final AuditService audit;
    private final SseHub sse;
    private final ObjectMapper json;
    private final BackgroundRuns background;
    private final ReplicaRegistry replicas;
    private final TransactionTemplate tx;

    /** This replica no longer holds the run: someone else marked it, or this replica cannot prove it is alive. */
    private static final class RunLost extends RuntimeException {
        RunLost(String why) {
            super(why, null, false, false);
        }
    }

    void start(BulkRunEntity run, AuditEvent event, Operator operator) {
        background.start(run.getId(), operator, () -> execute(run, event, operator));
    }

    /** False when the run is not executing in this process. */
    boolean requestStop(UUID runId) {
        return background.requestStop(runId);
    }

    /** Tell every replica to stop the run, for one that executes somewhere else. */
    void signalStop(UUID runId) {
        background.signalStop(runId);
    }

    private void execute(BulkRunEntity run, AuditEvent event, Operator operator) {
        BooleanSupplier stop = () -> background.stopRequested(run.getId());
        List<BulkRunItemEntity> rows = items.findByRunIdOrderByOrdinal(run.getId());
        String error = null;
        boolean lost = false;
        try {
            processPending(run, rows, stop, event, operator);
        } catch (RunLost e) {
            lost = true;
            log.warn(
                    "Bulk run {} is no longer executing on this replica ({}); stopped without writing",
                    run.getId(),
                    e.getMessage());
        } catch (RuntimeException e) {
            // A failure outside any one queue's command, such as the database going away.
            log.error("Bulk run {} stopped unexpectedly", run.getId(), e);
            error = "The run stopped unexpectedly: " + e.getMessage();
        } finally {
            if (!lost) {
                finish(run, rows, event, stop.getAsBoolean(), background.stoppedForShutdown(run.getId()), error);
            }
        }
    }

    private void processPending(
            BulkRunEntity run,
            List<BulkRunItemEntity> rows,
            BooleanSupplier stop,
            AuditEvent event,
            Operator operator) {
        boolean halted = false;
        for (BulkRunItemEntity item : rows) {
            if (item.getStatus() != BulkItemStatus.PENDING) {
                continue;
            }
            checkAlive(run, stop);
            if (stop.getAsBoolean() || halted) {
                item.finish(
                        stop.getAsBoolean() ? BulkItemStatus.CANCELLED : BulkItemStatus.SKIPPED,
                        null,
                        null,
                        null,
                        Instant.now());
                write(run, () -> items.save(item));
            } else {
                actOn(run, item, event, operator);
                progress(run, rows, BulkRunStatus.RUNNING);
                boolean bad = item.getStatus() == BulkItemStatus.FAILED || item.getStatus() == BulkItemStatus.PARTIAL;
                halted = bad && !run.isContinueOnFailure();
            }
        }
    }

    /** Throws {@link RunLost} when this replica's heartbeat has lapsed, and picks up a stop asked on another replica. */
    private void checkAlive(BulkRunEntity run, BooleanSupplier stop) {
        if (!replicas.heartbeatFresh()) {
            throw new RunLost("this replica's heartbeat has lapsed");
        }
        // A stop asked on another replica whose signal did not reach this one is on the row.
        if (!stop.getAsBoolean() && runs.existsByIdAndStopRequestedAtIsNotNull(run.getId())) {
            background.requestStop(run.getId());
        }
    }

    /**
     * Runs {@code writes} in one transaction that first takes the run's row lock, and only while this
     * replica still executes the run.
     *
     * @throws RunLost when it does not, so nothing is written
     */
    private void write(BulkRunEntity run, Runnable writes) {
        boolean held = Boolean.TRUE.equals(tx.execute(status -> {
            if (runs.fence(run.getId(), replicas.id()) == 0) {
                return false;
            }
            writes.run();
            return true;
        }));
        if (!held) {
            throw new RunLost("the run was taken over");
        }
    }

    private void actOn(BulkRunEntity run, BulkRunItemEntity item, AuditEvent event, Operator operator) {
        item.begin(Instant.now());
        write(run, () -> items.save(item));
        LifecycleOutcome[] result = new LifecycleOutcome[1];
        String[] failure = new String[1];
        String permission = run.getOperation().permission();
        if (!handoff.stillHolds(operator, run.getClusterId(), ResourceRef.queue(item.getQueueName()), permission)) {
            failure[0] =
                    "The permission %s on this queue is no longer held, so it was not acted on.".formatted(permission);
        } else {
            handoff.runAs(
                    operator,
                    () -> ScopedValue.where(AuditScope.PARENT, event.getId()).run(() -> {
                        try {
                            result[0] = command(run, item);
                        } catch (RuntimeException e) {
                            // One queue's failure is that queue's outcome, never the run's death.
                            failure[0] = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        }
                    }));
        }
        if (result[0] == null) {
            item.finish(BulkItemStatus.FAILED, failure[0], null, null, Instant.now());
        } else {
            List<NodeOutcome> nodes = result[0].nodes();
            item.finish(
                    classify(nodes),
                    failureSummary(nodes),
                    result[0].totalAffected(),
                    json.writeValueAsString(nodes),
                    Instant.now());
        }
        write(run, () -> items.save(item));
    }

    /** The single-queue command for this operation, with the run's override and options. */
    private LifecycleOutcome command(BulkRunEntity run, BulkRunItemEntity item) {
        UUID clusterId = run.getClusterId();
        String queue = item.getQueueName();
        return switch (run.getOperation()) {
            case PAUSE -> unwrap(queues.setPaused(clusterId, queue, true, false));
            case RESUME -> unwrap(queues.setPaused(clusterId, queue, false, false));
            case DELETE ->
                unwrap(queues.deleteQueue(
                        clusterId,
                        queue,
                        false,
                        run.isOverrideCap(),
                        json.readValue(run.getOptions(), RunOptions.class).disconnectConsumers()));
            case PURGE -> purge(run, item);
        };
    }

    /** A purge is one node per call, so it is issued on every node hosting the queue and folded into one outcome. */
    private LifecycleOutcome purge(BulkRunEntity run, BulkRunItemEntity item) {
        List<NodeOutcome> nodes = new ArrayList<>();
        for (NodeFigure node :
                json.readValue(item.getEstimate(), ItemEstimate.class).nodes()) {
            NodeOutcome outcome;
            try {
                outcome = switch (messages.purge(
                        run.getClusterId(), item.getQueueName(), node.nodeId(), false, run.isOverrideCap())) {
                    case Attempt.Ok<MessageService.Outcome>(MessageService.Outcome.Affected affected) ->
                        new NodeOutcome(node.nodeId(), node.nodeName(), NodeStatus.APPLIED, affected.count(), null);
                    case Attempt.Ok<MessageService.Outcome>(var other) ->
                        NodeOutcome.failed(node.nodeId(), node.nodeName(), "Unexpected purge result: " + other);
                    case Attempt.Failed<MessageService.Outcome> failed ->
                        NodeOutcome.failed(node.nodeId(), node.nodeName(), failed.detail());
                };
            } catch (RuntimeException e) {
                outcome = NodeOutcome.failed(node.nodeId(), node.nodeName(), e.getMessage());
            }
            nodes.add(outcome);
        }
        return new LifecycleOutcome(false, 0, false, nodes);
    }

    private static LifecycleOutcome unwrap(Attempt<LifecycleOutcome> attempt) {
        return switch (attempt) {
            case Attempt.Ok<LifecycleOutcome>(var value) -> value;
            case Attempt.Failed<LifecycleOutcome>(var _, var detail) -> throw new IllegalStateException(detail);
        };
    }

    /** Every node that acted applied or was already there: succeeded; some failed: partial; none acted: failed. */
    static BulkItemStatus classify(List<NodeOutcome> nodes) {
        long ok = nodes.stream()
                .filter(n -> n.status() == NodeStatus.APPLIED || n.status() == NodeStatus.ALREADY)
                .count();
        long bad = nodes.stream().filter(n -> n.status() == NodeStatus.FAILED).count();
        if (ok > 0 && bad == 0) {
            return BulkItemStatus.SUCCEEDED;
        }
        return ok > 0 ? BulkItemStatus.PARTIAL : BulkItemStatus.FAILED;
    }

    private static String failureSummary(List<NodeOutcome> nodes) {
        List<String> failed = nodes.stream()
                .filter(n -> n.status() == NodeStatus.FAILED)
                .map(n -> n.nodeName() + ": " + n.error())
                .toList();
        if (!failed.isEmpty()) {
            return String.join(" | ", failed);
        }
        return nodes.stream().anyMatch(n -> n.status() != NodeStatus.SKIPPED_NOT_LIVE)
                ? null
                : "No node hosting this queue was live, so it was not acted on.";
    }

    private void progress(BulkRunEntity run, List<BulkRunItemEntity> rows, BulkRunStatus status) {
        int succeeded =
                (int) rows.stream().filter(i -> i.getStatus().succeeded()).count();
        int failed = (int) rows.stream().filter(i -> i.getStatus().failed()).count();
        int skipped = (int) rows.stream().filter(i -> i.getStatus().skipped()).count();
        run.count(succeeded, failed, skipped);
        write(run, () -> runs.save(run));
        // The run is about its queues: whoever reads one of them is told of its progress.
        sse.publishAbout(
                run.getClusterId(),
                TOPIC,
                new BulkProgress(run.getId(), status, succeeded, failed, skipped, run.getTotalItems()),
                rows.stream().map(BulkRunItemEntity::getQueueName).toList(),
                List.of());
    }

    private static BulkRunStatus terminalStatus(
            List<BulkRunItemEntity> rows, boolean stopped, boolean shutdown, long succeeded) {
        if (stopped && rows.stream().anyMatch(i -> i.getStatus() == BulkItemStatus.CANCELLED)) {
            return shutdown ? BulkRunStatus.INTERRUPTED : BulkRunStatus.STOPPED;
        }
        if (succeeded == rows.size()) {
            return BulkRunStatus.SUCCEEDED;
        }
        return succeeded == 0 ? BulkRunStatus.FAILED : BulkRunStatus.PARTIAL;
    }

    /**
     * Always reached while this replica still holds the run: the run gets a terminal status and its audit
     * event an outcome, whatever happened. A run that was taken over is left to whoever took it, audit
     * event included.
     */
    private void finish(
            BulkRunEntity run,
            List<BulkRunItemEntity> rows,
            AuditEvent event,
            boolean stopped,
            boolean shutdown,
            String error) {
        Instant now = Instant.now();
        try {
            for (BulkRunItemEntity item : rows) {
                // Only after an unexpected failure can an item be left unfinished.
                if (item.getStatus() == BulkItemStatus.PENDING || item.getStatus() == BulkItemStatus.RUNNING) {
                    item.finish(
                            item.getStatus() == BulkItemStatus.RUNNING
                                    ? BulkItemStatus.UNKNOWN
                                    : BulkItemStatus.CANCELLED,
                            item.getStatus() == BulkItemStatus.RUNNING ? error : null,
                            null,
                            null,
                            now);
                    write(run, () -> items.save(item));
                }
            }
        } catch (RunLost e) {
            tookOver(run, e);
            return;
        }
        long succeeded = rows.stream().filter(i -> i.getStatus().succeeded()).count();
        BulkRunStatus status = terminalStatus(rows, stopped, shutdown, succeeded);
        if (status == BulkRunStatus.INTERRUPTED) {
            error = SHUTDOWN;
        }
        boolean lost = false;
        try {
            run.finish(status, error, now);
            progress(run, rows, status);
        } catch (RunLost e) {
            lost = true;
            tookOver(run, e);
        } finally {
            if (!lost) {
                finishAudit(run, rows, event, status, succeeded, error);
            }
        }
    }

    private static void tookOver(BulkRunEntity run, RunLost e) {
        log.warn("Bulk run {} was taken over before it could finish ({}); left as it is", run.getId(), e.getMessage());
    }

    private void finishAudit(
            BulkRunEntity run,
            List<BulkRunItemEntity> rows,
            AuditEvent event,
            BulkRunStatus status,
            long succeeded,
            String error) {
        long affected = rows.stream()
                .map(BulkRunItemEntity::getAffected)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        String summary = status == BulkRunStatus.SUCCEEDED
                ? null
                : Objects.requireNonNullElse(
                        error, "%s: %d of %d queues succeeded.".formatted(status, succeeded, rows.size()));
        audit.finish(
                event,
                status != BulkRunStatus.SUCCEEDED,
                affected,
                summary,
                Map.of("runId", run.getId().toString(), "status", status.name()));
    }
}
