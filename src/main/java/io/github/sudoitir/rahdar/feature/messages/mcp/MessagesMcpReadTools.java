package io.github.sudoitir.rahdar.feature.messages.mcp;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.rahdar.feature.messages.MessageService;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpProperties;
import io.github.sudoitir.rahdar.platform.mcp.McpResources;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code browse_messages} MCP tool.
 *
 * <p>Reads only: nothing here needs the dry-run/confirm contract, which is the review
 * boundary ADR-0045 draws between read and mutating tool classes. Every method is a thin
 * adapter over the services the REST layer already uses.
 */
@Component
@RequiredArgsConstructor
public class MessagesMcpReadTools {

    private final McpProperties props;
    private final MessageService messages;
    private final McpResources resources;

    /**
     * Headers, or one body, behind an optional id (ADR-0054).
     *
     * <p>The body was always the drill-down from a header row: same cluster, same
     * subscription, one more identifier. Two tools made a model choose between them before
     * it had the id that distinguishes them.
     */
    @McpTool(
            name = "browse_messages",
            description = "A capped page of message headers on a subscription, or one message's full body by id.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult browseMessages(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String subscription,
            @McpToolParam(required = false) String position,
            @McpToolParam(required = false) String filter,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        McpArgs.required("subscription", subscription);
        long mid = 0;
        boolean body = position != null && !position.isBlank();
        if (body) {
            try {
                mid = Long.parseLong(position.trim());
            } catch (NumberFormatException _) {
                throw McpErrors.invalidParams("position must be the numeric id a header row returned.");
            }
        }
        long detailId = mid;
        int capped = props.clamp(limit);
        return McpErrors.guard(() -> {
            String name = resources
                    .require(id, "subscription", subscription, ResourceClass.SUBSCRIPTION)
                    .name();
            return body ? messages.detail(id, name, detailId, null, null) : headers(id, name, filter, capped);
        });
    }

    private McpViews.Page<McpViews.MessageHeader> headers(
            UUID clusterId, String subscription, String filter, int limit) {
        MessageViews.MessagePageView page = messages.browse(clusterId, subscription, null, filter, 1, limit + 1);
        List<McpViews.MessageHeader> rows = page.data().stream()
                .map(m -> new McpViews.MessageHeader(
                        m.position(),
                        subscription,
                        page.node().toString(),
                        m.timestamp() > 0 ? Instant.ofEpochMilli(m.timestamp()) : null,
                        m.size(),
                        String.valueOf(m.type()),
                        m.correlationId()))
                .toList();
        return McpViews.Page.of(rows, limit, "broker order");
    }
}
