package io.github.sudoitir.rahdar.feature.routing;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.RouteNodeCell;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.RouteView;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.BrokerXmlSnippets;
import io.github.sudoitir.rahdar.platform.broker.DivertRoute;
import io.github.sudoitir.rahdar.platform.broker.DivertSettings;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The cross-node routing views — diverts and bridges — read live through to the
 * brokers, on the same pattern as {@code PagedListService} (ADR-0017): one fan-out
 * per serving node, rows merged, filtered, sorted and paged in memory, a node that
 * errors contributing nothing rather than failing the view.
 *
 * <p>Reading costs two round trips per node rather than one, and cannot cost fewer.
 * A divert is a sub-component of its source address, so its object name carries an
 * address that {@code DivertNames} does not report — the first call is a JMX search
 * and the second a batched read. See {@code DivertOperations}.
 *
 * <p>A divert is merged across nodes by name: one divert deployed on three nodes is
 * one row attributed to three nodes, not three rows. The same divert name with a
 * different source or destination on some node is a real divergence, so the merge
 * key is the whole routing shape and such a divert appears as two rows, each naming
 * the nodes it was found on.
 *
 * <p>The rows carry no claim about where a divert came from. Artemis exposes no
 * marker for it and no configured-state source (ADR-0065 D2). Ownership is a
 * separate, honest fact drawn from Rahdar's own records.
 */
@Service
@RequiredArgsConstructor
public class RoutingService {

    /** Ownership Rahdar can assert. Anything else is left unattributed, on purpose. */
    private static final String OWNER_CAPTURE = "MESSAGE_CAPTURE";

    private static final String OWNER_OPERATOR = "OPERATOR";

    private static final String ROUTE_FORWARD = "FORWARD";
    private static final String ROUTE_LINK = "LINK";

    private final ClusterDirectory nodes;
    private final BrokerGateway gateway;
    private final SystemExtensions extensions;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver permissions;
    private final AuditService audit;

    /** A divert and the node it was read from. */
    private record NodeDivert(UUID nodeId, String nodeName, DivertRoute route, boolean retroactive) {}

    /** A bridge and the node it was read from. */
    private record NodeBridge(UUID nodeId, String nodeName, BridgeRoute route) {}

    /** Every route the caller may see: the diverts and the bridges, in one list. */
    public PagedView<RouteView> routes(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireVisible(clusterId);
        List<ClusterNode> serving = servingManageableNodes(clusterId);
        List<RouteView> views = new ArrayList<>(extensions
                .forCluster(clusterId, DivertSettings.class)
                .map(settings -> diverts(clusterId, serving, settings))
                .orElse(List.of()));
        views.addAll(bridges(clusterId, serving));

        // Filtering is the question "what touches this", so the filter covers both ends of a route as well as
        // its name.
        List<RouteView> filtered = views.stream()
                .filter(v -> query.matches(v.name()) || query.matches(v.source()) || query.matches(v.target()))
                .toList();
        return query.paginate(filtered, Comparator.comparing(RouteView::source, nullSafe()));
    }

    private List<RouteView> diverts(UUID clusterId, List<ClusterNode> serving, DivertSettings settings) {
        ResourceFilter destinations = permissions.filter(clusterId, ResourceType.DESTINATION);
        int nodesTotal = serving.size();
        Set<String> ownedByOperator = operatorOwnedDivertNames(clusterId);

        List<NodeDivert> rows = fanOut(clusterId, serving, (target, nodeId, nodeName) -> {
            Set<String> retroactive = settings.retroactiveDiverts(target);
            return DivertRoute.list(target).stream()
                    .map(route -> new NodeDivert(nodeId, nodeName, route, retroactive.contains(route.name())))
                    .toList();
        });

        // Merged on the whole routing shape, not on the name: the same name pointing
        // somewhere else on one node is a divergence, and collapsing it would hide
        // exactly the thing an operator opened this view to find.
        Map<List<String>, List<NodeDivert>> merged = new LinkedHashMap<>();
        for (NodeDivert row : rows) {
            merged.computeIfAbsent(divertKey(row), k -> new ArrayList<>()).add(row);
        }
        return merged.values().stream()
                .filter(group -> seen(group.get(0).route(), destinations))
                .map(group -> toDivertView(clusterId, group, nodesTotal, ownedByOperator))
                .toList();
    }

    private List<RouteView> bridges(UUID clusterId, List<ClusterNode> serving) {
        ResourceFilter subscriptions = permissions.filter(clusterId, ResourceType.SUBSCRIPTION);
        int nodesTotal = serving.size();

        List<NodeBridge> rows = fanOut(
                clusterId,
                serving,
                (target, nodeId, nodeName) -> BridgeRoute.list(target).stream()
                        .map(route -> new NodeBridge(nodeId, nodeName, route))
                        .toList());

        Map<String, List<NodeBridge>> merged = new LinkedHashMap<>();
        for (NodeBridge row : rows) {
            merged.computeIfAbsent(String.valueOf(row.route().name()), k -> new ArrayList<>())
                    .add(row);
        }
        return merged.values().stream()
                .filter(group -> subscriptions.readable(group.get(0).route().queueName()))
                .map(group -> toBridgeView(clusterId, group, nodesTotal))
                .toList();
    }

    /**
     * A divert is seen by a caller who may read both addresses it runs between, since showing it names the
     * other one. A capture's or a plugin's tap forwards into an address Rahdar made, so only its source counts.
     */
    private static boolean seen(DivertRoute divert, ResourceFilter destinations) {
        String name = divert.name();
        boolean tap = name != null
                && (name.startsWith(RahdarNames.CAPTURE_PREFIX) || name.startsWith(RahdarNames.PLUGIN_TAP_PREFIX));
        return destinations.readable(divert.address()) && (tap || destinations.readable(divert.forwardingAddress()));
    }

    // ---- fan-out ---------------------------------------------------------

    /** What one node contributes to a routing view. */
    @FunctionalInterface
    private interface NodeRead<T> {
        List<T> apply(BrokerTarget target, UUID nodeId, String nodeName);
    }

    private <T> List<T> fanOut(UUID clusterId, List<ClusterNode> serving, NodeRead<T> read) {
        if (serving.isEmpty()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "This cluster has no reachable node with a management URL.");
        }
        List<T> merged = new ArrayList<>();
        BrokerConnectionException firstError = null;
        for (ClusterNode node : serving) {
            try {
                merged.addAll(read.apply(gateway.node(clusterId, node.getId()), node.getId(), node.getName()));
            } catch (BrokerConnectionException e) {
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        // A cluster with no diverts at all and a cluster no node answered for are
        // different answers, and only the second is an error.
        if (merged.isEmpty() && firstError != null) {
            throw firstError;
        }
        return merged;
    }

    // ---- merging ---------------------------------------------------------

    private static List<String> divertKey(NodeDivert row) {
        DivertRoute route = row.route();
        return Arrays.asList(
                route.name(),
                route.address(),
                route.forwardingAddress(),
                route.filter(),
                route.routingType(),
                String.valueOf(route.exclusive()));
    }

    private static String operatorOwner(String name, Set<String> ownedByOperator) {
        return ownedByOperator.contains(name) ? OWNER_OPERATOR : null;
    }

    private RouteView toDivertView(
            UUID clusterId, List<NodeDivert> group, int nodesTotal, Set<String> ownedByOperator) {
        DivertRoute first = group.get(0).route();
        String name = first.name();
        boolean capture = name != null && name.startsWith(RahdarNames.CAPTURE_PREFIX);
        String owner = capture ? OWNER_CAPTURE : operatorOwner(name, ownedByOperator);
        ClusterConnection connection = gateway.cluster(clusterId);
        Map<String, String> attributes = new LinkedHashMap<>();
        putIfPresent(attributes, "routingName", first.routingName());
        putIfPresent(attributes, "filter", first.filter());
        putIfPresent(attributes, "routingType", first.routingType());
        putIfPresent(attributes, "transformerClassName", first.transformerClassName());
        attributes.put("exclusive", String.valueOf(first.exclusive()));
        attributes.put("retroactiveResource", String.valueOf(group.get(0).retroactive()));
        if (capture) {
            putIfPresent(
                    attributes,
                    "captureSubscriptionId",
                    Optional.ofNullable(captureSubscriptionId(name))
                            .map(UUID::toString)
                            .orElse(null));
        }
        ResourceId id = DivertRoute.id(name);
        return new RouteView(
                id.format(),
                id.kind().value(),
                ResourceIds.label(id.kind()),
                name,
                ROUTE_FORWARD,
                ResourceIds.of(connection, ResourceClass.DESTINATION, first.address())
                        .format(),
                ResourceIds.of(connection, ResourceClass.DESTINATION, first.forwardingAddress())
                        .format(),
                attributes,
                group.size(),
                nodesTotal,
                group.stream()
                        .map(r -> new RouteNodeCell(r.nodeId(), r.nodeName(), null, null, null, null))
                        .toList(),
                owner,
                OWNER_OPERATOR.equals(owner)
                        ? BrokerXmlSnippets.forDivert(
                                name,
                                first.routingName(),
                                first.address(),
                                first.forwardingAddress(),
                                first.exclusive(),
                                first.filter(),
                                first.routingType())
                        : null);
    }

    private RouteView toBridgeView(UUID clusterId, List<NodeBridge> group, int nodesTotal) {
        BridgeRoute first = group.get(0).route();
        ClusterConnection connection = gateway.cluster(clusterId);
        Map<String, String> attributes = new LinkedHashMap<>();
        putIfPresent(attributes, "forwardingAddress", first.forwardingAddress());
        putIfPresent(attributes, "filter", first.filterString());
        putIfPresent(attributes, "discoveryGroupName", first.discoveryGroupName());
        putIfPresent(attributes, "transformerClassName", first.transformerClassName());
        attributes.put("staticConnectors", String.join(",", first.staticConnectors()));
        attributes.put("useDuplicateDetection", String.valueOf(first.useDuplicateDetection()));
        attributes.put("highlyAvailable", String.valueOf(first.highlyAvailable()));
        attributes.put(
                "started", String.valueOf(group.stream().anyMatch(r -> r.route().started())));
        attributes.put(
                "connected",
                String.valueOf(group.stream().anyMatch(r -> r.route().connected())));
        attributes.put(
                "messagesAcknowledged",
                String.valueOf(group.stream()
                        .mapToLong(r -> r.route().messagesAcknowledged())
                        .sum()));
        attributes.put(
                "messagesPendingAcknowledgement",
                String.valueOf(group.stream()
                        .mapToLong(r -> r.route().messagesPendingAcknowledgement())
                        .sum()));
        ResourceId id = BridgeRoute.id(first.name());
        return new RouteView(
                id.format(),
                id.kind().value(),
                ResourceIds.label(id.kind()),
                first.name(),
                ROUTE_LINK,
                first.queueName() == null
                        ? null
                        : ResourceIds.of(connection, ResourceClass.SUBSCRIPTION, first.queueName())
                                .format(),
                null,
                attributes,
                group.size(),
                nodesTotal,
                group.stream()
                        .map(r -> new RouteNodeCell(
                                r.nodeId(),
                                r.nodeName(),
                                r.route().started(),
                                r.route().connected(),
                                r.route().messagesAcknowledged(),
                                r.route().messagesPendingAcknowledgement()))
                        .toList(),
                null,
                null);
    }

    private static void putIfPresent(Map<String, String> attributes, String key, String value) {
        if (value != null && !value.isBlank()) {
            attributes.put(key, value);
        }
    }

    /**
     * The capture subscription a capture divert serves, from the name Rahdar itself
     * assigned. The last dot-separated segment is the subscription id; a name that
     * does not parse is reported without one rather than with a wrong one.
     */
    private static UUID captureSubscriptionId(String divertName) {
        int lastDot = divertName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == divertName.length() - 1) {
            return null;
        }
        try {
            return UUID.fromString(divertName.substring(lastDot + 1));
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    /**
     * The diverts Rahdar created through the routing screen and has not since
     * deleted, folded from its own audit trail — the only record that survives,
     * because the broker keeps none (ADR-0065 D2).
     *
     * <p>A create counts whatever its outcome, because a fan-out that failed on one
     * node is recorded as failed and still created the divert everywhere else; only a
     * <em>successful</em> delete takes the name back out. Over-inclusion is harmless
     * here — the set is only ever consulted for divert names that actually exist on a
     * broker — where under-inclusion would leave a divert Rahdar created unmarked.
     *
     * <p>What the marking claims is therefore exactly what the audit trail supports:
     * "Rahdar created a divert by this name and has no record of removing it". A
     * divert Rahdar created, someone deleted out of band, and someone else recreated
     * would be attributed to Rahdar wrongly; that is the limit of this evidence, and
     * it is a smaller error than claiming an origin the broker does not record at all.
     */
    private Set<String> operatorOwnedDivertNames(UUID clusterId) {
        return audit.ownedTargetNames(
                clusterId,
                DivertKind.TARGET_TYPE,
                DivertKind.CREATE_DIVERT.auditName(),
                DivertKind.DELETE_DIVERT.auditName());
    }

    // ---- plumbing --------------------------------------------------------

    /** One manageable endpoint per NodeID — the active one when the pair reports one. */
    private List<ClusterNode> servingManageableNodes(UUID clusterId) {
        Map<String, ClusterNode> perNodeId = new LinkedHashMap<>();
        for (ClusterNode n : nodes.nodes(clusterId)) {
            if (n.getManagementUrl() == null) {
                continue;
            }
            String key = n.getNativeNodeId() != null ? n.getNativeNodeId() : "id:" + n.getId();
            perNodeId.merge(key, n, (kept, candidate) -> Boolean.TRUE.equals(candidate.getActive()) ? candidate : kept);
        }
        return List.copyOf(perNodeId.values());
    }

    private static Comparator<String> nullSafe() {
        return Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
    }
}
