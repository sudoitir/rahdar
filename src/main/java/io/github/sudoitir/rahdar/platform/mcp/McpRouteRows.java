package io.github.sudoitir.rahdar.platform.mcp;

import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import java.util.List;
import java.util.UUID;

/**
 * The routes of a cluster as {@code list_resources} rows. The routing feature implements it, so the tool
 * lists routes with the role it lists everything else by while the module that owns routes stays optional:
 * with it disabled the role lists nothing.
 */
public interface McpRouteRows {

    /** The routes the caller may see on the cluster that match the query. */
    List<McpViews.ResourceRow> rows(UUID clusterId, ResourceQuery query);
}
