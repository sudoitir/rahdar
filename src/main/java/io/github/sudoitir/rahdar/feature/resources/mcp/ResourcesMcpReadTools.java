package io.github.sudoitir.rahdar.feature.resources.mcp;

import io.github.sudoitir.rahdar.feature.resources.CrossNodeAggregator;
import io.github.sudoitir.rahdar.feature.resources.PagedListService;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpProperties;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code list_resources} MCP tool. *
 * <p>Reads only: nothing here needs the dry-run/confirm contract, which is the review
 * boundary ADR-0045 draws between read and mutating tool classes. Every method is a thin
 * adapter over the services the REST layer already uses.
 */
@Component
@RequiredArgsConstructor
public class ResourcesMcpReadTools {

    private static final String PROTOCOL_LABEL = "protocol=";

    private final McpProperties props;
    private final CrossNodeAggregator queues;
    private final PagedListService lists;

    /**
     * The six resource lists behind one discriminator (design D5). Six tools would
     * cost six schemas on every {@code tools/list} to describe what is, to a model,
     * one question with a parameter.
     */
    public enum ListKind {
        QUEUES,
        ADDRESSES,
        CONSUMERS,
        SESSIONS,
        CONNECTIONS,
        PRODUCERS
    }

    @McpTool(
            name = "list_resources",
            description = "Merged cross-node listing of one kind of cluster resource. Capped and ordered.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult listResources(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String kind,
            @McpToolParam(required = false) String filter,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        ListKind k = McpArgs.enumOf(ListKind.class, "kind", kind, null);
        int capped = props.clamp(limit);
        return McpErrors.guard(() -> resources(id, k, filter, capped));
    }

    private McpViews.Page<McpViews.ResourceRow> resources(UUID clusterId, ListKind kind, String filter, int limit) {
        // One row over the cap is fetched so "truncated" is a fact, not a guess: a
        // page of exactly `limit` rows is ambiguous about whether more exist.
        ResourceQuery query = ResourceQuery.of(filter, 1, limit + 1, null);
        List<McpViews.ResourceRow> rows = new ArrayList<>();
        String orderedBy;
        switch (kind) {
            case QUEUES -> {
                orderedBy = "queueName";
                // Queues keep their own snapshot-backed path — they are aggregated from
                // subscription_snapshot, not fanned out live, and that is the cheap read.
                for (ResourceViews.QueueView q : queues.queues(clusterId, query).data()) {
                    rows.add(new McpViews.ResourceRow(
                            "queue",
                            q.queueName(),
                            q.nodesPresent() + "/" + q.nodesTotal() + " nodes",
                            "address=" + q.address() + " consumers=" + q.totalConsumerCount(),
                            q.totalMessageCount()));
                }
            }
            case ADDRESSES -> {
                orderedBy = "name";
                for (ResourceViews.AddressView a :
                        lists.addresses(clusterId, query).data()) {
                    rows.add(new McpViews.ResourceRow(
                            "address", a.name(), a.nodeName(), "queues=" + a.queueCount(), a.messageCount()));
                }
            }
            case CONSUMERS -> {
                orderedBy = "queueName";
                for (ResourceViews.ConsumerView c :
                        lists.consumers(clusterId, query).data()) {
                    rows.add(new McpViews.ResourceRow(
                            "consumer",
                            c.queueName(),
                            c.nodeName(),
                            PROTOCOL_LABEL + c.protocol() + " acked=" + c.messagesAcknowledged(),
                            c.messagesDelivered()));
                }
            }
            case SESSIONS -> {
                orderedBy = "sessionId";
                for (ResourceViews.SessionView x :
                        lists.sessions(clusterId, query).data()) {
                    rows.add(new McpViews.ResourceRow(
                            "session",
                            x.sessionId(),
                            x.nodeName(),
                            "user=" + x.user() + " producers=" + x.producerCount(),
                            x.consumerCount()));
                }
            }
            case CONNECTIONS -> {
                orderedBy = "remoteAddress";
                for (ResourceViews.ConnectionView x :
                        lists.connections(clusterId, query).data()) {
                    rows.add(new McpViews.ResourceRow(
                            "connection",
                            x.remoteAddress(),
                            x.nodeName(),
                            PROTOCOL_LABEL + x.protocol() + " client=" + x.clientId(),
                            x.sessionCount()));
                }
            }
            case PRODUCERS -> {
                orderedBy = "address";
                for (ResourceViews.ProducerView x :
                        lists.producers(clusterId, query).data()) {
                    rows.add(new McpViews.ResourceRow(
                            "producer", x.address(), x.nodeName(), PROTOCOL_LABEL + x.protocol(), x.messagesSent()));
                }
            }
            default -> throw McpErrors.invalidParams("unsupported kind");
        }
        return McpViews.Page.of(rows, limit, orderedBy);
    }
}
