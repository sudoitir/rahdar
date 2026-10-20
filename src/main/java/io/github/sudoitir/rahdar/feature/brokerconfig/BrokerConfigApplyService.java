package io.github.sudoitir.rahdar.feature.brokerconfig;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome.NodeApply;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome.StepApply;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome.StepStatus;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome.Verification;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigOperations.ReadScope;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.Hazard;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.HazardKind;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.NodePlan;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.Op;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.Section;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan.Step;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigApplyEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigApplyEntity.Outcome;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigApplyRepository;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigDeclarationEntity.ApplyMode;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity.Basis;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateEntity.State;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigNodeStateRepository;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigOwnedItemEntity;
import io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence.BrokerConfigOwnedItemRepository;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The apply engine (ADR-0067 D3–D7, D12): a plan computed from what every live node
 * is running, hazards named before any write, and — for a real run — the canary
 * first, re-read and verified, then the rest one at a time, halting on the first
 * failure. Nothing is rolled back and nothing beyond the failed node is touched.
 *
 * <p>Deliberately not a {@code LifecycleKind}: the lifecycle fan-out applies to every
 * node regardless of individual failures (ADR-0049), which is the wrong shape for a
 * setting that can stop producers on every node at once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrokerConfigApplyService {

    static final String AUDIT_APPLY = "APPLY_BROKER_CONFIG";
    private static final String CLUSTER = "cluster";

    private final BrokerConfigService configs;
    private final BrokerConfigReads reads;
    private final BrokerConfigOperations ops;
    private final BrokerConfigApplyRepository applies;
    private final BrokerConfigOwnedItemRepository ownedItems;
    private final BrokerConfigNodeStateRepository nodeStates;
    private final BrokerConfigDriftService drift;
    private final ClusterAccessGuard clusterAccess;
    private final CapabilityLedger capabilities;
    private final SettingsService settings;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final ClusterLock lock;
    private final ClusterSecrets secrets;
    private final SseHub sseHub;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    // ---- entry points ------------------------------------------------------

    /** A dry run: the plan, the hazards, no write. Audited, like every lifecycle dry run. */
    public BrokerConfigApplyOutcome plan(UUID clusterId, BrokerConfigApplyRequest request) {
        clusterAccess.requireCluster(clusterId, BrokerConfigPermissions.CONFIG_APPLY);
        // The nodes are read before any transaction opens; only the rows below are written in one.
        Prepared p = prepare(clusterId, request);
        return transactions.execute(status -> {
            BrokerConfigApplyEntity row = applies.save(new BrokerConfigApplyEntity(
                    clusterId,
                    p.revision.id(),
                    mapper.writeValueAsString(p.plan),
                    actorResolver.resolve().displayName(),
                    p.plan.canaryNodeId(),
                    true));
            AuditEvent event = audit.begin(
                    actorResolver.resolve(),
                    AUDIT_APPLY,
                    CLUSTER,
                    configs.clusterName(clusterId),
                    clusterId,
                    null,
                    params(p, request),
                    true);
            row.attachAudit(event.getId());
            List<NodeApply> nodes = preview(p.plan);
            BrokerConfigApplyOutcome outcome = new BrokerConfigApplyOutcome(
                    row.getId(),
                    true,
                    Outcome.DRY_RUN,
                    p.revision.revision(),
                    p.plan,
                    nodes,
                    p.stepCap,
                    p.overCap,
                    previewSummary(p),
                    event.getId());
            row.finish(Outcome.DRY_RUN, outcome.summary(), mapper.writeValueAsString(nodes));
            applies.save(row);
            audit.finish(event, false, 0, null, nodes);
            return outcome;
        });
    }

    /** A real run: refuse anything the preview did not cover, then canary, verify, continue, halt. */
    public BrokerConfigApplyOutcome apply(UUID clusterId, BrokerConfigApplyRequest request) {
        clusterAccess.requireCluster(clusterId, BrokerConfigPermissions.CONFIG_APPLY);
        AtomicReference<BrokerConfigApplyOutcome> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        boolean ran = lock.runIfHeld(clusterId, ClusterLock.Scope.CONFIG_APPLY, () -> {
            try {
                result.set(run(clusterId, request));
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });
        if (!ran) {
            throw new ConflictException(
                    "apply-in-progress",
                    "Another configuration apply is running on this cluster. Wait for it to finish.");
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    @Transactional(readOnly = true)
    public Page<BrokerConfigApplyEntity> history(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return applies.findByClusterIdOrderByStartedAtDesc(clusterId, PageRequest.of(query.page() - 1, query.size()));
    }

    @Transactional(readOnly = true)
    public Optional<BrokerConfigApplyEntity> one(UUID clusterId, long id) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return applies.findById(id).filter(a -> a.getClusterId().equals(clusterId));
    }

    /** One past apply with the plan that was shown and what happened per node. */
    public record Detail(BrokerConfigApplyEntity apply, Plan plan, List<NodeApply> nodes) {}

    @Transactional(readOnly = true)
    public Optional<Detail> detail(UUID clusterId, long id) {
        return one(clusterId, id)
                .map(a -> new Detail(
                        a,
                        mapper.readValue(a.getPlan(), Plan.class),
                        a.getOutcomeDetail() == null
                                ? List.of()
                                : mapper.readValue(
                                        a.getOutcomeDetail(),
                                        mapper.getTypeFactory().constructCollectionType(List.class, NodeApply.class))));
    }

    // ---- the run --------------------------------------------------------------

    private record Prepared(
            BrokerConfigService.CurrentRevision revision,
            Plan plan,
            List<ObservedNodeConfig> observed,
            Map<UUID, ClusterNode> nodes,
            Set<OwnedItem> owned,
            int stepCap,
            boolean overCap) {}

    private Prepared prepare(UUID clusterId, BrokerConfigApplyRequest request) {
        BrokerConfigService.CurrentRevision revision = configs.current(clusterId)
                .orElseThrow(() -> new ConflictException(
                        "no-declaration", "Declare the cluster's configuration before applying it."));
        if (request.revision() != null && request.revision() != revision.revision()) {
            throw new ConflictException(
                    "stale-revision",
                    "Revision " + request.revision() + " is not current; revision " + revision.revision()
                            + " is. Preview again.");
        }
        Set<OwnedItem> owned = drift.owned(clusterId);
        ReadScope scope = ReadScope.of(
                revision.document(),
                BrokerConfigDriftService.ownedKeys(owned, Section.ADDRESS_SETTING),
                BrokerConfigDriftService.ownedKeys(owned, Section.SECURITY_SETTING));
        Map<UUID, ClusterNode> nodes = new LinkedHashMap<>();
        reads.targets(clusterId).forEach(n -> nodes.put(n.getId(), n));
        List<ObservedNodeConfig> observed = reads.observe(clusterId, scope);
        PlanOptions options = new PlanOptions(
                request.nodeIds(), request.canaryNodeId(), request.removeUndeclared(), false, List.of());
        Plan plan = BrokerConfigPlanner.restrict(
                BrokerConfigPlanner.plan(revision.document(), observed, owned, options), request.stepIds());
        if (!plan.valid()) {
            throw new BrokerConfigInvalidException(plan.violations());
        }
        int cap = settings.intValue(BrokerConfigSettings.APPLY_STEP_CAP);
        return new Prepared(revision, plan, observed, nodes, owned, cap, plan.stepCount() > cap);
    }

    private BrokerConfigApplyOutcome run(UUID clusterId, BrokerConfigApplyRequest request) {
        Prepared p = prepare(clusterId, request);
        preflight(p);
        requireRunnable(p, request);
        if (p.plan.stepCount() == 0) {
            return nothingToDo(clusterId, p, request);
        }

        // The audit row commits on its own before the first write to a broker (ADR-0078); no transaction of
        // this method is open while the nodes are written.
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                AUDIT_APPLY,
                CLUSTER,
                configs.clusterName(clusterId),
                clusterId,
                null,
                params(p, request),
                false);
        BrokerConfigApplyEntity pending = new BrokerConfigApplyEntity(
                clusterId,
                p.revision.id(),
                mapper.writeValueAsString(p.plan),
                actorResolver.resolve().displayName(),
                p.plan.canaryNodeId(),
                false);
        pending.attachAudit(event.getId());
        BrokerConfigApplyEntity row = transactions.execute(status -> applies.save(pending));

        boolean lockoutGuard = p.plan.hazards().stream().anyMatch(h -> h.kind() == HazardKind.MANAGEMENT_ACCESS);
        List<NodePlan> order = new ArrayList<>();
        p.plan.nodes().stream()
                .filter(n -> n.readable() && n.nodeId().equals(p.plan.canaryNodeId()))
                .forEach(order::add);
        p.plan.nodes().stream()
                .filter(n -> n.readable() && !n.nodeId().equals(p.plan.canaryNodeId()))
                .forEach(order::add);

        RunResult run;
        try {
            run = runNodes(clusterId, p, order, lockoutGuard, row.getId());
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
        List<NodeApply> results = run.results();
        p.plan.nodes().stream()
                .filter(n -> !n.readable())
                .map(BrokerConfigApplyService::skipped)
                .forEach(results::add);

        boolean halted = run.haltReason() != null;
        Outcome outcome = outcome(halted, run.applied(), results.size());
        String summary = summarise(outcome, results, run.haltReason(), order.size());
        row.finish(outcome, summary, mapper.writeValueAsString(results));
        transactions.executeWithoutResult(status -> applies.save(row));
        audit.finish(event, halted, run.applied(), halted ? summary : null, results);
        publishAfterCommit(clusterId);
        return new BrokerConfigApplyOutcome(
                row.getId(),
                false,
                outcome,
                p.revision.revision(),
                p.plan,
                results,
                p.stepCap,
                p.overCap,
                summary,
                event.getId());
    }

    /** What must hold before a real run writes anything: the mode, the confirmed plan, acknowledged hazards, the cap. */
    private static void requireRunnable(Prepared p, BrokerConfigApplyRequest request) {
        if (p.revision.header().mode() == ApplyMode.CONFIG_MANAGED) {
            // ADR-0067 D2 makes this the config-managed cluster's whole promise: Rahdar
            // does not write to it. The UI disables the control with the reason, but the
            // UI is not the enforcement point — an MCP tool or a plain POST reached the
            // engine and applied. A dry run is still allowed: a plan is a comparison.
            throw new ConflictException(
                    "config-managed",
                    "This cluster's configuration is managed outside Rahdar, so Rahdar will not write to it."
                            + " Export the broker.xml fragment and deploy it with your own tooling, or switch the"
                            + " cluster to Rahdar-managed first.");
        }
        if (request.expectedPlanHash() != null && !request.expectedPlanHash().equals(p.plan.planHash())) {
            throw new ConflictException(
                    "plan-changed",
                    "The cluster changed since the preview: the plan is no longer the one that was confirmed."
                            + " Preview again.");
        }
        List<String> missing = new ArrayList<>(p.plan.highHazardIds());
        missing.removeAll(request.acknowledgedHazards());
        if (!missing.isEmpty()) {
            throw new HazardNotAcknowledgedException(missing);
        }
        if (p.overCap && !request.override()) {
            throw new BulkCapExceededException(p.plan.stepCount(), p.stepCap);
        }
    }

    /** What the nodes did, and the reason the run stopped early, if it did. */
    private record RunResult(List<NodeApply> results, long applied, String haltReason) {}

    /** Canary first, one node at a time; the first failure halts the run and later nodes are not attempted. */
    private RunResult runNodes(UUID clusterId, Prepared p, List<NodePlan> order, boolean lockoutGuard, long applyId) {
        List<NodeApply> results = new ArrayList<>();
        String haltReason = null;
        long applied = 0;
        for (NodePlan node : order) {
            if (haltReason != null) {
                results.add(
                        notAttempted(node, p.plan.canaryNodeId(), "Not attempted: the run halted on an earlier node."));
            } else {
                NodeApply result = applyTo(clusterId, p, node, lockoutGuard, applyId);
                results.add(result);
                recordNodeState(clusterId, p.revision.revision(), applyId, result);
                applied += result.steps().stream()
                        .filter(s -> s.status() == StepStatus.APPLIED)
                        .count();
                if (result.anyFailed()) {
                    haltReason = haltReason(node, result);
                }
            }
        }
        return new RunResult(results, applied, haltReason);
    }

    private static String haltReason(NodePlan node, NodeApply result) {
        return result.steps().stream()
                .filter(s -> s.status() == StepStatus.FAILED || s.verified() == Verification.MISMATCH)
                .findFirst()
                .map(s -> node.nodeName() + ", " + s.description() + ": "
                        + (s.error() != null ? s.error() : "read-back did not match"))
                .orElse(node.nodeName());
    }

    private static Outcome outcome(boolean halted, long applied, int nodeCount) {
        if (!halted) {
            return Outcome.APPLIED;
        }
        return applied == 0 && nodeCount == 1 ? Outcome.FAILED : Outcome.HALTED;
    }

    private BrokerConfigApplyOutcome nothingToDo(UUID clusterId, Prepared p, BrokerConfigApplyRequest request) {
        List<NodeApply> nodes = preview(p.plan);
        return transactions.execute(status -> {
            BrokerConfigApplyEntity row = applies.save(new BrokerConfigApplyEntity(
                    clusterId,
                    p.revision.id(),
                    mapper.writeValueAsString(p.plan),
                    actorResolver.resolve().displayName(),
                    p.plan.canaryNodeId(),
                    false));
            AuditEvent event = audit.begin(
                    actorResolver.resolve(),
                    AUDIT_APPLY,
                    CLUSTER,
                    configs.clusterName(clusterId),
                    clusterId,
                    null,
                    params(p, request),
                    false);
            row.attachAudit(event.getId());
            String summary =
                    "Every live node already matches revision " + p.revision.revision() + "; nothing was written.";
            row.finish(Outcome.APPLIED, summary, mapper.writeValueAsString(nodes));
            applies.save(row);
            audit.finish(event, false, 0, null, nodes);
            return new BrokerConfigApplyOutcome(
                    row.getId(),
                    false,
                    Outcome.APPLIED,
                    p.revision.revision(),
                    p.plan,
                    nodes,
                    p.stepCap,
                    p.overCap,
                    summary,
                    event.getId());
        });
    }

    /**
     * One node: every pending step in order, stopping at the first failure; then one
     * read-back to verify what was applied. A {@code MISMATCH} is a failure. When a
     * security change could have locked Rahdar out, the read-back itself must
     * succeed.
     */
    private NodeApply applyTo(UUID clusterId, Prepared p, NodePlan node, boolean lockoutGuard, long applyId) {
        ClusterNode entity = p.nodes.get(node.nodeId());
        boolean canary = node.nodeId().equals(p.plan.canaryNodeId());
        List<StepApply> steps = new ArrayList<>();
        ArtemisNode client;
        String broker;
        try {
            client = reads.client(clusterId, entity);
            broker = client.resolveBrokerObjectName();
        } catch (BrokerConnectionException e) {
            node.steps().stream()
                    .map(s -> stepApply(
                            s,
                            s.already() ? StepStatus.ALREADY : StepStatus.NOT_ATTEMPTED,
                            Verification.NOT_VERIFIED,
                            null))
                    .forEach(steps::add);
            publishProgress(clusterId, applyId, node, canary, "UNREACHABLE", 0);
            return new NodeApply(
                    node.nodeId(),
                    node.nodeName(),
                    true,
                    canary,
                    e.getMessage(),
                    steps,
                    "Could not connect: " + e.getMessage());
        }
        NodeRun run = new NodeRun(clusterId, p, node, canary, applyId, client, broker, steps);
        boolean failed = false;
        publishProgress(clusterId, applyId, node, canary, "APPLYING", 0);
        for (Step s : node.steps()) {
            if (s.already()) {
                steps.add(stepApply(s, StepStatus.ALREADY, Verification.VERIFIED, null));
            } else if (failed) {
                steps.add(stepApply(s, StepStatus.NOT_ATTEMPTED, Verification.NOT_VERIFIED, null));
            } else {
                failed = applyStep(run, s);
            }
        }
        if (failed) {
            publishProgress(clusterId, applyId, node, canary, "HALTED", steps.size());
            return new NodeApply(
                    node.nodeId(),
                    node.nodeName(),
                    true,
                    canary,
                    null,
                    steps,
                    "Halted on this node; later steps were not attempted.");
        }
        publishProgress(clusterId, applyId, node, canary, "VERIFYING", steps.size());
        NodeApply verified = verify(clusterId, p, node, entity, canary, steps, lockoutGuard);
        publishProgress(
                clusterId,
                applyId,
                node,
                canary,
                verified.anyFailed() ? "HALTED" : "DONE",
                verified.steps().size());
        return verified;
    }

    /** One node's run in progress: where it writes, and the steps recorded so far. */
    private record NodeRun(
            UUID clusterId,
            Prepared p,
            NodePlan node,
            boolean canary,
            long applyId,
            ArtemisNode client,
            String broker,
            List<StepApply> steps) {}

    /** One pending step, recorded on the run; true when it failed and the node must halt. */
    private boolean applyStep(NodeRun run, Step s) {
        UUID clusterId = run.clusterId();
        try {
            // Every write is a management POST, and the client waits for the node's
            // ceiling before each one it sends (ADR-0076).
            StepStatus status = execute(
                    clusterId, run.client(), run.broker(), run.p().revision.document(), s);
            capabilities.recordWriteSucceeded(clusterId);
            run.steps().add(stepApply(s, status, Verification.NOT_VERIFIED, null));
            recordOwnership(clusterId, run.p().revision.id(), s, status);
            publishProgress(
                    clusterId,
                    run.applyId(),
                    run.node(),
                    run.canary(),
                    "APPLYING",
                    run.steps().size());
            return false;
        } catch (ManagementRefusal e) {
            if (e.kind() == ManagementRefusal.Kind.ALREADY) {
                capabilities.recordWriteSucceeded(clusterId);
                run.steps().add(stepApply(s, StepStatus.ALREADY, Verification.NOT_VERIFIED, null));
                recordOwnership(clusterId, run.p().revision.id(), s, StepStatus.ALREADY);
                return false;
            }
            run.steps().add(stepApply(s, StepStatus.FAILED, Verification.NOT_VERIFIED, e.getMessage()));
            return true;
        } catch (BrokerConnectionException e) {
            if (e.kind() == BrokerConnectionException.Kind.CREDENTIALS_REJECTED) {
                capabilities.recordWriteRefused(clusterId, e.getMessage());
            }
            run.steps().add(stepApply(s, StepStatus.FAILED, Verification.NOT_VERIFIED, e.getMessage()));
            return true;
        }
    }

    /** Re-read the node and re-plan it: anything still pending that was applied is a mismatch. */
    private NodeApply verify(
            UUID clusterId,
            Prepared p,
            NodePlan node,
            ClusterNode entity,
            boolean canary,
            List<StepApply> steps,
            boolean lockoutGuard) {
        ReadScope scope = ReadScope.of(
                p.revision.document(),
                BrokerConfigDriftService.ownedKeys(p.owned, Section.ADDRESS_SETTING),
                BrokerConfigDriftService.ownedKeys(p.owned, Section.SECURITY_SETTING));
        ObservedNodeConfig after = reads.observe(clusterId, entity, scope);
        if (after.unavailableReason() != null) {
            List<StepApply> out = steps.stream()
                    .map(s -> withVerification(
                            s, s.status() == StepStatus.APPLIED ? unreadable(lockoutGuard) : s.verified()))
                    .toList();
            String note = lockoutGuard
                    ? "The verification read was refused after a security change covering the management address."
                            + " Rahdar may have lost its own access and cannot revert it: " + after.unavailableReason()
                    : "Applied, but the verification read failed: " + after.unavailableReason();
            return new NodeApply(node.nodeId(), node.nodeName(), true, canary, null, out, note);
        }
        // Every applied item is now Rahdar-owned for the re-plan, so a removal is judged the same way.
        Set<OwnedItem> owned = new HashSet<>(p.owned);
        Plan again = BrokerConfigPlanner.plan(
                p.revision.document(),
                List.of(after),
                owned,
                new PlanOptions(Set.of(node.nodeId()), node.nodeId(), false, false, List.of()));
        // Keyed by section and key, not by step id: the id carries the op, and a setting
        // that was ADDed is REPLACEd in the re-plan, so an id comparison would call every
        // mismatched setting verified.
        Set<String> stillPending = new HashSet<>();
        again.nodes().stream()
                .filter(n -> n.nodeId().equals(node.nodeId()))
                .flatMap(n -> n.steps().stream())
                .filter(s -> !s.already())
                .forEach(s -> stillPending.add(s.section() + ":" + s.key()));
        List<StepApply> out = steps.stream()
                .map(s -> withVerification(s, readBack(s, stillPending, p.revision.document(), after)))
                .toList();
        boolean mismatch = out.stream().anyMatch(s -> s.verified() == Verification.MISMATCH);
        String note = mismatch
                ? "Read-back after the apply does not match the declaration; the run halted here."
                : "Applied and verified by reading the node back.";
        return new NodeApply(node.nodeId(), node.nodeName(), true, canary, null, out, note);
    }

    /** What an applied step becomes when the node could not be read back: a failure if Rahdar may be locked out. */
    private static Verification unreadable(boolean lockoutGuard) {
        return lockoutGuard ? Verification.MISMATCH : Verification.UNVERIFIABLE;
    }

    /** A step's verdict from the re-plan: still pending means the node does not hold what was applied. */
    private static Verification readBack(
            StepApply s, Set<String> stillPending, BrokerConfigDocument doc, ObservedNodeConfig after) {
        if (s.status() != StepStatus.APPLIED && s.status() != StepStatus.ALREADY) {
            return s.verified();
        }
        boolean pending = stillPending.contains(s.section() + ":" + s.key());
        if (s.op() == Op.REMOVE) {
            return pending ? Verification.MISMATCH : Verification.VERIFIED;
        }
        if (pending) {
            return Verification.MISMATCH;
        }
        if (s.section() == Section.ADDRESS_SETTING && !echoesEveryDeclaredKey(doc, s.key(), after)) {
            return Verification.UNVERIFIABLE;
        }
        return Verification.VERIFIED;
    }

    private static StepApply withVerification(StepApply s, Verification verified) {
        return new StepApply(
                s.stepId(), s.section(), s.key(), s.op(), s.description(), s.status(), verified, s.error());
    }

    /**
     * What must be true of the cluster before Rahdar writes to it.
     *
     * <p>Only for a real run: a dry run is a comparison, and the cluster an operator
     * most needs to read a plan for is the one that is currently unwell. Two
     * conditions refuse, and both are about writing to a broker that is in the middle
     * of deciding who it is:
     *
     * <ul>
     *   <li>a node this run would write to is no longer live — the plan was computed
     *       against a topology that has since changed;
     *   <li>the cluster is in split-brain, corroborated or suspected, where "the live
     *       node" is not a single answer and a setting can land on the member that
     *       loses.
     * </ul>
     */
    private void preflight(Prepared p) {
        List<String> notLive = p.plan.nodes().stream()
                .filter(NodePlan::readable)
                .filter(n -> !n.steps().isEmpty())
                .map(n -> p.nodes.get(n.nodeId()))
                .filter(e -> e != null && !Boolean.TRUE.equals(e.getActive()))
                .map(ClusterNode::getName)
                .toList();
        if (!notLive.isEmpty()) {
            throw new ConflictException(
                    "target-not-live",
                    "The plan targets " + String.join(", ", notLive)
                            + ", which is no longer live. The cluster moved since the preview; preview again.");
        }
        List<String> unstable = p.nodes.values().stream()
                .filter(n -> n.getArtemisNodeId() != null)
                .filter(n -> n.getSplitBrain() != SplitBrainStatus.NONE)
                .map(ClusterNode::getName)
                .distinct()
                .toList();
        if (!unstable.isEmpty()) {
            throw new ConflictException(
                    "cluster-unstable",
                    "This cluster is in split-brain (" + String.join(", ", unstable)
                            + "), so which node is authoritative is not settled. A setting applied now can land on"
                            + " the member that loses. Resolve the split first.");
        }
    }

    /**
     * Write what the apply itself learned about this node into the drift state.
     *
     * <p>A read-back that did not match is drift, and it is drift the moment it is
     * seen: leaving it to the post-apply evaluation meant a mismatch that halted a
     * run could still leave the node reading IN_SYNC if that evaluation was skipped,
     * raced or threw — and the CONFIG_DRIFT condition reads this table, so the alert
     * went unraised too. A clean verify records the strongest basis there is; the
     * evaluation that follows preserves it for as long as the revision stands.
     */
    private void recordNodeState(UUID clusterId, int revision, long applyId, NodeApply result) {
        if (result.unavailableReason() != null) {
            return;
        }
        boolean mismatch = result.steps().stream().anyMatch(s -> s.verified() == Verification.MISMATCH);
        boolean failed = result.steps().stream().anyMatch(s -> s.status() == StepStatus.FAILED);
        if (!mismatch && !failed) {
            writeNodeState(
                    clusterId,
                    result.nodeId(),
                    State.IN_SYNC,
                    "Matches revision " + revision + ". "
                            + BrokerConfigDriftService.why(Basis.VERIFIED_APPLY, revision),
                    revision,
                    Basis.VERIFIED_APPLY,
                    applyId);
            return;
        }
        String detail = result.steps().stream()
                .filter(s -> s.verified() == Verification.MISMATCH)
                .findFirst()
                .map(s -> "Read-back after apply #" + applyId + " did not match: " + s.description())
                .orElseGet(() -> "Apply #" + applyId + " failed on this node; what it wrote is not the declaration.");
        writeNodeState(clusterId, result.nodeId(), State.DRIFTED, detail, revision, null, applyId);
    }

    private void writeNodeState(
            UUID clusterId, UUID nodeId, State state, String detail, int revision, Basis basis, Long basisRef) {
        BrokerConfigNodeStateEntity.Key key = new BrokerConfigNodeStateEntity.Key();
        key.setClusterId(clusterId);
        key.setNodeId(nodeId);
        BrokerConfigNodeStateEntity row =
                nodeStates.findById(key).orElseGet(() -> new BrokerConfigNodeStateEntity(clusterId, nodeId));
        row.recordState(state, detail, revision, "[]", basis, basisRef);
        nodeStates.save(row);
    }

    private static boolean echoesEveryDeclaredKey(BrokerConfigDocument doc, String match, ObservedNodeConfig after) {
        Map<String, Object> observed = after.addressSettings().getOrDefault(match, Map.of());
        return doc.addressSettings().stream()
                .filter(s -> s.match().equals(match))
                .findFirst()
                .map(s -> observed.keySet().containsAll(s.values().keySet()))
                .orElse(true);
    }

    /** The one management write a step stands for. */
    private StepStatus execute(UUID clusterId, ArtemisNode client, String broker, BrokerConfigDocument doc, Step s) {
        switch (s.section()) {
            case ADDRESS -> writeAddress(client, broker, s);
            case QUEUE -> writeQueue(client, broker, s);
            case ADDRESS_SETTING -> writeAddressSetting(client, broker, doc, s);
            case SECURITY_SETTING -> writeSecuritySetting(client, broker, doc, s);
            case DIVERT -> writeDivert(client, broker, s);
            case BRIDGE -> writeBridge(clusterId, client, broker, doc, s);
        }
        return StepStatus.APPLIED;
    }

    private void writeAddress(ArtemisNode client, String broker, Step s) {
        @SuppressWarnings("unchecked")
        List<String> types = (List<String>) s.after().get("routingTypes");
        if (s.op() == Op.REPLACE) {
            ops.updateAddress(client, broker, s.key(), new HashSet<>(types));
        } else {
            ops.createAddress(client, broker, s.key(), new HashSet<>(types));
        }
    }

    private void writeQueue(ArtemisNode client, String broker, Step s) {
        if (s.op() == Op.REPLACE) {
            ops.updateQueue(client, broker, s.after());
        } else {
            ops.createQueue(client, broker, s.after());
        }
    }

    private void writeAddressSetting(ArtemisNode client, String broker, BrokerConfigDocument doc, Step s) {
        if (s.op() == Op.REMOVE) {
            ops.removeAddressSettings(client, broker, s.key());
        } else {
            AddressSettingDecl decl = doc.addressSettings().stream()
                    .filter(d -> d.match().equals(s.key()))
                    .findFirst()
                    .orElseThrow();
            ops.addAddressSettings(client, broker, s.key(), decl.values());
        }
    }

    private void writeSecuritySetting(ArtemisNode client, String broker, BrokerConfigDocument doc, Step s) {
        if (s.op() == Op.REMOVE) {
            ops.removeSecuritySettings(client, broker, s.key());
        } else {
            SecuritySettingDecl decl = doc.securitySettings().stream()
                    .filter(d -> d.match().equals(s.key()))
                    .findFirst()
                    .orElseThrow();
            Map<PermissionType, Set<String>> roles = new EnumMap<>(PermissionType.class);
            roles.putAll(decl.permissions());
            ops.addSecuritySettings(client, broker, s.key(), roles);
        }
    }

    private void writeDivert(ArtemisNode client, String broker, Step s) {
        if (s.op() == Op.REMOVE) {
            ops.destroyDivert(client, broker, s.key());
        } else {
            ops.createDivert(client, broker, s.after());
        }
    }

    private void writeBridge(UUID clusterId, ArtemisNode client, String broker, BrokerConfigDocument doc, Step s) {
        if (s.op() == Op.REMOVE) {
            // The declared name, which removes every instance a concurrency
            // above one deployed (ADR-0091).
            ops.destroyBridge(client, broker, s.key());
        } else {
            ops.createBridge(client, broker, withCredential(clusterId, doc, s));
        }
    }

    /**
     * The bridge document plus its credential, resolved from the vault here and
     * nowhere earlier (ADR-0092). The plan, its hash, the stored apply row and the
     * audit parameters all carry the step's {@code after} map, which holds only the
     * reference — so the password exists on one stack frame and in one HTTP body.
     */
    private Map<String, Object> withCredential(UUID clusterId, BrokerConfigDocument doc, Step s) {
        String ref = doc.bridges().stream()
                .filter(b -> b.name().equals(s.key()))
                .map(BrokerConfigDocument.BridgeDecl::credentialRef)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (ref == null) {
            return s.after();
        }
        ClusterSecrets.Credential credential = secrets.resolve(clusterId, ref)
                .orElseThrow(() -> new ManagementRefusal(
                        ManagementRefusal.Kind.ARGUMENT,
                        "Bridge " + s.key() + " names the credential '" + ref
                                + "', which Rahdar does not hold. Set it on the bridge, or remove the reference."));
        Map<String, Object> config = new LinkedHashMap<>(s.after());
        config.put("user", credential.username());
        config.put("password", credential.password());
        return config;
    }

    private void recordOwnership(UUID clusterId, long revisionId, Step s, StepStatus status) {
        if (s.section() == Section.ADDRESS || s.section() == Section.QUEUE) {
            return;
        }
        BrokerConfigOwnedItemEntity.Key key = new BrokerConfigOwnedItemEntity.Key();
        key.setClusterId(clusterId);
        key.setKind(s.section().name());
        key.setItemKey(s.key());
        if (s.op() == Op.REMOVE) {
            ownedItems.findById(key).ifPresent(ownedItems::delete);
            return;
        }
        if (status != StepStatus.APPLIED) {
            return;
        }
        ownedItems
                .findById(key)
                .ifPresentOrElse(
                        e -> {
                            e.touch(revisionId);
                            ownedItems.save(e);
                        },
                        () -> ownedItems.save(new BrokerConfigOwnedItemEntity(
                                clusterId, s.section().name(), s.key(), revisionId)));
    }

    // ---- shapes ----------------------------------------------------------------

    private static List<NodeApply> preview(Plan plan) {
        List<NodeApply> out = new ArrayList<>();
        for (NodePlan node : plan.nodes()) {
            if (!node.readable()) {
                out.add(skipped(node));
                continue;
            }
            List<StepApply> steps = node.steps().stream()
                    .map(s -> stepApply(
                            s,
                            s.already() ? StepStatus.ALREADY : StepStatus.WOULD_APPLY,
                            Verification.NOT_VERIFIED,
                            null))
                    .toList();
            boolean canary = node.nodeId().equals(plan.canaryNodeId());
            out.add(new NodeApply(
                    node.nodeId(),
                    node.nodeName(),
                    true,
                    canary,
                    null,
                    steps,
                    canary ? "Canary: applied first and read back before any other node." : null));
        }
        return out;
    }

    private static NodeApply skipped(NodePlan node) {
        String note = node.live()
                ? "Unreachable: " + node.unavailableReason()
                : "Not live. A backup inherits what its primary holds once it becomes active.";
        return new NodeApply(
                node.nodeId(), node.nodeName(), node.live(), false, node.unavailableReason(), List.of(), note);
    }

    private static NodeApply notAttempted(NodePlan node, UUID canary, String note) {
        List<StepApply> steps = node.steps().stream()
                .map(s -> stepApply(
                        s,
                        s.already() ? StepStatus.ALREADY : StepStatus.NOT_ATTEMPTED,
                        Verification.NOT_VERIFIED,
                        null))
                .toList();
        return new NodeApply(node.nodeId(), node.nodeName(), true, node.nodeId().equals(canary), null, steps, note);
    }

    private static StepApply stepApply(Step s, StepStatus status, Verification verified, String error) {
        return new StepApply(s.id(), s.section(), s.key(), s.op(), s.description(), status, verified, error);
    }

    private static String previewSummary(Prepared p) {
        long readable = p.plan.nodes().stream().filter(NodePlan::readable).count();
        StringBuilder sb = new StringBuilder();
        sb.append(p.plan.stepCount())
                .append(p.plan.stepCount() == 1 ? " step" : " steps")
                .append(" on ")
                .append(readable)
                .append(readable == 1 ? " live node" : " live nodes")
                .append('.');
        if (p.plan.canaryNodeId() != null) {
            p.plan.nodes().stream()
                    .filter(n -> n.nodeId().equals(p.plan.canaryNodeId()))
                    .findFirst()
                    .ifPresent(n -> sb.append(" Canary: ").append(n.nodeName()).append('.'));
        }
        long high = p.plan.hazards().stream()
                .filter(h -> h.hazardClass() == HazardClass.HIGH)
                .count();
        if (high > 0) {
            sb.append(' ')
                    .append(high)
                    .append(high == 1 ? " high hazard" : " high hazards")
                    .append(" to acknowledge.");
        }
        if (p.overCap) {
            sb.append(" Over the step cap of ").append(p.stepCap).append("; an override is needed.");
        }
        return sb.toString();
    }

    private static String summarise(Outcome outcome, List<NodeApply> nodes, String haltReason, int targeted) {
        long applied = nodes.stream()
                .flatMap(n -> n.steps().stream())
                .filter(s -> s.status() == StepStatus.APPLIED)
                .count();
        if (outcome == Outcome.APPLIED) {
            return "Applied " + applied + (applied == 1 ? " step" : " steps") + " on " + targeted
                    + (targeted == 1 ? " node" : " nodes") + ", each verified by reading it back.";
        }
        long notAttempted = nodes.stream()
                .filter(n -> n.steps().stream().anyMatch(s -> s.status() == StepStatus.NOT_ATTEMPTED))
                .count();
        return "Halted at " + haltReason + ". " + applied + (applied == 1 ? " step was" : " steps were")
                + " applied before that; " + notAttempted + (notAttempted == 1 ? " node was" : " nodes were")
                + " not attempted. Nothing was rolled back. Re-running converges: what already matches is skipped.";
    }

    private static Map<String, Object> params(Prepared p, BrokerConfigApplyRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("revision", p.revision.revision());
        m.put("planHash", p.plan.planHash());
        m.put("stepCount", p.plan.stepCount());
        m.put("canaryNodeId", p.plan.canaryNodeId());
        m.put("nodeIds", request.nodeIds());
        m.put("removeUndeclared", request.removeUndeclared());
        m.put("acknowledgedHazards", request.acknowledgedHazards());
        m.put("override", request.override());
        m.put("stepIds", request.stepIds());
        m.put("hazards", p.plan.hazards().stream().map(Hazard::id).toList());
        return m;
    }

    /**
     * Where one node has got to, while the apply is still running.
     *
     * <p>Published <em>before</em> the transaction commits, which is the point: an
     * apply that takes a minute across a pair otherwise shows a spinner and then a
     * finished table, and the one moment an operator wants to watch — the canary
     * being written and read back before anything else is touched — is invisible.
     *
     * <p>These are advisory. The POST's own response is what says what happened; a
     * run that rolls back leaves the last progress frame on the screen for as long
     * as it takes the response to land, and the response replaces it. Subscribers
     * that do not know this kind ignore it, so the topic keeps its meaning for
     * everyone else ({@code kind} is what separates them).
     */
    private void publishProgress(UUID clusterId, long applyId, NodePlan node, boolean canary, String phase, int done) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("topic", BrokerConfigDriftService.SSE_TOPIC);
        frame.put("kind", "apply-progress");
        frame.put("clusterId", clusterId.toString());
        frame.put("applyId", applyId);
        frame.put("nodeId", node.nodeId().toString());
        frame.put("nodeName", node.nodeName());
        frame.put("canary", canary);
        frame.put("phase", phase);
        frame.put("done", done);
        frame.put("total", node.steps().size());
        sseHub.publish(clusterId, BrokerConfigDriftService.SSE_TOPIC, frame, null);
    }

    private void publishAfterCommit(UUID clusterId) {
        Runnable after = () -> {
            sseHub.publish(clusterId, BrokerConfigDriftService.SSE_TOPIC);
            drift.evaluateAfterApply(clusterId);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    after.run();
                }
            });
        } else {
            after.run();
        }
    }
}
