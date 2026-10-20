package io.github.sudoitir.rahdar.feature.transfer;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferLedger;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunEntity;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunRepository;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.SelectionKind;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferProgress;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferSelection;
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
import io.github.sudoitir.rahdar.platform.broker.RelayLink;
import io.github.sudoitir.rahdar.platform.broker.RelayedMessages.Provenance;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.system.artemis.AcceptanceProbe;
import io.github.sudoitir.rahdar.system.artemis.AcceptanceProbe.Facts;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisSelectors;
import io.github.sudoitir.rahdar.system.artemis.MBeanNames;
import io.github.sudoitir.rahdar.system.artemis.MessageOperations;
import io.github.sudoitir.rahdar.system.artemis.StagingQueues;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes one segment of a transfer run as a {@link BackgroundRuns} run (ADR-0097): the first
 * execute, a resume, or a return. Each batch re-checks the operator's grants on both clusters and the
 * target's room, resolves the live endpoint of each side, and is paced to the run's rate.
 *
 * <ul>
 *   <li><b>Move between two nodes</b>: the selection is taken off the source into a staging queue on
 *       the source broker, never more than twice a batch at a time, and relayed from there.
 *   <li><b>Move on one node</b>: the broker moves the messages itself, a batch per call.
 *   <li><b>Copy</b>: the source is browsed and relayed, and the copied ids recorded, so a resume skips them.
 * </ul>
 *
 * <p>A segment always ends with the run in a state and its audit events finished, whatever happened,
 * unless the run was taken over (ADR-0152): every write of the run's row takes its row lock first
 * ({@link TransferRunRepository#fence}) in the same transaction, only while this replica still
 * executes it. A run that recovery interrupted, because this replica's heartbeat lapsed, is never
 * overwritten; the runner stops acting, with no further broker call, and leaves the audit events as
 * recovery wrote them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TransferRunner {

    static final String TOPIC = "transfer";

    /** Empty receives from staging, with messages still counted in it, before the run stops rather than spin. */
    private static final int EMPTY_TRIES = 3;

    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    /** How often a wait looks on the run's row for a stop whose signal did not arrive. */
    private static final Duration ROW_CHECK = Duration.ofSeconds(2);

    private final TransferRunRepository runs;
    private final TransferLedger ledger;
    private final TransferNodes nodes;
    private final TransferFaults faults;
    private final BrokerRelay relay;
    private final MessageOperations messages;
    private final StagingQueues staging;
    private final AcceptanceProbe probe;
    private final NodeCallLimiter limiter;
    private final OperatorHandoff handoff;
    private final AuditService audit;
    private final SseHub sse;
    private final SettingsService settings;
    private final BackgroundRuns background;
    private final ObjectMapper json;
    private final ReplicaRegistry replicas;
    private final TransactionTemplate tx;

    /** This replica no longer holds the run: someone else marked it, or this replica cannot prove it is alive. */
    private static final class RunLost extends RuntimeException {
        RunLost(String why) {
            super(why, null, false, false);
        }
    }

    void start(UUID runId, Operator operator) {
        background.start(runId, operator, () -> execute(runId, operator));
    }

    /** False when the run is not executing in this process. */
    boolean requestStop(UUID runId) {
        return background.requestStop(runId);
    }

    /** Tell every replica to stop the run, for one that executes somewhere else. */
    void signalStop(UUID runId) {
        background.signalStop(runId);
    }

    /** How a segment ended. {@code snippet} is the {@code broker.xml} that would have avoided a failure. */
    private record End(TransferState state, String error, String snippet) {
        static End of(TransferState state) {
            return new End(state, null, null);
        }

        static End stopped(String why) {
            return new End(TransferState.STOPPED, why, null);
        }
    }

    /** One segment's state: the run, who it acts for, and the endpoints serving each side at this batch. */
    private final class Segment {
        final TransferRunEntity run;
        final Operator operator;
        final TransferSelection selection;
        ClusterNode source;
        ClusterNode target;

        Segment(TransferRunEntity run, Operator operator) {
            this.run = run;
            this.operator = operator;
            this.selection = json.readValue(run.getSelection(), TransferSelection.class);
        }

        UUID id() {
            return run.getId();
        }

        String frozenFilter() {
            return ArtemisSelectors.frozen(selection.filter(), run.getT0());
        }

        String sourceMbean(ArtemisNode client) {
            return MBeanNames.queue(
                    client.resolveBrokerObjectName(),
                    run.getSourceAddress(),
                    run.getSourceQueue(),
                    run.getSourceRoutingType());
        }

        String stagingMbean(ArtemisNode client) {
            String queue = StagingQueues.queueName(id());
            return MBeanNames.queue(client.resolveBrokerObjectName(), queue, queue, "ANYCAST");
        }

        void resolve() {
            source = nodes.serving(run.getSourceClusterId(), run.getSourceNativeNodeId(), run.getSourceNodeName());
            target = nodes.serving(run.getTargetClusterId(), run.getTargetNativeNodeId(), run.getTargetNodeName());
        }
    }

    private void execute(UUID runId, Operator operator) {
        TransferRunEntity run = runs.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        Segment s = new Segment(run, operator);
        End end;
        try {
            if (run.getState() == TransferState.RETURNING) {
                end = giveBack(s);
            } else if (run.getMode() == TransferMode.COPY) {
                end = copy(s);
            } else if (run.isSameNode()) {
                end = moveOnNode(s);
            } else {
                end = moveStaged(s);
            }
        } catch (BrokerConnectionException | IOException e) {
            end = new End(
                    TransferState.FAILED,
                    "A node failed mid-run: %s %s"
                            .formatted(
                                    e.getMessage(),
                                    run.getMode() == TransferMode.MOVE && !run.isSameNode()
                                            ? "Every message not yet delivered is held in the staging queue. Resume once"
                                                    + " the node answers, or return them to the source."
                                            : "Resume once the node answers."),
                    null);
        } catch (RunLost e) {
            log.warn(
                    "Transfer {} is no longer executing on this replica ({}); stopped without writing",
                    runId,
                    e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.error("Transfer {} stopped unexpectedly", runId, e);
            end = new End(TransferState.FAILED, "The run stopped unexpectedly: " + e.getMessage(), null);
        }
        finish(s, end);
    }

    // ---- move between two nodes -------------------------------------------------

    private End moveStaged(Segment s) throws IOException {
        String stagingQueue = StagingQueues.queueName(s.id());
        End g = guard(s);
        if (g != null) {
            return g;
        }
        try {
            staging.create(nodes.client(s.source), s.id());
        } catch (BrokerConnectionException e) {
            return new End(
                    TransferState.FAILED,
                    "The source broker did not create the staging queue %s, so nothing was moved: %s Rahdar's broker"
                                    .formatted(stagingQueue, e.getMessage())
                            + " user needs rights on rahdar.transfer.#.",
                    BrokerXmlSnippets.STAGING_SECURITY_SETTING);
        }
        RelayLink.Route route = new RelayLink.Route(
                stagingQueue, true, null, Optional.empty(), s.run.getTargetQueue(), provenance(s, true));
        InDoubt inDoubt = new InDoubt(s.id());
        Staged progress = new Staged();
        Link link = new Link();
        try {
            while (true) {
                g = guard(s);
                if (g != null) {
                    return g;
                }
                int batch = settings.intValue(TransferSettings.BATCH_SIZE);
                ArtemisNode source = nodes.client(s.source);
                // The larger of what the broker counts in staging and what the run has put there and not
                // yet delivered: the bound holds even when either is off for a moment. The bound is on
                // held(): it never exceeds twice the batch. The broker's count can read higher for an
                // instant, because it double-counts messages moving to the relay's consumer (issue #75).
                long depth = Math.max(messages.messageCount(source, s.stagingMbean(source)), held(s.run));
                if (!progress.exhausted && depth < 2L * batch) {
                    progress.exhausted = refill(s, source, (int) (2L * batch - depth), stagingQueue);
                }
                long started = System.nanoTime();
                RelayLink.Batch b = link.to(s, route).relay(batch, inDoubt);
                End end = afterStagedBatch(s, b, inDoubt, progress, source, stagingQueue, started);
                if (end != null) {
                    return end;
                }
            }
        } finally {
            link.close();
        }
    }

    /** What a staged move has seen so far: whether the source is drained, and how many empty batches ran. */
    private static final class Staged {
        boolean exhausted;
        int empties;
    }

    /** What one relayed batch means for a staged move: an end for the segment, or null to go on. */
    private End afterStagedBatch(
            Segment s,
            RelayLink.Batch b,
            InDoubt inDoubt,
            Staged progress,
            ArtemisNode source,
            String stagingQueue,
            long started) {
        switch (b.outcome()) {
            case ADDRESS_FULL -> {
                inDoubt.refused();
                return waitForCapacity(s, "The target refused a batch because its address is full.");
            }
            case EMPTY -> {
                long left = messages.messageCount(source, s.stagingMbean(source));
                if (progress.exhausted && left == 0) {
                    return finishStaged(s, source, stagingQueue);
                }
                if (++progress.empties >= EMPTY_TRIES) {
                    return End.stopped(("%d messages are held in staging queue %s but none could be received"
                                    + " (they may be scheduled for later delivery). Resume later, or"
                                    + " return them to the source.")
                            .formatted(left, stagingQueue));
                }
            }
            case RELAYED -> {
                progress.empties = 0;
                ledger.remove(s.id(), b.sourceIds());
                delivered(s, b);
                pace(started, (long) b.delivered() + b.duplicates());
            }
        }
        return null;
    }

    /**
     * A staged move's record of the batch whose target commit Rahdar did not see finish (transfer
     * design D1). Each batch's staging ids are recorded before the target commit and removed once
     * staging lets them go; a batch the target refused for room is removed again, since a refused
     * transaction delivered none of it. What stays recorded may be on the target already, so a
     * return to source settles it with the target instead of putting it back.
     */
    private final class InDoubt implements RelayLink.Hooks {
        private final UUID runId;
        /** The last attempt's ids that were not in doubt before it. */
        private Set<Long> attempted = Set.of();

        InDoubt(UUID runId) {
            this.runId = runId;
        }

        @Override
        public void beforeTargetCommit(List<Long> sourceIds) {
            Set<Long> fresh = new HashSet<>(sourceIds);
            fresh.removeAll(ledger.known(runId, sourceIds));
            ledger.recordCopied(runId, sourceIds);
            attempted = fresh;
        }

        @Override
        public void afterTargetCommit(List<Long> sourceIds) {
            faults.afterTargetCommit(runId);
        }

        void refused() {
            ledger.remove(runId, attempted);
            attempted = Set.of();
        }
    }

    /**
     * Take up to {@code want} more selected messages off the source into staging. True when the source
     * has no more to give: every id tried, or fewer matched than asked for.
     */
    private boolean refill(Segment s, ArtemisNode source, int want, String stagingQueue) {
        String mbean = s.sourceMbean(source);
        if (s.selection.kind() == SelectionKind.IDS) {
            List<Long> ids = s.selection.ids();
            int from = s.run.getIdCursor();
            if (from >= ids.size()) {
                return true;
            }
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + want));
            BulkResult result = messages.moveByIds(source, mbean, chunk, stagingQueue);
            int taken = chunk.size() - result.notDone().size();
            s.run.staged(result.affected(), taken);
            s.run.notTransferred(taken - result.affected());
            save(s);
            if (result.partial()) {
                throw new BrokerConnectionException(
                        BrokerConnectionException.Kind.BAD_RESPONSE,
                        "Taking messages into staging stopped: " + result.error());
            }
            return s.run.getIdCursor() >= ids.size();
        }
        long moved = messages.moveMessages(source, mbean, want, s.frozenFilter(), stagingQueue, false, want);
        s.run.staged(moved, 0);
        save(s);
        return moved < want;
    }

    /** Staging is empty and the source has no more: count what could not be taken, and remove staging. */
    private End finishStaged(Segment s, ArtemisNode source, String stagingQueue) {
        s.run.expired(Math.max(0, s.run.getStaged() - s.run.getDelivered() - s.run.getReturned()));
        if (s.selection.kind() != SelectionKind.IDS) {
            s.run.notTransferred(messages.countMessages(source, s.sourceMbean(source), s.frozenFilter()));
        }
        if (!staging.destroyIfEmpty(source, stagingQueue)) {
            return End.stopped("Staging queue %s received messages again after it emptied; resume to relay them."
                    .formatted(stagingQueue));
        }
        ledger.forget(s.id());
        return completed(s);
    }

    // ---- move on one node -------------------------------------------------------

    private End moveOnNode(Segment s) {
        while (true) {
            End g = guard(s);
            if (g != null) {
                return g;
            }
            int batch = settings.intValue(TransferSettings.BATCH_SIZE);
            ArtemisNode source = nodes.client(s.source);
            String mbean = s.sourceMbean(source);
            long started = System.nanoTime();
            boolean byIds = s.selection.kind() == SelectionKind.IDS;
            if (byIds && s.run.getIdCursor() >= s.selection.ids().size()) {
                return completed(s);
            }
            long moved = byIds
                    ? moveIdChunk(s, source, mbean, batch)
                    : messages.moveMessages(
                            source, mbean, batch, s.frozenFilter(), s.run.getTargetQueue(), false, batch);
            boolean done = byIds ? s.run.getIdCursor() >= s.selection.ids().size() : moved < batch;
            s.run.delivered(moved, 0, Instant.now());
            save(s);
            publish(s);
            if (done) {
                if (!byIds) {
                    s.run.notTransferred(messages.countMessages(source, mbean, s.frozenFilter()));
                }
                return completed(s);
            }
            pace(started, moved);
        }
    }

    /** Moves the next {@code batch} selected ids straight to the target queue; how many moved. */
    private long moveIdChunk(Segment s, ArtemisNode source, String mbean, int batch) {
        List<Long> ids = s.selection.ids();
        int from = s.run.getIdCursor();
        List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + batch));
        BulkResult result = messages.moveByIds(source, mbean, chunk, s.run.getTargetQueue());
        int taken = chunk.size() - result.notDone().size();
        long moved = result.affected();
        s.run.staged(0, taken);
        s.run.notTransferred(taken - moved);
        if (result.partial()) {
            s.run.delivered(moved, 0, Instant.now());
            save(s);
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE, "The move stopped: " + result.error());
        }
        return moved;
    }

    // ---- copy ------------------------------------------------------------------

    private End copy(Segment s) throws IOException {
        Set<Long> ids = s.selection.kind() == SelectionKind.IDS ? Set.copyOf(s.selection.ids()) : null;
        // The selection's end is when the run started: a filter or the whole queue is read as it was then.
        RelayLink.Route route = new RelayLink.Route(
                s.run.getSourceQueue(),
                false,
                ids == null ? s.selection.filter() : null,
                ids == null ? Optional.of(s.run.getT0()) : Optional.empty(),
                s.run.getTargetQueue(),
                provenance(s, false));
        RelayLink.Hooks hooks = new RelayLink.Hooks() {
            @Override
            public boolean selected(long sourceId) {
                return ids == null || ids.contains(sourceId);
            }

            @Override
            public Set<Long> alreadyRelayed(List<Long> sourceIds) {
                return ledger.known(s.id(), sourceIds);
            }

            @Override
            public void afterTargetCommit(List<Long> sourceIds) {
                faults.afterTargetCommit(s.id());
                ledger.recordCopied(s.id(), sourceIds);
            }
        };
        End stopped = relayAll(s, route, hooks, ids);
        if (stopped != null) {
            return stopped;
        }
        countNotCopied(s, ids);
        ledger.forget(s.id());
        return completed(s);
    }

    /** Relays batches until the source has nothing more for the run; an end when the run must stop first. */
    private End relayAll(Segment s, RelayLink.Route route, RelayLink.Hooks hooks, Set<Long> ids) throws IOException {
        Link link = new Link();
        try {
            while (true) {
                End g = guard(s);
                if (g != null) {
                    return g;
                }
                long started = System.nanoTime();
                RelayLink.Batch b = link.to(s, route).relay(settings.intValue(TransferSettings.BATCH_SIZE), hooks);
                if (b.outcome() == RelayLink.Outcome.EMPTY) {
                    return null;
                }
                if (b.outcome() == RelayLink.Outcome.ADDRESS_FULL) {
                    End w = waitForCapacity(s, "The target refused a batch because its address is full.");
                    if (w != null) {
                        return w;
                    }
                } else {
                    delivered(s, b);
                    if (ids != null && ledger.count(s.id()) >= ids.size()) {
                        return null;
                    }
                    pace(started, (long) b.delivered() + b.duplicates());
                }
            }
        } finally {
            link.close();
        }
    }

    /** Records how many of the selection a copy left uncopied: still in delivery, scheduled or gone. */
    private void countNotCopied(Segment s, Set<Long> ids) {
        long copied = ledger.count(s.id());
        if (ids != null) {
            s.run.notTransferred(ids.size() - copied);
        } else if (s.run.getEstimate() != null) {
            // The selection's size at t0: what is not copied is in delivery, scheduled or gone.
            s.run.notTransferred(Math.max(0, s.run.getEstimate() - copied));
        } else {
            // ponytail: countMessages looks at no more than management-browse-page-size messages, so on
            // a deep queue this under-counts; only reached when the preview could not size the selection.
            ArtemisNode source = nodes.client(s.source);
            long matching = messages.countMessages(source, s.sourceMbean(source), s.frozenFilter());
            s.run.notTransferred(Math.max(0, matching - copied));
        }
    }

    // ---- return to source ----------------------------------------------------------

    /**
     * Put what staging holds back on the source queue. A batch whose delivery Rahdar did not see
     * finish is settled with the target first, by relaying it again: the target refuses what it
     * already has, as a duplicate, and takes the rest. What cannot be settled, because the target
     * does not answer, is kept in staging rather than risk a message on both brokers.
     */
    private End giveBack(Segment s) throws IOException {
        String stagingQueue = StagingQueues.queueName(s.id());
        ClusterNode node =
                nodes.serving(s.run.getSourceClusterId(), s.run.getSourceNativeNodeId(), s.run.getSourceNodeName());
        ArtemisNode source = nodes.client(node);
        String mbean = s.stagingMbean(source);
        Set<Long> unsettled = settle(s, node, ledger.all(s.id()));
        if (!unsettled.isEmpty()) {
            List<Long> safe = messages.listIds(source, mbean).stream()
                    .filter(id -> !unsettled.contains(id))
                    .toList();
            BulkResult result = messages.moveByIds(source, mbean, safe, s.run.getSourceQueue());
            s.run.returned(result.affected());
            save(s);
            publish(s);
            return new End(
                    TransferState.FAILED,
                    ("%d messages in staging queue %s may already be on the target: Rahdar stopped seeing their"
                                    + " delivery before it finished, and the target did not answer to settle it. They"
                                    + " were kept rather than risk a message on both brokers; resume the run, or"
                                    + " return again once the target answers.")
                            .formatted(unsettled.size(), stagingQueue),
                    null);
        }
        int batch = settings.intValue(TransferSettings.BATCH_SIZE);
        long moved;
        do {
            moved = messages.moveMessages(source, mbean, batch, "", s.run.getSourceQueue(), false, batch);
            s.run.returned(moved);
            save(s);
            publish(s);
        } while (moved >= batch);
        if (!staging.destroyIfEmpty(source, stagingQueue)) {
            return new End(
                    TransferState.FAILED,
                    "%d messages are still in staging queue %s (in delivery or scheduled), so it was kept. Return"
                                    .formatted(messages.messageCount(source, mbean), stagingQueue)
                            + " again later.",
                    null);
        }
        ledger.forget(s.id());
        return End.of(TransferState.RETURNED);
    }

    /**
     * Relay the in-doubt staged messages to the target once more, so each ends on exactly one
     * broker. Returns the ids still unsettled: all of them when the target cannot be reached.
     */
    private Set<Long> settle(Segment s, ClusterNode source, Set<Long> inDoubt) throws IOException {
        if (inDoubt.isEmpty()) {
            return inDoubt;
        }
        Set<Long> left = new HashSet<>(inDoubt);
        RelayLink.Route route = new RelayLink.Route(
                StagingQueues.queueName(s.id()),
                true,
                null,
                Optional.empty(),
                s.run.getTargetQueue(),
                provenance(s, true));
        RelayLink.Hooks only = new RelayLink.Hooks() {
            @Override
            public boolean selected(long sourceId) {
                return left.contains(sourceId);
            }
        };
        try {
            ClusterNode target =
                    nodes.serving(s.run.getTargetClusterId(), s.run.getTargetNativeNodeId(), s.run.getTargetNodeName());
            try (RelayLink link = relay.link(nodes.core(source), nodes.core(target), route)) {
                // A duplicate refusal costs one pass before the messages go one by one; two per message is ample.
                for (int pass = 0; pass <= 2 * inDoubt.size() && !left.isEmpty(); pass++) {
                    RelayLink.Batch b = link.relay(left.size(), only);
                    if (b.outcome() != RelayLink.Outcome.RELAYED) {
                        break;
                    }
                    b.sourceIds().forEach(left::remove);
                    delivered(s, b);
                }
            }
        } catch (BrokerConnectionException e) {
            log.info(
                    "Transfer {}: the target could not settle {} in-doubt messages: {}",
                    s.id(),
                    left.size(),
                    e.getMessage());
        }
        Set<Long> settled = new HashSet<>(inDoubt);
        settled.removeAll(left);
        ledger.remove(s.id(), settled);
        return left;
    }

    // ---- per batch ------------------------------------------------------------------

    /**
     * Before every batch: a stop asked for, a grant withdrawn, the endpoints serving each side, and the
     * target's room. Null to go on; otherwise how the segment ends.
     */
    private End guard(Segment s) {
        if (!replicas.heartbeatFresh()) {
            throw new RunLost("this replica's heartbeat has lapsed");
        }
        if (stopAsked(s.id())) {
            return End.stopped("Stopped by the operator.");
        }
        String sourcePermission = s.run.getMode().sourcePermission();
        if (!handoff.stillHolds(
                s.operator,
                s.run.getSourceClusterId(),
                ResourceRef.subscription(s.run.getSourceQueue()),
                sourcePermission)) {
            return End.stopped("The permission %s on the source queue was withdrawn, so the run stopped."
                    .formatted(sourcePermission));
        }
        if (!handoff.stillHolds(
                s.operator,
                s.run.getTargetClusterId(),
                ResourceRef.subscription(s.run.getTargetQueue()),
                Permissions.SUBSCRIPTION_READ)) {
            return End.stopped("The permission %s on the target queue was withdrawn, so the run stopped."
                    .formatted(Permissions.SUBSCRIPTION_READ));
        }
        if (!handoff.stillHolds(
                s.operator,
                s.run.getTargetClusterId(),
                ResourceRef.destination(s.run.getTargetAddress()),
                MessagePermissions.MESSAGE_SEND)) {
            return End.stopped("The permission %s on the target address was withdrawn, so the run stopped."
                    .formatted(MessagePermissions.MESSAGE_SEND));
        }
        s.resolve();
        TargetAcceptance.BatchVerdict verdict = check(s);
        if (verdict.refusal() != null) {
            return new End(
                    TransferState.FAILED,
                    verdict.refusal().words(),
                    verdict.refusal().snippet());
        }
        if (verdict.waitReason() != null) {
            return waitForCapacity(s, verdict.waitReason());
        }
        // One permit on each node per batch, on top of the management calls the transports charge.
        limiter.acquire(s.source.getManagementUrl(), 1, TOPIC);
        limiter.acquire(s.target.getManagementUrl(), 1, TOPIC);
        return null;
    }

    private TargetAcceptance.BatchVerdict check(Segment s) {
        Facts facts = probe.read(
                nodes.client(s.target), s.run.getTargetAddress(), s.run.getTargetQueue(), s.run.getTargetRoutingType());
        int batch = settings.intValue(TransferSettings.BATCH_SIZE);
        long batchBytes = s.run.getEstimateBytes() == null || s.run.getEstimate() == null || s.run.getEstimate() == 0
                ? 0
                : s.run.getEstimateBytes() / s.run.getEstimate() * batch;
        return TargetAcceptance.batch(
                facts,
                batchBytes,
                settings.intValue(TransferSettings.CAPACITY_THRESHOLD_PERCENT),
                s.run.getTargetAddress(),
                s.run.getTargetQueue());
    }

    /**
     * Wait for the target to have room, polling with backoff. Null once it has; a stop when asked to, or
     * when the wait runs out; a failure when the target turns into one that would drop messages.
     */
    private End waitForCapacity(Segment s, String reason) {
        s.run.waiting(reason, Instant.now());
        save(s);
        publish(s);
        Duration wait = settings.duration(TransferSettings.CAPACITY_WAIT);
        Instant deadline = Instant.now().plus(wait);
        Duration delay = FIRST_BACKOFF;
        while (true) {
            if (!sleep(s.id(), delay)) {
                return End.stopped("Stopped by the operator while waiting for the target to have room.");
            }
            TargetAcceptance.BatchVerdict verdict;
            try {
                s.resolve();
                verdict = check(s);
            } catch (BrokerConnectionException _) {
                verdict = new TargetAcceptance.BatchVerdict(null, reason);
            }
            if (verdict.refusal() != null) {
                return new End(
                        TransferState.FAILED,
                        verdict.refusal().words(),
                        verdict.refusal().snippet());
            }
            if (verdict.waitReason() == null) {
                s.run.running(Instant.now());
                save(s);
                publish(s);
                return null;
            }
            reason = verdict.waitReason();
            if (Instant.now().isAfter(deadline)) {
                return End.stopped(
                        "The target stayed full for %s: %s Resume the run once it has room.".formatted(wait, reason));
            }
            delay = delay.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay.multipliedBy(2);
        }
    }

    private void delivered(Segment s, RelayLink.Batch b) {
        s.run.delivered((long) b.delivered() + b.duplicates(), b.bytes(), Instant.now());
        save(s);
        publish(s);
    }

    /** Keep a run at or under its messages-per-second rate. */
    private void pace(long startedNanos, long messages) {
        int rate = Math.max(1, settings.intValue(TransferSettings.MESSAGES_PER_SECOND));
        long owed = messages * 1_000_000_000L / rate - (System.nanoTime() - startedNanos);
        if (owed > 0) {
            try {
                Thread.sleep(Duration.ofNanos(owed));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Whether the operator asked to stop this run: this replica was told, or the request is on the run's
     * row, which is how it arrives when the signal to this replica was lost (ADR-0152).
     */
    private boolean stopAsked(UUID runId) {
        if (background.stopRequested(runId)) {
            return true;
        }
        if (runs.existsByIdAndStopRequestedAtIsNotNull(runId)) {
            background.requestStop(runId);
            return true;
        }
        return false;
    }

    /** Sleep, looking for a stop four times a second and on the run's row every few seconds. False when one was asked for. */
    private boolean sleep(UUID runId, Duration duration) {
        long end = System.nanoTime() + duration.toNanos();
        long nextRowCheck = System.nanoTime() + ROW_CHECK.toNanos();
        try {
            while (System.nanoTime() < end) {
                if (background.stopRequested(runId)) {
                    return false;
                }
                if (System.nanoTime() - nextRowCheck >= 0) {
                    if (stopAsked(runId)) {
                        return false;
                    }
                    nextRowCheck = System.nanoTime() + ROW_CHECK.toNanos();
                }
                Thread.sleep(Math.clamp((end - System.nanoTime()) / 1_000_000, 1, 250));
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !stopAsked(runId);
    }

    private Provenance provenance(Segment s, boolean staged) {
        return new Provenance(
                s.id(), s.run.getSourceClusterId(), s.run.getSourceNodeName(), s.run.getSourceQueue(), staged);
    }

    private static End completed(Segment s) {
        return End.of(
                s.run.getNotTransferred() + s.run.getExpired() > 0 ? TransferState.PARTIAL : TransferState.SUCCEEDED);
    }

    /** The relay between the two endpoints serving now; reopened when either side fails over. */
    private final class Link {
        private RelayLink open;
        private String key;

        RelayLink to(Segment s, RelayLink.Route route) {
            String now = s.source.getDataUrl() + "|" + s.target.getDataUrl();
            if (!now.equals(key)) {
                close();
                open = relay.link(nodes.core(s.source), nodes.core(s.target), route);
                key = now;
            }
            return open;
        }

        void close() {
            if (open != null) {
                open.close();
                open = null;
                key = null;
            }
        }
    }

    // ---- state and progress ------------------------------------------------------------

    /**
     * Writes the run's row, in a transaction that first takes its row lock, and only while this replica
     * still executes it.
     *
     * @throws RunLost when it does not, so nothing is written
     */
    private void save(Segment s) {
        boolean held = Boolean.TRUE.equals(tx.execute(status -> {
            if (runs.fence(s.id(), replicas.id()) == 0) {
                return false;
            }
            runs.save(s.run);
            return true;
        }));
        if (!held) {
            throw new RunLost("the run was taken over");
        }
    }

    /** Messages a cross-node move has taken into staging and not yet delivered, expired or returned. */
    private static long held(TransferRunEntity run) {
        return run.getMode() == TransferMode.MOVE && !run.isSameNode()
                ? Math.max(0, run.getStaged() - run.getDelivered() - run.getExpired() - run.getReturned())
                : 0;
    }

    private void publish(Segment s) {
        TransferRunEntity run = s.run;
        long held = held(run);
        TransferProgress progress = new TransferProgress(
                run.getId(),
                run.getState(),
                run.getStaged(),
                held,
                run.getDelivered(),
                run.getNotTransferred(),
                run.getEstimate());
        // A run is about its source queue on the cluster it is read from, and its target on the one it goes to.
        sse.publishAbout(run.getSourceClusterId(), TOPIC, progress, sourceQueues(run), targetAddresses(run, true));
        if (!run.getTargetClusterId().equals(run.getSourceClusterId())) {
            sse.publishAbout(run.getTargetClusterId(), TOPIC, progress, List.of(), targetAddresses(run, false));
        }
    }

    /** The queues of the cluster the run is read from that it is about: its source, and its target when it is on the same one. */
    private static List<String> sourceQueues(TransferRunEntity run) {
        boolean sameCluster = run.getSourceClusterId().equals(run.getTargetClusterId());
        return named(run.getSourceQueue(), sameCluster ? run.getTargetQueue() : null);
    }

    /** The target address, for the cluster it is on: the source's cluster only when it is the same one. */
    private static List<String> targetAddresses(TransferRunEntity run, boolean onSourceCluster) {
        boolean sameCluster = run.getSourceClusterId().equals(run.getTargetClusterId());
        return onSourceCluster && !sameCluster ? List.of() : named(run.getTargetAddress());
    }

    private static List<String> named(String... names) {
        return Arrays.stream(names).filter(Objects::nonNull).toList();
    }

    /** Always reached: the run gets its state and the segment's audit events their outcome, whatever happened. */
    private void finish(Segment s, End ended) {
        // Rahdar's own shutdown stopped it: interrupted, so it is offered for resume like a crash.
        End end = ended.state() == TransferState.STOPPED && background.stoppedForShutdown(s.id())
                ? new End(TransferState.INTERRUPTED, TransferRecovery.INTERRUPTED, ended.snippet())
                : ended;
        TransferRunEntity run = s.run;
        boolean lost = false;
        try {
            run.finish(end.state(), end.error(), end.snippet(), Instant.now());
            save(s);
            publish(s);
            sse.publishAbout(run.getSourceClusterId(), "queues", sourceQueues(run), targetAddresses(run, true));
            if (!run.getTargetClusterId().equals(run.getSourceClusterId())) {
                sse.publishAbout(
                        run.getTargetClusterId(), "queues", named(run.getTargetQueue()), targetAddresses(run, false));
            }
        } catch (RunLost e) {
            lost = true;
            log.warn(
                    "Transfer {} was taken over before it could finish ({}); left as it is",
                    run.getId(),
                    e.getMessage());
        } finally {
            if (!lost) {
                finishAudit(run, end);
            }
        }
    }

    private void finishAudit(TransferRunEntity run, End end) {
        boolean ok = end.state() == TransferState.SUCCEEDED || end.state() == TransferState.RETURNED;
        long affected = end.state() == TransferState.RETURNED ? run.getReturned() : run.getDelivered();
        String summary = ok
                ? null
                : Objects.requireNonNullElse(
                        end.error(),
                        "%s: %d delivered, %d not transferred, %d expired."
                                .formatted(end.state(), run.getDelivered(), run.getNotTransferred(), run.getExpired()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", run.getId().toString());
        detail.put("state", end.state().name());
        detail.put("delivered", run.getDelivered());
        detail.put("notTransferred", run.getNotTransferred());
        detail.put("expired", run.getExpired());
        detail.put("returned", run.getReturned());
        for (Long id : new Long[] {run.getAuditEventId(), run.getTargetAuditEventId()}) {
            if (id != null) {
                audit.byId(id).ifPresent(event -> audit.finish(event, !ok, affected, summary, detail));
            }
        }
    }
}
