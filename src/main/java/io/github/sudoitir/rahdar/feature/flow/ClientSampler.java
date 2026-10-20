package io.github.sudoitir.rahdar.feature.flow;

import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Edge;
import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Kind;
import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Member;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.NodeSample;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.Route;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.RouteKind;
import io.github.sudoitir.rahdar.feature.flow.MemberDeltas.MemberRate;
import io.github.sudoitir.rahdar.feature.flow.MemberDeltas.Reading;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowMember;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowRoute;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowSample;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Samples producers, consumers and the routing between them for the clusters whose flow is observed
 * (ADR-0081).
 *
 * <p>Per observed cluster: take the {@code FLOW_SAMPLE} lock or skip; per serving node, <em>one</em>
 * bulk POST carrying the producer and consumer listings, divert and bridge pattern reads, listings
 * of store-and-forward, temporary and filtered queues, and the dead-letter and expiry addresses, all
 * capped at the per-node row setting; per-object deltas; one short transaction per node after the
 * broker calls; a {@code flow} signal only when what was persisted changed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientSampler {

    private final FlowStore store;
    private final ClusterDirectory directory;
    private final BrokerConnections connections;
    private final ClusterLock lock;
    private final ClusterOwnership ownership;
    private final SettingsService settings;
    private final SseHub hub;
    private final MeterRegistry meters;
    private final Clock clock;
    private final SystemExtensions extensions;

    /** Per cluster, per node and kind: the previous sweep's counters. Held only while this instance samples. */
    private final Map<UUID, Map<String, MemberDeltas>> deltas = new ConcurrentHashMap<>();

    /** Per cluster: a digest of what the last sweep persisted, so an unchanged sweep sends no signal. */
    private final Map<UUID, Integer> digests = new ConcurrentHashMap<>();

    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    /**
     * One pass over every observed cluster this replica owns. Clusters run concurrently; a slow one
     * delays no other. What a replica held for a cluster it no longer owns is dropped here.
     */
    public void sweepObserved() {
        Instant now = clock.instant();
        store.forgetUnobserved(now.minus(FlowSettings.sampleInterval(settings).multipliedBy(3)));
        Set<UUID> observed = new HashSet<>(store.observedClusters(now));
        observed.removeIf(clusterId -> !ownership.owns(clusterId));
        deltas.keySet().retainAll(observed);
        digests.keySet().retainAll(observed);
        if (observed.isEmpty()) {
            skipped("no-lease");
            return;
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            observed.forEach(clusterId -> pool.submit(() -> sweepIfHeld(clusterId)));
        }
    }

    private void sweepIfHeld(UUID clusterId) {
        if (!running.add(clusterId)) {
            skipped("overlap");
            return;
        }
        try {
            if (!lock.runIfHeld(clusterId, ClusterLock.Scope.FLOW_SAMPLE, () -> sweep(clusterId))) {
                // Another instance samples this cluster. If this one takes over later, its first
                // sweep must read "measuring" rather than divide by a stale baseline.
                deltas.remove(clusterId);
                digests.remove(clusterId);
                skipped("lock-held");
            }
        } catch (RuntimeException e) {
            log.warn("Flow sweep of cluster {} failed: {}", clusterId, e.getMessage());
        } finally {
            running.remove(clusterId);
        }
    }

    void sweep(UUID clusterId) {
        Timer.Sample timing = Timer.start(meters);
        int cap = Math.max(1, settings.intValue(FlowSettings.MAX_ROWS_PER_NODE));
        List<ClusterNode> nodes = servingManageableNodes(clusterId);
        Map<ClusterNode, Future<NodeResult>> pending = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ClusterNode node : nodes) {
                pending.put(node, pool.submit(() -> sampleNode(clusterId, node, cap)));
            }
        }
        Map<String, MemberDeltas> clusterDeltas = deltas.computeIfAbsent(clusterId, k -> new ConcurrentHashMap<>());
        List<Object> digest = new ArrayList<>();
        for (var entry : pending.entrySet()) {
            ClusterNode node = entry.getKey();
            NodeResult result = entry.getValue().resultNow();
            boolean usable = result.sample().errorKind() == null || result.partial();
            List<Edge> edges = usable ? edges(clusterDeltas, node.getId(), result) : List.of();
            List<Route> routes = usable ? rateRoutes(clusterDeltas, node.getId(), result) : List.of();
            store.persistNode(result.sample(), edges, routes);
            meters.counter("rahdar.flow.sample.rows").increment(result.members().size());
            if (result.truncated()) {
                meters.counter("rahdar.flow.sample.truncated").increment();
            }
            digest.add(result.sample().errorKind());
            edges.stream()
                    .sorted(Comparator.comparing(Edge::toString))
                    .forEach(e -> digest.add(List.of(
                            e.kind(),
                            e.clientId(),
                            e.user(),
                            e.remoteHost(),
                            e.destination(),
                            e.subscription(),
                            e.memberCount(),
                            rounded(e.rate()),
                            e.stalled())));
            routes.stream()
                    .sorted(Comparator.comparing(Route::toString))
                    .forEach(r -> digest.add(List.of(
                            r.kind(),
                            String.valueOf(r.name()),
                            String.valueOf(r.source()),
                            String.valueOf(r.target()),
                            r.exclusive(),
                            r.connected(),
                            rounded(r.rate()))));
        }
        timing.stop(meters.timer("rahdar.flow.sample.duration"));
        Integer previous = digests.put(clusterId, digest.hashCode());
        if (!Objects.equals(previous, digest.hashCode())) {
            hub.publish(clusterId, FlowModule.TOPIC);
        }
        log.debug("Flow sweep of cluster {}: {} node(s)", clusterId, nodes.size());
    }

    /** Everything flow reads from one node. Broker I/O only; nothing here touches the database. */
    NodeResult sampleNode(UUID clusterId, ClusterNode node, int cap) {
        Instant at = clock.instant();
        try {
            FlowSample sampled =
                    extensions.require(clusterId, FlowSampler.class).sample(clusterId, node.getId(), cap);
            List<Member> members = new ArrayList<>();
            List<Reading> producerReadings = new ArrayList<>();
            List<Reading> consumerReadings = new ArrayList<>();
            for (FlowMember producer : sampled.producers()) {
                producerReadings.add(new Reading(producer.memberId(), producer.counter(), 0));
                members.add(member(Kind.PRODUCE, producer));
            }
            for (FlowMember consumer : sampled.consumers()) {
                consumerReadings.add(new Reading(consumer.memberId(), consumer.counter(), consumer.unacked()));
                members.add(member(Kind.CONSUME, consumer));
            }
            List<Route> routes =
                    sampled.routes().stream().map(ClientSampler::route).toList();

            String errorKind = sampled.errorKind();
            NodeSample sample = new NodeSample(
                    node.getId(),
                    clusterId,
                    at,
                    sampled.producers().size(),
                    (int) Math.min(Integer.MAX_VALUE, sampled.producersTotal()),
                    sampled.consumers().size(),
                    (int) Math.min(Integer.MAX_VALUE, sampled.consumersTotal()),
                    sampled.error(),
                    errorKind);
            boolean partial = errorKind == null
                    || FlowSampler.COUNTER_UNAVAILABLE.equals(errorKind)
                    || FlowSampler.ROUTING_UNAVAILABLE.equals(errorKind)
                    || !sampled.producers().isEmpty()
                    || !sampled.consumers().isEmpty();
            boolean truncated = sampled.producersTotal() > sampled.producers().size()
                    || sampled.consumersTotal() > sampled.consumers().size();
            return new NodeResult(sample, members, producerReadings, consumerReadings, routes, partial, truncated);
        } catch (BrokerConnectionException e) {
            return failed(clusterId, node, at, e.kind().name(), e.getMessage());
        } catch (RuntimeException e) {
            return failed(clusterId, node, at, FlowSampler.BAD_RESPONSE, e.getMessage());
        }
    }

    private static Route route(FlowRoute r) {
        return new Route(
                RouteKind.valueOf(r.kind()),
                r.name(),
                r.source(),
                r.target(),
                r.filter(),
                r.transformer(),
                r.exclusive(),
                r.connected(),
                r.counter(),
                null);
    }

    private List<Edge> edges(Map<String, MemberDeltas> clusterDeltas, UUID nodeId, NodeResult result) {
        Instant at = result.sample().sampledAt();
        Map<String, MemberRate> producerRates = clusterDeltas
                .computeIfAbsent(nodeId + "/P", k -> new MemberDeltas())
                .apply(at, result.producerReadings());
        Map<String, MemberRate> consumerRates = clusterDeltas
                .computeIfAbsent(nodeId + "/C", k -> new MemberDeltas())
                .apply(at, result.consumerReadings());
        List<Member> rated = new ArrayList<>(result.members().size());
        int p = 0;
        int c = 0;
        for (Member m : result.members()) {
            Reading reading = m.kind() == Kind.PRODUCE
                    ? result.producerReadings().get(p++)
                    : result.consumerReadings().get(c++);
            MemberRate rate = (m.kind() == Kind.PRODUCE ? producerRates : consumerRates).get(reading.memberId());
            rated.add(new Member(
                    m.kind(),
                    m.clientId(),
                    m.user(),
                    m.remoteAddress(),
                    m.protocol(),
                    m.destination(),
                    m.subscription(),
                    rate == null ? null : rate.rate(),
                    reading.unacked(),
                    rate != null && rate.stalled()));
        }
        return ClientEdges.aggregate(rated);
    }

    /** Bridges and store-and-forward queues carry a counter; their rate is its delta, like a client's. */
    private List<Route> rateRoutes(Map<String, MemberDeltas> clusterDeltas, UUID nodeId, NodeResult result) {
        List<Reading> readings = result.routes().stream()
                .filter(ClientSampler::counted)
                .map(r -> new Reading(r.kind() + ":" + r.name(), r.counter(), 0))
                .toList();
        Map<String, MemberRate> rates = clusterDeltas
                .computeIfAbsent(nodeId + "/R", k -> new MemberDeltas())
                .apply(result.sample().sampledAt(), readings);
        return result.routes().stream()
                .map(r -> {
                    MemberRate rate = counted(r) ? rates.get(r.kind() + ":" + r.name()) : null;
                    return rate == null
                            ? r
                            : new Route(
                                    r.kind(),
                                    r.name(),
                                    r.source(),
                                    r.target(),
                                    r.filter(),
                                    r.transformer(),
                                    r.exclusive(),
                                    r.connected(),
                                    r.counter(),
                                    rate.rate());
                })
                .toList();
    }

    private static boolean counted(Route r) {
        return r.kind() == RouteKind.BRIDGE || r.kind() == RouteKind.STORE_AND_FORWARD;
    }

    private static Member member(Kind kind, FlowMember m) {
        return new Member(
                kind,
                m.clientId(),
                m.user(),
                m.remoteAddress(),
                m.protocol(),
                m.destination(),
                m.subscription(),
                null,
                0,
                false);
    }

    private static NodeResult failed(UUID clusterId, ClusterNode node, Instant at, String kind, String error) {
        return new NodeResult(
                new NodeSample(node.getId(), clusterId, at, 0, 0, 0, 0, error, kind),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false);
    }

    /** One manageable endpoint per NodeID: the active one when the pair reports one. */
    private List<ClusterNode> servingManageableNodes(UUID clusterId) {
        Map<String, ClusterNode> perNodeId = new LinkedHashMap<>();
        for (ClusterNode n : directory.nodes(clusterId)) {
            if (n.getManagementUrl() == null) {
                continue;
            }
            String key = n.getNativeNodeId() != null ? n.getNativeNodeId() : "id:" + n.getId();
            perNodeId.merge(key, n, (kept, candidate) -> Boolean.TRUE.equals(candidate.getActive()) ? candidate : kept);
        }
        return List.copyOf(perNodeId.values());
    }

    private void skipped(String reason) {
        meters.counter("rahdar.flow.sample.skipped", "reason", reason).increment();
    }

    private static long rounded(Double rate) {
        return rate == null ? -1L : Math.round(rate * 10);
    }

    record NodeResult(
            NodeSample sample,
            List<Member> members,
            List<Reading> producerReadings,
            List<Reading> consumerReadings,
            List<Route> routes,
            boolean partial,
            boolean truncated) {}
}
