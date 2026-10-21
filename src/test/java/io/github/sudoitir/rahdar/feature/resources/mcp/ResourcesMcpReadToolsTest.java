package io.github.sudoitir.rahdar.feature.resources.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.resources.CrossNodeAggregator;
import io.github.sudoitir.rahdar.feature.resources.PagedListService;
import io.github.sudoitir.rahdar.platform.mcp.McpProperties;
import io.github.sudoitir.rahdar.platform.mcp.McpRouteRows;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpError;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** {@code list_resources} by role: routes are listed through the one tool, filtered by kind, with their ids. */
class ResourcesMcpReadToolsTest {

    private static final McpViews.ResourceRow DIVERT =
            row("artemis.divert/orders-copy", "artemis.divert", "Divert", "orders-copy");
    private static final McpViews.ResourceRow BRIDGE = row("artemis.bridge/to-hq", "artemis.bridge", "Bridge", "to-hq");

    private final UUID cluster = UUID.randomUUID();
    private final McpRouteRows routes = mock(McpRouteRows.class);

    @SuppressWarnings("unchecked")
    private ResourcesMcpReadTools tools(McpRouteRows provided) {
        ObjectProvider<McpRouteRows> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(provided);
        return new ResourcesMcpReadTools(
                new McpProperties(25, 100), mock(CrossNodeAggregator.class), mock(PagedListService.class), provider);
    }

    private static McpViews.ResourceRow row(String id, String kind, String kindLabel, String name) {
        return new McpViews.ResourceRow(id, kind, kindLabel, name, "1/1 nodes", null, "FORWARD", null);
    }

    @SuppressWarnings("unchecked")
    private McpViews.Page<McpViews.ResourceRow> list(McpRouteRows provided, String role, String kind) {
        return (McpViews.Page<McpViews.ResourceRow>) tools(provided)
                .listResources(cluster.toString(), role, kind, null, null)
                .structuredContent();
    }

    @Test
    void routesOfEveryKindAreListedWithTheirIdsAndKindLabels() {
        when(routes.rows(any(), any())).thenReturn(List.of(DIVERT, BRIDGE));

        McpViews.Page<McpViews.ResourceRow> page = list(routes, "route", null);

        assertThat(page.items()).extracting(McpViews.ResourceRow::id).containsExactly(DIVERT.id(), BRIDGE.id());
        assertThat(page.items()).extracting(McpViews.ResourceRow::kindLabel).containsExactly("Divert", "Bridge");
    }

    @Test
    void aKindKeepsTheRowsOfThatKind() {
        when(routes.rows(any(), any())).thenReturn(List.of(DIVERT, BRIDGE));

        assertThat(list(routes, "route", "artemis.bridge").items()).containsExactly(BRIDGE);
    }

    @Test
    void withoutTheRoutingFeatureTheRouteRoleListsNothing() {
        assertThat(list(null, "route", null).items()).isEmpty();
    }

    @Test
    void anUnknownRoleNamesTheRolesItAccepts() {
        assertThatThrownBy(() -> list(routes, "queues", null))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("role must be one of")
                .hasMessageContaining("subscription")
                .hasMessageContaining("route");
    }
}
