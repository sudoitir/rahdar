package io.github.sudoitir.rahdar.feature.flow;

import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Edge;
import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Kind;
import io.github.sudoitir.rahdar.feature.flow.FlowQuery.Focus;
import io.github.sudoitir.rahdar.feature.flow.FlowQuery.GroupBy;
import io.github.sudoitir.rahdar.feature.flow.FlowQuery.Layer;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.NodeSample;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.Route;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.RouteKind;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.StoredEdge;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.StoredRoute;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.EdgeKind;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.Fault;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowBrokerNodeView;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowDelivery;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowEdgeView;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowFocusView;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowGraphView;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowKpis;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowNodeRate;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowNodeShare;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowNodeView;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowTotals;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.NodeKind;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.NodeRole;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.NodeSampleState;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.RateSource;
import io.github.sudoitir.rahdar.kernel.core.HeavyRequests;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.MetricSamples;
import io.github.sudoitir.rahdar.platform.scrape.MetricSamples.SubjectRate;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.platform.scrape.ScrapeSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Assembles the bounded flow graph for one read (flow-visualization spec, design D6). Stateless:
 * every instance answers the same from the shared caches.
 *
 * <p>A <em>path</em> is one address-to-queue route with the clients producing to that address and
 * consuming from that queue, an address produced to that has no queue, a store-and-forward queue to
 * another node, or a client's collapsed temporary queues. Paths are what the bound counts and what
 * the ranking orders; routing layers are drawn around the chosen paths.
 */
@Service
@RequiredArgsConstructor
public class FlowGraphService {

    /** The management access that lets Rahdar's broker user list producers and consumers. */
    static final String LIST_ACCESS_SNIPPET = """
            <!-- etc/management.xml: allow the role Rahdar connects with to call list operations -->
            <authorisation>
               <role-access>
                  <match domain="org.apache.activemq.artemis">
                     <access method="list*" roles="amq"/>
                     <access method="get*" roles="amq"/>
                  </match>
               </role-access>
            </authorisation>""";

    static final String WILDCARD_ASSUMPTION = "Wildcard matches assume the broker's default syntax: words separated"
            + " by '.', '*' matching one word and '#' any number. A broker.xml that redefines these is not"
            + " readable over management.";

    private static final String UNIDENTIFIED = "unidentified client";

    /** The address key of producers that name none: their messages go wherever each message says. */
    static final String ANONYMOUS = "";

    static final String TEMPORARY_PREFIX = "temporary:";

    private final FlowStore store;
    private final QueueSnapshots snapshots;
    private final MetricSamples metrics;
    private final ClusterDirectory directory;
    private final SettingsService settings;
    private final FlowDemand demand;
    private final ClusterAccessGuard access;
    private final PermissionResolver permissions;
    private final Clock clock;

    public FlowGraphView graph(UUID clusterId, FlowQuery query) {
        access.requireVisible(clusterId);
        demand.renew(clusterId);
        Readable readable = new Readable(
                permissions.filter(clusterId, ResourceType.SUBSCRIPTION),
                permissions.filter(clusterId, ResourceType.DESTINATION));

        Instant now = clock.instant();
        Duration sampleInterval = FlowSettings.sampleInterval(settings);
        Duration tierC = settings.duration(ScrapeSettings.TIER_C);
        Set<Layer> layers = query.layers();
        boolean capture = layers.contains(Layer.CAPTURE);
        NodeNames names = nodeNames(clusterId);

        List<NodeSample> samples = store.nodeSamples(clusterId);
        List<StoredRoute> routes = store.routes(clusterId);
        Set<String> temporaryQueues = temporaryQueues(routes);

        Map<String, QueueAgg> queues = queueAggregates(clusterId, names.byId(), temporaryQueues, capture, readable);
        Rates rates = rates(clusterId, query.byNode(), now.minus(tierC.multipliedBy(3)), now);
        Picture picture = new Picture(queues, new TreeMap<>(), new TreeMap<>(), rates.added(), rates.acked());
        addClients(clusterId, query, names.byId(), temporaryQueues, picture, readable);

        Routing routing = new Routing();
        routes.stream()
                .filter(stored -> readable.sees(stored.route()))
                .forEach(stored -> routing.add(stored, names.byId().get(stored.nodeId()), layers, capture));
        routing.foldHopsInto(queues);

        List<Path> all = paths(queues, picture.producers());
        Comparator<Path> ranking = Comparator.comparingInt((Path p) -> bucket(score(p, query, picture)))
                .reversed()
                .thenComparing(Path::key);
        List<Path> ranked = all.stream().sorted(ranking).toList();
        Selection selection = select(query, all, ranked, picture);
        List<Path> chosen = selection.chosen();

        int sampledNodes = samples.size();
        Freshness fresh = new Freshness(now, sampleInterval, tierC);
        Drawing drawing = new Drawing();
        draw(chosen, picture, queueFilters(routes), fresh, drawing);
        drawDiverts(routing.diverts.values(), sampledNodes, drawing, queues);
        drawBridges(routing.bridges.values(), sampledNodes, knownAddresses(picture), fresh, drawing);
        drawHops(routing.hops.values(), names.byArtemisId(), fresh, drawing);
        List<String> assumptions = drawWildcards(drawing);
        if (layers.contains(Layer.DEAD_LETTER)) {
            drawFailureRoutes(drawing, queues, routing.deadLetter, EdgeKind.DEAD_LETTER, NodeRole.DEAD_LETTER);
            drawFailureRoutes(drawing, queues, routing.expiry, EdgeKind.EXPIRY, NodeRole.EXPIRY);
        }

        List<FlowBrokerNodeView> brokerNodes = samples.stream()
                .map(s -> brokerNode(s, names.byId().get(s.nodeId())))
                .sorted(Comparator.comparing(FlowBrokerNodeView::name, Comparator.nullsLast(String::compareTo)))
                .toList();
        Instant sampledAt = samples.stream()
                .map(NodeSample::sampledAt)
                .max(Instant::compareTo)
                .orElse(null);

        List<FlowNodeView> drawnNodes = drawing.sortedNodes();
        List<FlowEdgeView> drawnEdges = drawing.sortedEdges();
        FlowKpis kpis = kpis(picture, brokerNodes, routing, sampledNodes);
        if (query.byNode()) {
            Breakdown breakdown = new Breakdown(names.byId(), samples, rates.addedByNode(), rates.ackedByNode(), fresh);
            drawnNodes = drawnNodes.stream().map(n -> breakdown.node(n, queues)).toList();
            drawnEdges = drawnEdges.stream()
                    .map(e -> breakdown.edge(e, picture.producers(), picture.consumers()))
                    .toList();
            brokerNodes = brokerNodes.stream()
                    .map(b -> breakdown.brokerNode(b, queues))
                    .toList();
        }

        return new FlowGraphView(
                drawnNodes,
                drawnEdges,
                kpis,
                new FlowTotals(
                        all.size(),
                        chosen.size(),
                        query.limit(),
                        query.clamped(),
                        selection.limited() ? HeavyRequests.Bound.ROWS : null),
                selection.focusView(),
                sampledAt,
                samples.isEmpty(),
                sampleInterval.toSeconds(),
                layers.stream().map(Enum::name).sorted().toList(),
                assumptions,
                brokerNodes);
    }

    private NodeNames nodeNames(UUID clusterId) {
        Map<UUID, String> byId = new HashMap<>();
        Map<String, String> byArtemisId = new HashMap<>();
        for (ClusterNode n : directory.nodes(clusterId)) {
            byId.put(n.getId(), n.getName());
            if (n.getNativeNodeId() != null) {
                // A live/backup pair shares one NodeID: name the logical node by its serving endpoint,
                // never by whichever of the pair happens to be listed last.
                byArtemisId.merge(
                        n.getNativeNodeId(),
                        n.getName(),
                        (first, next) -> Boolean.TRUE.equals(n.getActive()) ? next : first);
            }
        }
        return new NodeNames(byId, byArtemisId);
    }

    /**
     * What the caller may read: a client is drawn through the queue it consumes or the address it produces to,
     * a routing object through the addresses or queue it joins, and what belongs to the cluster as a whole
     * (hops between nodes, temporary queues, the failure addresses of the cluster) only for a caller who reads
     * everything.
     */
    private record Readable(ResourceFilter queues, ResourceFilter addresses) {

        boolean sees(Edge edge) {
            return edge.kind() == Kind.PRODUCE
                    ? addresses.readable(edge.destination())
                    : queues.readable(edge.subscription());
        }

        boolean sees(Route route) {
            return switch (route.kind()) {
                case DIVERT ->
                    addresses.readable(route.source())
                            && (captureOwned(route.name()) || addresses.readable(route.target()));
                case BRIDGE -> queues.readable(route.source()) || addresses.readable(route.source());
                case QUEUE_FILTER -> queues.readable(route.target());
                case DEAD_LETTER, EXPIRY ->
                    addresses.readable(route.target())
                            && (isPattern(route.source()) || addresses.readable(route.source()));
                case STORE_AND_FORWARD, TEMPORARY_QUEUE -> queues.everything() && addresses.everything();
            };
        }

        /** Whether a routing source is a match such as {@code #}, which names no address of its own. */
        private static boolean isPattern(String source) {
            return source == null || source.contains("#") || source.contains("*");
        }
    }

    private static Set<String> temporaryQueues(List<StoredRoute> routes) {
        return routes.stream()
                .filter(r -> r.route().kind() == RouteKind.TEMPORARY_QUEUE)
                .map(r -> r.route().target())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Map<String, String> queueFilters(List<StoredRoute> routes) {
        Map<String, String> queueFilters = new HashMap<>();
        for (StoredRoute r : routes) {
            if (r.route().kind() == RouteKind.QUEUE_FILTER) {
                queueFilters.put(r.route().target(), r.route().filter());
            }
        }
        return queueFilters;
    }

    private Map<String, QueueAgg> queueAggregates(
            UUID clusterId,
            Map<UUID, String> nodeNames,
            Set<String> temporaryQueues,
            boolean capture,
            Readable readable) {
        Map<String, QueueAgg> queues = new TreeMap<>();
        for (QueueSnapshot s : snapshots.forCluster(clusterId)) {
            boolean hidden = !readable.queues().readable(s.queueName())
                    || internal(s.address())
                    || internal(s.queueName())
                    || temporaryQueues.contains(s.queueName())
                    || (captureOwned(s.queueName()) && !capture);
            if (!hidden) {
                queues.computeIfAbsent(s.queueName(), name -> new QueueAgg(name, s.address()))
                        .add(s, nodeNames.get(s.nodeId()));
            }
        }
        return queues;
    }

    /**
     * With a breakdown, the per-node rates are read and the totals derived from them — the same
     * aggregate the unsplit read computes in SQL, so one query per metric either way (ADR-0110).
     */
    private Rates rates(UUID clusterId, boolean byNode, Instant windowStart, Instant now) {
        Instant until = now.plusSeconds(1);
        if (byNode) {
            Map<String, Map<UUID, SubjectRate>> addedByNode = metrics.latestRateWithTimeBySubjectAndNode(
                    clusterId, MetricKeys.ENQUEUED.name(), windowStart, until);
            Map<String, Map<UUID, SubjectRate>> ackedByNode = metrics.latestRateWithTimeBySubjectAndNode(
                    clusterId, MetricKeys.ACKNOWLEDGED.name(), windowStart, until);
            return new Rates(summed(addedByNode), summed(ackedByNode), addedByNode, ackedByNode);
        }
        return new Rates(
                metrics.latestRateWithTimeBySubject(clusterId, MetricKeys.ENQUEUED.name(), windowStart, until),
                metrics.latestRateWithTimeBySubject(clusterId, MetricKeys.ACKNOWLEDGED.name(), windowStart, until),
                Map.of(),
                Map.of());
    }

    private void addClients(
            UUID clusterId,
            FlowQuery query,
            Map<UUID, String> nodeNames,
            Set<String> temporaryQueues,
            Picture picture,
            Readable readable) {
        boolean capture = query.layers().contains(Layer.CAPTURE);
        for (StoredEdge stored : store.edges(clusterId)) {
            Edge e = stored.edge();
            boolean hidden = !readable.sees(e)
                    || internal(e.destination())
                    || internal(e.subscription())
                    || ((captureOwned(e.subscription()) || captureOwned(e.destination())) && !capture);
            if (!hidden) {
                addClient(stored, query, nodeNames.get(stored.nodeId()), temporaryQueues, picture);
            }
        }
    }

    private static void addClient(
            StoredEdge stored, FlowQuery query, String nodeName, Set<String> temporaryQueues, Picture picture) {
        Edge e = stored.edge();
        String label = label(e, query.groupBy());
        if (e.kind() == Kind.PRODUCE) {
            picture.producers().computeIfAbsent(label, ClientAgg::new).add(stored, nodeName, e.destination());
        } else if (temporaryQueues.contains(e.subscription())) {
            if (query.layers().contains(Layer.TEMPORARY)) {
                // One node per client for all its temporary queues: each is short-lived and uniquely
                // named, and drawn one by one they would bury the paths an operator came for.
                String collapsed = TEMPORARY_PREFIX + label;
                picture.queues()
                        .computeIfAbsent(collapsed, name -> new QueueAgg(name, null, NodeRole.TEMPORARY))
                        .temporary
                        .add(e.subscription());
                picture.consumers().computeIfAbsent(label, ClientAgg::new).add(stored, nodeName, collapsed);
            }
        } else {
            picture.consumers().computeIfAbsent(label, ClientAgg::new).add(stored, nodeName, e.subscription());
            if (!e.subscription().isEmpty()) {
                // A queue can be consumed before the slow queue sweep has seen it.
                picture.queues().computeIfAbsent(e.subscription(), name -> new QueueAgg(name, e.destination()));
            }
        }
    }

    private static Selection select(FlowQuery query, List<Path> all, List<Path> ranked, Picture picture) {
        if (query.focus() == null) {
            return new Selection(ranked.stream().limit(query.limit()).toList(), null, ranked.size() > query.limit());
        }
        Set<Path> reach = neighbourhood(query.focus(), query.hops(), all, picture.producers(), picture.consumers());
        List<Path> candidates = ranked.stream().filter(reach::contains).toList();
        List<Path> chosen = candidates.stream().limit(query.limit()).toList();
        FlowFocusView focusView = new FlowFocusView(
                query.focus().kind().name().toLowerCase(Locale.ROOT),
                query.focus().name(),
                query.hops(),
                !reach.isEmpty());
        return new Selection(chosen, focusView, candidates.size() > query.limit());
    }

    private static Set<String> knownAddresses(Picture picture) {
        Set<String> knownAddresses = new TreeSet<>();
        picture.queues().values().stream()
                .map(q -> q.address)
                .filter(Objects::nonNull)
                .forEach(knownAddresses::add);
        picture.producers().values().forEach(c -> knownAddresses.addAll(c.targets.keySet()));
        return knownAddresses;
    }

    private static List<Path> paths(Map<String, QueueAgg> queues, Map<String, ClientAgg> producers) {
        Set<Path> paths = new LinkedHashSet<>();
        Set<String> routed = new TreeSet<>();
        for (QueueAgg q : queues.values()) {
            paths.add(new Path(q.address, q.name));
            if (q.address != null) {
                routed.add(q.address);
            }
        }
        for (ClientAgg p : producers.values()) {
            for (String address : p.targets.keySet()) {
                if (!routed.contains(address)) {
                    paths.add(new Path(address, null));
                }
            }
        }
        return List.copyOf(paths);
    }

    private static Double score(Path p, FlowQuery query, Picture picture) {
        if (p.queue() == null) {
            // No queue: the only measure is what producers send to the address.
            return query.rank() == FlowQuery.Rank.IN ? targetRate(picture.producers(), p.address()) : null;
        }
        QueueAgg q = picture.queues().get(p.queue());
        if (q.role == NodeRole.STORE_AND_FORWARD) {
            return query.rank() == FlowQuery.Rank.BACKLOG ? null : q.ownRate;
        }
        if (q.role == NodeRole.TEMPORARY) {
            return query.rank() == FlowQuery.Rank.BACKLOG ? null : targetRate(picture.consumers(), q.name);
        }
        return switch (query.rank()) {
            case IN -> rate(picture.added().get(p.queue()));
            case OUT -> rate(picture.acked().get(p.queue()));
            case BACKLOG -> q.messageCount == null ? null : q.messageCount.doubleValue();
        };
    }

    private static Double targetRate(Map<String, ClientAgg> clients, String target) {
        Double sum = null;
        for (ClientAgg c : clients.values()) {
            TargetAgg t = c.targets.get(target);
            if (t != null && t.rate != null) {
                sum = (sum == null ? 0 : sum) + t.rate;
            }
        }
        return sum;
    }

    /**
     * Half-decade buckets of a score, so paths whose rates differ slightly keep their order between
     * refreshes without any memory of the last one. Unknown ranks below zero, so a path still being
     * measured never displaces a measured one and never reads as idle.
     */
    static int bucket(Double score) {
        if (score == null) {
            return Integer.MIN_VALUE;
        }
        if (score <= 0) {
            return Integer.MIN_VALUE + 1;
        }
        return (int) Math.floor(Math.log10(score) * 2);
    }

    private static Set<Path> neighbourhood(
            Focus focus, int hops, List<Path> all, Map<String, ClientAgg> producers, Map<String, ClientAgg> consumers) {
        Set<Path> reach = all.stream()
                .filter(p -> focused(focus, p, producers, consumers))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (int hop = 1; hop < hops && !reach.isEmpty(); hop++) {
            reach = widen(reach, all, producers, consumers);
        }
        return reach;
    }

    private static boolean focused(
            Focus focus, Path p, Map<String, ClientAgg> producers, Map<String, ClientAgg> consumers) {
        return switch (focus.kind()) {
            case SUBSCRIPTION -> focus.name().equals(p.queue());
            case DESTINATION -> focus.name().equals(p.address());
            case CLIENT -> touches(producers.get(focus.name()), consumers.get(focus.name()), p);
        };
    }

    /** One more hop: every path on an address the reach touches, or that a client of the reach touches. */
    private static Set<Path> widen(
            Set<Path> reach, List<Path> all, Map<String, ClientAgg> producers, Map<String, ClientAgg> consumers) {
        Set<String> addresses = reach.stream()
                .map(Path::address)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> queueNames =
                reach.stream().map(Path::queue).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        List<ClientAgg> clients = new ArrayList<>();
        producers.values().stream()
                .filter(c -> c.targets.keySet().stream().anyMatch(addresses::contains))
                .forEach(clients::add);
        consumers.values().stream()
                .filter(c -> c.targets.keySet().stream().anyMatch(queueNames::contains))
                .forEach(clients::add);
        Set<Path> next = new LinkedHashSet<>(reach);
        for (Path p : all) {
            if (p.address() != null && addresses.contains(p.address())) {
                next.add(p);
            }
            for (ClientAgg c : clients) {
                if (touches(producers.get(c.label), consumers.get(c.label), p)) {
                    next.add(p);
                }
            }
        }
        return next;
    }

    private static boolean touches(ClientAgg producer, ClientAgg consumer, Path p) {
        return (producer != null && p.address() != null && producer.targets.containsKey(p.address()))
                || (consumer != null && p.queue() != null && consumer.targets.containsKey(p.queue()));
    }

    // ---- drawing ----------------------------------------------------------------------------------

    /** The nodes and edges being drawn, keyed so that routing layers can add to what paths drew. */
    private static final class Drawing {
        final Map<String, FlowNodeView> nodes = new LinkedHashMap<>();
        final Map<String, FlowEdgeView> edges = new LinkedHashMap<>();
        final Set<String> addresses = new TreeSet<>();
        final Set<String> queues = new TreeSet<>();

        List<FlowNodeView> sortedNodes() {
            return nodes.values().stream()
                    .sorted(Comparator.comparing(FlowNodeView::kind).thenComparing(FlowNodeView::id))
                    .toList();
        }

        List<FlowEdgeView> sortedEdges() {
            return edges.values().stream()
                    .sorted(Comparator.comparing(FlowEdgeView::id))
                    .toList();
        }

        void node(FlowNodeView view) {
            nodes.putIfAbsent(view.id(), view);
        }

        void edge(FlowEdgeView view) {
            if (nodes.containsKey(view.source()) && nodes.containsKey(view.target())) {
                edges.putIfAbsent(view.id(), view);
            }
        }
    }

    private static void draw(
            List<Path> chosen, Picture picture, Map<String, String> queueFilters, Fresh fresh, Drawing drawing) {
        for (Path p : chosen) {
            if (p.address() != null) {
                drawing.addresses.add(p.address());
            }
            if (p.queue() != null) {
                drawing.queues.add(p.queue());
            }
        }

        for (String address : drawing.addresses) {
            addressNode(drawing, address, picture.queues());
        }

        for (String name : drawing.queues) {
            drawQueue(name, picture, queueFilters, fresh, drawing);
        }

        drawClients(picture.producers(), drawing.addresses, NodeKind.PRODUCER, fresh, drawing);
        drawClients(picture.consumers(), drawing.queues, NodeKind.CONSUMER, fresh, drawing);
    }

    private static void drawQueue(
            String name, Picture picture, Map<String, String> queueFilters, Fresh fresh, Drawing drawing) {
        QueueAgg q = picture.queues().get(name);
        NodeRole role = q.role != null ? q.role : captureRole(name);
        drawing.node(new FlowNodeView(
                subscriptionId(name),
                NodeKind.SUBSCRIPTION,
                role,
                queueLabel(q),
                q.role == NodeRole.TEMPORARY ? q.temporary.size() : null,
                q.messageCount,
                q.consumerCount,
                q.routingType == null ? List.of() : List.of(q.routingType),
                List.of(),
                List.of(),
                List.of(),
                List.copyOf(q.brokerNodes),
                q.noConsumer() ? List.of(Fault.NO_CONSUMER) : List.of()));
        if (q.address != null) {
            drawing.edge(routeEdge(q, picture.added().get(name), queueFilters.get(name), fresh));
        }
    }

    /** The capture role of a resource Rahdar's message capture owns, else none. */
    private static NodeRole captureRole(String name) {
        return captureOwned(name) ? NodeRole.CAPTURE : null;
    }

    private static FlowEdgeView routeEdge(QueueAgg q, SubjectRate rate, String filter, Fresh fresh) {
        return new FlowEdgeView(
                "route:" + q.address + "->" + q.name,
                EdgeKind.ROUTE,
                destinationId(q.address),
                subscriptionId(q.name),
                rate(rate),
                RateSource.SUBSCRIPTION_METRIC,
                rate == null ? null : rate.asOf(),
                rate != null && rate.span().compareTo(fresh.sampleInterval().multipliedBy(2)) > 0
                        ? rate.span().toSeconds()
                        : null,
                rate != null && fresh.olderThan(rate.asOf(), fresh.tierC()),
                delivery(q.routingType),
                null,
                null,
                filter,
                null,
                false,
                null,
                null,
                captureOwned(q.name),
                List.of());
    }

    private static void addressNode(Drawing drawing, String address, Map<String, QueueAgg> queues) {
        Set<String> routingTypes = new TreeSet<>();
        Set<String> brokerNodes = new TreeSet<>();
        queues.values().stream().filter(q -> address.equals(q.address)).forEach(q -> {
            if (q.routingType != null) {
                routingTypes.add(q.routingType);
            }
            brokerNodes.addAll(q.brokerNodes);
        });
        boolean anonymous = ANONYMOUS.equals(address);
        drawing.node(new FlowNodeView(
                destinationId(address),
                NodeKind.DESTINATION,
                anonymous ? NodeRole.ANONYMOUS : captureRole(address),
                anonymous ? "anonymous producers — address chosen per message" : address,
                null,
                null,
                null,
                List.copyOf(routingTypes),
                List.of(),
                List.of(),
                List.of(),
                List.copyOf(brokerNodes),
                List.of()));
    }

    private static String queueLabel(QueueAgg q) {
        if (q.role == NodeRole.TEMPORARY) {
            return "temporary queues ×" + q.temporary.size();
        }
        return q.name;
    }

    private static void drawClients(
            Map<String, ClientAgg> clients, Set<String> shownTargets, NodeKind kind, Fresh fresh, Drawing drawing) {
        for (ClientAgg c : clients.values()) {
            List<Map.Entry<String, TargetAgg>> shown = c.targets.entrySet().stream()
                    .filter(t -> shownTargets.contains(t.getKey()))
                    .toList();
            if (!shown.isEmpty()) {
                drawClient(c, shown, kind, fresh, drawing);
            }
        }
    }

    private static void drawClient(
            ClientAgg c, List<Map.Entry<String, TargetAgg>> shown, NodeKind kind, Fresh fresh, Drawing drawing) {
        boolean producing = kind == NodeKind.PRODUCER;
        String id = (producing ? "producer:" : "consumer:") + c.label;
        boolean stalled = shown.stream().anyMatch(t -> t.getValue().stalled);
        drawing.node(new FlowNodeView(
                id,
                kind,
                null,
                c.label,
                c.members,
                null,
                null,
                List.of(),
                List.copyOf(c.protocols),
                List.copyOf(c.hosts),
                List.copyOf(c.users),
                List.copyOf(c.brokerNodes),
                stalled ? List.of(Fault.STALLED) : List.of()));
        for (Map.Entry<String, TargetAgg> t : shown) {
            drawing.edge(clientEdge(c, id, producing, t.getKey(), t.getValue(), fresh));
        }
    }

    private static FlowEdgeView clientEdge(
            ClientAgg c, String clientId, boolean producing, String key, TargetAgg agg, Fresh fresh) {
        String target = producing ? destinationId(key) : subscriptionId(key);
        return new FlowEdgeView(
                (producing ? "produce:" : "consume:") + c.label + "->" + key,
                producing ? EdgeKind.PRODUCE : EdgeKind.CONSUME,
                producing ? clientId : target,
                producing ? target : clientId,
                agg.rate,
                RateSource.SAMPLER,
                agg.asOf,
                null,
                agg.asOf != null && fresh.olderThan(agg.asOf, fresh.sampleInterval()),
                null,
                agg.members,
                null,
                null,
                null,
                false,
                null,
                null,
                false,
                agg.stalled ? List.of(Fault.STALLED) : List.of());
    }

    /**
     * A divert from a shown address. Its target address is drawn even when none of its queues made the
     * bound, so the operator sees where the messages go. An exclusive divert without a filter takes
     * every message before the address's own queues see it, which the route edges then say.
     */
    private static void drawDiverts(
            Collection<RoutingAgg> diverts, int sampledNodes, Drawing drawing, Map<String, QueueAgg> queues) {
        for (RoutingAgg d : diverts) {
            Route r = d.route;
            if (drawing.addresses.contains(r.source())) {
                addressNode(drawing, r.target(), queues);
                drawing.edge(divertEdge(d, sampledNodes));
                if (r.exclusive() && (r.filter() == null || r.filter().isBlank())) {
                    bypassRoutesFrom(r.source(), drawing);
                }
            }
        }
    }

    private static FlowEdgeView divertEdge(RoutingAgg d, int sampledNodes) {
        Route r = d.route;
        List<Fault> faults = d.nodes.size() < sampledNodes ? List.of(Fault.PARTIAL_PRESENCE) : List.of();
        return new FlowEdgeView(
                "forward:" + r.name() + ":" + r.source() + "->" + r.target(),
                EdgeKind.FORWARD,
                destinationId(r.source()),
                destinationId(r.target()),
                null,
                RateSource.NONE,
                d.asOf,
                null,
                false,
                null,
                null,
                r.exclusive(),
                r.filter(),
                r.transformer(),
                false,
                d.nodes.size(),
                sampledNodes,
                captureOwned(r.name()),
                faults);
    }

    private static void bypassRoutesFrom(String address, Drawing drawing) {
        for (String edgeId : List.copyOf(drawing.edges.keySet())) {
            FlowEdgeView e = drawing.edges.get(edgeId);
            if (e.kind() == EdgeKind.ROUTE && e.source().equals(destinationId(address))) {
                drawing.edges.put(edgeId, bypassed(e));
            }
        }
    }

    private static void drawBridges(
            Collection<RoutingAgg> bridges,
            int sampledNodes,
            Set<String> knownAddresses,
            Fresh fresh,
            Drawing drawing) {
        for (RoutingAgg b : bridges) {
            Route r = b.route;
            if (!drawing.queues.contains(r.source())) {
                continue;
            }
            String target;
            if (knownAddresses.contains(r.target())) {
                target = destinationId(r.target());
                drawing.node(new FlowNodeView(
                        target,
                        NodeKind.DESTINATION,
                        null,
                        r.target(),
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));
            } else {
                target = "remote:link:" + r.target();
                drawing.node(new FlowNodeView(
                        target,
                        NodeKind.REMOTE,
                        NodeRole.LINK_TARGET,
                        r.target(),
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()));
            }
            List<Fault> faults = new ArrayList<>();
            if (b.connectedOn < b.nodes.size()) {
                faults.add(Fault.LINK_DOWN);
            }
            if (b.nodes.size() < sampledNodes) {
                faults.add(Fault.PARTIAL_PRESENCE);
            }
            drawing.edge(new FlowEdgeView(
                    "link:" + r.name(),
                    EdgeKind.LINK,
                    subscriptionId(r.source()),
                    target,
                    b.rate,
                    RateSource.SAMPLER,
                    b.asOf,
                    null,
                    b.asOf != null && fresh.olderThan(b.asOf, fresh.sampleInterval()),
                    null,
                    null,
                    null,
                    r.filter(),
                    r.transformer(),
                    false,
                    b.nodes.size(),
                    sampledNodes,
                    false,
                    List.copyOf(faults)));
        }
    }

    private static void drawHops(
            Collection<HopAgg> hops, Map<String, String> nodeNamesByArtemisId, Fresh fresh, Drawing drawing) {
        for (HopAgg hop : hops) {
            if (!drawing.queues.contains(hop.queue)) {
                continue;
            }
            String nodeName = nodeNamesByArtemisId.get(hop.targetNodeId);
            String target = "remote:node:" + hop.targetNodeId;
            drawing.node(new FlowNodeView(
                    target,
                    NodeKind.REMOTE,
                    NodeRole.CLUSTER_NODE,
                    nodeName != null ? nodeName : "node " + shortId(hop.targetNodeId),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()));
            drawing.edge(new FlowEdgeView(
                    "hop:" + hop.queue,
                    EdgeKind.CLUSTER_HOP,
                    subscriptionId(hop.queue),
                    target,
                    hop.rate,
                    RateSource.SAMPLER,
                    hop.asOf,
                    null,
                    hop.asOf != null && fresh.olderThan(hop.asOf, fresh.sampleInterval()),
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    false,
                    List.of()));
        }
    }

    /** Joins each shown concrete address to the shown wildcard addresses it matches, and says what that assumes. */
    private static List<String> drawWildcards(Drawing drawing) {
        List<String> wildcards =
                drawing.addresses.stream().filter(WildcardMatcher::isWildcard).toList();
        boolean drew = false;
        for (String wildcard : wildcards) {
            for (String address : drawing.addresses) {
                if (ANONYMOUS.equals(address) || WildcardMatcher.isWildcard(address)) {
                    continue;
                }
                if (WildcardMatcher.matches(wildcard, address)) {
                    drawing.edge(new FlowEdgeView(
                            "wildcard:" + address + "->" + wildcard,
                            EdgeKind.WILDCARD,
                            destinationId(address),
                            destinationId(wildcard),
                            null,
                            RateSource.NONE,
                            null,
                            null,
                            false,
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            null,
                            false,
                            List.of()));
                    drew = true;
                }
            }
        }
        return drew ? List.of(WILDCARD_ASSUMPTION) : List.of();
    }

    private static void drawFailureRoutes(
            Drawing drawing, Map<String, QueueAgg> queues, String address, EdgeKind kind, NodeRole role) {
        if (address == null || address.isBlank()) {
            return;
        }
        List<String> sources = drawing.queues.stream()
                .filter(name -> {
                    QueueAgg q = queues.get(name);
                    return q.role == null && !address.equals(q.address);
                })
                .toList();
        if (sources.isEmpty()) {
            return;
        }
        drawing.nodes.remove(destinationId(address));
        drawing.node(new FlowNodeView(
                destinationId(address),
                NodeKind.DESTINATION,
                role,
                address,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
        for (String name : sources) {
            drawing.edge(new FlowEdgeView(
                    kind.name().toLowerCase(Locale.ROOT) + ":" + name,
                    kind,
                    subscriptionId(name),
                    destinationId(address),
                    null,
                    RateSource.NONE,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    false,
                    List.of()));
        }
    }

    private static FlowEdgeView bypassed(FlowEdgeView e) {
        return new FlowEdgeView(
                e.id(),
                e.kind(),
                e.source(),
                e.target(),
                e.rate(),
                e.rateSource(),
                e.asOf(),
                e.averagedOverSeconds(),
                e.stale(),
                e.delivery(),
                e.members(),
                e.exclusive(),
                e.filter(),
                e.transformer(),
                true,
                e.presentOn(),
                e.presentOf(),
                e.rahdar(),
                e.faults());
    }

    private static FlowKpis kpis(
            Picture picture, List<FlowBrokerNodeView> brokerNodes, Routing routing, int sampledNodes) {
        Double in = null;
        Double out = null;
        long backlog = 0;
        int faults = 0;
        for (QueueAgg q : picture.queues().values()) {
            if (q.role != null) {
                continue;
            }
            in = plusRate(in, picture.added().get(q.name));
            out = plusRate(out, picture.acked().get(q.name));
            backlog += q.messageCount == null ? 0 : q.messageCount;
            faults += q.noConsumer() ? 1 : 0;
        }
        faults += clientRoutingAndNodeFaults(picture.consumers(), routing, brokerNodes, sampledNodes);
        Set<String> clients = new TreeSet<>(picture.producers().keySet());
        clients.addAll(picture.consumers().keySet());
        return new FlowKpis(in, out, backlog, clients.size(), faults);
    }

    /** A total stays unknown (null) until some subject reports a rate. */
    private static Double plusRate(Double total, SubjectRate rate) {
        if (rate == null) {
            return total;
        }
        return (total == null ? 0 : total) + rate.rate();
    }

    private static int clientRoutingAndNodeFaults(
            Map<String, ClientAgg> consumers, Routing routing, List<FlowBrokerNodeView> brokerNodes, int sampledNodes) {
        int faults = 0;
        for (ClientAgg c : consumers.values()) {
            faults += (int) c.targets.values().stream().filter(t -> t.stalled).count();
        }
        for (RoutingAgg d : routing.diverts.values()) {
            faults += d.nodes.size() < sampledNodes ? 1 : 0;
        }
        for (RoutingAgg b : routing.bridges.values()) {
            faults += b.connectedOn < b.nodes.size() || b.nodes.size() < sampledNodes ? 1 : 0;
        }
        faults += (int) brokerNodes.stream()
                .filter(n -> n.state() != NodeSampleState.OK)
                .count();
        return faults;
    }

    private static FlowBrokerNodeView brokerNode(NodeSample s, String name) {
        NodeSampleState state = state(s.errorKind());
        String message =
                switch (state) {
                    case OK -> null;
                    case UNREACHABLE -> "This node did not answer the latest sweep, so its clients are not shown.";
                    case PERMISSION_DENIED ->
                        "The broker refused to list producers or consumers for Rahdar's user, so this node's clients"
                                + " are not shown.";
                    case COUNTER_UNAVAILABLE ->
                        "This broker does not report the counters a rate needs; its rates are unavailable, not zero.";
                    case ROUTING_UNAVAILABLE ->
                        "Clients on this node are shown, but its diverts, bridges or queue details could not be read"
                                + " (" + s.error() + "), so routing on it may be missing.";
                    case FAILED -> s.error() == null ? "The latest sweep of this node failed." : s.error();
                };
        return new FlowBrokerNodeView(
                s.nodeId().toString(),
                name,
                state,
                message,
                s.sampledAt(),
                s.producersSeen(),
                s.producersTotal(),
                s.consumersSeen(),
                s.consumersTotal(),
                s.producersTotal() > s.producersSeen() || s.consumersTotal() > s.consumersSeen(),
                state == NodeSampleState.PERMISSION_DENIED ? LIST_ACCESS_SNIPPET : null);
    }

    private static NodeSampleState state(String errorKind) {
        if (errorKind == null) {
            return NodeSampleState.OK;
        }
        return switch (errorKind) {
            case "UNREACHABLE", "TLS_FAILED", "WRONG_PATH", "NOT_ARTEMIS" -> NodeSampleState.UNREACHABLE;
            case "CREDENTIALS_REJECTED", "PERMISSION_DENIED" -> NodeSampleState.PERMISSION_DENIED;
            case "COUNTER_UNAVAILABLE" -> NodeSampleState.COUNTER_UNAVAILABLE;
            case "ROUTING_UNAVAILABLE" -> NodeSampleState.ROUTING_UNAVAILABLE;
            default -> NodeSampleState.FAILED;
        };
    }

    /** The label a client is grouped under, falling back through the identity parts the grouping allows. */
    static String label(Edge e, GroupBy groupBy) {
        return switch (groupBy) {
            case CLIENT_ID -> firstNonBlank(e.clientId(), e.user(), e.remoteHost());
            case USER -> firstNonBlank(e.user(), e.remoteHost());
            case HOST -> firstNonBlank(e.remoteHost());
        };
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return UNIDENTIFIED;
    }

    /** Broker-internal addresses and queues, which are not the operator's resources. */
    static boolean internal(String name) {
        return name != null && (name.startsWith("$.") || name.equals("activemq.notifications"));
    }

    /** A divert, address or queue Rahdar's message capture owns (ADR-0079). */
    static boolean captureOwned(String name) {
        return name != null && name.startsWith(RahdarNames.CAPTURE_PREFIX);
    }

    private static FlowDelivery delivery(String routingType) {
        if ("MULTICAST".equalsIgnoreCase(routingType)) {
            return FlowDelivery.COPY;
        }
        return "ANYCAST".equalsIgnoreCase(routingType) ? FlowDelivery.SHARED : null;
    }

    private static Double rate(SubjectRate r) {
        return r == null ? null : r.rate();
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String destinationId(String address) {
        return "destination:" + address;
    }

    private static String subscriptionId(String queue) {
        return "subscription:" + queue;
    }

    private static Map<String, SubjectRate> summed(Map<String, Map<UUID, SubjectRate>> byNode) {
        Map<String, SubjectRate> out = new HashMap<>();
        byNode.forEach((subject, perNode) -> out.put(subject, SubjectRate.sum(perNode.values())));
        return out;
    }

    /**
     * Each drawn resource broken down per broker node (ADR-0110), from the per-node rows the graph is
     * otherwise summed from. A figure a node did not report is null, never 0, and a node whose figures
     * are old — or whose latest sweep failed — is marked stale.
     */
    private static final class Breakdown {
        private final Map<UUID, String> names;
        private final Map<UUID, NodeSampleState> states = new HashMap<>();
        private final Map<String, Map<UUID, SubjectRate>> added;
        private final Map<String, Map<UUID, SubjectRate>> acked;
        private final Freshness fresh;

        Breakdown(
                Map<UUID, String> names,
                List<NodeSample> samples,
                Map<String, Map<UUID, SubjectRate>> added,
                Map<String, Map<UUID, SubjectRate>> acked,
                Freshness fresh) {
            this.names = names;
            this.added = added;
            this.acked = acked;
            this.fresh = fresh;
            samples.forEach(s -> states.put(s.nodeId(), state(s.errorKind())));
        }

        private String name(UUID node) {
            return names.getOrDefault(node, "removed node " + node.toString().substring(0, 8));
        }

        private static Double rateOf(Map<String, Map<UUID, SubjectRate>> rates, String queue, UUID node) {
            SubjectRate r = rates.getOrDefault(queue, Map.of()).get(node);
            return r == null ? null : r.rate();
        }

        /** A sum of per-node counts where a node that gave none leaves the other's count as it is. */
        private static Long plusCount(Long a, Long b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return Long.valueOf(a + b);
        }

        private static Double plus(Double a, Double b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return a + b;
        }

        private List<FlowNodeShare> shares(List<QueueAgg> of) {
            Map<UUID, FlowNodeShare> out = new TreeMap<>();
            for (QueueAgg q : of) {
                for (Map.Entry<UUID, QueueSnapshot> e : q.perNode.entrySet()) {
                    UUID node = e.getKey();
                    QueueSnapshot s = e.getValue();
                    FlowNodeShare prev = out.get(node);
                    Double in = rateOf(added, q.name, node);
                    Double outRate = rateOf(acked, q.name, node);
                    boolean stale = fresh.olderThan(s.ts(), fresh.tierC());
                    out.put(
                            node,
                            prev == null
                                    ? new FlowNodeShare(
                                            node.toString(),
                                            name(node),
                                            s.messageCount(),
                                            s.consumerCount(),
                                            in,
                                            outRate,
                                            stale)
                                    : new FlowNodeShare(
                                            prev.nodeId(),
                                            prev.node(),
                                            plusCount(prev.messageCount(), s.messageCount()),
                                            prev.consumerCount() + s.consumerCount(),
                                            plus(prev.inRate(), in),
                                            plus(prev.outRate(), outRate),
                                            prev.stale() || stale));
                }
            }
            return out.values().stream()
                    .sorted(Comparator.comparing(FlowNodeShare::node))
                    .toList();
        }

        FlowNodeView node(FlowNodeView n, Map<String, QueueAgg> queues) {
            if (n.kind() == NodeKind.SUBSCRIPTION && n.role() != NodeRole.TEMPORARY) {
                QueueAgg q = queues.get(n.id().substring("subscription:".length()));
                return q == null || q.perNode.isEmpty() ? n : n.withByNode(shares(List.of(q)));
            }
            if (n.kind() == NodeKind.DESTINATION && n.role() == null) {
                String address = n.id().substring("destination:".length());
                List<QueueAgg> bound = queues.values().stream()
                        .filter(q -> address.equals(q.address) && q.role == null)
                        .toList();
                return bound.isEmpty() ? n : n.withByNode(shares(bound));
            }
            return n;
        }

        FlowEdgeView edge(FlowEdgeView e, Map<String, ClientAgg> producers, Map<String, ClientAgg> consumers) {
            if (e.kind() != EdgeKind.PRODUCE && e.kind() != EdgeKind.CONSUME) {
                return e;
            }
            boolean producing = e.kind() == EdgeKind.PRODUCE;
            // produce:<label>-><address>, consume:<label>-><queue>
            String rest = e.id().substring(producing ? "produce:".length() : "consume:".length());
            int arrow = rest.lastIndexOf("->");
            ClientAgg client = (producing ? producers : consumers).get(rest.substring(0, arrow));
            TargetAgg target = client == null ? null : client.targets.get(rest.substring(arrow + 2));
            if (target == null || target.perNode.isEmpty()) {
                return e;
            }
            List<FlowNodeRate> rates = target.perNode.entrySet().stream()
                    .map(entry -> {
                        NodeRateAgg r = entry.getValue();
                        boolean failed = states.getOrDefault(entry.getKey(), NodeSampleState.OK) != NodeSampleState.OK;
                        return new FlowNodeRate(
                                entry.getKey().toString(),
                                name(entry.getKey()),
                                r.rate,
                                r.asOf,
                                failed || (r.asOf != null && fresh.olderThan(r.asOf, fresh.sampleInterval())));
                    })
                    .sorted(Comparator.comparing(FlowNodeRate::node))
                    .toList();
            return e.withByNode(rates);
        }

        FlowBrokerNodeView brokerNode(FlowBrokerNodeView b, Map<String, QueueAgg> queues) {
            UUID node = UUID.fromString(b.nodeId());
            Long backlog = null;
            Long consumerTotal = null;
            Double in = null;
            Double out = null;
            for (QueueAgg q : queues.values()) {
                if (q.role != null) {
                    continue;
                }
                QueueSnapshot s = q.perNode.get(node);
                if (s != null) {
                    if (s.backlogKnown()) {
                        backlog = (backlog == null ? 0 : backlog) + s.messageCount();
                    }
                    consumerTotal = (consumerTotal == null ? 0 : consumerTotal) + s.consumerCount();
                }
                in = plus(in, rateOf(added, q.name, node));
                out = plus(out, rateOf(acked, q.name, node));
            }
            return b.withTotals(backlog, consumerTotal, in, out);
        }
    }

    /** The measured picture one read draws from: queues and clients, with the rates attached to queues. */
    private record Picture(
            Map<String, QueueAgg> queues,
            Map<String, ClientAgg> producers,
            Map<String, ClientAgg> consumers,
            Map<String, SubjectRate> added,
            Map<String, SubjectRate> acked) {}

    private record Rates(
            Map<String, SubjectRate> added,
            Map<String, SubjectRate> acked,
            Map<String, Map<UUID, SubjectRate>> addedByNode,
            Map<String, Map<UUID, SubjectRate>> ackedByNode) {}

    private record NodeNames(Map<UUID, String> byId, Map<String, String> byArtemisId) {}

    private record Selection(List<Path> chosen, FlowFocusView focusView, boolean limited) {}

    /** The store-and-forward hops, diverts, bridges and failure addresses the routes add up to. */
    private static final class Routing {
        final Map<String, HopAgg> hops = new TreeMap<>();
        final Map<List<Object>, RoutingAgg> diverts = new LinkedHashMap<>();
        final Map<List<Object>, RoutingAgg> bridges = new LinkedHashMap<>();
        String deadLetter;
        String expiry;

        void add(StoredRoute stored, String nodeName, Set<Layer> layers, boolean capture) {
            Route r = stored.route();
            switch (r.kind()) {
                case STORE_AND_FORWARD -> addHop(stored, nodeName, layers);
                case DIVERT -> addDivert(stored, layers, capture);
                case BRIDGE -> addBridge(stored, layers);
                case DEAD_LETTER -> deadLetter = r.target();
                case EXPIRY -> expiry = r.target();
                default -> {
                    // Temporary and filtered queues are read separately.
                }
            }
        }

        private void addHop(StoredRoute stored, String nodeName, Set<Layer> layers) {
            Route r = stored.route();
            if (layers.contains(Layer.CLUSTER)) {
                hops.computeIfAbsent(r.name(), name -> new HopAgg(name, r.target()))
                        .add(r, stored.sampledAt(), nodeName);
            }
        }

        private void addDivert(StoredRoute stored, Set<Layer> layers, boolean capture) {
            Route r = stored.route();
            if (layers.contains(Layer.ROUTES) && (capture || !captureOwned(r.name()))) {
                diverts.computeIfAbsent(
                                List.of(r.name(), r.source(), r.target(), r.exclusive(), String.valueOf(r.filter())),
                                k -> new RoutingAgg(r))
                        .add(r, stored.sampledAt(), stored.nodeId());
            }
        }

        private void addBridge(StoredRoute stored, Set<Layer> layers) {
            Route r = stored.route();
            if (layers.contains(Layer.ROUTES)) {
                bridges.computeIfAbsent(List.of(r.name(), r.source(), r.target()), k -> new RoutingAgg(r))
                        .add(r, stored.sampledAt(), stored.nodeId());
            }
        }

        /** A store-and-forward queue is a queue like any other, with the hop's rate as its own. */
        void foldHopsInto(Map<String, QueueAgg> queues) {
            for (HopAgg hop : hops.values()) {
                QueueAgg q =
                        queues.computeIfAbsent(hop.queue, name -> new QueueAgg(name, null, NodeRole.STORE_AND_FORWARD));
                q.ownRate = hop.rate;
                q.ownAsOf = hop.asOf;
                q.brokerNodes.addAll(hop.brokerNodes);
            }
        }
    }

    private record Path(String address, String queue) {
        String key() {
            return queue == null ? "a:" + address : "q:" + queue;
        }
    }

    private interface Fresh {
        Duration sampleInterval();

        Duration tierC();

        boolean olderThan(Instant asOf, Duration interval);
    }

    private record Freshness(Instant now, Duration sampleInterval, Duration tierC) implements Fresh {
        @Override
        public boolean olderThan(Instant asOf, Duration interval) {
            return Duration.between(asOf, now).compareTo(interval.multipliedBy(3)) > 0;
        }
    }

    private static final class QueueAgg {
        final String name;
        /** Null for a queue no address routes to in the drawing: store-and-forward or collapsed temporary. */
        final String address;

        final NodeRole role;
        String routingType;
        Long messageCount;
        Long consumerCount;
        Double ownRate;
        Instant ownAsOf;
        final Set<String> brokerNodes = new TreeSet<>();
        final Set<String> temporary = new TreeSet<>();

        QueueAgg(String name, String address) {
            this(name, address == null || address.isBlank() ? null : address, null);
        }

        QueueAgg(String name, String address, NodeRole role) {
            this.name = name;
            this.address = address;
            this.role = role;
        }

        /** Each node's part, for a breakdown (ADR-0110). */
        final Map<UUID, QueueSnapshot> perNode = new HashMap<>();

        void add(QueueSnapshot s, String nodeName) {
            perNode.put(s.nodeId(), s);
            routingType = s.routingType();
            if (s.backlogKnown()) {
                messageCount = (messageCount == null ? 0 : messageCount) + s.messageCount();
            }
            consumerCount = (consumerCount == null ? 0 : consumerCount) + s.consumerCount();
            if (nodeName != null) {
                brokerNodes.add(nodeName);
            }
        }

        boolean noConsumer() {
            return role == null
                    && messageCount != null
                    && messageCount > 0
                    && consumerCount != null
                    && consumerCount == 0;
        }
    }

    private static final class TargetAgg {
        Double rate;
        int members;
        boolean stalled;
        Instant asOf;
        /** Each node's part of the rate, for a breakdown (ADR-0110). */
        final Map<UUID, NodeRateAgg> perNode = new HashMap<>();
    }

    private static final class NodeRateAgg {
        Double rate;
        Instant asOf;

        void add(Double r, Instant sampledAt) {
            if (r != null) {
                rate = (rate == null ? 0 : rate) + r;
            }
            asOf = asOf == null || sampledAt.isAfter(asOf) ? sampledAt : asOf;
        }
    }

    private static final class ClientAgg {
        final String label;
        int members;
        final Set<String> protocols = new TreeSet<>();
        final Set<String> hosts = new TreeSet<>();
        final Set<String> users = new TreeSet<>();
        final Set<String> brokerNodes = new TreeSet<>();
        /** Keyed by address for a producer, by queue for a consumer. */
        final Map<String, TargetAgg> targets = new TreeMap<>();

        ClientAgg(String label) {
            this.label = label;
        }

        void add(StoredEdge stored, String nodeName, String target) {
            Edge e = stored.edge();
            members += e.memberCount();
            addIfPresent(protocols, e.protocol());
            addIfPresent(hosts, e.remoteHost());
            addIfPresent(users, e.user());
            addIfPresent(brokerNodes, nodeName);
            TargetAgg t = targets.computeIfAbsent(target, k -> new TargetAgg());
            if (e.rate() != null) {
                t.rate = (t.rate == null ? 0 : t.rate) + e.rate();
            }
            t.members += e.memberCount();
            t.stalled |= e.stalled();
            t.perNode.computeIfAbsent(stored.nodeId(), k -> new NodeRateAgg()).add(e.rate(), stored.sampledAt());
            t.asOf = t.asOf == null || stored.sampledAt().isAfter(t.asOf) ? stored.sampledAt() : t.asOf;
        }

        private static void addIfPresent(Set<String> set, String value) {
            if (value != null && !value.isBlank()) {
                set.add(value);
            }
        }
    }

    /** A divert or bridge merged across the nodes it is deployed on. */
    private static final class RoutingAgg {
        final Route route;
        final Set<UUID> nodes = new TreeSet<>();
        int connectedOn;
        Double rate;
        Instant asOf;

        RoutingAgg(Route route) {
            this.route = route;
        }

        void add(Route r, Instant sampledAt, UUID nodeId) {
            if (nodes.add(nodeId) && r.connected()) {
                connectedOn++;
            }
            if (r.rate() != null) {
                rate = (rate == null ? 0 : rate) + r.rate();
            }
            asOf = asOf == null || sampledAt.isAfter(asOf) ? sampledAt : asOf;
        }
    }

    /** One store-and-forward queue, toward the node it is named for. */
    private static final class HopAgg {
        final String queue;
        final String targetNodeId;
        final Set<String> brokerNodes = new TreeSet<>();
        Double rate;
        Instant asOf;

        HopAgg(String queue, String targetNodeId) {
            this.queue = queue;
            this.targetNodeId = Objects.requireNonNullElse(targetNodeId, "");
        }

        void add(Route r, Instant sampledAt, String nodeName) {
            if (nodeName != null) {
                brokerNodes.add(nodeName);
            }
            if (r.rate() != null) {
                rate = (rate == null ? 0 : rate) + r.rate();
            }
            asOf = asOf == null || sampledAt.isAfter(asOf) ? sampledAt : asOf;
        }
    }
}
