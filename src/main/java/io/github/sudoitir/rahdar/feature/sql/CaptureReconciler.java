package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageCaptureNodeEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageCaptureNodeRepository;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.settings.RahdarInstance;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDutyReleased;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import io.github.sudoitir.rahdar.platform.clusters.ServingNodes;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Converges the capture taps that should exist onto the ones that do (ADR-0062 D4).
 *
 * <p>This is the only lifecycle mechanism capture has. There is no separate install
 * flow to keep in step with it: creating a capture subscription writes a row, and the
 * next pass makes it true. That one property makes this simultaneously the
 * crash-recovery path, the failover path, the broker-restart path and the new-queue
 * path — a promoted backup carries no capture divert, and the next pass installs one.
 *
 * <p>Desired state is the {@code CAPTURE} subscriptions in Postgres, expanded through
 * {@link CaptureAddresses}. Actual state is each node's own divert names, filtered to this
 * instance's prefix, together with which of those this instance is draining.
 *
 * <p>Two ownership rules, and they are not the same rule. A divert belonging to
 * another Rahdar is never touched — its name says so, and nothing else could tell us
 * it exists. A second instance of <em>this</em> Rahdar shares the name and the
 * database, so the whole pass is taken under a per-cluster advisory lock; not holding
 * it means the other instance is doing this work, which is a reason to stop rather
 * than a failure to report.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CaptureReconciler {

    private final MessageIndexSubscriptionRepository subscriptions;
    private final MessageCaptureNodeRepository captureNodes;
    private final ClusterDirectory nodes;
    private final BrokerConnections connections;
    private final ClusterLock clusterLock;
    private final ClusterOwnership ownership;
    private final CaptureTap tap;
    private final CaptureConsumer consumers;
    private final CaptureBus bus;
    private final RahdarInstance instance;
    private final CaptureAddresses addresses;
    private final AuditService audit;
    private final CaptureLoss loss;
    private final ArtemisNodes artemisNodes;

    /**
     * Clusters this instance has installed a tap on. In memory, because it exists only
     * to keep sweeping a cluster after its subscriptions are gone; a restart with no
     * subscription left means the taps were already removed, and a restart with one
     * left repopulates this on the next install.
     */
    private final Set<UUID> installedOn = ConcurrentHashMap.newKeySet();

    /**
     * Taps refused for a reason that will not change until something about the tap or its
     * node does, keyed by node and tap, with what they were refused under. A refusal is
     * recorded once as FAILED and audited once; later passes neither retry it against the
     * broker nor write another audit row until the fingerprint changes (message-capture spec).
     * In memory, so a restart tries each once more, which is the right place to re-check.
     */
    private final Map<String, String> refused = new ConcurrentHashMap<>();

    /**
     * One sweep of every cluster Rahdar knows about, once, at startup.
     *
     * <p>A tap outlives the process that created it (ADR-0065), so a Rahdar that was
     * killed between a subscription's deletion and the sweep that should have followed
     * comes back with an orphan nothing else would ever visit — and an orphaned divert
     * pointing at a deleted address makes the <em>source</em> address unusable, which
     * is precisely the kind of harm Rahdar must never do to a broker. One bounded pass
     * at startup closes that, and costs one divert listing per node, once.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        for (ClusterNode node : nodes.allNodes()) {
            installedOn.add(node.getClusterId());
        }
        reconcile();
    }

    /** A cluster this replica no longer owns: close its drains so the new owner's can start. */
    @EventListener
    void onDutyReleased(ClusterDutyReleased released) {
        Thread.startVirtualThread(() -> consumers.stopCluster(released.clusterId()));
    }

    /** Registered with {@code JobRunner}. One pass per owned cluster that captures anything. */
    public void reconcile() {
        for (UUID clusterId : capturingClusters()) {
            if (ownership.owns(clusterId)) {
                reconcileNow(clusterId);
            } else {
                // A pass that raced the release may have started a drain after it: close it here,
                // or the capture queue's one consumer stays taken and the new owner's drain is refused.
                consumers.stopCluster(clusterId);
            }
        }
    }

    /**
     * One pass over one cluster, under its lock — for a caller outside the scheduled pass, such
     * as a deletion's sweep. Never unlocked: two passes racing each other could each remove what
     * the other just installed.
     */
    public void reconcileNow(UUID clusterId) {
        clusterLock.runIfHeld(clusterId, () -> {
            try {
                reconcileCluster(clusterId);
            } catch (RuntimeException e) {
                log.warn("Capture reconcile for cluster {} did not complete: {}", clusterId, e.getMessage());
            }
        });
    }

    /**
     * Remove a subscription's taps, then converge — so a changed filter or bound is installed
     * afresh rather than left on the broker under the old values. The divert and queue are
     * only ever created, never updated in place, so re-creating them is the one way a change
     * reaches the broker. The period between removal and reinstall is recorded as a gap.
     */
    public void reinstall(UUID clusterId, UUID subscriptionId) {
        clusterLock.runIfHeld(clusterId, () -> {
            refused.keySet().removeIf(key -> key.endsWith("." + subscriptionId));
            for (ClusterNode node : servingNodes(clusterId)) {
                try {
                    ArtemisNode client = client(clusterId, node);
                    for (String name : tap.installedNames(client, instance.id())) {
                        if (subscriptionId.equals(CaptureNames.subscriptionOf(name))) {
                            consumers.stop(node.getId(), name);
                            tap.remove(client, instance.id(), name);
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn(
                            "Could not remove the capture taps of {} on {} to re-create them: {}",
                            subscriptionId,
                            node.getName(),
                            e.getMessage());
                }
            }
            try {
                reconcileCluster(clusterId);
            } catch (RuntimeException e) {
                log.warn("Capture reconcile for cluster {} did not complete: {}", clusterId, e.getMessage());
            }
        });
    }

    /**
     * The clusters a pass visits: those with a capture subscription, plus any this
     * instance has installed a tap on since it started.
     *
     * <p>The second half is what makes removal work. Deleting the last capture
     * subscription for a cluster deletes its per-node state with it, so a pass driven
     * only by subscriptions would stop visiting exactly the cluster that still has an
     * orphan on it. Clusters Rahdar has never touched are never contacted, so this
     * costs nothing where there is nothing to do.
     */
    private Set<UUID> capturingClusters() {
        Set<UUID> ids = new LinkedHashSet<>(installedOn);
        for (MessageIndexSubscriptionEntity subscription : subscriptions.findByEnabledTrue()) {
            if (subscription.getMode() == CaptureMode.CAPTURE) {
                ids.add(subscription.getClusterId());
            }
        }
        return ids;
    }

    // ---- one cluster -----------------------------------------------------

    /**
     * Deliberately not transactional. A pass makes management calls to every live
     * node, and a database transaction held open across broker HTTP would tie up a
     * connection for as long as the slowest broker takes to answer. Each write here
     * is its own short transaction, and the loop is idempotent, so a pass that dies
     * half-way is simply repeated. Callers take the cluster lock ({@link #reconcileNow}).
     */
    void reconcileCluster(UUID clusterId) {
        List<ClusterNode> serving = servingNodes(clusterId);
        List<MessageIndexSubscriptionEntity> capturing = subscriptions.findByEnabledTrue().stream()
                .filter(s -> clusterId.equals(s.getClusterId()))
                .filter(s -> s.getMode() == CaptureMode.CAPTURE)
                .toList();

        Map<UUID, List<Desired>> desiredByNode = desired(clusterId, capturing, serving);

        boolean nothingLeft = true;
        for (ClusterNode node : serving) {
            List<Desired> wanted = desiredByNode.getOrDefault(node.getId(), List.of());
            log.debug("Capture pass on {}: {} tap(s) wanted", node.getName(), wanted.size());
            try {
                nothingLeft &= reconcileNode(clusterId, node, wanted);
            } catch (RuntimeException e) {
                nothingLeft = false;
                // Unreachable is transient and is retried, so it is not FAILED. It is
                // also not nothing: a node Rahdar cannot reach is a node it is not
                // capturing, and leaving the last known state standing would report
                // coverage that is not happening (D4).
                log.warn("Capture reconcile could not finish on {}: {}", node.getName(), e.toString());
                for (Desired desired : wanted) {
                    state(
                            desired,
                            node,
                            CaptureState.PENDING,
                            "This node did not answer, so capture could not be asserted on it: " + e.getMessage(),
                            false);
                }
            }
        }
        if (nothingLeft) {
            // Nothing wanted and nothing left on any serving node: stop visiting this cluster
            // until something asks for a tap again. Decided here, not per node, so a node whose
            // orphan survived keeps its cluster visited whichever node the pass reached last.
            installedOn.remove(clusterId);
        }
        stopDrainsOnNodesNoLongerServing(clusterId, serving);
        loss.measure(clusterId);
    }

    /**
     * A node that failed over away, or stopped answering, keeps no drain: the pooled
     * connection it holds points at a broker that is not serving the address any more.
     */
    private void stopDrainsOnNodesNoLongerServing(UUID clusterId, List<ClusterNode> serving) {
        Set<UUID> servingIds = serving.stream().map(ClusterNode::getId).collect(Collectors.toSet());
        for (ClusterNode node : nodes.nodes(clusterId)) {
            if (servingIds.contains(node.getId())) {
                continue;
            }
            for (String name : consumers.drainingOn(node.getId())) {
                consumers.stop(node.getId(), name);
            }
        }
    }

    /** One tap that should exist: a subscription's source address on a node. */
    private record Desired(MessageIndexSubscriptionEntity subscription, String address, String name) {}

    /**
     * The taps that should exist: every address a subscription resolves to, on every
     * serving node.
     *
     * <p>The address set is resolved cluster-wide and then asserted per node, not
     * resolved per node. That distinction is what makes failover work. A promoted
     * backup has no {@code queue_snapshot} rows until the scrape reaches it, which can
     * be minutes; resolving per node would leave the newly live node untapped for
     * exactly that window — the window an operator is most likely to be watching. A
     * divert on an address a node has not seen traffic for yet costs nothing and is
     * ready when it does.
     *
     * <p>A pattern that resolves to nothing yields nothing, and whatever it matched
     * before becomes an orphan that the same pass removes.
     */
    private Map<UUID, List<Desired>> desired(
            UUID clusterId, List<MessageIndexSubscriptionEntity> capturing, List<ClusterNode> serving) {
        Map<UUID, List<Desired>> byNode = new LinkedHashMap<>();
        for (MessageIndexSubscriptionEntity subscription : capturing) {
            for (String address : addresses.of(clusterId, subscription)) {
                for (ClusterNode node : serving) {
                    byNode.computeIfAbsent(node.getId(), k -> new ArrayList<>())
                            .add(new Desired(
                                    subscription,
                                    address,
                                    CaptureNames.of(instance.id(), address, subscription.getId())));
                }
            }
        }
        return byNode;
    }

    // ---- one node --------------------------------------------------------

    /** Whether this node has nothing wanted and nothing of this instance's left on it. */
    private boolean reconcileNode(UUID clusterId, ClusterNode node, List<Desired> wanted) {
        ArtemisNode client = client(clusterId, node);
        Set<String> actual = new LinkedHashSet<>(tap.installedNames(client, instance.id()));
        log.debug("Capture pass on {}: {} tap(s) already installed", node.getName(), actual.size());
        Set<String> wantedNames = new LinkedHashSet<>();
        wanted.forEach(d -> wantedNames.add(d.name()));

        // Drift only. A pass that re-asserts what is already there would be one
        // management write per tap per interval, forever, for no change (D4).
        for (Desired d : wanted) {
            if (needsInstall(node, d, actual)) {
                if (consumers.isDraining(node.getId(), d.name())) {
                    consumers.stop(node.getId(), d.name());
                }
                install(clusterId, node, d);
            }
        }

        // What was removed is no longer installed, so the checks below read what is left: an
        // orphan removed here must not keep its cluster visited, one that failed must.
        actual.removeIf(orphan -> !wantedNames.contains(orphan) && removeOrphan(clusterId, node, client, orphan));

        // A drain for a tap this node no longer has is stopped whatever the reason it
        // went away — including a broker that was rebuilt underneath us.
        for (String draining : consumers.drainingOn(node.getId())) {
            if (!wantedNames.contains(draining) && !actual.contains(draining)) {
                consumers.stop(node.getId(), draining);
            }
        }
        return wanted.isEmpty() && actual.isEmpty();
    }

    /**
     * A tap is healthy only when both halves are: the divert is on the broker and
     * this instance is draining its queue. Draining alone is not enough — a divert
     * removed out of band leaves the drain reading an empty queue while capture
     * reports ACTIVE. The divert alone is not enough either: a promoted backup
     * inherits the divert through the bindings journal but not the non-durable
     * capture queue. An unhealthy tap is installed unless the broker already refused
     * this exact one.
     */
    private boolean needsInstall(ClusterNode node, Desired d, Set<String> actual) {
        boolean healthy = consumers.isDraining(node.getId(), d.name()) && actual.contains(d.name());
        return !healthy && !fingerprint(d, node).equals(refused.get(refusalKey(node, d)));
    }

    private void install(UUID clusterId, ClusterNode node, Desired desired) {
        AuditEvent event = audit.begin(
                Actor.system(),
                "INSTALL_CAPTURE",
                "CAPTURE",
                desired.name(),
                clusterId,
                node.getId(),
                Map.of(
                        "address",
                        desired.address(),
                        "subscription",
                        desired.subscription().getId()),
                false);
        try {
            // Checked before anything is created: a tap nobody can drain copies production
            // payload into a queue that only fills.
            if (node.getDataUrl() == null) {
                throw new CaptureRefusedException(
                        "This node has no Core URL registered, and capture is drained over the Core protocol. "
                                + "Add one to the cluster's connection and capture starts on the next pass.",
                        null);
            }
            tap.install(
                    client(clusterId, node),
                    instance.id(),
                    new CaptureTap.Spec(
                            desired.subscription().getId(),
                            desired.address(),
                            desired.subscription().getRingSize(),
                            desired.subscription().getFilterString()));
            startDraining(clusterId, node, desired);
            installedOn.add(clusterId);
            refused.remove(refusalKey(node, desired));
            state(desired, node, CaptureState.ACTIVE, null, true);
            audit.succeed(event, 1);
        } catch (CaptureRefusedException e) {
            // A statement about the broker's configuration, not a transient failure:
            // the next pass will get the same answer, so it is recorded once as the reason
            // this node is not captured rather than retried and re-audited every pass.
            refuse(node, desired, e.getMessage(), event);
        } catch (ManagementRefusal e) {
            if (e.kind() == ManagementRefusal.Kind.ARGUMENT) {
                // The broker rejected what was asked — an invalid filter, typically. Asking
                // again with the same arguments gets the same answer.
                refuse(node, desired, "The broker rejected this capture: " + e.getMessage(), event);
            } else {
                state(desired, node, CaptureState.PENDING, reason(e), false);
                audit.fail(event, reason(e));
            }
        } catch (RuntimeException e) {
            state(desired, node, CaptureState.PENDING, reason(e), false);
            audit.fail(event, reason(e));
        }
    }

    private void refuse(ClusterNode node, Desired desired, String why, AuditEvent event) {
        refused.put(refusalKey(node, desired), fingerprint(desired, node));
        state(desired, node, CaptureState.FAILED, why, false);
        audit.fail(event, why);
    }

    private static String refusalKey(ClusterNode node, Desired desired) {
        return node.getId() + "|" + desired.name();
    }

    /** Everything a refusal could depend on; a change to any of it is worth asking the broker again. */
    private static String fingerprint(Desired desired, ClusterNode node) {
        MessageIndexSubscriptionEntity s = desired.subscription();
        return s.getRingSize() + "|" + s.getFilterString() + "|" + s.getBodyCapBytes() + "|" + s.getMaxRate() + "|"
                + node.getDataUrl() + "|" + node.getManagementUrl();
    }

    private void startDraining(UUID clusterId, ClusterNode node, Desired desired) {
        consumers.start(new CaptureConsumer.Spec(
                clusterId,
                node.getId(),
                node.getName(),
                desired.subscription().getId(),
                desired.name(),
                CaptureNames.queueOf(desired.name()),
                desired.address(),
                desired.subscription().getBodyCapBytes(),
                desired.subscription().getMaxRate()));
    }

    /** Whether the tap is gone from the node. A failure is audited and retried on the next pass. */
    private boolean removeOrphan(UUID clusterId, ClusterNode node, ArtemisNode client, String name) {
        AuditEvent event = audit.begin(
                Actor.system(), "REMOVE_CAPTURE", "CAPTURE", name, clusterId, node.getId(), Map.of(), false);
        try {
            consumers.stop(node.getId(), name);
            tap.remove(client, instance.id(), name);
            UUID subscriptionId = CaptureNames.subscriptionOf(name);
            if (subscriptionId != null) {
                captureNodes.deleteBySubscriptionIdAndNodeId(subscriptionId, node.getId());
                if (subscriptions.findById(subscriptionId).isEmpty()) {
                    bus.forget(subscriptionId);
                }
            }
            audit.succeed(event, 1);
            return true;
        } catch (RuntimeException e) {
            audit.fail(event, reason(e));
            return false;
        }
    }

    // ---- per-node state --------------------------------------------------

    private void state(Desired desired, ClusterNode node, CaptureState newState, String detail, boolean covered) {
        MessageCaptureNodeEntity row = captureNodes
                .findBySubscriptionIdAndNodeId(desired.subscription().getId(), node.getId())
                .orElseGet(() -> {
                    MessageCaptureNodeEntity fresh = new MessageCaptureNodeEntity();
                    fresh.setSubscriptionId(desired.subscription().getId());
                    fresh.setNodeId(node.getId());
                    return fresh;
                });
        // capturedFrom is set when coverage starts and cleared when it stops, never
        // carried across a gap: a re-install after a failover does not cover the
        // interval the node was live and untapped (D4). The gap itself is stated, so
        // an operator reading a window that starts an hour ago is told why.
        String reported = detail;
        if (covered && row.getCapturedFrom() == null) {
            if (row.getUpdatedAt() != null && row.getCaptureState() != CaptureState.PENDING) {
                reported = "Capture restarted on this node at " + Instant.now() + "; nothing between "
                        + row.getUpdatedAt() + " and then was captured here.";
            }
            row.setCapturedFrom(Instant.now());
        } else if (!covered) {
            row.setCapturedFrom(null);
        }
        row.setCaptureState(newState);
        row.setCaptureDetail(reported);
        row.setUpdatedAt(Instant.now());
        captureNodes.save(row);
    }

    // ---- plumbing --------------------------------------------------------

    /**
     * The nodes to assert a tap on: one per HA pair, and only ones that answered the
     * last scrape.
     *
     * <p>The second filter is not redundant. A node that cannot be reached keeps its
     * last known {@code Active}, so for a while after a failover the pair reports two
     * live members — the dead one and the promoted one — and the pair-reduction would
     * pick the dead one and leave the live node untapped for exactly the interval an
     * operator is watching. A node with a standing error is not serving anything.
     */
    List<ClusterNode> servingNodes(UUID clusterId) {
        List<ClusterNode> all = nodes.nodes(clusterId);
        List<ClusterNode> answering =
                all.stream().filter(n -> n.getLastError() == null).toList();
        // If nothing answered, fall back rather than reporting an empty desired state —
        // an empty desired state would look like "remove every tap".
        return ServingNodes.from(answering.isEmpty() ? all : answering);
    }

    /** The client waits for the node's ceiling before each of an install's requests (ADR-0076). */
    private ArtemisNode client(UUID clusterId, ClusterNode node) {
        return artemisNodes.on(clusterId, node.getId());
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
