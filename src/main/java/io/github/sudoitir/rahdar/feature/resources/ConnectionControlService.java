package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.resources.ConnectionOperations.ConnectionSnapshot;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.Command;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.Estimate;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Closing a connection, a session, or an address's consumers (ADR-0057).
 *
 * <p>Three properties shape everything here, and all three follow from the fact
 * that a connection identifier is issued by one node and may be gone by the time
 * an operator clicks:
 *
 * <ul>
 *   <li><b>A close by id names a node.</b> Every other mutating operation in
 *       Rahdar takes a cluster; this one does not, and the inconsistency is
 *       deliberate (D1). Trying an id on every node would either mean nothing or
 *       collide with an unrelated connection. Only the address-scoped close is a
 *       coherent cluster-wide intent, and it fans out.
 *   <li><b>A vanished target is a success</b> (D2). The requested state — that
 *       connection is not open — holds. The outcome still distinguishes
 *       {@code APPLIED} from {@code ALREADY} so the audit record is accurate.
 *   <li><b>The target is re-read immediately before the close</b> (D3), never
 *       trusted from the row a client sent. That read is the only chance to
 *       capture who is being disconnected: afterwards the id resolves to nothing,
 *       and an audit row naming only the id would be useless.
 *   </ul>
 *
 * <p>Nothing is retried. The operation is not idempotent and an id can be reissued
 * between the read and the close, so a retry may act on a different connection.
 *
 * <p>The result is a {@link LifecycleOutcome} — the same per-node shape change 01
 * introduced — with exactly one entry for a node-scoped close. That keeps one
 * outcome vocabulary and one UI component across every cluster mutation.
 */
@Service
@RequiredArgsConstructor
public class ConnectionControlService {

    private final ClusterDirectory brokerNodes;
    private final ConnectionOperations ops;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final SseHub sseHub;
    private final ClusterAccessGuard clusterAccess;
    private final BrokerCommands commands;
    private final BrokerGateway gateway;

    /**
     * One close's result: what was found immediately before it, and what each node
     * reported.
     *
     * @param target the pre-close snapshot, or {@code null} for an address-scoped
     *     close (which has no single application to name) and for a target that was
     *     already gone
     */
    public record CloseResult(
            ConnectionCloseKind kind, String subject, ConnectionSnapshot target, LifecycleOutcome outcome) {}

    /**
     * What one audit row carries. {@code audit_event.params} is immutable after
     * insert — by design — and the identity is only knowable after the pre-close
     * read, so it rides in the outcome detail beside the per-node result (D3).
     */
    private record CloseDetail(ConnectionSnapshot target, List<NodeOutcome> nodes) {}

    // ---- node-scoped closes ----------------------------------------------

    public Attempt<CloseResult> closeConnection(UUID clusterId, UUID nodeId, String connectionId, boolean dryRun) {
        return byId(clusterId, nodeId, ConnectionCloseKind.CONNECTION, connectionId, dryRun);
    }

    public Attempt<CloseResult> closeSession(UUID clusterId, UUID nodeId, String sessionId, boolean dryRun) {
        return byId(clusterId, nodeId, ConnectionCloseKind.CHANNEL, sessionId, dryRun);
    }

    public Attempt<CloseResult> closeConsumerConnection(
            UUID clusterId, UUID nodeId, String consumerId, boolean dryRun) {
        return byId(clusterId, nodeId, ConnectionCloseKind.CONSUMER, consumerId, dryRun);
    }

    /**
     * The one path for every close that names an id on a node.
     *
     * <p>Resolution walks outwards from whatever id the view had — a consumer knows
     * its session, a session knows its connection — because the broker's rows carry
     * the link and the client must not be the one to supply it.
     */
    private Attempt<CloseResult> byId(
            UUID clusterId, UUID nodeId, ConnectionCloseKind kind, String id, boolean dryRun) {
        clusterAccess.requireCluster(clusterId, kind.permission());
        String subject = requireId(kind, id);
        ClusterNode node = manageableNode(clusterId, nodeId);

        AuditEvent event = audit.begin(
                actorResolver.resolve(),
                kind.auditName(),
                kind.targetType(),
                subject,
                clusterId,
                node.getId(),
                Map.of("id", subject),
                dryRun);

        try {
            BrokerTarget broker = gateway.node(clusterId, node.getId());
            Resolved resolved = resolve(broker, kind, subject);

            // Already gone. The requested state holds, so this is a success on every
            // path — including the audit row, which records what was asked for and
            // that there was nothing left to close.
            if (resolved == null) {
                // ALREADY on a preview too. A dry run of a target that has already
                // gone has nothing it "would apply" to, and reporting one would put
                // "already gone" and "would apply" on the screen together.
                LifecycleOutcome outcome = single(node, dryRun, NodeStatus.ALREADY, null);
                audit.finish(event, false, 0, null, new CloseDetail(null, outcome.nodes()));
                return new Attempt.Ok<>(new CloseResult(kind, subject, null, outcome));
            }

            ConnectionSnapshot target = resolved.target();

            if (dryRun) {
                LifecycleOutcome outcome = single(node, true, NodeStatus.WOULD_APPLY, target.messagesInTransit());
                audit.finish(event, false, 0, null, new CloseDetail(target, outcome.nodes()));
                return new Attempt.Ok<>(new CloseResult(kind, subject, target, outcome));
            }

            boolean closed = ops.close(broker, resolved.closes());

            LifecycleOutcome outcome = single(
                    node,
                    false,
                    closed ? NodeStatus.APPLIED : NodeStatus.ALREADY,
                    closed ? target.messagesInTransit() : null);
            audit.finish(event, false, closed ? 1 : 0, null, new CloseDetail(target, outcome.nodes()));
            publishAfterCommit(clusterId);
            return new Attempt.Ok<>(new CloseResult(kind, subject, target, outcome));
        } catch (BrokerConnectionException e) {
            audit.fail(event, e.getMessage());
            return new Attempt.Failed<>(e.kind(), e.getMessage());
        }
    }

    /** What a close by id ends up acting on, once the broker's own rows have been walked. */
    private record Resolved(ResourceId closes, ConnectionSnapshot target) {}

    private Resolved resolve(BrokerTarget node, ConnectionCloseKind kind, String id) {
        ClusterConnection c = node.connection();
        switch (kind) {
            case CONNECTION -> {
                ConnectionSnapshot target = ops.readConnection(node, id);
                return target == null ? null : new Resolved(ResourceIds.of(c, ResourceClass.CONNECTION, id), target);
            }
            case CHANNEL -> {
                ConnectionSnapshot target = ops.readSession(node, id);
                if (target == null || target.connectionId() == null) {
                    return null;
                }
                return new Resolved(ResourceIds.of(c, ResourceClass.CHANNEL, id), target);
            }
            case CONSUMER -> {
                // A consumer names its session, and the session its connection, so the connection
                // is found through them rather than guessed from the remote address. Closing a
                // consumer closes the connection behind it: the intervention the slow-consumer
                // finding implies.
                String sessionId = ops.sessionOfConsumer(node, id);
                String connectionId = sessionId == null ? null : ops.connectionOfSession(node, sessionId);
                if (connectionId == null) {
                    return null;
                }
                ConnectionSnapshot target = ops.readConnection(node, connectionId);
                return target == null
                        ? null
                        : new Resolved(ResourceIds.of(c, ResourceClass.CONNECTION, connectionId), target);
            }
            default -> throw new IllegalStateException("not a by-id close: " + kind);
        }
    }

    // ---- address-scoped close --------------------------------------------

    /**
     * Close every consumer connection bound to an address, on every live node.
     *
     * <p>The one cluster-wide close, and the only one with an unbounded blast
     * radius — so it is estimated per node first, checked against the ADR-0022 bulk
     * cap, and refused above it without an explicit override (D6). It is not exempt
     * because it is "just a disconnect": each consumer it closes returns its
     * in-flight messages to a queue.
     */
    public Attempt<CloseResult> closeAddressConsumers(
            UUID clusterId, String address, boolean dryRun, boolean override) {
        ConnectionCloseKind kind = ConnectionCloseKind.DESTINATION;
        clusterAccess.requireCluster(clusterId, kind.permission());
        String subject = requireId(kind, address);
        LifecycleOutcome outcome = commands.run(Command.builder()
                .clusterId(clusterId)
                .permission(kind.permission())
                .auditAction(kind.auditName())
                .targetType(kind.targetType())
                .targetName(subject)
                .params(Map.of("address", subject))
                .dryRun(dryRun)
                .override(override)
                .action(node -> ops.close(node, ResourceIds.of(node.connection(), ResourceClass.DESTINATION, subject))
                        ? NodeStatus.APPLIED
                        : NodeStatus.ALREADY)
                // Each consumer it closes returns its in-flight messages to a queue, and a
                // node that could not be counted leaves the blast radius unknown.
                .estimate(new Estimate("consumer count", true, node -> ops.countConsumersForAddress(node, subject)))
                .auditDetail(nodes -> new CloseDetail(null, nodes))
                .signal(() -> publishTopics(clusterId))
                .build());
        return new Attempt.Ok<>(new CloseResult(kind, subject, null, outcome));
    }

    // ---- plumbing --------------------------------------------------------

    /**
     * A node-scoped close's outcome, in the same per-node shape a cluster-wide one
     * uses — with one entry. The cap fields are zero and false because this kind is
     * not capped: it closes exactly one connection.
     */
    private static LifecycleOutcome single(ClusterNode node, boolean dryRun, NodeStatus status, Long affected) {
        return new LifecycleOutcome(
                dryRun, 0, false, List.of(new NodeOutcome(node.getId(), node.getName(), status, affected, null)));
    }

    private static String requireId(ConnectionCloseKind kind, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    kind == ConnectionCloseKind.DESTINATION ? "A destination is required." : "An id is required.");
        }
        return id.trim();
    }

    /** The node an id was issued by. A node with no management URL cannot be asked. */
    private ClusterNode manageableNode(UUID clusterId, UUID nodeId) {
        return brokerNodes.nodes(clusterId).stream()
                .filter(n -> n.getId().equals(nodeId) && n.getManagementUrl() != null)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("node", nodeId));
    }

    private void publishTopics(UUID clusterId) {
        sseHub.publish(clusterId, "connections");
        sseHub.publish(clusterId, "channels");
        sseHub.publish(clusterId, "consumers");
    }

    /**
     * A close changes the connection, session and consumer views at once, so all
     * three topics are nudged — a view left showing a connection that is gone is
     * exactly the staleness this feature exists to act on.
     */
    private void publishAfterCommit(UUID clusterId) {
        Runnable publish = () -> publishTopics(clusterId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
