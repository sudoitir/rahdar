package io.github.sudoitir.rahdar.feature.routing.web;

import io.github.sudoitir.rahdar.feature.routing.RoutingService;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.BridgeView;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.DivertView;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A cluster's routing: its diverts and its bridges.
 *
 * <p>Reading either needs only {@code cluster:read}. Creating and deleting a divert
 * are {@link DivertController}'s, under the Artemis namespace.
 *
 * <p>Bridges are read-only, permanently. Creating or changing one alters how a
 * cluster is wired to other brokers and is invisible to whatever manages that
 * cluster's configuration.
 */
@RestController
@RequestMapping("/clusters/{clusterId}")
@RequiredArgsConstructor
public class RoutingController {

    private final RoutingService routing;

    @GetMapping("/diverts")
    public PagedView<DivertView> diverts(@PathVariable UUID clusterId, ResourceQuery query) {
        return routing.diverts(clusterId, query);
    }

    @GetMapping("/bridges")
    public PagedView<BridgeView> bridges(@PathVariable UUID clusterId, ResourceQuery query) {
        return routing.bridges(clusterId, query);
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
