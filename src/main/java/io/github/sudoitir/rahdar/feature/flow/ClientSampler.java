package io.github.sudoitir.rahdar.feature.flow;

import static io.github.sudoitir.rahdar.system.artemis.ListRows.num;
import static io.github.sudoitir.rahdar.system.artemis.ListRows.str;

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
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import io.github.sudoitir.rahdar.system.artemis.BridgeRow;
import io.github.sudoitir.rahdar.system.artemis.DivertRow;
import io.github.sudoitir.rahdar.system.artemis.ListRows;
import io.github.sudoitir.rahdar.system.artemis.MBeanNames;
import io.github.sudoitir.rahdar.system.artemis.ManagementRequest;
import io.github.sudoitir.rahdar.system.artemis.ManagementResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import tools.jackson.databind.JsonNode;

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

    static final String LIST_PRODUCERS = "listProducers(java.lang.String,int,int)";
    static final String LIST_CONSUMERS = "listConsumers(java.lang.String,int,int)";
    static final String LIST_QUEUES = "listQueues(java.lang.String,int,int)";
    static final String ADDRESS_SETTINGS = "getAddressSettingsAsJSON(java.lang.String)";

    /** Queues a cluster connection creates to move messages to another node, named for that node. */
    static final String STORE_AND_FORWARD_PREFIX = "$.artemis.internal.sf.";

    private static final String STORE_AND_FORWARD_QUEUES =
            "{\"field\":\"name\",\"operation\":\"CONTAINS\",\"value\":\"" + STORE_AND_FORWARD_PREFIX + "\"}";
    private static final String TEMPORARY_QUEUES =
            "{\"field\":\"temporary\",\"operation\":\"EQUALS\",\"value\":\"true\"}";
    private static final String FILTERED_QUEUES = "{\"field\":\"filter\",\"operation\":\"NOT_EQUALS\",\"value\":\"\"}";

    private static final String ADDRESS = "address";
    private static final String BAD_RESPONSE = "BAD_RESPONSE";
    private static final String ROUTING_UNAVAILABLE = "ROUTING_UNAVAILABLE";
    private static final String COUNTER_UNAVAILABLE = "COUNTER_UNAVAILABLE";

    private final FlowStore store;
    private final ClusterDirectory directory;
    private final BrokerConnections connections;
    private final ClusterLock lock;
    private final ClusterOwnership ownership;
    private final SettingsService settings;
    private final SseHub hub;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ArtemisNodes artemisNodes;

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
                            e.address(),
                            e.queue(),
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

    /** Everything flow reads from one node, in one POST. Broker I/O only; nothing here touches the database. */
    NodeResult sampleNode(UUID clusterId, ClusterNode node, int cap) {
        Instant at = clock.instant();
        try {
            ArtemisNode client = artemisNodes.on(clusterId, node.getId());
            String mbean = client.resolveBrokerObjectName();
            List<ManagementResponse> entries = client.batch(List.of(
                    ManagementRequest.exec(mbean, LIST_PRODUCERS, ListRows.ALL, 1, cap),
                    ManagementRequest.exec(mbean, LIST_CONSUMERS, ListRows.ALL, 1, cap),
                    ManagementRequest.readAll(MBeanNames.divertsPattern(mbean)),
                    ManagementRequest.readAll(MBeanNames.bridgesPattern(mbean)),
                    ManagementRequest.exec(mbean, LIST_QUEUES, STORE_AND_FORWARD_QUEUES, 1, cap),
                    ManagementRequest.exec(mbean, LIST_QUEUES, TEMPORARY_QUEUES, 1, cap),
                    ManagementRequest.exec(mbean, LIST_QUEUES, FILTERED_QUEUES, 1, cap),
                    ManagementRequest.exec(mbean, ADDRESS_SETTINGS, "#")));
            if (entries.size() != 8) {
                return failed(clusterId, node, at, BAD_RESPONSE, "The broker returned an incomplete response.");
            }
            if (lacksNotEquals(entries.get(6))) {
                // An older broker has no NOT_EQUALS filter (ADR-0142): read the queues unfiltered and keep
                // the filtered ones here, as the route parse below already does.
                // ponytail: the cap now counts every queue, so a node with more queues than the cap can
                // miss filtered ones; page through them if a broker that old runs that many.
                entries = new ArrayList<>(entries);
                entries.set(6, client.single(ManagementRequest.exec(mbean, LIST_QUEUES, ListRows.ALL, 1, cap)));
            }

            Listing producers = listing(client, entries.get(0));
            Listing consumers = listing(client, entries.get(1));
            List<Member> members = new ArrayList<>();
            List<Reading> producerReadings = new ArrayList<>();
            List<Reading> consumerReadings = new ArrayList<>();
            boolean counterMissing = false;
            for (JsonNode row : producers.rows()) {
                counterMissing |= !row.has("msgSent");
                producerReadings.add(new Reading(memberId(row), num(row, "msgSent"), 0));
                members.add(member(Kind.PRODUCE, row, str(row, ADDRESS), null));
            }
            for (JsonNode row : consumers.rows()) {
                counterMissing |= !row.has("messagesAcknowledged");
                consumerReadings.add(
                        new Reading(memberId(row), num(row, "messagesAcknowledged"), num(row, "messagesInTransit")));
                members.add(member(Kind.CONSUME, row, str(row, ADDRESS), str(row, "queue")));
            }

            List<String> routingErrors = new ArrayList<>();
            List<Route> routes = routes(client, node, entries, routingErrors);

            String errorKind = errorKind(producers, consumers, counterMissing, routingErrors);
            String error = error(producers, consumers, routingErrors);
            NodeSample sample = new NodeSample(
                    node.getId(),
                    clusterId,
                    at,
                    producers.rows().size(),
                    (int) Math.min(Integer.MAX_VALUE, producers.total()),
                    consumers.rows().size(),
                    (int) Math.min(Integer.MAX_VALUE, consumers.total()),
                    error,
                    errorKind);
            boolean partial = errorKind == null
                    || COUNTER_UNAVAILABLE.equals(errorKind)
                    || ROUTING_UNAVAILABLE.equals(errorKind)
                    || !producers.rows().isEmpty()
                    || !consumers.rows().isEmpty();
            boolean truncated = producers.total() > producers.rows().size()
                    || consumers.total() > consumers.rows().size();
            return new NodeResult(sample, members, producerReadings, consumerReadings, routes, partial, truncated);
        } catch (BrokerConnectionException e) {
            return failed(clusterId, node, at, e.kind().name(), e.getMessage());
        } catch (RuntimeException e) {
            return failed(clusterId, node, at, BAD_RESPONSE, e.getMessage());
        }
    }

    /** Artemis before NOT_EQUALS rejects the filter by name; nothing else answers this way. */
    static boolean lacksNotEquals(ManagementResponse res) {
        return !res.ok()
                && res.error() != null
                && res.error().contains("No enum constant")
                && res.error().contains("NOT_EQUALS");
    }

    private static String errorKind(
            Listing producers, Listing consumers, boolean counterMissing, List<String> routingErrors) {
        if (producers.error() != null) {
            return producers.errorKind();
        }
        if (consumers.error() != null) {
            return consumers.errorKind();
        }
        if (counterMissing) {
            return COUNTER_UNAVAILABLE;
        }
        return routingErrors.isEmpty() ? null : ROUTING_UNAVAILABLE;
    }

    private static String error(Listing producers, Listing consumers, List<String> routingErrors) {
        if (producers.error() != null) {
            return producers.error();
        }
        if (consumers.error() != null) {
            return consumers.error();
        }
        return routingErrors.isEmpty() ? null : routingErrors.getFirst();
    }

    /** The routing entries of a node's POST, each read on its own: one failing entry loses only itself. */
    private static List<Route> routes(
            ArtemisNode client, ClusterNode node, List<ManagementResponse> entries, List<String> errors) {
        List<Route> routes = new ArrayList<>();
        for (JsonNode attrs : patternRead(entries.get(2), errors)) {
            DivertRow d = DivertRow.parse(attrs, node.getId(), node.getName());
            routes.add(new Route(
                    RouteKind.DIVERT,
                    d.uniqueName(),
                    d.address(),
                    d.forwardingAddress(),
                    d.filter(),
                    d.transformerClassName(),
                    d.exclusive(),
                    true,
                    0,
                    null));
        }
        for (JsonNode attrs : patternRead(entries.get(3), errors)) {
            BridgeRow b = BridgeRow.parse(attrs, node.getId(), node.getName());
            routes.add(new Route(
                    RouteKind.BRIDGE,
                    b.name(),
                    b.queueName(),
                    b.forwardingAddress(),
                    b.filterString(),
                    b.transformerClassName(),
                    false,
                    b.connected(),
                    b.messagesAcknowledged(),
                    null));
        }
        for (JsonNode row : queueRows(client, entries.get(4), errors)) {
            String name = str(row, "name");
            if (name != null && name.startsWith(STORE_AND_FORWARD_PREFIX)) {
                routes.add(new Route(
                        RouteKind.STORE_AND_FORWARD,
                        name,
                        str(row, ADDRESS),
                        receivingNodeId(name),
                        null,
                        null,
                        false,
                        true,
                        num(row, "messagesAdded"),
                        null));
            }
        }
        queueRows(client, entries.get(5), errors).stream()
                .map(row -> new Route(
                        RouteKind.TEMPORARY_QUEUE,
                        str(row, "name"),
                        str(row, ADDRESS),
                        str(row, "name"),
                        null,
                        null,
                        false,
                        true,
                        0,
                        null))
                .forEach(routes::add);
        for (JsonNode row : queueRows(client, entries.get(6), errors)) {
            String filter = str(row, "filter");
            if (filter != null && !filter.isBlank()) {
                routes.add(new Route(
                        RouteKind.QUEUE_FILTER,
                        str(row, "name"),
                        str(row, ADDRESS),
                        str(row, "name"),
                        filter,
                        null,
                        false,
                        true,
                        0,
                        null));
            }
        }
        ManagementResponse settings = entries.get(7);
        if (settings.ok()) {
            JsonNode parsed = client.parsed(settings);
            addressRoute(routes, RouteKind.DEAD_LETTER, str(parsed, "deadLetterAddress"));
            addressRoute(routes, RouteKind.EXPIRY, str(parsed, "expiryAddress"));
        } else {
            errors.add(entryError(settings));
        }
        return routes;
    }

    private static void addressRoute(List<Route> routes, RouteKind kind, String address) {
        if (address != null && !address.isBlank()) {
            routes.add(new Route(kind, "#", "#", address, null, null, false, true, 0, null));
        }
    }

    /**
     * The attribute maps of a Jolokia pattern read. A pattern that matches nothing answers 404 for that
     * entry alone, which means "none of these on this node", not a failure.
     */
    private static List<JsonNode> patternRead(ManagementResponse entry, List<String> errors) {
        if (entry.status() == 404) {
            return List.of();
        }
        if (!entry.ok()) {
            errors.add(entryError(entry));
            return List.of();
        }
        JsonNode value = entry.value();
        List<JsonNode> out = new ArrayList<>();
        if (value != null && value.isObject()) {
            for (String objectName : value.propertyNames()) {
                JsonNode attrs = value.get(objectName);
                if (attrs != null && attrs.isObject()) {
                    out.add(attrs);
                }
            }
        }
        return out;
    }

    private static List<JsonNode> queueRows(ArtemisNode client, ManagementResponse entry, List<String> errors) {
        Listing listing = listing(client, entry);
        if (listing.error() != null) {
            errors.add(listing.error());
        }
        return listing.rows();
    }

    /** {@code $.artemis.internal.sf.<cluster-connection>.<nodeID>}: the node id is the last segment. */
    static String receivingNodeId(String storeAndForwardQueue) {
        int dot = storeAndForwardQueue.lastIndexOf('.');
        return dot < 0 ? storeAndForwardQueue : storeAndForwardQueue.substring(dot + 1);
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
                    m.address(),
                    m.queue(),
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

    private static Member member(Kind kind, JsonNode row, String address, String queue) {
        return new Member(
                kind,
                str(row, "clientID"),
                firstNonBlank(str(row, "validatedUser"), str(row, "user")),
                str(row, "remoteAddress"),
                str(row, "protocol"),
                address,
                queue,
                null,
                0,
                false);
    }

    /** Broker ids are unique per node; the session is added so a reused id in a new session is a new member. */
    private static String memberId(JsonNode row) {
        return str(row, "id") + "@" + str(row, "session");
    }

    private static Listing listing(ArtemisNode client, ManagementResponse entry) {
        if (!entry.ok()) {
            String error = entryError(entry);
            String lower = error.toLowerCase(Locale.ROOT);
            String kind = entry.status() == 403 || lower.contains("permission") || lower.contains("security")
                    ? "PERMISSION_DENIED"
                    : BAD_RESPONSE;
            return new Listing(List.of(), 0, error, kind);
        }
        JsonNode env = client.parsed(entry);
        JsonNode data = env == null ? null : env.get("data");
        List<JsonNode> rows = new ArrayList<>();
        if (data != null && data.isArray()) {
            data.forEach(rows::add);
        }
        long total = env == null ? 0L : env.path("count").asLong(rows.size());
        return new Listing(rows, total, null, null);
    }

    private static String entryError(ManagementResponse entry) {
        return entry.error() != null ? entry.error() : "status " + entry.status();
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
            if (n.getJolokiaUrl() == null) {
                continue;
            }
            String key = n.getArtemisNodeId() != null ? n.getArtemisNodeId() : "id:" + n.getId();
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

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private record Listing(List<JsonNode> rows, long total, String error, String errorKind) {}

    record NodeResult(
            NodeSample sample,
            List<Member> members,
            List<Reading> producerReadings,
            List<Reading> consumerReadings,
            List<Route> routes,
            boolean partial,
            boolean truncated) {}
}
