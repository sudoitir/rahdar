package io.github.sudoitir.rahdar.feature.plugins.messaging.internal;

import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationEntity;
import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationNodeEntity;
import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationNodeRepository;
import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationRepository;
import io.github.sudoitir.rahdar.feature.plugins.messaging.PluginMessagingProperties;
import io.github.sudoitir.rahdar.feature.plugins.messaging.RegistrationMode;
import io.github.sudoitir.rahdar.feature.plugins.messaging.RegistrationState;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.settings.RahdarInstance;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.MessageTaps.TapSource;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDutyReleased;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import io.github.sudoitir.rahdar.platform.clusters.ServingNodes;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * Converges plugins' message registrations onto the broker (ADR-0111): capture's reconciler
 * (ADR-0062 D4) for plugin taps and consumers.
 *
 * <p>Desired state is every stored registration whose plugin is receiving and whose acting user
 * still holds what its mode needs. Actual state is each serving node's plugin tap diverts of this
 * instance, and the drains running here. One pass per cluster, under that cluster's
 * {@link ClusterLock.Scope#PLUGIN_MESSAGING} lock, makes the second match the first — which is also
 * the crash-recovery, failover, broker-restart, permission-revocation and plugin-stopped path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginMessagingReconciler {

    private final RegistrationRepository registrations;
    private final RegistrationNodeRepository nodeStates;
    private final ClusterDirectory nodes;
    private final BrokerConnections connections;
    private final ClusterLock clusterLock;
    private final ClusterOwnership ownership;
    private final RahdarInstance instance;
    private final PluginTap tap;
    private final PluginDrains drains;
    private final PluginHandlers handlers;
    private final AccessCheck access;
    private final AuditService audit;
    private final PluginMessagingProperties properties;
    private final Clock clock;

    /** Clusters this instance may hold taps on; kept so a cluster is swept after its last registration goes. */
    private final Set<UUID> installedOn = ConcurrentHashMap.newKeySet();

    /** Taps refused for a reason that will not change by itself, so they are not retried every pass. */
    private final Map<String, String> refused = new ConcurrentHashMap<>();

    /** One sweep of every known cluster at startup, so taps orphaned by a crash are reclaimed. */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        nodes.allNodes().forEach(n -> installedOn.add(n.getClusterId()));
        reconcile();
    }

    /** A cluster this replica no longer owns: stop delivering from it, so the new owner's drains can start. */
    @EventListener
    void onDutyReleased(ClusterDutyReleased released) {
        Thread.startVirtualThread(() -> drains.stopCluster(released.clusterId()));
    }

    /** The scheduled pass, over the clusters this replica owns. */
    public void reconcile() {
        Set<UUID> clusters = new LinkedHashSet<>(installedOn);
        registrations.findAll().forEach(r -> clusters.add(r.getClusterId()));
        for (UUID clusterId : clusters) {
            if (ownership.owns(clusterId)) {
                reconcileNow(clusterId);
            } else {
                // A pass that raced the release may have started a drain after it.
                drains.stopCluster(clusterId);
            }
        }
    }

    /** One pass over one cluster, if this instance holds its lock. */
    public void reconcileNow(UUID clusterId) {
        clusterLock.runIfHeld(clusterId, ClusterLock.Scope.PLUGIN_MESSAGING, () -> {
            try {
                reconcileCluster(clusterId);
            } catch (RuntimeException e) {
                log.warn("Plugin messaging pass for cluster {} did not complete: {}", clusterId, e.toString());
            }
        });
    }

    /** What one registration should be on every node this pass: delivering, or not and why. */
    private record Verdict(RegistrationState state, String detail) {
        boolean deliver() {
            return state == RegistrationState.ACTIVE;
        }
    }

    private void reconcileCluster(UUID clusterId) {
        List<RegistrationEntity> regs = registrations.findByClusterId(clusterId);
        List<ClusterNode> serving = servingNodes(clusterId);
        if (!regs.isEmpty()) {
            installedOn.add(clusterId);
        }

        Map<UUID, Verdict> verdicts = new HashMap<>();
        for (RegistrationEntity reg : regs) {
            verdicts.put(reg.getId(), verdict(reg));
        }

        boolean nothingLeft = true;
        for (ClusterNode node : serving) {
            Map<UUID, NodeResult> results = new HashMap<>();
            try {
                nothingLeft &= reconcileNode(clusterId, node, regs, verdicts, results);
            } catch (RuntimeException e) {
                nothingLeft = false;
                String why = "This node did not answer, so delivery could not be asserted on it: " + reason(e);
                regs.forEach(r -> results.putIfAbsent(r.getId(), new NodeResult(RegistrationState.PENDING, why, null)));
            }
            regs.forEach(r -> write(r, node, results.get(r.getId())));
        }
        if (nothingLeft && regs.isEmpty()) {
            installedOn.remove(clusterId);
        }

        // A node that failed over away or stopped answering keeps no drain, and no state row.
        Set<UUID> servingIds = serving.stream().map(ClusterNode::getId).collect(Collectors.toSet());
        for (ClusterNode node : nodes.nodes(clusterId)) {
            if (!servingIds.contains(node.getId())) {
                drains.runningOn(node.getId()).forEach(id -> drains.stop(id, node.getId()));
                regs.forEach(r -> nodeStates.deleteById(new RegistrationNodeEntity(r.getId(), node.getId()).getId()));
            }
        }
    }

    private Verdict verdict(RegistrationEntity reg) {
        if (!handlers.isReceiving(reg.getPluginId())) {
            return new Verdict(
                    RegistrationState.INACTIVE,
                    "The plugin " + reg.getPluginId() + " is not running, or declares no message handler,"
                            + " so nothing is delivered.");
        }
        Optional<String> denied = access.denial(
                reg.getActingUserId(), reg.getClusterId(), AccessCheck.needsOf(reg.getMode(), reg.getSubscription()));
        return denied.map(why -> new Verdict(
                        RegistrationState.SUSPENDED,
                        "Suspended: " + why + " It resumes" + " by itself once the permission is granted again."))
                .orElse(new Verdict(RegistrationState.ACTIVE, null));
    }

    private record NodeResult(RegistrationState state, String detail, Long dropped) {}

    /** Whether this node is left with nothing of this instance's. */
    private boolean reconcileNode(
            UUID clusterId,
            ClusterNode node,
            List<RegistrationEntity> regs,
            Map<UUID, Verdict> verdicts,
            Map<UUID, NodeResult> results) {
        Set<String> actualTaps = new LinkedHashSet<>(tap.installedNames(clusterId, node.getId(), instance.id()));
        Set<String> wantedTaps = new LinkedHashSet<>();

        for (RegistrationEntity reg : regs) {
            Verdict verdict = verdicts.get(reg.getId());
            if (!verdict.deliver()) {
                drains.stop(reg.getId(), node.getId());
                results.put(reg.getId(), new NodeResult(verdict.state(), verdict.detail(), null));
                continue;
            }
            if (reg.getMode() == RegistrationMode.TAP) {
                String name = TapNames.of(instance.id(), reg.getId());
                wantedTaps.add(name);
                results.put(reg.getId(), assertTap(clusterId, node, reg, name, actualTaps.contains(name)));
            } else {
                results.put(reg.getId(), assertConsumer(clusterId, node, reg));
            }
        }

        // Taps of this instance that nothing wants: a removed registration, a stopped plugin, a
        // suspended user, or a crash between a deletion and its sweep.
        for (String orphan : actualTaps) {
            if (!wantedTaps.contains(orphan)) {
                removeTap(clusterId, node, orphan);
            }
        }
        // Drains here for registrations that are gone altogether.
        Set<UUID> known = regs.stream().map(RegistrationEntity::getId).collect(Collectors.toSet());
        drains.runningOn(node.getId()).stream()
                .filter(id -> !known.contains(id))
                .forEach(id -> drains.stop(id, node.getId()));

        return wantedTaps.isEmpty()
                && tap.installedNames(clusterId, node.getId(), instance.id()).isEmpty();
    }

    private NodeResult assertTap(
            UUID clusterId, ClusterNode node, RegistrationEntity reg, String name, boolean installed) {
        if (installed && drains.isRunning(reg.getId(), node.getId())) {
            return new NodeResult(RegistrationState.ACTIVE, null, tap.dropped(clusterId, node.getId(), name));
        }
        String refusalKey = node.getId() + "|" + name;
        String fingerprint = reg.getSubscription() + "|" + node.getDataUrl() + "|" + node.getManagementUrl();
        if (installed && fingerprint.equals(refused.get(refusalKey))) {
            return new NodeResult(RegistrationState.FAILED, "Refused earlier on this node; see the audit log.", null);
        }
        drains.stop(reg.getId(), node.getId());
        if (node.getDataUrl() == null) {
            return new NodeResult(
                    RegistrationState.FAILED,
                    "This node has no Core URL registered, and delivery is over the Core protocol. Add one to the"
                            + " cluster's connection.",
                    null);
        }
        TapSource source =
                tap.source(clusterId, node.getId(), reg.getSubscription()).orElse(null);
        if (source == null) {
            return new NodeResult(
                    RegistrationState.PENDING,
                    "The subscription " + reg.getSubscription() + " does not exist on this node.",
                    null);
        }
        AuditEvent event = audit.begin(
                Actor.ofSystem(),
                "INSTALL_PLUGIN_TAP",
                "PLUGIN_TAP",
                name,
                clusterId,
                node.getId(),
                Map.of(
                        "plugin",
                        reg.getPluginId(),
                        "registration",
                        reg.getKey(),
                        "subscription",
                        reg.getSubscription()),
                false);
        try {
            tap.install(clusterId, node.getId(), instance.id(), reg.getId(), source, properties.tapRingSize());
            drains.start(spec(reg, node, TapNames.queueOf(name)));
            refused.remove(refusalKey);
            audit.succeed(event, 1);
            return new NodeResult(RegistrationState.ACTIVE, null, tap.dropped(clusterId, node.getId(), name));
        } catch (TapRefusedException e) {
            refused.put(refusalKey, fingerprint);
            audit.fail(event, e.getMessage());
            return new NodeResult(RegistrationState.FAILED, e.getMessage(), null);
        } catch (PluginDrains.StartFailure e) {
            if (e.servedElsewhere) {
                audit.succeed(event, 0);
                return new NodeResult(RegistrationState.SERVED_ELSEWHERE, e.getMessage(), null);
            }
            audit.fail(event, e.getMessage());
            return new NodeResult(RegistrationState.PENDING, e.getMessage(), null);
        } catch (RuntimeException e) {
            audit.fail(event, reason(e));
            return new NodeResult(RegistrationState.PENDING, reason(e), null);
        }
    }

    private NodeResult assertConsumer(UUID clusterId, ClusterNode node, RegistrationEntity reg) {
        int runningWith = drains.concurrency(reg.getId(), node.getId());
        if (runningWith == reg.getConcurrency()) {
            return new NodeResult(RegistrationState.ACTIVE, null, null);
        }
        if (runningWith > 0) {
            // Registered again with another concurrency: restarted with the new count. What the old
            // slots held and had not settled goes back to the queue.
            drains.stop(reg.getId(), node.getId());
        }
        if (node.getDataUrl() == null) {
            return new NodeResult(
                    RegistrationState.FAILED,
                    "This node has no Core URL registered, and delivery is over the Core protocol. Add one to the"
                            + " cluster's connection.",
                    null);
        }
        TapSource source =
                tap.source(clusterId, node.getId(), reg.getSubscription()).orElse(null);
        if (source == null) {
            return new NodeResult(
                    RegistrationState.PENDING,
                    "The subscription " + reg.getSubscription() + " does not exist on this node.",
                    null);
        }
        try {
            drains.start(spec(reg, node, source.queue()));
            return new NodeResult(RegistrationState.ACTIVE, null, null);
        } catch (PluginDrains.StartFailure e) {
            return new NodeResult(RegistrationState.PENDING, e.getMessage(), null);
        }
    }

    private PluginDrains.Spec spec(RegistrationEntity reg, ClusterNode node, String source) {
        return new PluginDrains.Spec(
                reg.getId(),
                reg.getPluginId(),
                reg.getKey(),
                reg.getMode(),
                reg.getConcurrency(),
                reg.getClusterId(),
                node.getId(),
                node.getName(),
                reg.getSubscription(),
                source);
    }

    private void removeTap(UUID clusterId, ClusterNode node, String name) {
        AuditEvent event = audit.begin(
                Actor.ofSystem(), "REMOVE_PLUGIN_TAP", "PLUGIN_TAP", name, clusterId, node.getId(), Map.of(), false);
        try {
            UUID registrationId = TapNames.registrationOf(name);
            if (registrationId != null) {
                drains.stop(registrationId, node.getId());
            }
            tap.remove(clusterId, node.getId(), instance.id(), name);
            refused.remove(node.getId() + "|" + name);
            audit.succeed(event, 1);
        } catch (RuntimeException e) {
            audit.fail(event, reason(e));
        }
    }

    private void write(RegistrationEntity reg, ClusterNode node, NodeResult result) {
        if (result == null) {
            return;
        }
        RegistrationNodeEntity row = nodeStates
                .findById(new RegistrationNodeEntity(reg.getId(), node.getId()).getId())
                .orElseGet(() -> new RegistrationNodeEntity(reg.getId(), node.getId()));
        boolean unchanged = row.getState() == result.state()
                && Objects.equals(row.getDetail(), result.detail())
                && Objects.equals(row.getDroppedCopies(), result.dropped());
        if (unchanged) {
            return;
        }
        row.setState(result.state());
        row.setDetail(result.detail());
        row.setDroppedCopies(result.dropped());
        row.setUpdatedAt(clock.instant());
        nodeStates.save(row);
    }

    /** One node per HA pair, and only nodes that answered the last scrape (see capture's reconciler). */
    public List<ClusterNode> servingNodes(UUID clusterId) {
        List<ClusterNode> all = nodes.nodes(clusterId);
        List<ClusterNode> answering =
                all.stream().filter(n -> n.getLastError() == null).toList();
        return ServingNodes.from(answering.isEmpty() ? all : answering);
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
