package io.github.sudoitir.rahdar.feature.resources.mcp;

import io.github.sudoitir.rahdar.feature.resources.CrossNodeAggregator;
import io.github.sudoitir.rahdar.feature.resources.PagedListService;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpProperties;
import io.github.sudoitir.rahdar.platform.mcp.McpRouteRows;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The {@code list_resources} MCP tool.
 *
 * <p>Reads only: nothing here needs the dry-run/confirm contract, which is the review
 * boundary ADR-0045 draws between read and mutating tool classes. Every method is a thin
 * adapter over the services the REST layer already uses.
 */
@Component
@RequiredArgsConstructor
public class ResourcesMcpReadTools {

    private static final String PROTOCOL_LABEL = "protocol=";

    private final McpProperties props;
    private final CrossNodeAggregator subscriptions;
    private final PagedListService lists;
    private final ObjectProvider<McpRouteRows> routeRows;

    /**
     * The resource lists behind one discriminator (design D5), by the role a resource plays whatever
     * system it is of. Seven tools would cost seven schemas on every {@code tools/list} to describe what
     * is, to a model, one question with a parameter.
     */
    public enum ListRole {
        DESTINATION,
        SUBSCRIPTION,
        ROUTE,
        CONNECTION,
        CHANNEL,
        CONSUMER,
        PRODUCER
    }

    @McpTool(
            name = "list_resources",
            description = "Merged cross-node listing of one role of cluster resource. Capped and ordered.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult listResources(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String role,
            @McpToolParam(required = false) String kind,
            @McpToolParam(required = false) String filter,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        ListRole r = McpArgs.enumOf(ListRole.class, "role", role, null);
        int capped = props.clamp(limit);
        return McpErrors.guard(() -> resources(id, r, kind, filter, capped));
    }

    private McpViews.Page<McpViews.ResourceRow> resources(
            UUID clusterId, ListRole role, String kind, String filter, int limit) {
        // One row over the cap is fetched so "truncated" is a fact, not a guess: a
        // page of exactly `limit` rows is ambiguous about whether more exist. A kind filter is applied
        // to the rows, so the window it is applied to is the widest one.
        boolean ofKind = kind != null && !kind.isBlank();
        ResourceQuery query = ResourceQuery.of(filter, 1, ofKind ? ResourceQuery.MAX_SIZE : limit + 1, null);
        List<McpViews.ResourceRow> rows = rowsOf(clusterId, role, query);
        List<McpViews.ResourceRow> wanted =
                ofKind ? rows.stream().filter(r -> kind.trim().equals(r.kind())).toList() : rows;
        return McpViews.Page.of(wanted, limit, orderedBy(role));
    }

    private static String orderedBy(ListRole role) {
        return switch (role) {
            case SUBSCRIPTION, DESTINATION -> "name";
            case ROUTE -> "source";
            case CONSUMER -> "subscription";
            case CHANNEL -> "id";
            case CONNECTION -> "remoteAddress";
            case PRODUCER -> "destination";
            default -> throw McpErrors.invalidParams("unsupported role");
        };
    }

    private List<McpViews.ResourceRow> rowsOf(UUID clusterId, ListRole role, ResourceQuery query) {
        return switch (role) {
            // Subscriptions keep their own snapshot-backed path — they are aggregated from
            // subscription_snapshot, not fanned out live, and that is the cheap read.
            case SUBSCRIPTION ->
                subscriptions.subscriptions(clusterId, query).data().stream()
                        .map(q -> new McpViews.ResourceRow(
                                q.id(),
                                q.kind(),
                                q.kindLabel(),
                                q.name(),
                                q.nodesPresent() + "/" + q.nodesTotal() + " nodes",
                                null,
                                "destination=" + q.destination() + " consumers=" + q.totalConsumerCount(),
                                q.totalMessageCount()))
                        .toList();
            case DESTINATION ->
                lists.destinations(clusterId, query).data().stream()
                        .map(a -> new McpViews.ResourceRow(
                                a.id(),
                                a.kind(),
                                a.kindLabel(),
                                a.name(),
                                a.nodeName(),
                                a.nodeId(),
                                "subscriptions=" + a.subscriptionCount(),
                                a.messageCount()))
                        .toList();
            case ROUTE -> {
                McpRouteRows routes = routeRows.getIfAvailable();
                yield routes == null ? List.of() : routes.rows(clusterId, query);
            }
            case CONSUMER ->
                lists.consumers(clusterId, query).data().stream()
                        .map(c -> new McpViews.ResourceRow(
                                c.id(),
                                c.kind(),
                                c.kindLabel(),
                                ResourceIds.nameOf(c.id()),
                                c.nodeName(),
                                c.nodeId(),
                                "subscription=" + c.subscription() + " " + PROTOCOL_LABEL + c.protocol() + " acked="
                                        + c.messagesAcknowledged(),
                                c.messagesDelivered()))
                        .toList();
            case CHANNEL ->
                lists.channels(clusterId, query).data().stream()
                        .map(x -> new McpViews.ResourceRow(
                                x.id(),
                                x.kind(),
                                x.kindLabel(),
                                ResourceIds.nameOf(x.id()),
                                x.nodeName(),
                                x.nodeId(),
                                "user=" + x.user() + " producers=" + x.producerCount(),
                                x.consumerCount()))
                        .toList();
            case CONNECTION ->
                lists.connections(clusterId, query).data().stream()
                        .map(x -> new McpViews.ResourceRow(
                                x.id(),
                                x.kind(),
                                x.kindLabel(),
                                ResourceIds.nameOf(x.id()),
                                x.nodeName(),
                                x.nodeId(),
                                "remote=" + x.remoteAddress() + " " + PROTOCOL_LABEL + x.protocol() + " client="
                                        + x.clientId(),
                                x.channelCount()))
                        .toList();
            case PRODUCER ->
                lists.producers(clusterId, query).data().stream()
                        .map(x -> new McpViews.ResourceRow(
                                x.id(),
                                x.kind(),
                                x.kindLabel(),
                                ResourceIds.nameOf(x.id()),
                                x.nodeName(),
                                x.nodeId(),
                                "destination=" + x.destination() + " " + PROTOCOL_LABEL + x.protocol(),
                                x.messagesSent()))
                        .toList();
            default -> throw McpErrors.invalidParams("unsupported role");
        };
    }
}
