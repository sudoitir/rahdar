package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditScope;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.broker.VersionGate;
import io.github.sudoitir.rahdar.platform.clusters.CapabilityLedger;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs one cluster-wide broker write the same way every time (ADR-0071, ADR-0049):
 *
 * <ol>
 *   <li>the caller's permission, on each queue or address the command acts on, or on the cluster when
 *       it acts on none, checked after the audit row is opened, so a refused command leaves a
 *       {@code REFUSED} event;
 *   <li>one target per logical node, liveness from the polled {@code Active} attribute
 *       (non-negotiable #4);
 *   <li>the audit row, written before any broker call (non-negotiable #3);
 *   <li>a per-node estimate, so the bulk cap sees the whole blast radius (ADR-0022);
 *   <li>a dry run returns {@code WOULD_APPLY} per node and writes nothing, with each node's
 *       preflight warning, which the real run carries onto that node's result as well;
 *   <li>over the cap without an override, the refusal is audited and thrown;
 *   <li>the fan-out, one rate-limited call per live node, where a node's failure never
 *       aborts the others and nothing is rolled back (D3);
 *   <li>the audit row finished with the per-node detail (D4);
 *   <li>the caller's topic signal, after commit.
 * </ol>
 *
 * <p>No database transaction is held across the fan-out: the audit row and its outcome
 * each commit on their own (ADR-0078), and a broker call must never pin a pooled
 * connection for N nodes × the read timeout.
 */
@Component
@RequiredArgsConstructor
public class BrokerCommands {

    private final ClusterDirectory directory;
    private final BrokerConnections connections;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final SettingsService settings;
    private final ClusterAccessGuard clusterAccess;
    private final CapabilityLedger capabilities;
    private final ArtemisNodes artemisNodes;

    /** What one node's attempt does; returns the state the node reached. */
    @FunctionalInterface
    public interface NodeAction {
        NodeStatus apply(ArtemisNode client, String brokerMbean);
    }

    /**
     * What must be true on one node before the command may touch it, checked in the dry run and
     * again before the real attempt, so what was previewed is what is enforced.
     */
    @FunctionalInterface
    public interface NodePreflight {
        Check apply(ArtemisNode client, String brokerMbean);
    }

    /**
     * A preflight's verdict: a refusal fails the node without acting; a warning is shown in the
     * preview and does not stop the command.
     */
    public record Check(String refusal, String warning) {

        public static final Check OK = new Check(null, null);

        public static Check refuse(String reason) {
            return new Check(reason, null);
        }

        public static Check warn(String warning) {
            return new Check(null, warning);
        }
    }

    /** How much one node's attempt would destroy, for the cap check (ADR-0022). */
    @FunctionalInterface
    public interface NodeEstimate {
        long apply(ArtemisNode client);
    }

    /**
     * A destructive command's estimate.
     *
     * @param label what is counted, as an operator reads it: "message count"
     * @param unknownNeedsOverride whether a node that could not be counted makes the total a
     *     floor that needs the same explicit override an over-cap total does
     */
    public record Estimate(String label, boolean unknownNeedsOverride, NodeEstimate count) {}

    /**
     * One command.
     *
     * @param resources the queues or addresses the command acts on, each of which the caller needs
     *     {@code permission} on; empty for a command on the cluster as a whole, which needs it cluster-wide
     * @param estimate {@code null} for a command that destroys nothing
     * @param preflight {@code null} for a command with nothing to check first
     * @param requires the operation's version gate (ADR-0142), or {@code null}; a node whose
     *     recorded release is older is reported {@code UNSUPPORTED_VERSION} and never called
     * @param auditDetail shapes the per-node outcomes into the audit row's detail
     * @param signal the topic signal published after commit
     */
    @Builder
    public record Command(
            UUID clusterId,
            String permission,
            List<ResourceRef> resources,
            String auditAction,
            String targetType,
            String targetName,
            Map<String, ?> params,
            boolean dryRun,
            boolean override,
            NodeAction action,
            NodePreflight preflight,
            VersionGate requires,
            Estimate estimate,
            Function<List<NodeOutcome>, Object> auditDetail,
            Runnable signal) {

        public Command {
            resources = resources == null ? List.of() : List.copyOf(resources);
            params = params == null ? Map.of() : params;
            auditDetail = auditDetail == null ? nodes -> nodes : auditDetail;
            signal = signal == null ? () -> {} : signal;
        }
    }

    private record Target(ClusterNode node, boolean live) {}

    public LifecycleOutcome run(Command c) {
        // Opened first, so a change the caller may not make leaves a REFUSED event rather than none.
        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                c.auditAction(),
                c.targetType(),
                c.targetName(),
                c.clusterId(),
                null,
                c.params(),
                c.dryRun());
        List<Target> targets;
        try {
            ScopedValue.where(AuditScope.OWN_REFUSAL, true).run(() -> authorise(c));
        } catch (AccessDeniedException | NotFoundException e) {
            audit.refuse(event, e.getMessage());
            throw e;
        }
        try {
            targets = targets(c.clusterId());
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
        long cap = settings.intValue(BrokerSettings.BULK_CAP);

        Map<UUID, Long> estimates = estimate(c, targets);
        long total = estimates.values().stream()
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        // A node that could not be counted makes the total a floor, not a figure.
        boolean incomplete = estimates.containsValue(null);
        boolean overCap = c.estimate() != null
                && (total > cap || (incomplete && c.estimate().unknownNeedsOverride()));

        List<NodeOutcome> outcomes = new ArrayList<>();
        if (c.dryRun()) {
            for (Target t : targets) {
                outcomes.add(preview(c, t, estimates));
            }
            audit.finish(event, false, total, null, c.auditDetail().apply(outcomes));
            return new LifecycleOutcome(true, cap, overCap, outcomes);
        }

        if (overCap && !c.override()) {
            audit.finish(
                    event,
                    true,
                    total,
                    capReason(c, total, cap, incomplete),
                    c.auditDetail().apply(outcomes));
            throw new BulkCapExceededException(total, cap);
        }

        targets.stream()
                .map(t -> t.live()
                        ? applyTo(c, t, estimates.get(t.node().getId()))
                        : NodeOutcome.skipped(t.node().getId(), t.node().getName()))
                .forEach(outcomes::add);

        LifecycleOutcome outcome = new LifecycleOutcome(false, cap, overCap, outcomes);
        audit.finish(
                event,
                outcome.anyFailed(),
                outcome.totalAffected(),
                failureSummary(outcomes),
                c.auditDetail().apply(outcomes));
        signalAfterCommit(c.signal());
        return outcome;
    }

    private void authorise(Command c) {
        if (c.resources().isEmpty()) {
            clusterAccess.requireCluster(c.clusterId(), c.permission());
        } else {
            clusterAccess.requireAll(
                    c.clusterId(),
                    c.resources().stream()
                            .map(r -> new ClusterAccessGuard.Requirement(r, c.permission()))
                            .toList());
        }
    }

    /** Each live node's estimate; a node that could not be counted maps to {@code null}. */
    private Map<UUID, Long> estimate(Command c, List<Target> targets) {
        Map<UUID, Long> estimates = new LinkedHashMap<>();
        if (c.estimate() == null) {
            return estimates;
        }
        for (Target t : targets) {
            if (t.live()) {
                estimates.put(t.node().getId(), estimateOn(c, t));
            }
        }
        return estimates;
    }

    private Long estimateOn(Command c, Target t) {
        try {
            return c.estimate().count().apply(clientFor(c.clusterId(), t.node()));
        } catch (ManagementRefusal _) {
            // Nothing to destroy here — an absent resource counts as zero, and
            // the action reports the node as ALREADY.
            return 0L;
        } catch (BrokerConnectionException _) {
            return null;
        }
    }

    /** What a dry run says about one node: skipped, refused by its preflight, or would apply. */
    private NodeOutcome preview(Command c, Target t, Map<UUID, Long> estimates) {
        UUID id = t.node().getId();
        if (!t.live()) {
            return NodeOutcome.skipped(id, t.node().getName());
        }
        NodeOutcome unsupported = unsupported(c, t);
        if (unsupported != null) {
            return unsupported;
        }
        Check check = preflight(c, t);
        if (check.refusal() != null) {
            return NodeOutcome.failed(id, t.node().getName(), check.refusal());
        }
        boolean unknown = c.estimate() != null && estimates.get(id) == null;
        // Stated, never omitted: an absent count reads as zero.
        String note =
                unknown ? "This node did not answer, so its " + c.estimate().label() + " is unknown." : check.warning();
        return new NodeOutcome(id, t.node().getName(), NodeStatus.WOULD_APPLY, estimates.get(id), note);
    }

    private static String capReason(Command c, long total, long cap, boolean incomplete) {
        return total <= cap && incomplete
                ? "At least one node did not report its " + c.estimate().label()
                        + ", so the blast radius is not known (" + total + " counted, cap " + cap + ")."
                : "Over the safety cap (" + total + " > " + cap + ").";
    }

    /**
     * One node's attempt. Every failure mode becomes a node outcome rather than aborting
     * the fan-out: the nodes that succeeded are not reverted, and the divergence is what
     * gets reported (D3).
     */
    /** The node's outcome when its release is too old for the command, else null. */
    private static NodeOutcome unsupported(Command c, Target t) {
        String refusal =
                c.requires() == null ? null : c.requires().refusal(t.node().getVersion());
        return refusal == null
                ? null
                : new NodeOutcome(t.node().getId(), t.node().getName(), NodeStatus.UNSUPPORTED_VERSION, null, refusal);
    }

    private NodeOutcome applyTo(Command c, Target t, Long estimated) {
        UUID clusterId = c.clusterId();
        UUID nodeId = t.node().getId();
        String nodeName = t.node().getName();
        NodeOutcome unsupported = unsupported(c, t);
        if (unsupported != null) {
            return unsupported;
        }
        Check check = preflight(c, t);
        if (check.refusal() != null) {
            return NodeOutcome.failed(nodeId, nodeName, check.refusal());
        }
        try {
            ArtemisNode client = clientFor(clusterId, t.node());
            NodeStatus status = c.action().apply(client, client.resolveBrokerObjectName());
            capabilities.recordWriteSucceeded(clusterId);
            // The preflight's warning stays on the result, so what the preview stated beside
            // this node is still stated once it has happened — in the audit row too.
            return new NodeOutcome(
                    nodeId, nodeName, status, status == NodeStatus.APPLIED ? estimated : null, check.warning());
        } catch (ManagementRefusal e) {
            if (e.kind() == ManagementRefusal.Kind.ALREADY) {
                // Already in the requested state. The broker answered, so this is still
                // evidence the connection can write.
                capabilities.recordWriteSucceeded(clusterId);
                return new NodeOutcome(nodeId, nodeName, NodeStatus.ALREADY, null, null);
            }
            // An argument refusal says the request is wrong, not that the connection
            // cannot write — it must never disable the capability (D5).
            return NodeOutcome.failed(nodeId, nodeName, e.getMessage());
        } catch (BrokerConnectionException e) {
            if (e.kind() == BrokerConnectionException.Kind.CREDENTIALS_REJECTED) {
                capabilities.recordWriteRefused(clusterId, e.getMessage());
            }
            return NodeOutcome.failed(nodeId, nodeName, e.getMessage());
        }
    }

    /** The command's preflight on one node. A node that cannot be checked is refused, never assumed fine. */
    private Check preflight(Command c, Target t) {
        if (c.preflight() == null) {
            return Check.OK;
        }
        try {
            ArtemisNode client = clientFor(c.clusterId(), t.node());
            return c.preflight().apply(client, client.resolveBrokerObjectName());
        } catch (ManagementRefusal | BrokerConnectionException e) {
            return Check.refuse("Could not be checked before changing it: " + e.getMessage());
        }
    }

    /**
     * One target per <em>logical</em> node. A primary and its synced backup share an
     * Artemis NodeID; only one of them is live, and the backup refuses management writes,
     * so targeting the pair twice would report a healthy backup as skipped on every command.
     */
    private List<Target> targets(UUID clusterId) {
        Map<String, List<ClusterNode>> logical = new LinkedHashMap<>();
        for (ClusterNode node : directory.nodes(clusterId)) {
            if (node.getJolokiaUrl() == null) {
                continue;
            }
            String key = node.getArtemisNodeId() != null ? node.getArtemisNodeId() : "id:" + node.getId();
            logical.computeIfAbsent(key, k -> new ArrayList<>()).add(node);
        }
        List<Target> targets = new ArrayList<>();
        for (List<ClusterNode> group : logical.values()) {
            group.stream()
                    .filter(n -> Boolean.TRUE.equals(n.getActive()))
                    .findFirst()
                    .ifPresentOrElse(
                            live -> targets.add(new Target(live, true)),
                            () -> targets.add(new Target(group.get(0), false)));
        }
        if (targets.isEmpty()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "This cluster has no node with a management URL, so nothing can be changed on it.");
        }
        return targets;
    }

    private static String failureSummary(List<NodeOutcome> outcomes) {
        List<String> failed = outcomes.stream()
                .filter(n -> n.status() == NodeStatus.FAILED)
                .map(n -> n.nodeName() + ": " + n.error())
                .sorted(Comparator.naturalOrder())
                .toList();
        return failed.isEmpty() ? null : String.join(" | ", failed);
    }

    /** Every request the client sends waits for the node's ceiling itself (ADR-0076). */
    private ArtemisNode clientFor(UUID clusterId, ClusterNode node) {
        return artemisNodes.on(clusterId, node.getId());
    }

    private static void signalAfterCommit(Runnable signal) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    signal.run();
                }
            });
        } else {
            signal.run();
        }
    }
}
