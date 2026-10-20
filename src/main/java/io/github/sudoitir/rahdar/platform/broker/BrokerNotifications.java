package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.notification.Gap;
import io.github.sudoitir.openbroker.notification.NotificationListener;
import io.github.sudoitir.openbroker.notification.Notifications;
import io.github.sudoitir.openbroker.notification.StreamState;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Keeps one notification stream per serving node through the broker SDK (ADR-0026, D2), and hands
 * what each stream reports to every {@link BrokerEventSink}. Reconciled at the end of every tier-A
 * scrape, on the scheduler's virtual threads, never inside a transaction. The SDK reconnects a stream
 * that fails, with backoff, and says what it missed with a gap.
 *
 * <p>{@link #verdictFor(UUID)} is the cached answer {@code CapabilityProbe} reads: it opens no
 * connection (D5).
 */
@Component
@Slf4j
public class BrokerNotifications {

    private final BrokerGateway gateway;
    private final List<BrokerEventSink> sinks;
    private final ClockOffsetService clocks;

    /** A node's open stream: its cluster, the facet it came from, and the subscription to close. */
    private record Stream(UUID clusterId, Notifications facet, AutoCloseable subscription) {}

    /** nodeId -> its stream. */
    private final Map<UUID, Stream> active = new ConcurrentHashMap<>();
    /** clusterId -> its nodeIds, so a verdict can be built per cluster. */
    private final Map<UUID, Set<UUID>> nodesByCluster = new ConcurrentHashMap<>();

    public BrokerNotifications(BrokerGateway gateway, List<BrokerEventSink> sinks, ClockOffsetService clocks) {
        this.gateway = gateway;
        this.sinks = sinks;
        this.clocks = clocks;
    }

    /** One stream per live node with a data-plane address; a node that no longer qualifies is closed. */
    public void reconcile(UUID clusterId, List<NodeEndpoint> endpoints) {
        Set<UUID> desired = endpoints.stream()
                .filter(NodeEndpoint::live)
                .filter(e -> CoreUrl.dialable(e.coreUrl()) != null)
                .map(NodeEndpoint::id)
                .collect(Collectors.toSet());
        nodesByCluster.put(clusterId, endpoints.stream().map(NodeEndpoint::id).collect(Collectors.toSet()));

        for (Map.Entry<UUID, Stream> e : Set.copyOf(active.entrySet())) {
            if (e.getValue().clusterId().equals(clusterId) && !desired.contains(e.getKey())) {
                stop(e.getKey());
            }
        }
        for (UUID nodeId : desired) {
            if (!active.containsKey(nodeId)) {
                start(clusterId, nodeId);
            }
        }
    }

    private void start(UUID clusterId, UUID nodeId) {
        Optional<Notifications> facet = gateway.cluster(clusterId).notifications();
        if (facet.isEmpty()) {
            return;
        }
        AutoCloseable subscription =
                facet.get().subscribe(Scope.node(BrokerGateway.idOf(nodeId)), new Listener(clusterId, nodeId));
        active.put(nodeId, new Stream(clusterId, facet.get(), subscription));
        log.info("Subscribed to notifications on node {} ({})", nodeId, clusterId);
    }

    private void stop(UUID nodeId) {
        Stream stream = active.remove(nodeId);
        if (stream == null) {
            return;
        }
        try {
            stream.subscription().close();
        } catch (Exception e) {
            log.debug("Closing the notification stream of node {}: {}", nodeId, e.getMessage());
        }
        log.info("Closed the notification stream of node {}", nodeId);
    }

    /** The cached outcome for a cluster (D5): it opens no connection. */
    public SubscriptionVerdict verdictFor(UUID clusterId) {
        Set<UUID> nodeIds = nodesByCluster.get(clusterId);
        if (nodeIds == null || nodeIds.isEmpty()) {
            return new SubscriptionVerdict.NotAttempted();
        }
        Map<UUID, SubscriptionVerdict.NodeState> states = nodeStates();
        List<Instant> connected = nodeIds.stream()
                .map(states::get)
                .filter(s -> s instanceof SubscriptionVerdict.NodeState.Connected)
                .map(s -> ((SubscriptionVerdict.NodeState.Connected) s).since())
                .toList();
        if (!connected.isEmpty()) {
            return new SubscriptionVerdict.Connected(
                    connected.size(), connected.stream().min(Instant::compareTo).orElseThrow());
        }
        return nodeIds.stream()
                .map(states::get)
                .filter(s -> s instanceof SubscriptionVerdict.NodeState.Failed)
                .map(s -> (SubscriptionVerdict.NodeState.Failed) s)
                .findFirst()
                .<SubscriptionVerdict>map(f -> new SubscriptionVerdict.Failed(f.kind(), f.reason()))
                .orElseGet(SubscriptionVerdict.NotAttempted::new);
    }

    /** Each streamed node's state: established since when, or its latest failure. Opens nothing. */
    public Map<UUID, SubscriptionVerdict.NodeState> nodeStates() {
        Map<UUID, SubscriptionVerdict.NodeState> out = new HashMap<>();
        active.forEach((nodeId, stream) -> {
            StreamState state = stream.facet().state().get(BrokerGateway.idOf(nodeId));
            SubscriptionVerdict.NodeState mapped = state == null ? null : stateOf(state);
            if (mapped != null) {
                out.put(nodeId, mapped);
            }
        });
        return out;
    }

    private static SubscriptionVerdict.NodeState stateOf(StreamState state) {
        Instant at = state.since().orElseGet(Instant::now);
        if (state.phase() == StreamState.Phase.ACTIVE) {
            return new SubscriptionVerdict.NodeState.Connected(at);
        }
        return state.lastFailure()
                .<SubscriptionVerdict.NodeState>map(e ->
                        new SubscriptionVerdict.NodeState.Failed(SubscriptionVerdict.kindOf(e), e.getMessage(), at))
                .orElse(null);
    }

    /** Close a removed cluster's streams and forget it. */
    public void forget(UUID clusterId) {
        nodesByCluster.remove(clusterId);
        active.entrySet().stream()
                .filter(e -> e.getValue().clusterId().equals(clusterId))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(this::stop);
    }

    /** The clusters this holds streams for. */
    Set<UUID> clusterIds() {
        return Set.copyOf(nodesByCluster.keySet());
    }

    /** Close everything this holds open. Called at its shutdown phase. */
    public void closeAll() {
        Set.copyOf(active.keySet()).forEach(this::stop);
    }

    /** One node's listener: each event to every sink, one sink's failure never another's. */
    private final class Listener implements NotificationListener {

        private final UUID clusterId;
        private final UUID nodeId;

        Listener(UUID clusterId, UUID nodeId) {
            this.clusterId = clusterId;
            this.nodeId = nodeId;
        }

        @Override
        public void onEvent(io.github.sudoitir.openbroker.notification.BrokerEvent event) {
            BrokerEvent kept = BrokerEvents.of(clusterId, nodeId, event, clocks.brokerTime());
            for (BrokerEventSink sink : sinks) {
                try {
                    sink.accept(kept);
                } catch (RuntimeException e) {
                    log.warn(
                            "Notification sink {} rejected an event: {}",
                            sink.getClass().getSimpleName(),
                            e.toString());
                }
            }
        }

        @Override
        public void onGap(Gap gap) {
            if (gap.cause() != Gap.Cause.STARTED) {
                log.info(
                        "Notifications from node {} may have been missed since {} ({})",
                        nodeId,
                        gap.since(),
                        gap.cause());
            }
        }
    }
}
