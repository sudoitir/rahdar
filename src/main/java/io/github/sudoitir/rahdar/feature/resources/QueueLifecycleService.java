package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateDestinationRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateSubscriptionRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.UpdateResourceRequest;
import io.github.sudoitir.rahdar.feature.resources.QueueCalls.NodeQueues;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.DivertRoute;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.broker.QueueCounters;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.Check;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.Command;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.Estimate;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.NodeAction;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets.Target;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator.QueueLocation;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Queue, address and divert lifecycle across a cluster (ADR-0049).
 *
 * <p>A command names a <em>cluster</em>, not a node. Artemis cluster nodes each own
 * their own queues, so "create this queue on the cluster" is N management calls
 * with real partial-failure semantics — and the result is a per-node list, never a
 * boolean (D2). Target nodes come from observed live topology, never from stored
 * configuration (non-negotiable #4); a node that is not live is reported as
 * skipped, because it never received the command.
 *
 * <p>A partial failure is reported, never rolled back (D3). A {@code destroyQueue}
 * that succeeded on one node cannot be undone — the messages are gone — and a
 * compensating create would produce an empty queue with the same name, which is a
 * different state dressed up as the original.
 *
 * <p>One audit row per command carries the whole fan-out (D4), and a command that
 * failed on any node is recorded as failed with the per-node detail attached.
 */
@Service
@RequiredArgsConstructor
public class QueueLifecycleService {

    private static final String ADDRESS = "address";
    private static final String DURABLE = "durable";
    private static final String QUEUES_TOPIC = "subscriptions";

    private final QueueLocator queueLocator;
    private final QueueSnapshotUpsert snapshotWriter;
    private final Optional<DeclaredDiverts> declaredDiverts;
    private final Optional<CaptureTaps> captureTaps;
    private final SseHub sseHub;
    private final BrokerCommands commands;
    private final ClusterAccessGuard access;
    private final PermissionResolver permissions;
    private final OperationGate gate;
    private final BrokerMessages messages;
    private final QueueCounters counters;
    private final ClusterKinds kinds;
    private final WholeClusterLifecycle wholeCluster;

    /** What a delete would touch: how many messages, and the target's identity for the gate's state key. */
    public record Reach(long count, String identity, String detail) {

        public Reach(long count, String identity) {
            this(count, identity, null);
        }
    }

    /**
     * What the gate holds of a queue delete. {@code id} is the resource's canonical id on a cluster read as one,
     * and null on a per-node system.
     */
    record QueueDeleteParams(UUID clusterId, String queue, String id, boolean override, boolean disconnectConsumers) {}

    /** What the gate holds of an address delete. {@code id} is as on {@link QueueDeleteParams}. */
    record AddressDeleteParams(UUID clusterId, String address, String id) {}

    // ---- entry points ----------------------------------------------------

    public Attempt<LifecycleOutcome> createQueue(UUID clusterId, CreateSubscriptionRequest req, boolean dryRun) {
        // The queue is created as a name the caller's patterns cover, on an address they may also create:
        // a queue bound to someone else's address would receive what is sent to it.
        access.requireCreate(clusterId, ResourceRef.subscription(req.name()), ResourcePermissions.SUBSCRIPTION_CREATE);
        access.requireCreate(
                clusterId, ResourceRef.destination(req.destinationName()), ResourcePermissions.DESTINATION_CREATE);
        if (kinds.readAsOne(clusterId)) {
            return createWhole(clusterId, req, dryRun);
        }
        return run(clusterId, LifecycleKind.CREATE_SUBSCRIPTION, req.name(), params(req), dryRun, false, node -> {
            // A queue that exists as asked is ALREADY; one that exists differently is refused, naming how.
            return QueueCalls.createQueue(node, req);
        });
    }

    public Attempt<LifecycleOutcome> updateQueue(
            UUID clusterId, String queueName, UpdateResourceRequest req, boolean dryRun) {
        access.requireResource(
                clusterId, ResourceRef.subscription(queueName), LifecycleKind.UPDATE_RESOURCE.permission());
        Attributes patch = QueueCalls.patch(req);
        if (patch.isEmpty()) {
            throw new IllegalArgumentException("An update must change at least one field.");
        }
        resolveQueue(clusterId, queueName);
        return run(
                clusterId,
                LifecycleKind.UPDATE_RESOURCE,
                queueName,
                patch.asMap(),
                dryRun,
                false,
                node -> QueueCalls.updateQueue(node, queueName, patch));
    }

    /**
     * Delete a queue on every live node (ADR-0084). Each node is checked first, in the
     * preview and again for real: a queue with consumers is refused unless the operator asked
     * to disconnect them, and the diverts that forward only into this queue are named. For
     * real, those diverts go first and the queue after, so a divert that cannot be removed
     * leaves its queue in place rather than a divert forwarding into nothing.
     */
    public Attempt<LifecycleOutcome> deleteQueue(
            UUID clusterId, String queueName, boolean dryRun, boolean override, boolean disconnectConsumers) {
        access.requireResource(
                clusterId, ResourceRef.subscription(queueName), LifecycleKind.DELETE_SUBSCRIPTION.permission());
        if (dryRun) {
            return runDeleteQueue(clusterId, queueName, true, override, disconnectConsumers);
        }
        return gatedDeleteQueue(new QueueDeleteParams(clusterId, queueName, null, override, disconnectConsumers));
    }

    /**
     * Delete a subscription by the id the route resolved. A cluster read as one changes it as a whole through its
     * driver (ADR-0224); any other cluster deletes it by name on every node.
     */
    public Attempt<LifecycleOutcome> deleteQueue(
            UUID clusterId, Target target, boolean dryRun, boolean override, boolean disconnectConsumers) {
        if (!kinds.readAsOne(clusterId)) {
            return deleteQueue(clusterId, target.name(), dryRun, override, disconnectConsumers);
        }
        LifecycleKind kind = LifecycleKind.DELETE_SUBSCRIPTION;
        access.requireResource(clusterId, ResourceRef.subscription(target.name()), kind.permission());
        if (dryRun) {
            return runWholeDelete(clusterId, target.id(), kind, true, override, disconnectConsumers);
        }
        return gatedDeleteQueue(
                new QueueDeleteParams(clusterId, target.name(), target.id().format(), override, disconnectConsumers));
    }

    @Gated("subscription.delete")
    private Attempt<LifecycleOutcome> gatedDeleteQueue(QueueDeleteParams p) {
        return gate.run(Operation.of(p), () -> {
            if (p.id() == null) {
                return runDeleteQueue(p.clusterId(), p.queue(), false, p.override(), p.disconnectConsumers());
            }
            return runWholeDelete(
                    p.clusterId(),
                    ResourceId.parse(p.id()),
                    LifecycleKind.DELETE_SUBSCRIPTION,
                    false,
                    p.override(),
                    p.disconnectConsumers());
        });
    }

    private Attempt<LifecycleOutcome> runWholeDelete(
            UUID clusterId,
            ResourceId id,
            LifecycleKind kind,
            boolean dryRun,
            boolean override,
            boolean disconnectConsumers) {
        return wholeCluster.delete(clusterId, id, kind, dryRun, override, disconnectConsumers, () -> {
            if (kind == LifecycleKind.DELETE_SUBSCRIPTION) {
                snapshotWriter.forget(clusterId, id.name());
            }
            sseHub.publishAbout(clusterId, QUEUES_TOPIC, List.of(kind.resource(id.name())));
        });
    }

    /**
     * What deleting the resource would remove now on a cluster read as one, from the driver's preview: the messages
     * when it counts them, and the etag of what it saw as the gate's state key.
     */
    public Reach reachOfDelete(UUID clusterId, ResourceId id, LifecycleKind kind, boolean disconnectConsumers) {
        access.requireResource(clusterId, kind.resource(id.name()), kind.permission());
        WholeClusterLifecycle.Reach reach = wholeCluster.reachOfDelete(clusterId, id, disconnectConsumers);
        return new Reach(
                reach.messages().orElse(0L), reach.etag(), reach.basis().note());
    }

    /**
     * What deleting the queue would remove now, read without an audit row: the messages the scrape counts on its
     * nodes, and the queue's identity across the cluster for the gate's state key, which the broker reports.
     */
    public Reach reachOfQueueDelete(UUID clusterId, String queueName) {
        access.requireResource(
                clusterId, ResourceRef.subscription(queueName), LifecycleKind.DELETE_SUBSCRIPTION.permission());
        List<QueueLocation> found = queueLocator.locate(clusterId, queueName);
        if (found.isEmpty()) {
            throw new NotFoundException("queue", queueName);
        }
        return new Reach(
                found.stream()
                        .map(QueueLocation::messageCount)
                        .filter(Objects::nonNull)
                        .mapToLong(Long::longValue)
                        .sum(),
                messages.identity(clusterId, queueName));
    }

    private Attempt<LifecycleOutcome> runDeleteQueue(
            UUID clusterId, String queueName, boolean dryRun, boolean override, boolean disconnectConsumers) {
        ResolvedQueue queue = resolveQueue(clusterId, queueName);
        Set<String> declared = declaredDiverts.map(d -> d.names(clusterId)).orElse(Set.of());
        LifecycleKind kind = LifecycleKind.DELETE_SUBSCRIPTION;
        return new Attempt.Ok<>(commands.run(Command.builder()
                .clusterId(clusterId)
                .permission(kind.permission())
                .resources(List.of(ResourceRef.subscription(queueName)))
                .auditAction(kind.auditName())
                .targetType(kind.targetType())
                .targetName(queueName)
                .params(Map.of("disconnectConsumers", disconnectConsumers))
                .dryRun(dryRun)
                .override(override)
                .preflight(node ->
                        deletePreflight(deletePlan(clusterId, node, queue), queue, disconnectConsumers, declared))
                .action(node -> deleteWithDiverts(node, deletePlan(clusterId, node, queue), disconnectConsumers))
                .estimate(new Estimate("message count", false, node -> QueueCalls.messageCount(node, queueName)))
                .signal(() -> {
                    // The queue is gone from the brokers; the snapshot is a cache of
                    // what they run. Left alone it keeps the row until the next sweep,
                    // so the delete reads as though it did nothing and the row that is
                    // left fails every action against a destroyed MBean.
                    snapshotWriter.forget(clusterId, queueName);
                    sseHub.publishAbout(clusterId, QUEUES_TOPIC, List.of(queueName), List.of());
                })
                .build()));
    }

    /**
     * One node's view of a queue delete.
     *
     * @param dependents diverts that forward into the queue's address when the delete leaves that
     *     address with no binding — removed with it (D1)
     * @param keptIncoming diverts into the address that stay, because a divert from the address
     *     keeps it bound and routing (ADR-0085)
     * @param kept diverts whose source is the queue's address — they still route (D4)
     * @param tapsGone capture taps their subscription removes once the queue is gone (D5)
     * @param tapsKept capture taps their subscription keeps for another queue on the address (D5)
     */
    private record DeletePlan(
            ResolvedQueue queue,
            NodeQueues state,
            List<DivertRoute> dependents,
            List<DivertRoute> keptIncoming,
            List<DivertRoute> kept,
            List<DivertRoute> tapsGone,
            List<DivertRoute> tapsKept) {}

    private DeletePlan deletePlan(UUID clusterId, BrokerTarget node, ResolvedQueue queue) {
        NodeQueues state = QueueCalls.queues(node, queue.address(), queue.queueName());
        if (!state.present()) {
            return new DeletePlan(queue, state, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        List<DivertRoute> incoming = new ArrayList<>();
        List<DivertRoute> kept = new ArrayList<>();
        List<DivertRoute> tapsGone = new ArrayList<>();
        List<DivertRoute> tapsKept = new ArrayList<>();
        for (DivertRoute d : DivertRoute.list(node)) {
            sortDivert(d, clusterId, queue, incoming, kept, tapsGone, tapsKept);
        }
        // Only an address left with no binding at all breaks or re-creates through an incoming
        // divert. A divert from it keeps it bound and routing, so the incoming one still feeds it.
        boolean lastQueue = state.addressQueues().equals(List.of(queue.queueName()));
        boolean orphaned = lastQueue && kept.isEmpty();
        return new DeletePlan(
                queue,
                state,
                orphaned ? incoming : List.of(),
                lastQueue && !orphaned ? incoming : List.of(),
                kept,
                tapsGone,
                tapsKept);
    }

    /** Files one divert by how deleting {@code queue} touches it: forwarding in, routing out, or a capture tap. */
    private void sortDivert(
            DivertRoute d,
            UUID clusterId,
            ResolvedQueue queue,
            List<DivertRoute> incoming,
            List<DivertRoute> kept,
            List<DivertRoute> tapsGone,
            List<DivertRoute> tapsKept) {
        String address = queue.address();
        boolean capture = d.name() != null && d.name().startsWith(RahdarNames.CAPTURE_PREFIX);
        if (capture) {
            if (address.equals(d.address())) {
                boolean stays = captureTaps
                        .map(t -> t.coversWithout(clusterId, d.name(), address, queue.queueName()))
                        .orElse(false);
                (stays ? tapsKept : tapsGone).add(d);
            }
        } else if (address.equals(d.forwardingAddress())) {
            incoming.add(d);
        } else if (address.equals(d.address())) {
            kept.add(d);
        }
    }

    private static Check deletePreflight(
            DeletePlan plan, ResolvedQueue queue, boolean disconnectConsumers, Set<String> declared) {
        if (!plan.state().present()) {
            // Nothing here to delete; the node reports it as already gone.
            return Check.OK;
        }
        long consumers = plan.state().consumerCount();
        String counted = consumers + (consumers == 1 ? " consumer" : " consumers");
        if (consumers > 0 && !disconnectConsumers) {
            return Check.refuse("Queue '" + queue.queueName() + "' has " + counted + " attached on this node, and the"
                    + " broker will not delete it while they are. Stop the consumers, or tick \"Disconnect this"
                    + " queue's consumers\" (disconnectConsumers=true over the API or MCP) to close them.");
        }
        List<String> notes = new ArrayList<>();
        if (consumers > 0) {
            notes.add(counted + " will be disconnected. A client that reconnects can create the queue again if"
                    + " auto-create is on for '" + queue.address() + "'.");
        }
        if (!plan.dependents().isEmpty()) {
            notes.add("Removed with the queue, because they forward into '" + queue.address()
                    + "' and the delete leaves it with nothing bound: " + describe(plan.dependents())
                    + ". If one is in this node's"
                    + " broker.xml, the broker creates it again at its next restart, and the queue can come back"
                    + " with it.");
            List<String> redeclared = plan.dependents().stream()
                    .map(DivertRoute::name)
                    .filter(declared::contains)
                    .toList();
            if (!redeclared.isEmpty()) {
                notes.add(quoted(redeclared) + " in this cluster's declared configuration: the next apply, or the"
                        + " next broker.xml rendered from it, brings it back. Remove it from the declaration.");
            }
        }
        if (!plan.keptIncoming().isEmpty()) {
            notes.add("Diverts into '" + queue.address() + "' are kept, although this is its last queue, because"
                    + " the diverts from it keep it bound and still route what they forward: "
                    + quoted(plan.keptIncoming().stream().map(DivertRoute::name).toList()) + ".");
        }
        if (!plan.kept().isEmpty()) {
            notes.add("Diverts from '" + queue.address() + "' are kept, because they still route what producers"
                    + " send to it: "
                    + quoted(plan.kept().stream().map(DivertRoute::name).toList()) + ".");
        }
        if (!plan.tapsGone().isEmpty()) {
            notes.add("Capture taps on '" + queue.address() + "', each removed by its capture subscription once"
                    + " the scrape no longer shows the queue: "
                    + quoted(plan.tapsGone().stream().map(DivertRoute::name).toList()) + ".");
        }
        if (!plan.tapsKept().isEmpty()) {
            notes.add("Capture taps on '" + queue.address() + "' are kept, because their subscription still"
                    + " captures another queue there: "
                    + quoted(plan.tapsKept().stream().map(DivertRoute::name).toList()) + ".");
        }
        return notes.isEmpty() ? Check.OK : Check.warn(String.join(" ", notes));
    }

    /** The dependent diverts first, then the queue (D3). Nothing is rolled back. */
    private NodeStatus deleteWithDiverts(BrokerTarget node, DeletePlan plan, boolean disconnectConsumers) {
        List<DivertRoute> removed = new ArrayList<>();
        for (DivertRoute d : plan.dependents()) {
            try {
                QueueCalls.deleteDivert(node, d.name());
            } catch (ManagementRefusal e) {
                if (e.kind() != ManagementRefusal.Kind.ALREADY) {
                    throw new ManagementRefusal(e.kind(), divertFailed(d, e, removed));
                }
            } catch (BrokerConnectionException e) {
                throw new BrokerConnectionException(e.kind(), divertFailed(d, e, removed));
            }
            removed.add(d);
        }
        String gone = removed.isEmpty() ? "" : " Diverts already removed from this node: " + describe(removed) + ".";
        try {
            return QueueCalls.deleteQueue(node, plan.queue().queueName(), disconnectConsumers);
        } catch (ManagementRefusal e) {
            throw e.kind() == ManagementRefusal.Kind.ALREADY
                    ? e
                    : new ManagementRefusal(e.kind(), e.getMessage() + gone);
        } catch (BrokerConnectionException e) {
            throw new BrokerConnectionException(e.kind(), e.getMessage() + gone);
        }
    }

    private static String divertFailed(DivertRoute d, RuntimeException cause, List<DivertRoute> removed) {
        return "Divert '" + d.name() + "' could not be removed (" + cause.getMessage()
                + "), so the queue was not deleted on this node."
                + (removed.isEmpty() ? "" : " Diverts already removed from this node: " + describe(removed) + ".");
    }

    /** Everything needed to recreate each divert exactly, as the audit row keeps it (D3). */
    private static String describe(List<DivertRoute> diverts) {
        return diverts.stream()
                .map(d -> "'" + d.name() + "' (" + d.address() + " → " + d.forwardingAddress()
                        + ", routing name " + d.routingName()
                        + ", routing type " + d.routingType()
                        + (d.exclusive() ? ", exclusive" : "")
                        + (d.filter() == null || d.filter().isBlank() ? "" : ", filter " + d.filter())
                        + (d.transformerClassName() == null
                                        || d.transformerClassName().isBlank()
                                ? ""
                                : ", transformer " + d.transformerClassName())
                        + ")")
                .collect(Collectors.joining("; "));
    }

    private static String quoted(List<String> names) {
        return names.stream().map(n -> "'" + n + "'").collect(Collectors.joining(", "));
    }

    public Attempt<LifecycleOutcome> setPaused(UUID clusterId, String queueName, boolean paused, boolean dryRun) {
        LifecycleKind kind = paused ? LifecycleKind.PAUSE_SUBSCRIPTION : LifecycleKind.RESUME_SUBSCRIPTION;
        access.requireResource(clusterId, ResourceRef.subscription(queueName), kind.permission());
        resolveQueue(clusterId, queueName);
        return run(
                clusterId,
                kind,
                queueName,
                Map.of("paused", paused),
                dryRun,
                false,
                node -> QueueCalls.setPaused(node, queueName, paused));
    }

    public Attempt<LifecycleOutcome> resetCounter(UUID clusterId, String queueName, boolean dryRun) {
        access.requireResource(
                clusterId, ResourceRef.subscription(queueName), LifecycleKind.RESET_COUNTERS.permission());
        // Resolved first, so a queue no node has is not found rather than reset nowhere.
        resolveQueue(clusterId, queueName);
        return run(clusterId, LifecycleKind.RESET_COUNTERS, queueName, Map.of(), dryRun, false, node -> {
            counters.reset(node, queueName);
            return NodeStatus.APPLIED;
        });
    }

    public Attempt<LifecycleOutcome> createAddress(UUID clusterId, CreateDestinationRequest req, boolean dryRun) {
        access.requireCreate(
                clusterId, ResourceRef.destination(req.name()), LifecycleKind.CREATE_DESTINATION.permission());
        if (kinds.readAsOne(clusterId)) {
            return createWhole(clusterId, req, dryRun);
        }
        return run(
                clusterId,
                LifecycleKind.CREATE_DESTINATION,
                req.name(),
                Map.of("routingTypes", req.routingTypes()),
                dryRun,
                false,
                node -> QueueCalls.createAddress(node, req));
    }

    /**
     * Delete an address, force-free (D8). The broker refuses while queues are bound;
     * the refusal is re-raised naming them, because the broker's own message does
     * not, and the operator deletes the queues explicitly. One click that destroys
     * an unbounded amount of data with no per-queue count in the confirmation is not
     * a safe default, and the safe path costs one extra step.
     */
    public Attempt<LifecycleOutcome> deleteAddress(UUID clusterId, String address, boolean dryRun) {
        access.requireResource(
                clusterId, ResourceRef.destination(address), LifecycleKind.DELETE_DESTINATION.permission());
        if (dryRun) {
            return runDeleteAddress(clusterId, address, true);
        }
        return gatedDeleteAddress(new AddressDeleteParams(clusterId, address, null));
    }

    /** Delete a destination by the id the route resolved, as {@link #deleteQueue(UUID, Target, boolean, boolean, boolean)}. */
    public Attempt<LifecycleOutcome> deleteAddress(UUID clusterId, Target target, boolean dryRun) {
        if (!kinds.readAsOne(clusterId)) {
            return deleteAddress(clusterId, target.name(), dryRun);
        }
        access.requireResource(
                clusterId, ResourceRef.destination(target.name()), LifecycleKind.DELETE_DESTINATION.permission());
        if (dryRun) {
            return runWholeDelete(clusterId, target.id(), LifecycleKind.DELETE_DESTINATION, true, false, false);
        }
        return gatedDeleteAddress(
                new AddressDeleteParams(clusterId, target.name(), target.id().format()));
    }

    @Gated("destination.delete")
    private Attempt<LifecycleOutcome> gatedDeleteAddress(AddressDeleteParams p) {
        return gate.run(Operation.of(p), () -> {
            if (p.id() == null) {
                return runDeleteAddress(p.clusterId(), p.address(), false);
            }
            return runWholeDelete(
                    p.clusterId(), ResourceId.parse(p.id()), LifecycleKind.DELETE_DESTINATION, false, false, false);
        });
    }

    private Attempt<LifecycleOutcome> runDeleteAddress(UUID clusterId, String address, boolean dryRun) {
        // Taken here, on the caller's thread: the nodes' actions carry no security context.
        ResourceFilter readable = permissions.filter(clusterId, ResourceType.SUBSCRIPTION);
        return run(clusterId, LifecycleKind.DELETE_DESTINATION, address, Map.of(), dryRun, false, node -> {
            try {
                return QueueCalls.deleteAddress(node, address);
            } catch (ManagementRefusal e) {
                if (e.kind() == ManagementRefusal.Kind.BOUND_SUBSCRIPTIONS) {
                    throw new ManagementRefusal(
                            ManagementRefusal.Kind.BOUND_SUBSCRIPTIONS, boundQueuesMessage(node, address, readable));
                }
                throw e;
            }
        });
    }

    // ---- a cluster read as one --------------------------------------------

    private Attempt<LifecycleOutcome> createWhole(UUID clusterId, CreateSubscriptionRequest req, boolean dryRun) {
        ResourceId id = createdId(req.kind(), req.name());
        Attributes.Builder attributes = Attributes.builder();
        req.attributes().forEach(attributes::putRaw);
        attributes.put(StandardAttributes.DESTINATION, ResourceId.parse(req.destination()));
        Map<String, Object> audited = new LinkedHashMap<>(req.attributes());
        audited.put("id", id.format());
        audited.put("destination", req.destination());
        return wholeCluster.create(
                clusterId,
                id,
                LifecycleKind.CREATE_SUBSCRIPTION,
                attributes.build(),
                audited,
                dryRun,
                () -> sseHub.publishAbout(clusterId, QUEUES_TOPIC, List.of(ResourceRef.subscription(id.name()))));
    }

    private Attempt<LifecycleOutcome> createWhole(UUID clusterId, CreateDestinationRequest req, boolean dryRun) {
        ResourceId id = createdId(req.kind(), req.name());
        Map<String, Object> audited = new LinkedHashMap<>(req.attributes());
        audited.put("id", id.format());
        Attributes.Builder attributes = Attributes.builder();
        req.attributes().forEach(attributes::putRaw);
        return wholeCluster.create(
                clusterId,
                id,
                LifecycleKind.CREATE_DESTINATION,
                attributes.build(),
                audited,
                dryRun,
                () -> sseHub.publishAbout(clusterId, QUEUES_TOPIC, List.of(ResourceRef.destination(id.name()))));
    }

    /** A name with a slash is a path of several segments, such as a virtual host and a queue; any other is one. */
    private static ResourceId createdId(String kind, String name) {
        return name.contains("/") ? ResourceIds.parse(kind, name) : ResourceId.of(kind, name);
    }

    // ---- the fan-out -----------------------------------------------------

    private Attempt<LifecycleOutcome> run(
            UUID clusterId,
            LifecycleKind kind,
            String targetName,
            Map<String, ?> params,
            boolean dryRun,
            boolean override,
            NodeAction action) {
        List<ResourceRef> resources = List.of(kind.resource(targetName));
        return new Attempt.Ok<>(commands.run(Command.builder()
                .clusterId(clusterId)
                .permission(kind.permission())
                .resources(resources)
                .auditAction(kind.auditName())
                .targetType(kind.targetType())
                .targetName(targetName)
                .params(params)
                .dryRun(dryRun)
                .override(override)
                .action(action)
                .signal(() -> sseHub.publishAbout(clusterId, QUEUES_TOPIC, resources))
                .build()));
    }

    // ---- queue resolution ------------------------------------------------

    /** A queue's address and routing type, which the queue MBean object name needs. */
    record ResolvedQueue(String queueName, String address, String routingType) {}

    /**
     * The address and routing type for a queue name — never from the client, exactly as the
     * message path does it. The scraped snapshot answers first, and a queue the scrape has not
     * reached yet is looked up on the live nodes. One no node has is not found.
     */
    ResolvedQueue resolveQueue(UUID clusterId, String queueName) {
        return queueLocator.locate(clusterId, queueName).stream()
                .findFirst()
                .map(l -> new ResolvedQueue(queueName, l.address(), l.routingType()))
                .orElseThrow(() -> new NotFoundException("queue", queueName));
    }

    /**
     * What stops an address from being deleted. It names the queues the caller may read and counts the rest,
     * so deleting an address does not list another team's queues.
     */
    private String boundQueuesMessage(BrokerTarget node, String address, ResourceFilter readable) {
        List<String> queues = QueueCalls.queues(node, address, "").addressQueues();
        if (queues.isEmpty()) {
            return "Address '" + address + "' still has queues bound to it.";
        }
        List<String> named = queues.stream().filter(readable::readable).toList();
        int others = queues.size() - named.size();
        String who = named.isEmpty() ? " (none of them yours)" : ": " + String.join(", ", named) + andOthers(others);
        return "Address '" + address + "' still has " + queues.size() + " queue(s) bound to it" + who
                + ". Delete them first — this operation will not remove them for you.";
    }

    private static String andOthers(int others) {
        return others == 0 ? "" : " and " + others + " other" + plural(others);
    }

    private static String plural(int count) {
        return count == 1 ? "" : "s";
    }

    // ---- audit -----------------------------------------------------------

    private static Map<String, Object> params(CreateSubscriptionRequest req) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(ADDRESS, req.destinationName());
        params.put("routingType", req.routingType());
        params.put(DURABLE, req.durable());
        if (req.filter() != null && !req.filter().isBlank()) {
            params.put("filter", req.filter());
        }
        return params;
    }
}
