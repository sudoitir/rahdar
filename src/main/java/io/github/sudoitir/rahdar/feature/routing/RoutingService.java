package io.github.sudoitir.rahdar.feature.routing;

import io.github.sudoitir.rahdar.feature.queues.LifecycleKind;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.BridgeNodeCell;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.BridgeView;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.DivertView;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.NodeRef;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceKind;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerXmlSnippets;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import io.github.sudoitir.rahdar.system.artemis.BridgeOperations;
import io.github.sudoitir.rahdar.system.artemis.BridgeRow;
import io.github.sudoitir.rahdar.system.artemis.DivertOperations;
import io.github.sudoitir.rahdar.system.artemis.DivertRow;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final ClusterDirectory nodes;
    private final BrokerConnections connections;
    private final DivertOperations divertOps;
    private final BridgeOperations bridgeOps;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver permissions;
    private final AuditService audit;
    private final ArtemisNodes artemisNodes;

    public PagedView<DivertView> diverts(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireVisible(clusterId);
        ResourceFilter addresses = permissions.filter(clusterId, ResourceKind.ADDRESS);
        List<ClusterNode> serving = servingManageableNodes(clusterId);
        int nodesTotal = serving.size();
        Set<String> ownedByOperator = operatorOwnedDivertNames(clusterId);

        List<DivertRow> rows = fanOut(clusterId, serving, divertOps::listDiverts);

        // Merged on the whole routing shape, not on the name: the same name pointing
        // somewhere else on one node is a divergence, and collapsing it would hide
        // exactly the thing an operator opened this view to find.
        Map<List<String>, List<DivertRow>> merged = new LinkedHashMap<>();
        for (DivertRow row : rows) {
            merged.computeIfAbsent(divertKey(row), k -> new ArrayList<>()).add(row);
        }

        List<DivertView> views = merged.values().stream()
                .map(group -> toDivertView(group, nodesTotal, ownedByOperator))
                .filter(v -> seen(v, addresses))
                .toList();

        // Filtering by address is the question "what touches this address", so the
        // filter covers both ends of the divert as well as its name.
        List<DivertView> filtered = views.stream()
                .filter(v ->
                        query.matches(v.name()) || query.matches(v.address()) || query.matches(v.forwardingAddress()))
                .toList();
        return query.paginate(filtered, Comparator.comparing(DivertView::address, nullSafe()));
    }

    public PagedView<BridgeView> bridges(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireVisible(clusterId);
        ResourceFilter queues = permissions.filter(clusterId, ResourceKind.QUEUE);
        List<ClusterNode> serving = servingManageableNodes(clusterId);
        int nodesTotal = serving.size();

        List<BridgeRow> rows = fanOut(clusterId, serving, bridgeOps::listBridges);

        Map<String, List<BridgeRow>> merged = new LinkedHashMap<>();
        for (BridgeRow row : rows) {
            merged.computeIfAbsent(String.valueOf(row.name()), k -> new ArrayList<>())
                    .add(row);
        }
        List<BridgeView> views = merged.values().stream()
                .map(group -> toBridgeView(group, nodesTotal))
                .filter(v -> queues.readable(v.queueName()))
                .filter(v ->
                        query.matches(v.name()) || query.matches(v.queueName()) || query.matches(v.forwardingAddress()))
                .toList();
        return query.paginate(views, Comparator.comparing(BridgeView::name, nullSafe()));
    }

    /**
     * A divert is seen by a caller who may read both addresses it runs between, since showing it names the
     * other one. A capture's or a plugin's tap forwards into an address Rahdar made, so only its source counts.
     */
    private static boolean seen(DivertView divert, ResourceFilter addresses) {
        String name = divert.name();
        boolean tap = name != null
                && (name.startsWith(RahdarNames.CAPTURE_PREFIX) || name.startsWith(RahdarNames.PLUGIN_TAP_PREFIX));
        return addresses.readable(divert.address()) && (tap || addresses.readable(divert.forwardingAddress()));
    }

    // ---- fan-out ---------------------------------------------------------

    /** What one node contributes to a routing view. */
    @FunctionalInterface
    private interface NodeRead<T> {
        List<T> apply(ArtemisNode client, UUID nodeId, String nodeName);
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
                ArtemisNode client = artemisNodes.on(clusterId, node.getId());
                merged.addAll(read.apply(client, node.getId(), node.getName()));
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

    private static List<String> divertKey(DivertRow row) {
        return Arrays.asList(
                row.uniqueName(),
                row.address(),
                row.forwardingAddress(),
                row.filter(),
                row.routingType(),
                String.valueOf(row.exclusive()));
    }

    private static String operatorOwner(String name, Set<String> ownedByOperator) {
        return ownedByOperator.contains(name) ? OWNER_OPERATOR : null;
    }

    private DivertView toDivertView(List<DivertRow> group, int nodesTotal, Set<String> ownedByOperator) {
        DivertRow first = group.get(0);
        String name = first.uniqueName();
        boolean capture = name != null && name.startsWith(RahdarNames.CAPTURE_PREFIX);
        String owner = capture ? OWNER_CAPTURE : operatorOwner(name, ownedByOperator);
        return new DivertView(
                name,
                first.routingName(),
                first.address(),
                first.forwardingAddress(),
                first.filter(),
                first.routingType(),
                first.transformerClassName(),
                first.exclusive(),
                first.retroactiveResource(),
                owner,
                capture ? captureSubscriptionId(name) : null,
                OWNER_OPERATOR.equals(owner)
                        ? BrokerXmlSnippets.forDivert(
                                name,
                                first.routingName(),
                                first.address(),
                                first.forwardingAddress(),
                                first.exclusive(),
                                first.filter(),
                                first.routingType())
                        : null,
                group.size(),
                nodesTotal,
                group.stream().map(r -> new NodeRef(r.nodeId(), r.nodeName())).toList());
    }

    private static BridgeView toBridgeView(List<BridgeRow> group, int nodesTotal) {
        BridgeRow first = group.get(0);
        return new BridgeView(
                first.name(),
                first.queueName(),
                first.forwardingAddress(),
                first.filterString(),
                first.discoveryGroupName(),
                first.transformerClassName(),
                first.staticConnectors(),
                group.stream().mapToLong(BridgeRow::messagesAcknowledged).sum(),
                group.stream()
                        .mapToLong(BridgeRow::messagesPendingAcknowledgement)
                        .sum(),
                group.stream().anyMatch(BridgeRow::started),
                group.stream().anyMatch(BridgeRow::connected),
                first.useDuplicateDetection(),
                first.highlyAvailable(),
                group.size(),
                nodesTotal,
                group.stream()
                        .map(r -> new BridgeNodeCell(
                                r.nodeId(),
                                r.nodeName(),
                                r.started(),
                                r.connected(),
                                r.messagesAcknowledged(),
                                r.messagesPendingAcknowledgement()))
                        .toList());
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
                LifecycleKind.CREATE_DIVERT.targetType(),
                LifecycleKind.CREATE_DIVERT.auditName(),
                LifecycleKind.DELETE_DIVERT.auditName());
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
