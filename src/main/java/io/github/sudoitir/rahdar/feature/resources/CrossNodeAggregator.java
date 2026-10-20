package io.github.sudoitir.rahdar.feature.resources;

import static io.github.sudoitir.rahdar.kernel.plugin.ResourceKind.QUEUE;

import io.github.sudoitir.rahdar.feature.resources.QueueViewMapper.QueueKey;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.QueueView;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.security.TeamIndex;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.platform.scrape.ScrapeProperties;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the cross-node queue grid from {@code subscription_snapshot} (ADR-0017). A
 * primary and its synced backup share a NodeID and are one logical node; the
 * scrape only ever writes the live endpoint's rows, so there is no double count.
 * A node whose last sweep is stale keeps its last numbers, flagged — never
 * dropped.
 */
@Service
@RequiredArgsConstructor
public class CrossNodeAggregator {

    private final QueueSnapshots snapshots;
    private final ClusterDirectory nodes;
    private final QueueViewMapper mapper;
    private final ScrapeProperties properties;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver permissions;
    private final TeamIndex teams;

    @Transactional(readOnly = true)
    public PagedView<QueueView> queues(UUID clusterId, ResourceQuery query) {
        ResourceFilter readable = permissions.filter(clusterId, QUEUE);
        List<QueueView> rows = accessibleQueues(clusterId, readable).stream()
                .filter(v -> query.matches(v.queueName()) || query.matches(v.address()))
                .toList();
        return query.paginate(rows, comparatorFor(query.sortField()))
                .map(v -> v.withAccess(
                        readable.allowedActions(v.queueName()),
                        teams.ownerTeamOf(clusterId, ResourceRef.queue(v.queueName()))
                                .orElse(null)));
    }

    /**
     * Every queue in the cluster that the caller may read, rolled up across nodes and unpaged, for a
     * caller acting on behalf of a user.
     *
     * <p>Exists because {@link ResourceQuery} caps a page at 500 rows, which is right for
     * a grid and wrong for a caller that must consider every queue before it can rank
     * them — a consumer-health verdict that skipped the 501st queue could omit the worst
     * one in the cluster and still look complete (ADR-0089).
     */
    @Transactional(readOnly = true)
    public List<QueueView> allQueues(UUID clusterId) {
        return accessibleQueues(clusterId, permissions.filter(clusterId, QUEUE));
    }

    /** The cluster's queues that the caller may read, so a total never counts a hidden one. */
    private List<QueueView> accessibleQueues(UUID clusterId, ResourceFilter readable) {
        clusterAccess.requireVisible(clusterId);
        return rolledUp(clusterId).stream()
                .filter(v -> readable.readable(v.queueName()))
                .toList();
    }

    /**
     * The same roll-up with no access check, for scheduled evaluation.
     *
     * <p>A scrape-driven job runs on its own thread with no authenticated principal, so a
     * permission check there does not protect anything — it simply fails, and
     * {@link ClusterAccessGuard} fails as a 404, which surfaces as "cluster does not
     * exist" about a cluster that plainly does. The alert conditions already read
     * {@code subscription_snapshot} unguarded for exactly this reason; this keeps the guard at
     * the request boundary, where there is a user to check, and off the scheduler path.
     *
     * <p><b>Never call this from a request path.</b> {@link #allQueues} is that entry point.
     */
    @Transactional(readOnly = true)
    public List<QueueView> rollUp(UUID clusterId) {
        return rolledUp(clusterId);
    }

    private List<QueueView> rolledUp(UUID clusterId) {
        List<ClusterNode> nodeRows = nodes.nodes(clusterId);
        Map<UUID, String> nodeNames =
                nodeRows.stream().collect(Collectors.toMap(ClusterNode::getId, ClusterNode::getName));
        int nodesTotal = (int) nodeRows.stream()
                .map(CrossNodeAggregator::logicalKey)
                .distinct()
                .count();
        Instant staleBefore = Instant.now().minus(properties.tierCInterval().multipliedBy(2));

        Map<QueueKey, List<QueueSnapshot>> byKey = snapshots.forCluster(clusterId).stream()
                .collect(Collectors.groupingBy(
                        s -> new QueueKey(s.address(), s.queueName(), s.routingType()),
                        LinkedHashMap::new,
                        Collectors.toList()));

        return byKey.entrySet().stream()
                .map(e -> mapper.toView(e.getKey(), e.getValue(), nodeNames, nodesTotal, staleBefore))
                .toList();
    }

    private static String logicalKey(ClusterNode n) {
        return n.getNativeNodeId() != null ? n.getNativeNodeId() : "id:" + n.getId();
    }

    private static Comparator<QueueView> comparatorFor(String field) {
        if (field == null) {
            return null;
        }
        return switch (field) {
            case "queueName", "name" -> Comparator.comparing(QueueView::queueName, String.CASE_INSENSITIVE_ORDER);
            case "address" -> Comparator.comparing(QueueView::address, String.CASE_INSENSITIVE_ORDER);
            case "depth", "messageCount" -> Comparator.comparingLong(QueueView::totalMessageCount);
            case "consumers", "consumerCount" -> Comparator.comparingLong(QueueView::totalConsumerCount);
            case "delivering" -> Comparator.comparingLong(QueueView::totalDeliveringCount);
            case "scheduled" -> Comparator.comparingLong(QueueView::totalScheduledCount);
            default -> null;
        };
    }
}
