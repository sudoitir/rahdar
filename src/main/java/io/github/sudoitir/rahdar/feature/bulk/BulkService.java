package io.github.sudoitir.rahdar.feature.bulk;

import io.github.sudoitir.rahdar.feature.bulk.BulkRunMapper.ItemEstimate;
import io.github.sudoitir.rahdar.feature.bulk.BulkRunMapper.RunOptions;
import io.github.sudoitir.rahdar.feature.bulk.internal.persistence.BulkRunEntity;
import io.github.sudoitir.rahdar.feature.bulk.internal.persistence.BulkRunItemEntity;
import io.github.sudoitir.rahdar.feature.bulk.internal.persistence.BulkRunItemRepository;
import io.github.sudoitir.rahdar.feature.bulk.internal.persistence.BulkRunRepository;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkExecuteRequest;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkItemView;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkPreviewRequest;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkRunDetailView;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkRunView;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.BulkSelection;
import io.github.sudoitir.rahdar.feature.bulk.web.BulkViews.NodeFigure;
import io.github.sudoitir.rahdar.feature.resources.CrossNodeAggregator;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.QueueNodeCell;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.QueueView;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.gate.OperationHeldException;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard.Requirement;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff.Operator;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceIdTexts;
import io.github.sudoitir.rahdar.kernel.security.ResourceIdTexts.Of;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Bulk runs (ADR-0093): preview a frozen set of queues, execute it one queue at a time through the
 * single-queue commands, stop it, and read it back.
 */
@Service
@RequiredArgsConstructor
public class BulkService {

    static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(10);
    private static final int HISTORY_SCAN = 200;

    private final BulkRunRepository runs;
    private final BulkRunItemRepository items;
    private final BulkRunMapper views;
    private final BulkRunner runner;
    private final CrossNodeAggregator queues;
    private final ClusterAccessGuard clusterAccess;
    private final OperatorHandoff handoff;
    private final AuditService audit;
    private final SettingsService settings;
    private final ObjectMapper json;
    private final ReplicaRegistry replicas;
    private final OperationGate gate;
    private final ResourceIdTexts ids;

    /**
     * What the gate holds of a bulk run: the run, the plan it was previewed with (its hash, operation and queue
     * count, so an approver reads them without opening the run) and how it is to be started.
     */
    record BulkExecuteParams(
            UUID clusterId,
            UUID runId,
            String planHash,
            BulkOperation operation,
            int queues,
            boolean override,
            boolean continueOnFailure) {}

    // ---- preview -----------------------------------------------------------

    /**
     * Resolve and freeze the set, and state its blast radius from the aggregated queue snapshot: no
     * broker call per queue (ADR-0093 D2). The authoritative preflight still runs per queue on execute.
     * Writes no audit event: nothing is done.
     */
    public BulkRunDetailView preview(UUID clusterId, BulkPreviewRequest request) {
        BulkOperation operation = Objects.requireNonNull(request.operation(), "operation");
        clusterAccess.requireVisible(clusterId);
        Map<String, QueueView> byName = new LinkedHashMap<>();
        queues.allQueues(clusterId).forEach(q -> byName.putIfAbsent(q.queueName(), q));

        List<String> names = resolve(request, byName);
        // Every queue of the plan, before anything is stored: one the caller may not read is not found,
        // one they may read and not change is refused, and the run is not planned at all.
        clusterAccess.requireAll(clusterId, requirements(names, operation.permission()));
        int subscriptionCap = settings.intValue(BrokerSettings.BULK_SUBSCRIPTION_CAP);
        if (names.isEmpty()) {
            throw new BulkRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "bulk-nothing-selected",
                    "No subscription matches this selection.");
        }
        if (names.size() > subscriptionCap) {
            throw new BulkRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "bulk-subscription-cap-exceeded",
                    "%d subscriptions matched; a bulk run is capped at %d subscriptions (safety.bulk-subscription-cap). Narrow the selection."
                            .formatted(names.size(), subscriptionCap));
        }

        List<Planned> plan = names.stream()
                .map(name -> plan(operation, request.disconnectConsumers(), name, byName.get(name)))
                .toList();
        long estimate = 0;
        boolean complete = true;
        for (Planned p : plan) {
            if (p.refusal() != null || !operation.destructive()) {
                continue;
            }
            for (NodeFigure n : p.figures().nodes()) {
                if (n.messageCount() == null) {
                    complete = false;
                } else {
                    estimate += n.messageCount();
                }
            }
        }

        RunOptions options = new RunOptions(request.disconnectConsumers());
        Instant now = Instant.now();
        BulkRunEntity run = runs.save(new BulkRunEntity(
                clusterId,
                operation,
                handoff.capture().actor().displayName(),
                new BulkRunEntity.Preview(
                        planHash(operation, options, names),
                        json.writeValueAsString(new BulkSelection(request.names() == null ? null : names, request.q())),
                        json.writeValueAsString(options),
                        names.size(),
                        (int) plan.stream().filter(p -> p.refusal() != null).count(),
                        estimate,
                        complete),
                now,
                now.plus(PREVIEW_LIFETIME)));
        List<BulkRunItemEntity> rows = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            Planned p = plan.get(i);
            rows.add(
                    new BulkRunItemEntity(run.getId(), i, p.name(), p.refusal(), json.writeValueAsString(p.figures())));
        }
        items.saveAll(rows);
        return detail(run);
    }

    private record Planned(String name, String refusal, ItemEstimate figures) {}

    private static List<String> resolve(BulkPreviewRequest request, Map<String, QueueView> byName) {
        if (request.names() != null) {
            return List.copyOf(new LinkedHashSet<>(request.names()));
        }
        ResourceQuery query = ResourceQuery.of(request.q(), 1, 1, null);
        return byName.values().stream()
                .filter(q -> query.matches(q.queueName()) || query.matches(q.address()))
                .map(QueueView::queueName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** One queue's figures, refusal and warning, by the rules the single-queue preflight applies. */
    private static Planned plan(BulkOperation operation, boolean disconnectConsumers, String name, QueueView queue) {
        if (queue == null) {
            return new Planned(
                    name, "No queue with this name exists on the cluster.", new ItemEstimate(List.of(), null));
        }
        List<NodeFigure> nodes =
                queue.perNode().stream().map(BulkService::figure).toList();
        List<String> warnings = new ArrayList<>();
        List<String> unknown = nodes.stream()
                .filter(n -> n.messageCount() == null)
                .map(NodeFigure::nodeName)
                .toList();
        if (!unknown.isEmpty()) {
            warnings.add("Node %s has not answered recently, so this queue's figures there are unknown."
                    .formatted(String.join(", ", unknown)));
        }
        long consumers = nodes.stream()
                .map(NodeFigure::consumerCount)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        String refusal = null;
        switch (operation) {
            case DELETE -> {
                if (consumers > 0 && !disconnectConsumers) {
                    refusal =
                            "%d consumers are attached. A delete refuses a queue with consumers unless they are disconnected."
                                    .formatted(consumers);
                } else if (consumers > 0) {
                    warnings.add("%d consumers will be disconnected.".formatted(consumers));
                }
            }
            case PAUSE -> {
                if (!nodes.isEmpty() && nodes.stream().allMatch(NodeFigure::paused)) {
                    warnings.add("Already paused; nothing changes.");
                }
            }
            case RESUME -> {
                if (nodes.stream().noneMatch(NodeFigure::paused)) {
                    warnings.add("Not paused; nothing changes.");
                }
            }
            case PURGE -> {
                // A purge has nothing to warn about.
            }
        }
        return new Planned(
                name, refusal, new ItemEstimate(nodes, warnings.isEmpty() ? null : String.join(" ", warnings)));
    }

    /** A node that has not answered within the freshness window has unknown figures, never zero. */
    private static NodeFigure figure(QueueNodeCell cell) {
        return new NodeFigure(
                cell.nodeId(),
                cell.nodeName(),
                cell.stale() ? null : cell.messageCount(),
                cell.stale() ? null : cell.consumerCount(),
                cell.paused());
    }

    /** SHA-256 over what the run will do: the operation, the options that change it, the queues in order. */
    static String planHash(BulkOperation operation, RunOptions options, List<String> names) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String plan = operation.name() + "\n" + options.disconnectConsumers() + "\n" + String.join("\n", names);
            return HexFormat.of().formatHex(sha.digest(plan.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- execute and stop -------------------------------------------------

    /**
     * Start the previewed run in the background and return at once. Refused when the plan is not the
     * one previewed, the preview expired or already ran, a destructive estimate is over the cap
     * without the override, or another run is executing on the cluster.
     */
    public BulkRunView execute(UUID clusterId, UUID runId, BulkExecuteRequest request) {
        BulkRunEntity run = load(clusterId, runId);
        BulkOperation operation = run.getOperation();
        requireOnRun(clusterId, run, operation.permission());
        if (run.getStatus() != BulkRunStatus.PREVIEWED) {
            throw new ConflictException(
                    "bulk-run-started", "This bulk run has already been executed. Preview again to run it again.");
        }
        if (!run.getPlanHash().equals(request.planHash())) {
            throw new ConflictException(
                    "bulk-plan-mismatch",
                    "This is not the plan that was previewed. Preview again and confirm what it shows.");
        }
        if (Instant.now().isAfter(run.getExpiresAt())) {
            throw new BulkRefusedException(
                    HttpStatus.GONE,
                    "bulk-preview-expired",
                    "This preview expired at %s; queues may have changed since. Preview again."
                            .formatted(run.getExpiresAt()));
        }
        long cap = settings.intValue(BrokerSettings.BULK_CAP);
        if (operation.destructive() && run.getEstimate() > cap && !request.override()) {
            throw new BulkCapExceededException(run.getEstimate(), cap);
        }

        return gatedExecute(
                new BulkExecuteParams(
                        clusterId,
                        runId,
                        run.getPlanHash(),
                        operation,
                        run.getTotalItems(),
                        request.override(),
                        request.continueOnFailure()),
                run,
                request);
    }

    /**
     * What a bulk run would do, read from the previewed plan: its queues, its estimate and the plan's hash as the
     * gate's state key, so a run whose plan changed since it was requested is refused when it would start.
     */
    public Reach reach(UUID clusterId, UUID runId) {
        BulkRunEntity run = load(clusterId, runId);
        requireOnRun(clusterId, run, run.getOperation().permission());
        return new Reach(
                run.getOperation(),
                run.getTotalItems(),
                run.getEstimate(),
                run.isEstimateComplete(),
                run.getPlanHash());
    }

    /** A preview, as the gate reads it. */
    public record Reach(BulkOperation operation, long queues, long messages, boolean complete, String planHash) {}

    /**
     * Starts the run through the approval gate as {@code bulk.execute}. Held, the preview is kept until the hold ends,
     * so the approved run can still start; started, the queues are covered by this operation and pass on their own.
     */
    @Gated("bulk.execute")
    private BulkRunView gatedExecute(BulkExecuteParams params, BulkRunEntity run, BulkExecuteRequest request) {
        try {
            return gate.run(Operation.of(params), () -> start(run, request));
        } catch (OperationHeldException e) {
            runs.extendPreview(run.getId(), e.expiresAt().plus(PREVIEW_LIFETIME));
            throw e;
        }
    }

    private BulkRunView start(BulkRunEntity run, BulkExecuteRequest request) {
        UUID clusterId = run.getClusterId();
        UUID runId = run.getId();
        BulkOperation operation = run.getOperation();
        Operator operator = handoff.capture();
        try {
            // Claimed once: a second execute of the same preview finds it no longer PREVIEWED.
            if (runs.transition(runId, BulkRunStatus.PREVIEWED, BulkRunStatus.RUNNING) == 0) {
                throw new ConflictException(
                        "bulk-run-started", "This bulk run has already been executed. Preview again to run it again.");
            }
        } catch (DataIntegrityViolationException _) {
            String running = runs.findFirstByClusterIdAndStatus(clusterId, BulkRunStatus.RUNNING)
                    .map(r -> r.getId().toString())
                    .orElse("another run");
            throw new ConflictException(
                    "bulk-run-in-progress",
                    "Bulk run " + running + " is executing on this cluster. Wait for it to finish, or stop it.");
        }
        run.start(
                operator.actor().displayName(),
                request.override(),
                request.continueOnFailure(),
                replicas.id(),
                Instant.now());
        AuditEvent event = audit.begin(
                operator.actor(),
                operation.auditAction(),
                "QUEUE",
                run.getTotalItems() + " queues",
                clusterId,
                null,
                auditParams(run),
                false);
        run.attachAudit(event.getId());
        run = runs.save(run);
        runner.start(run, event, operator);
        return view(run);
    }

    private Map<String, Object> auditParams(BulkRunEntity run) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("runId", run.getId().toString());
        params.put("queues", run.getTotalItems());
        params.put("selection", json.readValue(run.getSelection(), BulkSelection.class));
        params.put("estimate", run.getEstimate());
        params.put("estimateComplete", run.isEstimateComplete());
        params.put("overrideCap", run.isOverrideCap());
        params.put("continueOnFailure", run.isContinueOnFailure());
        params.put("disconnectConsumers", options(run).disconnectConsumers());
        return params;
    }

    /** Ask a running run to stop, on whichever replica executes it: the queue in flight finishes, the rest are cancelled. */
    public BulkRunView stop(UUID clusterId, UUID runId) {
        BulkRunEntity run = load(clusterId, runId);
        requireOnRun(clusterId, run, run.getOperation().permission());
        boolean here = runner.requestStop(runId);
        if (!here && run.getStatus() != BulkRunStatus.RUNNING) {
            throw new ConflictException(
                    "bulk-run-not-running", "This bulk run is not executing, so there is nothing to stop.");
        }
        // Recorded on the run, so a signal that is lost cannot leave it running (ADR-0152).
        runs.requestStop(runId, Instant.now());
        if (!here) {
            // Executing on another replica: every replica hears it at once, the executing one acts.
            runner.signalStop(runId);
        }
        return view(run);
    }

    // ---- read --------------------------------------------------------------

    /** A run is read by a caller who may read every queue it acts on. */
    public BulkRunDetailView get(UUID clusterId, UUID runId) {
        clusterAccess.requireVisible(clusterId);
        BulkRunEntity run = load(clusterId, runId);
        requireOnRun(clusterId, run, Permissions.SUBSCRIPTION_READ);
        return detail(run);
    }

    /**
     * Runs that were executed, newest first. A caller who reads every queue of the cluster sees them all; any
     * other sees the runs whose queues they may all read, among the latest {@value #HISTORY_SCAN}.
     */
    public PagedView<BulkRunView> history(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireVisible(clusterId);
        if (clusterAccess.holds(clusterId, Permissions.SUBSCRIPTION_READ)) {
            return PagedView.of(
                    runs.findByClusterIdAndStatusNotOrderByCreatedAtDesc(
                            clusterId, BulkRunStatus.PREVIEWED, PageRequest.of(query.page() - 1, query.size())),
                    this::view);
        }
        List<BulkRunView> readable = runs
                .findByClusterIdAndStatusNotOrderByCreatedAtDesc(
                        clusterId, BulkRunStatus.PREVIEWED, PageRequest.of(0, HISTORY_SCAN))
                .stream()
                .filter(run -> clusterAccess.mayAll(clusterId, requirements(run, Permissions.SUBSCRIPTION_READ)))
                .map(this::view)
                .toList();
        return query.paginate(readable, null);
    }

    /**
     * The permission on every queue the run acts on. A queue the caller may not read is answered with the run's own
     * not-found, which names no queue and says nothing of what the run touches.
     */
    private void requireOnRun(UUID clusterId, BulkRunEntity run, String permission) {
        try {
            clusterAccess.requireAll(clusterId, requirements(run, permission));
        } catch (NotFoundException _) {
            throw new NotFoundException("bulk run", run.getId());
        }
    }

    /** What acting on {@code names} with the permission needs: the permission on each queue. */
    private static List<Requirement> requirements(List<String> names, String permission) {
        return names.stream()
                .map(name -> new Requirement(ResourceRef.subscription(name), permission))
                .toList();
    }

    /** The stored plan's requirements: the permission on each queue the run acts on. */
    private List<Requirement> requirements(BulkRunEntity run, String permission) {
        return requirements(
                items.findByRunIdOrderByOrdinal(run.getId()).stream()
                        .map(BulkRunItemEntity::getSubscription)
                        .toList(),
                permission);
    }

    private BulkRunEntity load(UUID clusterId, UUID runId) {
        return runs.findByIdAndClusterId(runId, clusterId).orElseThrow(() -> new NotFoundException("bulk run", runId));
    }

    private BulkRunDetailView detail(BulkRunEntity run) {
        Of idTexts = ids.of(run.getClusterId());
        List<BulkItemView> rows = items.findByRunIdOrderByOrdinal(run.getId()).stream()
                .map(item -> view(idTexts, item))
                .toList();
        return new BulkRunDetailView(view(run), rows);
    }

    private BulkRunView view(BulkRunEntity run) {
        return views.toView(
                run,
                settings.intValue(BrokerSettings.BULK_CAP),
                json.readValue(run.getSelection(), BulkSelection.class),
                options(run));
    }

    private BulkItemView view(Of idTexts, BulkRunItemEntity item) {
        List<NodeOutcome> outcome = item.getOutcome() == null
                ? null
                : json.readValue(item.getOutcome(), new TypeReference<List<NodeOutcome>>() {});
        return views.toView(
                item,
                idTexts.subscription(item.getSubscription()),
                json.readValue(item.getEstimate(), ItemEstimate.class),
                outcome);
    }

    private RunOptions options(BulkRunEntity run) {
        return json.readValue(run.getOptions(), RunOptions.class);
    }
}
