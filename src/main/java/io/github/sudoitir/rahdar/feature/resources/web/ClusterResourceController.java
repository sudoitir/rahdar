package io.github.sudoitir.rahdar.feature.resources.web;

import io.github.sudoitir.rahdar.feature.resources.BacklogService;
import io.github.sudoitir.rahdar.feature.resources.CrossNodeAggregator;
import io.github.sudoitir.rahdar.feature.resources.PagedListService;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ChannelView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ConnectionView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ConsumerView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.DestinationView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ProducerView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.SubscriptionView;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.platform.broker.BacklogBreakdown.Breakdown;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cross-node resource views (ADR-0017), one list per role. {@code subscriptions} is aggregated from the
 * {@code subscription_snapshot} cache; the rest are live-through fan-outs. All share the
 * {@code ?q=&page=&size=&sort=} envelope. A cluster with no reachable management
 * endpoint surfaces the classified {@code BrokerConnectionException} through
 * {@link io.github.sudoitir.rahdar.kernel.core.web.ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/clusters/{clusterId}")
@RequiredArgsConstructor
public class ClusterResourceController {

    private final CrossNodeAggregator aggregator;
    private final PagedListService pagedList;
    private final BacklogService backlogs;

    @GetMapping("/subscriptions")
    public PagedView<SubscriptionView> subscriptions(@PathVariable UUID clusterId, ResourceQuery query) {
        return aggregator.subscriptions(clusterId, query);
    }

    /** Where a subscription's backlog sits by partition, slowest first; for a partitioned log only. */
    @GetMapping("/subscriptions/{kind}/{path}/backlog")
    public Breakdown backlog(@PathVariable UUID clusterId, @PathVariable String kind, @PathVariable String path) {
        return backlogs.breakdown(clusterId, kind, path);
    }

    @GetMapping("/destinations")
    public PagedView<DestinationView> destinations(@PathVariable UUID clusterId, ResourceQuery query) {
        return pagedList.destinations(clusterId, query);
    }

    @GetMapping("/consumers")
    public PagedView<ConsumerView> consumers(@PathVariable UUID clusterId, ResourceQuery query) {
        return pagedList.consumers(clusterId, query);
    }

    @GetMapping("/channels")
    public PagedView<ChannelView> channels(@PathVariable UUID clusterId, ResourceQuery query) {
        return pagedList.channels(clusterId, query);
    }

    @GetMapping("/connections")
    public PagedView<ConnectionView> connections(@PathVariable UUID clusterId, ResourceQuery query) {
        return pagedList.connections(clusterId, query);
    }

    @GetMapping("/producers")
    public PagedView<ProducerView> producers(@PathVariable UUID clusterId, ResourceQuery query) {
        return pagedList.producers(clusterId, query);
    }

    /** Binds {@code ?q=&page=&size=&sort=} to the shared query envelope. */
    @ModelAttribute
    ResourceQuery resourceQuery(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ResourceQuery.of(q, page, size, sort);
    }
}
