package io.github.sudoitir.rahdar.feature.queues;

import io.github.sudoitir.rahdar.feature.queues.LifecycleRequests.CreateAddressRequest;
import io.github.sudoitir.rahdar.feature.queues.LifecycleRequests.CreateDivertRequest;
import io.github.sudoitir.rahdar.feature.queues.LifecycleRequests.CreateQueueRequest;
import io.github.sudoitir.rahdar.feature.queues.LifecycleRequests.UpdateQueueRequest;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceKind;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.Check;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.Command;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.Estimate;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.NodeAction;
import io.github.sudoitir.rahdar.platform.clusters.BrokerCommands.NodePreflight;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import io.github.sudoitir.rahdar.system.artemis.DivertOperations;
import io.github.sudoitir.rahdar.system.artemis.DivertRow;
import io.github.sudoitir.rahdar.system.artemis.MBeanNames;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import io.github.sudoitir.rahdar.system.artemis.QueueLocator;
import io.github.sudoitir.rahdar.system.artemis.QueueLocator.QueueLocation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    private static final String ROUTING_TYPE = "routing-type";
    private static final String DURABLE = "durable";
    private static final String FILTER_STRING = "filter-string";
    private static final String MAX_CONSUMERS = "max-consumers";
    private static final String PURGE_ON_NO_CONSUMERS = "purge-on-no-consumers";
    private static final String EXCLUSIVE = "exclusive";
    private static final String NON_DESTRUCTIVE = "non-destructive";
    private static final String RING_SIZE = "ring-size";
    private static final String REQUESTED = ", requested ";
    private static final String QUEUES_TOPIC = "queues";

    private final QueueLocator queueLocator;
    private final QueueSnapshotUpsert snapshotWriter;
    private final Optional<DeclaredDiverts> declaredDiverts;
    private final Optional<CaptureTaps> captureTaps;
    private final QueueLifecycleOperations ops;
    private final DivertOperations divertOps;
    private final SseHub sseHub;
    private final BrokerCommands commands;
    private final ClusterAccessGuard access;
    private final PermissionResolver permissions;
    private final ClusterDirectory clusters;
    private final BrokerConnections connections;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final OperationGate gate;
    private final ArtemisNodes artemisNodes;
    private final BrokerMessages messages;

    /** What a delete would touch: how many messages, and the target's identity for the gate's state key. */
    public record Reach(long count, String identity) {}

    /** What the gate holds of a queue delete. */
    record QueueDeleteParams(UUID clusterId, String queue, boolean override, boolean disconnectConsumers) {}

    /** What the gate holds of an address delete. */
    record AddressDeleteParams(UUID clusterId, String address) {}

    // ---- entry points ----------------------------------------------------

    public Attempt<LifecycleOutcome> createQueue(UUID clusterId, CreateQueueRequest req, boolean dryRun) {
        // The queue is created as a name the caller's patterns cover, on an address they may also create:
        // a queue bound to someone else's address would receive what is sent to it.
        access.requireCreate(clusterId, ResourceRef.queue(req.name()), QueuePermissions.QUEUE_CREATE);
        access.requireCreate(clusterId, ResourceRef.address(req.address()), QueuePermissions.ADDRESS_CREATE);
        ResolvedQueue asked = new ResolvedQueue(req.name(), req.address(), req.routingType());
        return run(clusterId, LifecycleKind.CREATE_QUEUE, req.name(), params(req), dryRun, false, (client, broker) -> {
            try {
                ops.createQueue(client, broker, queueConfig(req));
                return NodeStatus.APPLIED;
            } catch (ManagementRefusal e) {
                if (e.kind() != ManagementRefusal.Kind.ALREADY) {
                    throw e;
                }
                // The queue already exists here. Whether that is success depends on
                // whether it is the queue that was asked for: same configuration is
                // ALREADY, a different one is a failure that names the difference,
                // because reporting it as success would hide a real mismatch.
                JsonNode existing = asConfigNode(ops.readQueueConfig(client, queueMbean(client, asked)));
                if (matchesRequest(existing, req)) {
                    return NodeStatus.ALREADY;
                }
                throw new ManagementRefusal(
                        ManagementRefusal.Kind.ARGUMENT,
                        "A queue named '" + req.name() + "' already exists here but "
                                + describeDifference(existing, req) + ".");
            }
        });
    }

    public Attempt<LifecycleOutcome> updateQueue(
            UUID clusterId, String queueName, UpdateQueueRequest req, boolean dryRun) {
        access.requireResource(clusterId, ResourceRef.queue(queueName), LifecycleKind.UPDATE_QUEUE.permission());
        Map<String, Object> patch = patch(req);
        if (patch.isEmpty()) {
            throw new IllegalArgumentException("An update must change at least one field.");
        }
        ResolvedQueue queue = resolveQueue(clusterId, queueName);
        return run(clusterId, LifecycleKind.UPDATE_QUEUE, queueName, patch, dryRun, false, (client, broker) -> {
            ops.updateQueue(client, broker, queueMbean(client, queue), patch);
            return NodeStatus.APPLIED;
        });
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
        access.requireResource(clusterId, ResourceRef.queue(queueName), LifecycleKind.DELETE_QUEUE.permission());
        if (dryRun) {
            return runDeleteQueue(clusterId, queueName, true, override, disconnectConsumers);
        }
        return gatedDeleteQueue(new QueueDeleteParams(clusterId, queueName, override, disconnectConsumers));
    }

    @Gated("queue.delete")
    private Attempt<LifecycleOutcome> gatedDeleteQueue(QueueDeleteParams p) {
        return gate.run(
                Operation.of(p),
                () -> runDeleteQueue(p.clusterId(), p.queue(), false, p.override(), p.disconnectConsumers()));
    }

    /**
     * What deleting the queue would remove now, read without an audit row: the messages the scrape counts on its
     * nodes, and the queue's identity across the cluster for the gate's state key, which the broker reports.
     */
    public Reach reachOfQueueDelete(UUID clusterId, String queueName) {
        access.requireResource(clusterId, ResourceRef.queue(queueName), LifecycleKind.DELETE_QUEUE.permission());
        List<QueueLocation> found = queueLocator.locate(clusterId, queueName);
        if (found.isEmpty()) {
            throw new NotFoundException("queue", queueName);
        }
        return new Reach(
                found.stream().mapToLong(QueueLocation::messageCount).sum(), messages.identity(clusterId, queueName));
    }

    private Attempt<LifecycleOutcome> runDeleteQueue(
            UUID clusterId, String queueName, boolean dryRun, boolean override, boolean disconnectConsumers) {
        ResolvedQueue queue = resolveQueue(clusterId, queueName);
        Set<String> declared = declaredDiverts.map(d -> d.names(clusterId)).orElse(Set.of());
        LifecycleKind kind = LifecycleKind.DELETE_QUEUE;
        return new Attempt.Ok<>(commands.run(Command.builder()
                .clusterId(clusterId)
                .permission(kind.permission())
                .resources(List.of(ResourceRef.queue(queueName)))
                .auditAction(kind.auditName())
                .targetType(kind.targetType())
                .targetName(queueName)
                .params(Map.of("disconnectConsumers", disconnectConsumers))
                .dryRun(dryRun)
                .override(override)
                .preflight(artemisPreflight((client, broker) -> deletePreflight(
                        deletePlan(clusterId, client, broker, queue), queue, disconnectConsumers, declared)))
                .action(artemis((client, broker) -> deleteWithDiverts(
                        client, broker, deletePlan(clusterId, client, broker, queue), disconnectConsumers)))
                .estimate(new Estimate("message count", false, node -> {
                    ArtemisNode client = artemisNodes.on(node);
                    return ops.messageCount(client, queueMbean(client, queue));
                }))
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
            QueueLifecycleOperations.DeleteState state,
            List<DivertRow> dependents,
            List<DivertRow> keptIncoming,
            List<DivertRow> kept,
            List<DivertRow> tapsGone,
            List<DivertRow> tapsKept) {}

    private DeletePlan deletePlan(UUID clusterId, ArtemisNode client, String broker, ResolvedQueue queue) {
        var state = ops.deleteState(client, broker, queue.address(), queue.queueName(), queue.routingType());
        if (!state.present()) {
            return new DeletePlan(queue, state, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        List<DivertRow> incoming = new ArrayList<>();
        List<DivertRow> kept = new ArrayList<>();
        List<DivertRow> tapsGone = new ArrayList<>();
        List<DivertRow> tapsKept = new ArrayList<>();
        for (DivertRow d : divertOps.listDiverts(client, null, null)) {
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
            DivertRow d,
            UUID clusterId,
            ResolvedQueue queue,
            List<DivertRow> incoming,
            List<DivertRow> kept,
            List<DivertRow> tapsGone,
            List<DivertRow> tapsKept) {
        String address = queue.address();
        boolean capture = d.uniqueName() != null && d.uniqueName().startsWith(DivertOperations.CAPTURE_PREFIX);
        if (capture) {
            if (address.equals(d.address())) {
                boolean stays = captureTaps
                        .map(t -> t.coversWithout(clusterId, d.uniqueName(), address, queue.queueName()))
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
                    .map(DivertRow::uniqueName)
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
                    + quoted(plan.keptIncoming().stream()
                            .map(DivertRow::uniqueName)
                            .toList()) + ".");
        }
        if (!plan.kept().isEmpty()) {
            notes.add("Diverts from '" + queue.address() + "' are kept, because they still route what producers"
                    + " send to it: "
                    + quoted(plan.kept().stream().map(DivertRow::uniqueName).toList()) + ".");
        }
        if (!plan.tapsGone().isEmpty()) {
            notes.add("Capture taps on '" + queue.address() + "', each removed by its capture subscription once"
                    + " the scrape no longer shows the queue: "
                    + quoted(plan.tapsGone().stream().map(DivertRow::uniqueName).toList()) + ".");
        }
        if (!plan.tapsKept().isEmpty()) {
            notes.add("Capture taps on '" + queue.address() + "' are kept, because their subscription still"
                    + " captures another queue there: "
                    + quoted(plan.tapsKept().stream().map(DivertRow::uniqueName).toList()) + ".");
        }
        return notes.isEmpty() ? Check.OK : Check.warn(String.join(" ", notes));
    }

    /** The dependent diverts first, then the queue (D3). Nothing is rolled back. */
    private NodeStatus deleteWithDiverts(
            ArtemisNode client, String broker, DeletePlan plan, boolean disconnectConsumers) {
        List<DivertRow> removed = new ArrayList<>();
        for (DivertRow d : plan.dependents()) {
            try {
                divertOps.destroyDivert(client, broker, d.uniqueName());
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
            ops.destroyQueue(client, broker, plan.queue().queueName(), disconnectConsumers);
        } catch (ManagementRefusal e) {
            throw e.kind() == ManagementRefusal.Kind.ALREADY
                    ? e
                    : new ManagementRefusal(e.kind(), e.getMessage() + gone);
        } catch (BrokerConnectionException e) {
            throw new BrokerConnectionException(e.kind(), e.getMessage() + gone);
        }
        return NodeStatus.APPLIED;
    }

    private static String divertFailed(DivertRow d, RuntimeException cause, List<DivertRow> removed) {
        return "Divert '" + d.uniqueName() + "' could not be removed (" + cause.getMessage()
                + "), so the queue was not deleted on this node."
                + (removed.isEmpty() ? "" : " Diverts already removed from this node: " + describe(removed) + ".");
    }

    /** Everything needed to recreate each divert exactly, as the audit row keeps it (D3). */
    private static String describe(List<DivertRow> diverts) {
        return diverts.stream()
                .map(d -> "'" + d.uniqueName() + "' (" + d.address() + " → " + d.forwardingAddress()
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
        LifecycleKind kind = paused ? LifecycleKind.PAUSE_QUEUE : LifecycleKind.RESUME_QUEUE;
        access.requireResource(clusterId, ResourceRef.queue(queueName), kind.permission());
        ResolvedQueue queue = resolveQueue(clusterId, queueName);
        return run(clusterId, kind, queueName, Map.of("paused", paused), dryRun, false, (client, broker) -> {
            String mbean = queueMbean(client, queue);
            if (ops.isPaused(client, mbean) == paused) {
                return NodeStatus.ALREADY;
            }
            if (paused) {
                ops.pause(client, mbean);
            } else {
                ops.resume(client, mbean);
            }
            return NodeStatus.APPLIED;
        });
    }

    public Attempt<LifecycleOutcome> resetCounter(UUID clusterId, String queueName, boolean dryRun) {
        access.requireResource(clusterId, ResourceRef.queue(queueName), LifecycleKind.RESET_QUEUE_COUNTER.permission());
        ResolvedQueue queue = resolveQueue(clusterId, queueName);
        return run(
                clusterId, LifecycleKind.RESET_QUEUE_COUNTER, queueName, Map.of(), dryRun, false, (client, broker) -> {
                    ops.resetMessageCounter(client, queueMbean(client, queue));
                    return NodeStatus.APPLIED;
                });
    }

    public Attempt<LifecycleOutcome> createAddress(UUID clusterId, CreateAddressRequest req, boolean dryRun) {
        access.requireCreate(clusterId, ResourceRef.address(req.name()), LifecycleKind.CREATE_ADDRESS.permission());
        return run(
                clusterId,
                LifecycleKind.CREATE_ADDRESS,
                req.name(),
                Map.of("routingTypes", req.routingTypes()),
                dryRun,
                false,
                (client, broker) -> {
                    ops.createAddress(
                            client, broker, req.name(), req.routingTypes().toUpperCase());
                    return NodeStatus.APPLIED;
                });
    }

    /**
     * Delete an address, force-free (D8). The broker refuses while queues are bound;
     * the refusal is re-raised naming them, because the broker's own message does
     * not, and the operator deletes the queues explicitly. One click that destroys
     * an unbounded amount of data with no per-queue count in the confirmation is not
     * a safe default, and the safe path costs one extra step.
     */
    public Attempt<LifecycleOutcome> deleteAddress(UUID clusterId, String address, boolean dryRun) {
        access.requireResource(clusterId, ResourceRef.address(address), LifecycleKind.DELETE_ADDRESS.permission());
        if (dryRun) {
            return runDeleteAddress(clusterId, address, true);
        }
        return gatedDeleteAddress(new AddressDeleteParams(clusterId, address));
    }

    @Gated("address.delete")
    private Attempt<LifecycleOutcome> gatedDeleteAddress(AddressDeleteParams p) {
        return gate.run(Operation.of(p), () -> runDeleteAddress(p.clusterId(), p.address(), false));
    }

    private Attempt<LifecycleOutcome> runDeleteAddress(UUID clusterId, String address, boolean dryRun) {
        // Taken here, on the caller's thread: the nodes' actions carry no security context.
        ResourceFilter readable = permissions.filter(clusterId, ResourceKind.QUEUE);
        return run(clusterId, LifecycleKind.DELETE_ADDRESS, address, Map.of(), dryRun, false, (client, broker) -> {
            try {
                ops.deleteAddress(client, broker, address);
            } catch (ManagementRefusal e) {
                if (e.kind() == ManagementRefusal.Kind.BOUND_QUEUES) {
                    throw new ManagementRefusal(
                            ManagementRefusal.Kind.BOUND_QUEUES, boundQueuesMessage(client, broker, address, readable));
                }
                throw e;
            }
            return NodeStatus.APPLIED;
        });
    }

    // ---- diverts ---------------------------------------------------------

    /**
     * Create a divert on every live node. A divert exists per node, so this is the
     * same fan-out every other topology mutation uses, with the same per-node
     * outcomes and the same preview.
     *
     * <p>Each node is checked first, in the preview and again for real: a forwarding
     * address that is not there would make producers to the source fail, and a cycle
     * would copy a message around it indefinitely. The result is what the broker
     * deployed, read back — an identical divert already there is {@code ALREADY}, a
     * different one under the same name fails naming the difference.
     */
    public Attempt<LifecycleOutcome> createDivert(UUID clusterId, CreateDivertRequest req, boolean dryRun) {
        requireValid(req);
        refuseCaptureName(req.name());
        Map<String, Object> config = DivertOperations.divertConfig(
                req.name(),
                req.routingName(),
                req.address(),
                req.forwardingAddress(),
                Boolean.TRUE.equals(req.exclusive()),
                req.filter(),
                req.routingType());
        Map<String, Object> audited = new LinkedHashMap<>(config);
        audited.put("acknowledgeCaptureShadowing", req.acknowledgeCaptureShadowing());
        return new Attempt.Ok<>(commands.run(Command.builder()
                .clusterId(clusterId)
                .permission(LifecycleKind.CREATE_DIVERT.permission())
                // A divert copies what reaches one address into another: it needs both.
                .resources(List.of(ResourceRef.address(req.address()), ResourceRef.address(req.forwardingAddress())))
                .auditAction(LifecycleKind.CREATE_DIVERT.auditName())
                .targetType(LifecycleKind.CREATE_DIVERT.targetType())
                .targetName(req.name())
                .params(audited)
                .dryRun(dryRun)
                .preflight(artemisPreflight((client, broker) -> divertPreflight(client, broker, req)))
                .action(artemis((client, broker) -> divertOps.createVerified(client, broker, config)))
                .signal(() -> sseHub.publishAbout(
                        clusterId, QUEUES_TOPIC, List.of(), List.of(req.address(), req.forwardingAddress())))
                .build()));
    }

    private Check divertPreflight(ArtemisNode client, String broker, CreateDivertRequest req) {
        List<DivertRow> existing = divertOps.listDiverts(client, null, null);
        String cycle = DivertOperations.cycle(existing, req.name(), req.address(), req.forwardingAddress());
        if (cycle != null) {
            return Check.refuse("This divert would complete a cycle of diverts: " + cycle
                    + ". A message would be copied around it without end.");
        }
        if (!divertOps.addressAvailable(client, broker, req.forwardingAddress())) {
            return Check.refuse("The forwarding address '" + req.forwardingAddress() + "' does not exist on this node"
                    + " and is not created automatically, so producers to '" + req.address() + "' would fail."
                    + " Create the address first, or declare it in broker.xml: <addresses><address name=\""
                    + req.forwardingAddress() + "\"><anycast/></address></addresses>");
        }
        boolean captured = existing.stream()
                .anyMatch(d -> d.uniqueName() != null
                        && d.uniqueName().startsWith(DivertOperations.CAPTURE_PREFIX)
                        && req.address().equals(d.address()));
        if (Boolean.TRUE.equals(req.exclusive()) && captured) {
            String effect = "'" + req.address() + "' is being captured, and Artemis applies exclusive diverts before"
                    + " every other one: capture of it would observe nothing while this divert exists.";
            return Boolean.TRUE.equals(req.acknowledgeCaptureShadowing())
                    ? Check.warn(effect)
                    : Check.refuse(effect + " Set acknowledgeCaptureShadowing to create it anyway.");
        }
        return Check.OK;
    }

    /** Bean validation for callers that do not pass through a validated controller argument (MCP). */
    private void requireValid(CreateDivertRequest req) {
        var violations = validator.validate(req);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
    }

    /** Capture's diverts are managed by their subscription (ADR-0079), whichever API is asked. */
    private static void refuseCaptureName(String name) {
        if (name != null && name.startsWith(DivertOperations.CAPTURE_PREFIX)) {
            throw new IllegalArgumentException("'" + name + "' is in the namespace reserved for message capture ("
                    + DivertOperations.CAPTURE_PREFIX + "). Manage it from its capture subscription instead.");
        }
        if (name != null && name.startsWith(DivertOperations.PLUGIN_TAP_PREFIX)) {
            throw new IllegalArgumentException("'" + name + "' is in the namespace reserved for plugins' message taps ("
                    + DivertOperations.PLUGIN_TAP_PREFIX
                    + "). It is removed with the plugin registration that owns it.");
        }
    }

    /**
     * Delete a divert from every live node. Nothing else removes one — a divert
     * created over management outlives the broker process (ADR-0065) — so this is
     * the only path, and the confirmation says so rather than implying a restart
     * would do it.
     */
    public Attempt<LifecycleOutcome> deleteDivert(UUID clusterId, String name, boolean dryRun) {
        refuseCaptureName(name);
        boolean wholeCluster = access.holds(clusterId, LifecycleKind.DELETE_DIVERT.permission());
        Set<ResourceRef> ends = wholeCluster ? Set.of() : divertEnds(clusterId, name);
        return new Attempt.Ok<>(commands.run(Command.builder()
                .clusterId(clusterId)
                .permission(LifecycleKind.DELETE_DIVERT.permission())
                .resources(List.copyOf(ends))
                .auditAction(LifecycleKind.DELETE_DIVERT.auditName())
                .targetType(LifecycleKind.DELETE_DIVERT.targetType())
                .targetName(name)
                .params(Map.of())
                .dryRun(dryRun)
                // Each node's own divert is what is destroyed there: one that runs between addresses nobody
                // checked is left alone, so a name that means something else on another node costs nothing.
                .preflight(artemisPreflight((client, broker) -> wholeCluster
                                || endsOn(client, name).stream().allMatch(ends::contains)
                        ? Check.OK
                        : Check.refuse("The divert on this node is not one you may delete, so it was left alone.")))
                .action(artemis((client, broker) -> {
                    divertOps.destroyDivert(client, broker, name);
                    return NodeStatus.APPLIED;
                }))
                .signal(() -> sseHub.publishAbout(clusterId, QUEUES_TOPIC, ends))
                .build()));
    }

    /**
     * The addresses the divert runs between on every node that has it, which deleting it needs
     * {@code divert:write} on: it is destroyed wherever it is deployed. A caller who may not read or write
     * all of them is told the divert does not exist, as is one whom nothing is known of it, so a name
     * cannot be told from a missing one.
     */
    private Set<ResourceRef> divertEnds(UUID clusterId, String name) {
        access.requireVisible(clusterId);
        Set<ResourceRef> ends = new LinkedHashSet<>();
        for (ClusterNode node : clusters.nodes(clusterId)) {
            if (node.getManagementUrl() == null) {
                continue;
            }
            try {
                ends.addAll(endsOn(artemisNodes.on(clusterId, node.getId()), name));
            } catch (BrokerConnectionException _) {
                // Another node may still answer; a node that cannot is checked again when it is acted on.
            }
        }
        if (ends.isEmpty()) {
            throw new NotFoundException("divert", name);
        }
        try {
            access.requireAll(
                    clusterId,
                    ends.stream()
                            .map(end ->
                                    new ClusterAccessGuard.Requirement(end, LifecycleKind.DELETE_DIVERT.permission()))
                            .toList());
        } catch (NotFoundException _) {
            throw new NotFoundException("divert", name);
        }
        return ends;
    }

    private List<ResourceRef> endsOn(ArtemisNode client, String name) {
        return divertOps
                .find(client, name)
                .map(d -> List.of(ResourceRef.address(d.address()), ResourceRef.address(d.forwardingAddress())))
                .orElse(List.of());
    }

    // ---- the fan-out -----------------------------------------------------

    private Attempt<LifecycleOutcome> run(
            UUID clusterId,
            LifecycleKind kind,
            String targetName,
            Map<String, ?> params,
            boolean dryRun,
            boolean override,
            ArtemisAction action) {
        return new Attempt.Ok<>(commands.run(Command.builder()
                .clusterId(clusterId)
                .permission(kind.permission())
                .resources(kind.resource(targetName).stream().toList())
                .auditAction(kind.auditName())
                .targetType(kind.targetType())
                .targetName(targetName)
                .params(params)
                .dryRun(dryRun)
                .override(override)
                .action(artemis(action))
                .signal(() -> sseHub.publishAbout(
                        clusterId,
                        QUEUES_TOPIC,
                        kind.resource(targetName).stream().toList()))
                .build()));
    }

    /** What a command does on one node's Artemis agent. */
    @FunctionalInterface
    interface ArtemisAction {
        NodeStatus apply(ArtemisNode client, String brokerMbean);
    }

    /** A check on one node's Artemis agent. */
    @FunctionalInterface
    interface ArtemisPreflight {
        Check apply(ArtemisNode client, String brokerMbean);
    }

    private NodeAction artemis(ArtemisAction action) {
        return node -> {
            ArtemisNode client = artemisNodes.on(node);
            return action.apply(client, client.resolveBrokerObjectName());
        };
    }

    private NodePreflight artemisPreflight(ArtemisPreflight check) {
        return node -> {
            ArtemisNode client = artemisNodes.on(node);
            return check.apply(client, client.resolveBrokerObjectName());
        };
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

    private static String queueMbean(ArtemisNode client, ResolvedQueue queue) {
        return MBeanNames.queue(
                client.resolveBrokerObjectName(), queue.address(), queue.queueName(), queue.routingType());
    }

    /**
     * What stops an address from being deleted. It names the queues the caller may read and counts the rest,
     * so deleting an address does not list another team's queues.
     */
    private String boundQueuesMessage(ArtemisNode client, String brokerMbean, String address, ResourceFilter readable) {
        List<String> queues = ops.boundQueues(client, MBeanNames.address(brokerMbean, address));
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

    // ---- request -> broker configuration ---------------------------------

    private static Map<String, Object> queueConfig(CreateQueueRequest req) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", req.name());
        config.put(ADDRESS, req.address());
        config.put(ROUTING_TYPE, req.routingType().toUpperCase());
        config.put(DURABLE, req.durable());
        config.put("auto-create-address", req.autoCreateAddress());
        putIfPresent(config, FILTER_STRING, blankToNull(req.filter()));
        putIfPresent(config, MAX_CONSUMERS, req.maxConsumers());
        putIfPresent(config, PURGE_ON_NO_CONSUMERS, req.purgeOnNoConsumers());
        putIfPresent(config, EXCLUSIVE, req.exclusive());
        putIfPresent(config, NON_DESTRUCTIVE, req.nonDestructive());
        putIfPresent(config, RING_SIZE, req.ringSize());
        return config;
    }

    private static Map<String, Object> patch(UpdateQueueRequest req) {
        Map<String, Object> patch = new LinkedHashMap<>();
        // A filter is patchable to empty on purpose — clearing it is a real edit —
        // so it is included whenever the field was sent at all.
        putIfPresent(patch, FILTER_STRING, req.filter());
        putIfPresent(patch, MAX_CONSUMERS, req.maxConsumers());
        putIfPresent(patch, PURGE_ON_NO_CONSUMERS, req.purgeOnNoConsumers());
        putIfPresent(patch, EXCLUSIVE, req.exclusive());
        putIfPresent(patch, NON_DESTRUCTIVE, req.nonDestructive());
        putIfPresent(patch, RING_SIZE, req.ringSize());
        return patch;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Map<String, Object> params(CreateQueueRequest req) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(ADDRESS, req.address());
        params.put("routingType", req.routingType());
        params.put(DURABLE, req.durable());
        putIfPresent(params, "filter", blankToNull(req.filter()));
        return params;
    }

    /**
     * Whether a queue the broker reports already existing matches what was asked
     * for. {@code createQueue(config, ignoreIfExists = true)} answers 200 with the
     * <em>existing</em> configuration, so this is what separates "already in the
     * requested state" from "exists, but differs" — and a mismatch must be reported
     * rather than passed off as success (the {@code ALREADY} risk in the design).
     */
    private static boolean matchesRequest(JsonNode existing, CreateQueueRequest req) {
        if (existing == null || !existing.isObject()) {
            return false;
        }
        return sameText(existing, ADDRESS, req.address())
                && sameText(existing, ROUTING_TYPE, req.routingType().toUpperCase())
                && sameText(existing, FILTER_STRING, blankToNull(req.filter()))
                && sameBoolean(existing, DURABLE, req.durable())
                && sameNumber(existing, MAX_CONSUMERS, req.maxConsumers())
                && sameBoolean(existing, PURGE_ON_NO_CONSUMERS, req.purgeOnNoConsumers())
                && sameBoolean(existing, EXCLUSIVE, req.exclusive())
                && sameBoolean(existing, NON_DESTRUCTIVE, req.nonDestructive())
                && sameNumber(existing, RING_SIZE, req.ringSize());
    }

    /**
     * The queue configuration read back from a node's MBean, as a JSON object, so
     * the same comparison works against it and against a broker-returned document.
     */
    private JsonNode asConfigNode(Map<String, Object> config) {
        return mapper.valueToTree(config);
    }

    /** A field the request did not specify is not a difference — the broker's default stands. */
    private static boolean sameText(JsonNode node, String key, String expected) {
        if (expected == null) {
            return true;
        }
        JsonNode v = node.get(key);
        return v != null && !v.isNull() && expected.equals(v.asString());
    }

    private static boolean sameBoolean(JsonNode node, String key, Boolean expected) {
        if (expected == null) {
            return true;
        }
        JsonNode v = node.get(key);
        return v != null && !v.isNull() && v.asBoolean() == expected;
    }

    private static boolean sameNumber(JsonNode node, String key, Number expected) {
        if (expected == null) {
            return true;
        }
        JsonNode v = node.get(key);
        return v != null && !v.isNull() && v.asLong() == expected.longValue();
    }

    /** Describe a differing existing queue well enough that the operator can act on it. */
    static String describeDifference(JsonNode existing, CreateQueueRequest req) {
        List<String> differences = new ArrayList<>();
        if (!sameText(existing, ADDRESS, req.address())) {
            differences.add("address is " + text(existing, ADDRESS) + REQUESTED + req.address());
        }
        if (!sameText(existing, ROUTING_TYPE, req.routingType().toUpperCase())) {
            differences.add("routing type is " + text(existing, ROUTING_TYPE) + REQUESTED + req.routingType());
        }
        if (!sameText(existing, FILTER_STRING, blankToNull(req.filter()))) {
            differences.add("filter is " + text(existing, FILTER_STRING) + REQUESTED + req.filter());
        }
        return differences.isEmpty() ? "the existing queue differs from the request" : String.join("; ", differences);
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? "unset" : v.asString();
    }
}
