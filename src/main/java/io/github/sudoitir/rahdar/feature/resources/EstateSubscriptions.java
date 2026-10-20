package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ClusterRef;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.EstateSubscriptionView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.SubscriptionView;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The subscriptions of every cluster the caller may see, in one list, on the same snapshot read
 * {@link CrossNodeAggregator} builds a cluster's list from. A cluster the caller cannot see contributes
 * nothing, and within a cluster only the subscriptions they may read; a cluster whose subscriptions cannot be
 * read right now is left out rather than failing the list.
 */
@Service
@RequiredArgsConstructor
public class EstateSubscriptions {

    private final ClusterDirectory clusters;
    private final ClusterKinds kinds;
    private final CrossNodeAggregator aggregator;
    private final PermissionResolver permissions;

    public PagedView<EstateSubscriptionView> list(ResourceQuery query, UUID onlyCluster) {
        List<EstateSubscriptionView> rows = new ArrayList<>();
        for (RegisteredCluster cluster : clusters.clusters()) {
            if ((onlyCluster != null && !onlyCluster.equals(cluster.getId()))
                    || !permissions.canSeeCluster(cluster.getId())) {
                continue;
            }
            ClusterRef ref = new ClusterRef(cluster.getId(), cluster.getName(), kinds.systemId(cluster.getId()));
            try {
                for (SubscriptionView subscription : aggregator.allQueues(cluster.getId())) {
                    if (query.matches(subscription.name())
                            || query.matches(subscription.destinationName())
                            || query.matches(ref.name())) {
                        rows.add(new EstateSubscriptionView(ref, subscription));
                    }
                }
            } catch (BrokerConnectionException _) {
                // Its subscriptions cannot be read at the moment; the cluster's own list says why.
            }
        }
        return query.paginate(rows, comparatorFor(query.sortField()));
    }

    private static Comparator<EstateSubscriptionView> comparatorFor(String field) {
        if (field == null) {
            return null;
        }
        return switch (field) {
            case "name" -> Comparator.comparing(v -> v.subscription().name(), String.CASE_INSENSITIVE_ORDER);
            case "cluster" -> Comparator.comparing(v -> v.cluster().name(), String.CASE_INSENSITIVE_ORDER);
            case "destination" ->
                Comparator.comparing(v -> v.subscription().destinationName(), String.CASE_INSENSITIVE_ORDER);
            case "depth", "messageCount" ->
                Comparator.comparingLong(v -> v.subscription().totalMessageCount());
            case "consumers", "consumerCount" ->
                Comparator.comparingLong(v -> v.subscription().totalConsumerCount());
            default -> null;
        };
    }
}
