package io.github.sudoitir.rahdar.feature.routing.mcp;

import io.github.sudoitir.rahdar.feature.routing.RoutingService;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.RouteView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.platform.mcp.McpRouteRows;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The routes {@code list_resources} lists, read from the same service the routes API uses. */
@Component
@RequiredArgsConstructor
public class RouteMcpRows implements McpRouteRows {

    private final RoutingService routing;

    @Override
    public List<McpViews.ResourceRow> rows(UUID clusterId, ResourceQuery query) {
        return routing.routes(clusterId, query).data().stream()
                .map(RouteMcpRows::row)
                .toList();
    }

    private static McpViews.ResourceRow row(RouteView r) {
        return new McpViews.ResourceRow(
                r.id(),
                r.kind(),
                r.kindLabel().singular(),
                r.name(),
                r.nodesPresent() + "/" + r.nodesTotal() + " nodes",
                null,
                r.routeClass() + " source=" + r.source() + " target=" + r.target(),
                null);
    }
}
