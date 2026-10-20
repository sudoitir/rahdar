package io.github.sudoitir.rahdar.feature.transfer;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.queues.QueuePermissions;
import io.github.sudoitir.rahdar.feature.transfer.TransferRunMapper.Derived;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunEntity;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunRepository;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.Finding;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.FindingKind;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.OrphanReturnRequest;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.OrphanReturnView;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.OrphanView;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.SelectionKind;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferEnd;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferExecuteRequest;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferPreviewRequest;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferSelection;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditScope;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.gate.OperationHeldException;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard.Requirement;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff.Operator;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.BrokerXmlSnippets;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.clusters.CapabilityLedger;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ServingNodes;
import io.github.sudoitir.rahdar.platform.clusters.SplitBrainStatus;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator.QueueLocation;
import io.github.sudoitir.rahdar.system.artemis.AcceptanceProbe;
import io.github.sudoitir.rahdar.system.artemis.AcceptanceProbe.Facts;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisSelectors;
import io.github.sudoitir.rahdar.system.artemis.MBeanNames;
import io.github.sudoitir.rahdar.system.artemis.MessageOperations;
import io.github.sudoitir.rahdar.system.artemis.StagingQueues;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Transfer runs (ADR-0097): preview one with no broker side effect, execute it in the background,
 * stop, resume or return it, and read it back. Every action is authorised on both clusters; a target
 * cluster the operator cannot send to answers as one that does not exist.
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(10);
    static final String AUDIT_ACTION = "message.transfer";
    private static final String AUDIT_QUEUE = "QUEUE";
    private static final String RUN_ID = "runId";
    private static final String ANYCAST = "ANYCAST";
    private static final int HISTORY = 100;

    private final TransferRunRepository runs;
    private final TransferRunMapper views;
    private final TransferRunner runner;
    private final TransferNodes nodes;
    private final ClusterDirectory directory;
    private final CapabilityLedger capabilities;
    private final QueueLocator locator;
    private final MessageOperations messages;
    private final AcceptanceProbe probe;
    private final StagingQueues staging;
    private final ClusterAccessGuard access;
    private final PermissionResolver permissions;
    private final OperatorHandoff handoff;
    private final AuditService audit;
    private final SettingsService settings;
    private final ObjectMapper json;
    private final ReplicaRegistry replicas;
    private final OperationGate gate;

    /**
     * What the gate holds of a transfer: the run, the plan it was previewed with (its hash, mode and ends, so an
     * approver reads them without opening the run) and the confirmations the operator gave.
     */
    record TransferExecuteParams(
            UUID clusterId,
            UUID runId,
            String planHash,
            TransferMode mode,
            String sourceQueue,
            UUID targetClusterId,
            String targetQueue,
            boolean override,
            List<String> acknowledged,
            String confirmQueue) {}

    // ---- preview -----------------------------------------------------------

    /**
     * Resolve both ends, freeze the selection at now, estimate it, and check the target can accept it.
     * Reads only: no staging queue is created and no message is touched. Writes no audit event.
     */
    public TransferRunView preview(UUID clusterId, TransferPreviewRequest request) {
        TransferMode mode = request.mode();
        access.requireResource(clusterId, ResourceRef.queue(request.sourceQueue()), mode.sourcePermission());
        access.requireVisible(request.targetClusterId());
        TransferSelection selection = normalise(request.selection());

        ClusterNode source = nodes.node(clusterId, request.sourceNodeId());
        ClusterNode target = nodes.node(request.targetClusterId(), request.targetNodeId());
        for (ClusterNode node : List.of(source, target)) {
            if (node.getNativeNodeId() == null || node.getManagementUrl() == null) {
                throw new TransferRefusedException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "transfer-node-unmanaged",
                        "Node %s has not been read yet or has no management URL, so it cannot take part in a transfer."
                                .formatted(node.getName()));
            }
        }
        QueueLocation from = locate(clusterId, request.sourceQueue(), source)
                .orElseThrow(() -> new NotFoundException("queue", request.sourceQueue()));
        Optional<QueueLocation> to = locate(request.targetClusterId(), request.targetQueue(), target);
        String targetAddress = authorisedTargetAddress(request, to);
        String targetRoutingType = to.map(QueueLocation::routingType).orElse(ANYCAST);
        boolean sameCluster = clusterId.equals(request.targetClusterId());
        boolean sameNode = sameCluster && source.getNativeNodeId().equals(target.getNativeNodeId());
        Instant t0 = Instant.now();

        ArtemisNode sourceClient = nodes.client(source);
        Facts sourceFacts = probe.read(sourceClient, from.address(), from.queueName(), from.routingType());
        Long count = estimate(sourceClient, from, selection, t0, sourceFacts);
        Long bytes = projectedBytes(count, sourceFacts);

        boolean targetLive = TransferNodes.live(target);
        Facts targetFacts = targetLive
                ? probe.read(nodes.client(target), targetAddress, request.targetQueue(), targetRoutingType)
                : null;
        int threshold = settings.intValue(TransferSettings.CAPACITY_THRESHOLD_PERCENT);
        List<Finding> findings = new ArrayList<>(TargetAcceptance.evaluate(
                new TargetAcceptance.Input(
                        targetFacts,
                        count,
                        bytes,
                        sameNode && from.queueName().equals(request.targetQueue()),
                        sameCluster,
                        targetLive,
                        TransferNodes.backup(target),
                        target.getSplitBrain() == SplitBrainStatus.CRITICAL,
                        threshold),
                targetAddress,
                request.targetQueue()));
        findings.addAll(capabilityFindings(mode, clusterId, source, target, sameNode));
        if (count == null && sourceFacts.messageCount() != null) {
            findings.add(new Finding(
                    FindingKind.UNKNOWN,
                    "selection-size",
                    ("Queue %s holds %d messages, more than the %d the broker looks at to count a filter"
                                    + " (management-browse-page-size), so how many match is not known until the run"
                                    + " counts them. Running it needs the safety-cap override.")
                            .formatted(from.queueName(), sourceFacts.messageCount(), browsePageSize(sourceFacts)),
                    null));
        }

        Instant now = Instant.now();
        TransferRunEntity run = runs.save(TransferRunEntity.builder()
                .mode(mode)
                .sourceClusterId(clusterId)
                .sourceNodeId(source.getId())
                .sourceNodeName(source.getName())
                .sourceNativeNodeId(source.getNativeNodeId())
                .sourceQueue(from.queueName())
                .sourceAddress(from.address())
                .sourceRoutingType(from.routingType())
                .targetClusterId(request.targetClusterId())
                .targetNodeId(target.getId())
                .targetNodeName(target.getName())
                .targetNativeNodeId(target.getNativeNodeId())
                .targetQueue(request.targetQueue())
                .targetAddress(targetAddress)
                .targetRoutingType(targetRoutingType)
                .sameNode(sameNode)
                .selection(json.writeValueAsString(selection))
                .findings(json.writeValueAsString(findings))
                .t0(t0)
                .planHash(new Plan(
                                mode,
                                source,
                                target,
                                from.queueName(),
                                request.targetQueue(),
                                targetAddress,
                                selection,
                                t0)
                        .hash())
                .estimate(count)
                .estimateBytes(bytes)
                .username(handoff.capture().actor().displayName())
                .createdAt(now)
                .expiresAt(now.plus(PREVIEW_LIFETIME))
                .build());
        return view(run);
    }

    private static TransferSelection normalise(TransferSelection selection) {
        return switch (selection.kind()) {
            case IDS -> {
                if (selection.ids() == null || selection.ids().isEmpty()) {
                    throw new TransferRefusedException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "transfer-nothing-selected", "No message id was given.");
                }
                yield new TransferSelection(SelectionKind.IDS, List.copyOf(new LinkedHashSet<>(selection.ids())), null);
            }
            case FILTER -> {
                if (selection.filter() == null || selection.filter().isBlank()) {
                    throw new TransferRefusedException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "transfer-nothing-selected",
                            "A filter selection needs a filter. Choose the whole queue instead to take everything.");
                }
                if (!ArtemisSelectors.isCompleteExpression(selection.filter())) {
                    throw new TransferRefusedException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "transfer-invalid-filter",
                            "The filter is not one complete selector expression, so it cannot be limited to the"
                                    + " messages that existed when the transfer started. Check its parentheses and"
                                    + " quotes.");
                }
                yield new TransferSelection(
                        SelectionKind.FILTER, null, selection.filter().strip());
            }
            case ALL -> new TransferSelection(SelectionKind.ALL, null, null);
        };
    }

    /** The queue's copy on this node, or on another endpoint of the same broker node. */
    private Optional<QueueLocation> locate(UUID clusterId, String queue, ClusterNode node) {
        List<UUID> sameBroker = directory.nodes(clusterId).stream()
                .filter(n -> node.getNativeNodeId().equals(n.getNativeNodeId()))
                .map(ClusterNode::getId)
                .toList();
        List<QueueLocation> found = locator.locate(clusterId, queue);
        return found.stream()
                .filter(l -> l.nodeId().equals(node.getId()))
                .findFirst()
                .or(() -> found.stream()
                        .filter(l -> sameBroker.contains(l.nodeId()))
                        .findFirst());
    }

    /**
     * The address a transfer sends to, and whether the caller may send there. Messages go to the target
     * queue, so the address is the one that queue is bound to, never one the caller names: a queue
     * that exists is read and sent to through its own address, and naming another address is refused.
     * A queue the broker would create is checked as a creation, under the address named (default: the
     * queue's own name). The not-found of a target the caller cannot read names neither the queue nor the address.
     */
    String authorisedTargetAddress(TransferPreviewRequest request, Optional<QueueLocation> to) {
        UUID cluster = request.targetClusterId();
        String supplied =
                request.targetAddress() == null || request.targetAddress().isBlank()
                        ? null
                        : request.targetAddress().strip();
        if (to.isPresent()) {
            String bound = to.get().address();
            access.requireAll(
                    cluster,
                    List.of(
                            new Requirement(ResourceRef.queue(request.targetQueue()), Permissions.QUEUE_READ),
                            new Requirement(ResourceRef.address(bound), MessagePermissions.MESSAGE_SEND)));
            if (supplied != null && !supplied.equals(bound)) {
                throw new TransferRefusedException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "transfer-target-address",
                        "Queue %s is bound to address %s, not %s. Leave the address out to send to the queue's own."
                                .formatted(request.targetQueue(), bound, supplied));
            }
            return bound;
        }
        String address = supplied != null ? supplied : request.targetQueue();
        access.requireCreate(cluster, ResourceRef.queue(request.targetQueue()), QueuePermissions.QUEUE_CREATE);
        access.requireAll(
                cluster, List.of(new Requirement(ResourceRef.address(address), MessagePermissions.MESSAGE_SEND)));
        return address;
    }

    /**
     * The selection's size, or null when it is not known. Exact for ids, and for the whole queue its
     * depth. A filter's count is the broker's {@code countMessages}, which looks at no more than the
     * address's {@code management-browse-page-size} messages: on a deeper queue it would under-count,
     * so the size is then unknown.
     */
    private Long estimate(
            ArtemisNode client, QueueLocation from, TransferSelection selection, Instant t0, Facts source) {
        return switch (selection.kind()) {
            case IDS -> (long) selection.ids().size();
            case ALL -> source.messageCount();
            case FILTER -> {
                if (source.messageCount() == null || source.messageCount() > browsePageSize(source)) {
                    yield null;
                }
                try {
                    String mbean = MBeanNames.queue(
                            client.resolveBrokerObjectName(), from.address(), from.queueName(), from.routingType());
                    yield messages.countMessages(client, mbean, ArtemisSelectors.frozen(selection.filter(), t0));
                } catch (BrokerConnectionException _) {
                    yield null;
                }
            }
        };
    }

    /** How many messages the broker looks at to count a filter; Artemis defaults it to 200. */
    static int browsePageSize(Facts source) {
        return source.addressSettings() == null
                ? 200
                : source.addressSettings().path("managementBrowsePageSize").asInt(200);
    }

    /** The count times the source queue's mean persistent message size; null when either is unknown. */
    static Long projectedBytes(Long count, Facts source) {
        if (count == null
                || source.persistentSize() == null
                || source.messageCount() == null
                || source.messageCount() <= 0) {
            return null;
        }
        return count * (source.persistentSize() / source.messageCount());
    }

    /** What the nodes' connectivity and Rahdar's rights on them say, beside the target's own verdict. */
    private List<Finding> capabilityFindings(
            TransferMode mode, UUID clusterId, ClusterNode source, ClusterNode target, boolean sameNode) {
        List<Finding> out = new ArrayList<>();
        if (!TransferNodes.live(source) || TransferNodes.backup(source)) {
            out.add(new Finding(
                    FindingKind.REFUSE,
                    "source-not-live",
                    "The source node %s is not live now, so its messages cannot be taken.".formatted(source.getName()),
                    null));
        }
        if (mode == TransferMode.COPY || !sameNode) {
            coreFindings(sameNode ? List.of(source) : List.of(source, target), source, out);
        }
        if (mode == TransferMode.MOVE) {
            moveFindings(clusterId, sameNode, out);
        }
        return out;
    }

    private static void coreFindings(List<ClusterNode> nodes, ClusterNode source, List<Finding> out) {
        for (ClusterNode node : nodes) {
            if (node.getDataUrl() == null) {
                out.add(new Finding(
                        FindingKind.REFUSE,
                        node == source ? "source-no-core" : "target-no-core",
                        "Node %s has no Core connection, which a transfer relays messages over."
                                .formatted(node.getName()),
                        BrokerXmlSnippets.CORE_ACCEPTOR));
            }
        }
    }

    private void moveFindings(UUID clusterId, boolean sameNode, List<Finding> out) {
        capabilities
                .managementWrite(clusterId)
                .ifPresentOrElse(
                        assessment -> {
                            if (assessment.status() != BrokerCapabilities.CapabilityStatus.AVAILABLE) {
                                out.add(new Finding(
                                        FindingKind.REFUSE,
                                        "management-write",
                                        "A move takes messages off the source through management operations, and "
                                                + assessment.reason(),
                                        assessment.brokerXmlSnippet()));
                            }
                        },
                        () -> out.add(new Finding(
                                FindingKind.UNKNOWN,
                                "management-write",
                                "Whether the source broker allows Rahdar's management writes has not been"
                                        + " established yet; a move needs them.",
                                BrokerXmlSnippets.MANAGEMENT_SECURITY_SETTING)));
        if (!sameNode) {
            out.add(new Finding(
                    FindingKind.UNKNOWN,
                    "staging-rights",
                    "A move parks its messages in a queue named %s<run> on the source broker. Whether Rahdar's"
                                    .formatted(StagingQueues.PREFIX)
                            + " broker user may create and consume it is known only when the run starts; it"
                            + " fails then, before moving anything, if it may not.",
                    BrokerXmlSnippets.STAGING_SECURITY_SETTING));
        }
    }

    /** What the run will do: both ends, the mode, the selection, and its frozen moment. */
    private record Plan(
            TransferMode mode,
            ClusterNode source,
            ClusterNode target,
            String sourceQueue,
            String targetQueue,
            String targetAddress,
            TransferSelection selection,
            Instant t0) {

        /** SHA-256 over the plan, so an execute can only run what the preview showed. */
        String hash() {
            String plan = String.join(
                    "\n",
                    mode.name(),
                    source.getClusterId().toString(),
                    source.getNativeNodeId(),
                    sourceQueue,
                    target.getClusterId().toString(),
                    target.getNativeNodeId(),
                    targetQueue,
                    targetAddress,
                    selection.kind().name(),
                    String.valueOf(selection.ids()),
                    String.valueOf(selection.filter()),
                    Long.toString(t0.toEpochMilli()));
            try {
                return HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(plan.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ---- execute, stop, resume, return ---------------------------------------

    /**
     * Start the previewed run in the background and return at once. Refused when the plan is not the one
     * previewed, the preview expired or already ran, the preview refused the transfer, a warning was not
     * acknowledged, a move was not confirmed by its source queue's name, the selection is over the cap
     * without the override, another run is active on the source queue, or too many runs are active.
     */
    public TransferRunView execute(UUID clusterId, UUID runId, TransferExecuteRequest request) {
        TransferRunEntity run = load(clusterId, runId);
        requireRunPermissions(run);
        if (run.getState() != TransferState.PREVIEWED) {
            throw new ConflictException(
                    "transfer-run-started", "This transfer has already been started. Preview again to run it again.");
        }
        if (!run.getPlanHash().equals(request.planHash())) {
            throw new ConflictException(
                    "transfer-plan-mismatch",
                    "This is not the plan that was previewed. Preview again and confirm what it shows.");
        }
        if (Instant.now().isAfter(run.getExpiresAt())) {
            throw new TransferRefusedException(
                    HttpStatus.GONE,
                    "transfer-preview-expired",
                    "This preview expired at %s; the queues may have changed since. Preview again."
                            .formatted(run.getExpiresAt()));
        }
        List<Finding> findings = findings(run);
        List<String> refusals = findings.stream()
                .filter(f -> f.kind() == FindingKind.REFUSE)
                .map(Finding::words)
                .toList();
        if (!refusals.isEmpty()) {
            throw new TransferRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "transfer-refused", String.join(" ", refusals));
        }
        Set<String> acknowledged = request.acknowledged() == null ? Set.of() : Set.copyOf(request.acknowledged());
        List<String> missing = findings.stream()
                .filter(f -> f.kind() == FindingKind.WARN && !acknowledged.contains(f.code()))
                .map(Finding::code)
                .toList();
        if (!missing.isEmpty()) {
            throw new TransferRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "transfer-unacknowledged",
                    "Acknowledge every warning the preview raised before running it: " + String.join(", ", missing)
                            + ".");
        }
        if (run.getMode() == TransferMode.MOVE && !run.getSourceQueue().equals(request.confirmQueue())) {
            throw new TransferRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "transfer-confirmation-mismatch",
                    "Send the source queue's name, %s, as confirmQueue to confirm the move."
                            .formatted(run.getSourceQueue()));
        }
        long cap = settings.intValue(BrokerSettings.BULK_CAP);
        if (!request.override() && run.getEstimate() == null) {
            throw new TransferRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "transfer-size-unknown",
                    "How many messages this transfer selects is not known, so it cannot be held to the safety cap"
                            + " of %d. Confirm the override to run it.".formatted(cap));
        }
        if (!request.override() && run.getEstimate() > cap) {
            throw new BulkCapExceededException(run.getEstimate(), cap);
        }

        return gatedExecute(
                new TransferExecuteParams(
                        clusterId,
                        runId,
                        run.getPlanHash(),
                        run.getMode(),
                        run.getSourceQueue(),
                        run.getTargetClusterId(),
                        run.getTargetQueue(),
                        request.override(),
                        request.acknowledged() == null ? List.of() : List.copyOf(request.acknowledged()),
                        request.confirmQueue()),
                run,
                request);
    }

    /**
     * What a transfer would do, read from the previewed plan: its messages, and the plan's hash as the gate's state
     * key, so a plan that changed since it was requested is refused when it would start.
     */
    public Reach reach(UUID clusterId, UUID runId) {
        TransferRunEntity run = load(clusterId, runId);
        requireRunPermissions(run);
        return new Reach(run.getEstimate(), run.getPlanHash());
    }

    /** A preview, as the gate reads it; {@code messages} is {@code null} when the selection's size is not known. */
    public record Reach(Long messages, String planHash) {}

    /**
     * Starts the run through the approval gate as {@code transfer.execute}. Held, the preview is kept until the hold
     * ends, so the approved run can still start.
     */
    @Gated("transfer.execute")
    private TransferRunView gatedExecute(
            TransferExecuteParams params, TransferRunEntity run, TransferExecuteRequest request) {
        try {
            return gate.run(Operation.of(params), () -> start(run, request));
        } catch (OperationHeldException e) {
            runs.extendPreview(run.getId(), e.expiresAt().plus(PREVIEW_LIFETIME));
            throw e;
        }
    }

    private TransferRunView start(TransferRunEntity preview, TransferExecuteRequest request) {
        UUID runId = preview.getId();
        Operator operator = handoff.capture();
        claim(preview, Set.of(TransferState.PREVIEWED), TransferState.RUNNING);
        TransferRunEntity run = reload(runId);
        run.begin(
                TransferState.RUNNING,
                operator.actor().displayName(),
                operator.principal().userId(),
                replicas.id(),
                Instant.now());
        run.overrideCap(request.override());
        beginAudit(run, operator, AUDIT_ACTION, auditParams(run));
        run = runs.save(run);
        runner.start(run.getId(), operator);
        return view(run);
    }

    /** Ask a running run to stop: the batch in flight finishes, then the run stops, resumable. */
    public TransferRunView stop(UUID clusterId, UUID runId) {
        TransferRunEntity run = load(clusterId, runId);
        requireRunPermissions(run);
        boolean here = runner.requestStop(runId);
        if (!here && !run.getState().active()) {
            throw new ConflictException(
                    "transfer-run-not-running", "This transfer is not running, so there is nothing to stop.");
        }
        // Recorded on the run, so a signal that is lost cannot leave it running (ADR-0152).
        runs.requestStop(runId, Instant.now());
        if (!here) {
            // Executing on another replica: every replica hears it at once, the executing one acts.
            runner.signalStop(runId);
        }
        Operator operator = handoff.capture();
        AuditEvent event = childOf(
                run.getAuditEventId(),
                () -> audit.begin(
                        operator.actor(),
                        AUDIT_ACTION + ".stop",
                        AUDIT_QUEUE,
                        run.getSourceQueue(),
                        run.getSourceClusterId(),
                        run.getSourceNodeId(),
                        Map.of(RUN_ID, runId.toString()),
                        false));
        audit.succeed(event, 0);
        return view(run);
    }

    /** Continue a stopped, interrupted or failed run where it left off. */
    public TransferRunView resume(UUID clusterId, UUID runId) {
        TransferRunEntity run = load(clusterId, runId);
        requireRunPermissions(run);
        requireResumable(run);
        Operator operator = handoff.capture();
        claim(run, TransferState.RESUMABLE, TransferState.RUNNING);
        run = reload(runId);
        Long previous = run.getAuditEventId();
        run.begin(
                TransferState.RUNNING,
                operator.actor().displayName(),
                operator.principal().userId(),
                replicas.id(),
                Instant.now());
        TransferRunEntity resumed = run;
        childOf(previous, () -> {
            beginAudit(resumed, operator, AUDIT_ACTION + ".resume", auditParams(resumed));
            return null;
        });
        run = runs.save(run);
        runner.start(run.getId(), operator);
        return view(run);
    }

    /** Put every message a stopped, interrupted or failed move holds in staging back on its source queue. */
    public TransferRunView returnToSource(UUID clusterId, UUID runId) {
        TransferRunEntity loaded = load(clusterId, runId);
        asRun(
                loaded,
                () -> access.requireResource(
                        loaded.getSourceClusterId(),
                        ResourceRef.queue(loaded.getSourceQueue()),
                        MessagePermissions.MESSAGE_MOVE));
        TransferRunEntity run = loaded;
        requireReadable(run);
        requireResumable(run);
        if (run.getMode() != TransferMode.MOVE || run.isSameNode()) {
            throw new TransferRefusedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "transfer-not-returnable",
                    "Only a move between two nodes holds messages in staging; this run holds none to return.");
        }
        Operator operator = handoff.capture();
        claim(run, TransferState.RESUMABLE, TransferState.RETURNING);
        run = reload(runId);
        Long previous = run.getAuditEventId();
        run.begin(
                TransferState.RETURNING,
                operator.actor().displayName(),
                operator.principal().userId(),
                replicas.id(),
                Instant.now());
        TransferRunEntity returning = run;
        AuditEvent event = childOf(
                previous,
                () -> audit.begin(
                        operator.actor(),
                        AUDIT_ACTION + ".return",
                        AUDIT_QUEUE,
                        returning.getSourceQueue(),
                        returning.getSourceClusterId(),
                        returning.getSourceNodeId(),
                        Map.of(RUN_ID, runId.toString(), "stagingQueue", StagingQueues.queueName(runId)),
                        false));
        run.attachAudit(event.getId(), null);
        run = runs.save(run);
        runner.start(run.getId(), operator);
        return view(run);
    }

    private void requireResumable(TransferRunEntity run) {
        if (!run.getState().resumable()) {
            throw new ConflictException(
                    "transfer-run-not-resumable",
                    "This transfer is %s; only a stopped, interrupted or failed run can be resumed or returned."
                            .formatted(run.getState()));
        }
    }

    /**
     * Move the run from {@code from} to {@code to}, once. Refused when too many runs are active, when
     * another run is active on the same source queue, or when the run is no longer in {@code from}.
     */
    private void claim(TransferRunEntity run, Collection<TransferState> from, TransferState to) {
        int max = settings.intValue(TransferSettings.MAX_CONCURRENT_RUNS);
        if (runs.countByStateIn(TransferState.ACTIVE) >= max) {
            throw new ConflictException(
                    "transfer-concurrency-limit",
                    "%d transfers are already running, the most allowed at once (transfer.max-concurrent-runs)."
                                    .formatted(max)
                            + " Wait for one to finish.");
        }
        try {
            if (runs.transition(run.getId(), from, to) == 0) {
                throw new ConflictException(
                        "transfer-run-state", "This transfer changed state meanwhile. Reload it and try again.");
            }
        } catch (DataIntegrityViolationException _) {
            throw new ConflictException(
                    "transfer-run-in-progress",
                    "Another transfer is running from queue %s. Wait for it to finish, or stop it."
                            .formatted(run.getSourceQueue()));
        }
    }

    /** The segment's audit event on the source cluster, and its linked event on the target cluster. */
    private void beginAudit(TransferRunEntity run, Operator operator, String action, Map<String, ?> params) {
        AuditEvent source = audit.begin(
                operator.actor(),
                action,
                AUDIT_QUEUE,
                run.getSourceQueue(),
                run.getSourceClusterId(),
                run.getSourceNodeId(),
                params,
                false);
        AuditEvent target = childOf(
                source.getId(),
                () -> audit.begin(
                        operator.actor(),
                        action + ".in",
                        AUDIT_QUEUE,
                        run.getTargetQueue(),
                        run.getTargetClusterId(),
                        run.getTargetNodeId(),
                        params,
                        false));
        run.attachAudit(source.getId(), target.getId());
    }

    private static <T> T childOf(Long parent, ScopedValue.CallableOp<T, RuntimeException> action) {
        return parent == null
                ? action.call()
                : ScopedValue.where(AuditScope.PARENT, parent).call(action);
    }

    private Map<String, Object> auditParams(TransferRunEntity run) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(RUN_ID, run.getId().toString());
        params.put("mode", run.getMode().name());
        params.put("source", run.getSourceNodeName() + "/" + run.getSourceQueue());
        params.put("target", run.getTargetNodeName() + "/" + run.getTargetQueue());
        params.put("targetClusterId", run.getTargetClusterId().toString());
        params.put("selection", selection(run));
        params.put("t0", run.getT0().toString());
        params.put("estimate", run.getEstimate());
        params.put("overrideCap", run.isOverrideCap());
        return params;
    }

    // ---- read ---------------------------------------------------------------

    public TransferRunView get(UUID clusterId, UUID runId) {
        access.requireVisible(clusterId);
        TransferRunEntity run = load(clusterId, runId);
        requireReadable(run);
        return view(run);
    }

    /** Runs where this cluster is the source or the target, newest first; a run whose queue or address the operator cannot read is left out. */
    public List<TransferRunView> history(UUID clusterId) {
        access.requireVisible(clusterId);
        return runs.history(clusterId, Limit.of(HISTORY)).stream()
                .filter(r -> permissions.can(
                                r.getSourceClusterId(), ResourceRef.queue(r.getSourceQueue()), Permissions.QUEUE_READ)
                        && permissions.can(
                                r.getTargetClusterId(),
                                ResourceRef.address(r.getTargetAddress()),
                                Permissions.ADDRESS_READ))
                .map(this::view)
                .toList();
    }

    // ---- orphaned staging ------------------------------------------------------

    /** Staging queues on this cluster's live nodes that no run knows: left by a run whose record is gone. */
    public List<OrphanView> orphans(UUID clusterId) {
        access.requireCluster(clusterId, Permissions.CLUSTER_READ);
        List<OrphanView> out = new ArrayList<>();
        for (ClusterNode node : ServingNodes.from(directory.nodes(clusterId))) {
            if (!TransferNodes.live(node)) {
                continue;
            }
            try {
                ArtemisNode client = nodes.client(node);
                for (String queue : staging.list(client)) {
                    UUID runId = runIdOf(queue);
                    if (runId != null && runs.existsById(runId)) {
                        continue;
                    }
                    out.add(new OrphanView(node.getId(), node.getName(), queue, depth(client, queue)));
                }
            } catch (BrokerConnectionException _) {
                // A node that cannot be read shows none; its orphans are still there for the next look.
            }
        }
        return out;
    }

    /** Put an orphaned staging queue's messages on a queue of the same node, then remove the staging queue. */
    public OrphanReturnView returnOrphan(UUID clusterId, OrphanReturnRequest request) {
        // A staging queue is named by no team's pattern, so only a grant that reaches the cluster passes; the queue the
        // messages go back into is read and sent to through its own address, as in a transfer.
        access.requireVisible(clusterId);
        String returnAddress = locator.locate(clusterId, request.targetQueue()).stream()
                .findFirst()
                .map(QueueLocation::address)
                .orElseThrow(() -> access.unreadableAmongSeveral(clusterId));
        access.requireAll(
                clusterId,
                List.of(
                        new Requirement(ResourceRef.queue(request.stagingQueue()), MessagePermissions.MESSAGE_MOVE),
                        new Requirement(ResourceRef.queue(request.targetQueue()), Permissions.QUEUE_READ),
                        new Requirement(ResourceRef.address(returnAddress), MessagePermissions.MESSAGE_SEND)));
        ClusterNode node = nodes.node(clusterId, request.nodeId());
        String queue = request.stagingQueue();
        if (!queue.startsWith(StagingQueues.PREFIX)) {
            throw new IllegalArgumentException(queue + " is not a Rahdar staging queue.");
        }
        UUID runId = runIdOf(queue);
        if (runId != null && runs.existsById(runId)) {
            throw new ConflictException(
                    "transfer-not-orphaned",
                    "Staging queue %s belongs to transfer %s. Resume or return that run instead."
                            .formatted(queue, runId));
        }
        Operator operator = handoff.capture();
        AuditEvent event = audit.begin(
                operator.actor(),
                AUDIT_ACTION + ".orphan-return",
                AUDIT_QUEUE,
                queue,
                clusterId,
                node.getId(),
                Map.of("stagingQueue", queue, "target", request.targetQueue()),
                false);
        long returned = 0;
        try {
            ArtemisNode client = nodes.client(node);
            String mbean = MBeanNames.queue(client.resolveBrokerObjectName(), queue, queue, ANYCAST);
            int chunk = settings.intValue(TransferSettings.BATCH_SIZE);
            long moved;
            do {
                moved = messages.moveMessages(client, mbean, chunk, "", request.targetQueue(), false, chunk);
                returned += moved;
            } while (moved >= chunk);
            boolean removed = staging.destroyIfEmpty(client, queue);
            Long left = removed ? Long.valueOf(0) : depth(client, queue);
            long remaining = left == null ? 0 : left;
            if (removed) {
                audit.succeed(event, returned);
            } else {
                audit.failPartial(
                        event,
                        returned,
                        "%d messages are still in %s (in delivery or scheduled); it was kept."
                                .formatted(remaining, queue));
            }
            return new OrphanReturnView(queue, returned, remaining, removed);
        } catch (RuntimeException e) {
            if (returned > 0) {
                audit.failPartial(event, returned, e.getMessage());
            } else {
                audit.fail(event, e.getMessage());
            }
            throw e;
        }
    }

    private Long depth(ArtemisNode client, String queue) {
        try {
            return messages.messageCount(
                    client, MBeanNames.queue(client.resolveBrokerObjectName(), queue, queue, ANYCAST));
        } catch (BrokerConnectionException _) {
            return null;
        }
    }

    private static UUID runIdOf(String stagingQueue) {
        try {
            return UUID.fromString(stagingQueue.substring(StagingQueues.PREFIX.length()));
        } catch (IllegalArgumentException | IndexOutOfBoundsException _) {
            return null;
        }
    }

    // ---- plumbing -------------------------------------------------------------

    /** A run this cluster is the source or target of; 404 otherwise. */
    private TransferRunEntity load(UUID clusterId, UUID runId) {
        return runs.findById(runId)
                .filter(r -> r.getSourceClusterId().equals(clusterId)
                        || r.getTargetClusterId().equals(clusterId))
                .orElseThrow(() -> new NotFoundException("transfer", runId));
    }

    private TransferRunEntity reload(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> new NotFoundException("transfer", runId));
    }

    /** Acting on a run needs its mode's permission on the source queue and {@code message:send} on the target address. */
    private void requireRunPermissions(TransferRunEntity run) {
        asRun(run, () -> {
            access.requireResource(
                    run.getSourceClusterId(),
                    ResourceRef.queue(run.getSourceQueue()),
                    run.getMode().sourcePermission());
            access.requireAll(
                    run.getTargetClusterId(),
                    List.of(
                            new Requirement(ResourceRef.queue(run.getTargetQueue()), Permissions.QUEUE_READ),
                            new Requirement(
                                    ResourceRef.address(run.getTargetAddress()), MessagePermissions.MESSAGE_SEND)));
        });
    }

    /**
     * Runs a check on a run's queues and addresses, and answers a queue the caller may not read with the
     * run's own not-found: the run is not theirs to know, and its queues are not named.
     */
    private static void asRun(TransferRunEntity run, Runnable check) {
        try {
            check.run();
        } catch (NotFoundException _) {
            throw new NotFoundException("transfer", run.getId());
        }
    }

    /** A run is read through its source queue and its target address. */
    private void requireReadable(TransferRunEntity run) {
        asRun(run, () -> {
            access.requireResource(
                    run.getSourceClusterId(), ResourceRef.queue(run.getSourceQueue()), Permissions.QUEUE_READ);
            access.requireResource(
                    run.getTargetClusterId(), ResourceRef.address(run.getTargetAddress()), Permissions.ADDRESS_READ);
        });
    }

    List<Finding> findings(TransferRunEntity run) {
        return json.readValue(run.getFindings(), new TypeReference<List<Finding>>() {});
    }

    TransferSelection selection(TransferRunEntity run) {
        return json.readValue(run.getSelection(), TransferSelection.class);
    }

    TransferRunView view(TransferRunEntity run) {
        TransferSelection selection = selection(run);
        boolean staged = run.getMode() == TransferMode.MOVE && !run.isSameNode();
        long held =
                staged ? Math.max(0, run.getStaged() - run.getDelivered() - run.getExpired() - run.getReturned()) : 0;
        Double rate = null;
        if (run.getState().active() && run.getStartedAt() != null) {
            double seconds = Duration.between(run.getStartedAt(), Instant.now()).toMillis() / 1000.0;
            rate = seconds > 0 ? run.getDelivered() / seconds : null;
        }
        List<String> notes = new ArrayList<>();
        notes.add("Messages published over AMQP, MQTT, STOMP or OpenWire arrive in their Core form.");
        if (run.getMode() == TransferMode.MOVE) {
            notes.add("A move clears each message's expiration, as the broker's own move does, so moved messages"
                    + " never expire on the target. A copy keeps it.");
        }
        if (selection.kind() != SelectionKind.IDS) {
            notes.add("Only messages timestamped at or before %s are selected, so messages produced during the run"
                            .formatted(run.getT0())
                    + " stay; a message whose producer's clock runs ahead is left out too.");
        }
        long cap = settings.intValue(BrokerSettings.BULK_CAP);
        return views.toView(
                run,
                new TransferEnd(
                        run.getSourceClusterId(),
                        run.getSourceNodeId(),
                        run.getSourceNodeName(),
                        run.getSourceQueue(),
                        run.getSourceAddress()),
                new TransferEnd(
                        run.getTargetClusterId(),
                        run.getTargetNodeId(),
                        run.getTargetNodeName(),
                        run.getTargetQueue(),
                        run.getTargetAddress()),
                selection,
                findings(run),
                new Derived(
                        notes,
                        held,
                        rate,
                        staged ? StagingQueues.queueName(run.getId()) : null,
                        cap,
                        run.getEstimate() == null || run.getEstimate() > cap,
                        run.getState().resumable(),
                        run.getState().resumable() && staged));
    }
}
