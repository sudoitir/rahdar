package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.feature.resources.ResourceViewMapper.NodeRef;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ChannelView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ConnectionView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ConsumerView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.DestinationView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ProducerView;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.security.TeamIndex;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Live-through fan-out for the five point-in-time resource views (destinations,
 * consumers, channels, connections, producers) — ADR-0017.
 *
 * <p>One list per serving node (the whole list, never a page), rows tagged
 * with their logical node, then merged / filtered / sorted / paged in memory. A
 * node that errors contributes nothing and is reflected only by a shorter list —
 * it is not an error unless <em>every</em> node failed, in which case the first
 * classified failure is rethrown for the UI to render (capability ledger +
 * {@code broker.xml} advice, non-negotiable #5).
 *
 * <p>A row the caller may not read is dropped before the text filter, the sort, the page and the count,
 * so a total never includes a hidden row. A consumer is read through its subscription and a producer through its
 * destination; a channel or connection is read by a caller who holds {@code connection:read} on the cluster,
 * and otherwise only through the consumers and producers they may read, and shows only those.
 */
@Service
@RequiredArgsConstructor
public class PagedListService {

    private final ClusterDirectory nodes;
    private final ResourceViewMapper mapper;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver permissions;
    private final TeamIndex teams;
    private final BrokerGateway gateway;

    public PagedView<DestinationView> destinations(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireVisible(clusterId);
        ResourceFilter readable = permissions.filter(clusterId, ResourceType.DESTINATION);
        List<DestinationView> rows = gather(clusterId, ResourceClass.DESTINATION, mapper::destination).stream()
                .filter(a -> readable.readable(a.name()))
                .toList();
        return page(rows, query, List.of(DestinationView::name), nameComparator())
                .map(a -> a.withAccess(
                        readable.allowedActions(a.name()),
                        teams.ownerTeamOf(clusterId, ResourceRef.destination(a.name()))
                                .orElse(null)));
    }

    public PagedView<ConsumerView> consumers(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireVisible(clusterId);
        List<ConsumerView> rows = readableConsumers(clusterId);
        List<String> actions = connectionActions(clusterId);
        return page(
                        rows,
                        query,
                        // The fields other views link a consumer by: its subscription, and the channel it runs in.
                        List.of(ConsumerView::subscription, ConsumerView::channel),
                        Comparator.comparing(ConsumerView::subscription, nullSafe()))
                .map(c -> c.withAllowedActions(actions));
    }

    public PagedView<ChannelView> channels(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireVisible(clusterId);
        List<String> actions = connectionActions(clusterId);
        return page(
                        visibleChannels(clusterId),
                        query,
                        List.of(ChannelView::id, ChannelView::connection, ChannelView::user),
                        Comparator.comparing(ChannelView::id, nullSafe()))
                .map(s -> s.withAllowedActions(actions));
    }

    public PagedView<ConnectionView> connections(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireVisible(clusterId);
        List<String> actions = connectionActions(clusterId);
        return page(
                        visibleConnections(clusterId),
                        query,
                        // A flow client is named by its client id; a channel names its connection by id.
                        List.of(ConnectionView::remoteAddress, ConnectionView::clientId, ConnectionView::id),
                        Comparator.comparing(ConnectionView::remoteAddress, nullSafe()))
                .map(c -> c.withAllowedActions(actions));
    }

    public PagedView<ProducerView> producers(UUID clusterId, ResourceQuery query) {
        clusterAccess.requireVisible(clusterId);
        List<ProducerView> rows = readableProducers(clusterId);
        return page(
                rows,
                query,
                List.of(ProducerView::destination, ProducerView::clientId, ProducerView::channel),
                Comparator.comparing(ProducerView::destination, nullSafe()));
    }

    // ---- what the caller may see ---------------------------------------------------------

    /** A consumer is seen through its subscription. */
    private List<ConsumerView> readableConsumers(UUID clusterId) {
        ResourceFilter readable = permissions.filter(clusterId, ResourceType.SUBSCRIPTION);
        return gather(clusterId, ResourceClass.CONSUMER, mapper::consumer).stream()
                .filter(c -> readable.readable(c.subscriptionName()))
                .toList();
    }

    /** A producer is seen through its destination. */
    private List<ProducerView> readableProducers(UUID clusterId) {
        ResourceFilter readable = permissions.filter(clusterId, ResourceType.DESTINATION);
        return gather(clusterId, ResourceClass.PRODUCER, mapper::producer).stream()
                .filter(p -> readable.readable(p.destinationName()))
                .toList();
    }

    /**
     * A channel is seen by a caller who reads connections of the cluster, and otherwise only through the
     * consumers and producers of it that they may read, counting no others.
     */
    private List<ChannelView> visibleChannels(UUID clusterId) {
        List<ChannelView> all = gather(clusterId, ResourceClass.CHANNEL, mapper::channel);
        if (permissions.can(clusterId, ResourcePermissions.CONNECTION_READ)) {
            return all;
        }
        Map<ChannelKey, Long> consumers = readableConsumers(clusterId).stream()
                .collect(Collectors.groupingBy(c -> new ChannelKey(c.nodeId(), c.channel()), Collectors.counting()));
        Map<ChannelKey, Long> producers = readableProducers(clusterId).stream()
                .collect(Collectors.groupingBy(p -> new ChannelKey(p.nodeId(), p.channel()), Collectors.counting()));
        return all.stream()
                .map(s -> {
                    ChannelKey key = new ChannelKey(s.nodeId(), s.id());
                    return s.trimmedTo(consumers.getOrDefault(key, 0L), producers.getOrDefault(key, 0L));
                })
                .filter(s -> s.consumerCount() + s.producerCount() > 0)
                .toList();
    }

    /** A connection is seen by a caller who reads connections, and otherwise through the channels they see. */
    private List<ConnectionView> visibleConnections(UUID clusterId) {
        List<ConnectionView> all = gather(clusterId, ResourceClass.CONNECTION, mapper::connection);
        if (permissions.can(clusterId, ResourcePermissions.CONNECTION_READ)) {
            return all;
        }
        Map<ConnectionKey, Long> channels = visibleChannels(clusterId).stream()
                .collect(Collectors.groupingBy(
                        s -> new ConnectionKey(s.nodeId(), s.connection()), Collectors.counting()));
        return all.stream()
                .map(c -> c.trimmedTo(channels.getOrDefault(new ConnectionKey(c.nodeId(), c.id()), 0L)))
                .filter(c -> c.channelCount() > 0)
                .toList();
    }

    /** What a caller may do to a connection, a channel or a consumer's connection: it is a cluster-wide right. */
    private List<String> connectionActions(UUID clusterId) {
        return permissions.can(clusterId, ResourcePermissions.CONNECTION_CLOSE)
                ? List.of(ResourcePermissions.CONNECTION_CLOSE)
                : List.of();
    }

    private record ChannelKey(UUID nodeId, String channel) {}

    private record ConnectionKey(UUID nodeId, String connection) {}

    // ---- fan-out ---------------------------------------------------------------------------

    /** The text filter, then sort, page and count, on rows the caller may already see. */
    private static <T> PagedView<T> page(
            List<T> visible, ResourceQuery query, List<Function<T, String>> filterFields, Comparator<T> comparator) {
        List<T> filtered = visible.stream()
                .filter(row -> filterFields.stream().anyMatch(field -> query.matches(field.apply(row))))
                .toList();
        return query.paginate(filtered, comparator);
    }

    /** One listed resource of a node → its view. */
    @FunctionalInterface
    private interface RowMapper<T> {
        T map(Resource resource, NodeRef node, ClusterConnection connection);
    }

    /** Every node's rows of one kind, merged and tagged with their node; unfiltered, so never returned as is. */
    private <T> List<T> gather(UUID clusterId, ResourceClass kind, RowMapper<T> rowMapper) {
        List<ClusterNode> servingNodes = servingManageableNodes(clusterId);
        if (servingNodes.isEmpty()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "This cluster has no reachable node with a management URL.");
        }

        ClusterConnection connection = gateway.cluster(clusterId);
        List<T> merged = new ArrayList<>();
        BrokerConnectionException firstError = null;
        for (ClusterNode node : servingNodes) {
            try {
                NodeRef ref = new NodeRef(node.getId(), node.getName());
                fetch(clusterId, node, kind).forEach(resource -> merged.add(rowMapper.map(resource, ref, connection)));
            } catch (BrokerConnectionException e) {
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        if (merged.isEmpty() && firstError != null) {
            throw firstError;
        }
        return merged;
    }

    /**
     * One node's full list of one kind, shared by every request for it within {@link #FRESH}.
     *
     * <p>Every open tab re-reads these views on each stream signal, and each read is a node's
     * whole list. Concurrent and near-simultaneous reads of the same list therefore wait for
     * one broker call rather than each making their own. A failed read is not kept, so the
     * next request tries again.
     */
    private List<Resource> fetch(UUID clusterId, ClusterNode node, ResourceClass kind) {
        ListKey key = new ListKey(clusterId, node.getId(), kind);
        long now = System.nanoTime();
        CompletableFuture<List<Resource>> mine = new CompletableFuture<>();
        RecentList winner = recent.compute(
                key,
                (k, current) -> current != null && current.freshUntil() > now
                        ? current
                        : new RecentList(mine, now + FRESH.toNanos()));
        if (winner.page() == mine) {
            try {
                mine.complete(list(clusterId, node, kind));
            } catch (RuntimeException e) {
                recent.remove(key, winner);
                mine.completeExceptionally(e);
            }
        }
        try {
            return winner.page().join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }
    }

    /** One node's whole list of one class, failing as Rahdar's broker exceptions. */
    private List<Resource> list(UUID clusterId, ClusterNode node, ResourceClass kind) {
        try {
            return gateway.cluster(clusterId)
                    .resources()
                    .read(io.github.sudoitir.openbroker.model.ResourceQuery.of(kind)
                            .in(Scope.node(BrokerGateway.idOf(node.getId()))))
                    .orThrow()
                    .items();
        } catch (BrokerException e) {
            throw BrokerFailures.translate(e);
        }
    }

    /** How long a node's list is shared between requests. Short enough that a view is never visibly stale. */
    private static final Duration FRESH = Duration.ofSeconds(2);

    private record ListKey(UUID clusterId, UUID nodeId, ResourceClass kind) {}

    private record RecentList(CompletableFuture<List<Resource>> page, long freshUntil) {}

    /** At most one entry per (cluster, node, kind), so bounded by the estate, not by traffic. */
    private final Map<ListKey, RecentList> recent = new ConcurrentHashMap<>();

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

    private static Comparator<DestinationView> nameComparator() {
        return Comparator.comparing(DestinationView::name, nullSafe());
    }

    private static Comparator<String> nullSafe() {
        return Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
    }
}
