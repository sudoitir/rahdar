package io.github.sudoitir.rahdar.feature.rr.mcp;

import io.github.sudoitir.rahdar.feature.rr.FlowQuery;
import io.github.sudoitir.rahdar.feature.rr.RequestReplyService;
import io.github.sudoitir.rahdar.feature.rr.RrMetrics;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpProperties;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code trace_request_reply} MCP tool. *
 * <p>Reads only: nothing here needs the dry-run/confirm contract, which is the review
 * boundary ADR-0045 draws between read and mutating tool classes. Every method is a thin
 * adapter over the services the REST layer already uses.
 */
@Component
@RequiredArgsConstructor
public class RrMcpTools {

    private final McpProperties props;
    private final RequestReplyService requestReply;
    private final RrMetrics rrMetrics;

    private enum RrMode {
        FLOWS,
        STATS,
        EXPECTATIONS,
        /** Why there are no flows — the sampler's account and the ranked reasons. */
        DIAGNOSTICS
    }

    @McpTool(
            name = "trace_request_reply",
            description = "Request-reply tracing: flows, latency and timeouts, expectations, or diagnostics.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult traceRequestReply(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String mode,
            @McpToolParam(required = false) String address,
            @McpToolParam(required = false) String window,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        RrMode m = McpArgs.enumOf(RrMode.class, "mode", mode, RrMode.FLOWS);
        Duration w = McpArgs.window(window == null ? "15m" : window);
        int capped = props.clamp(limit);
        return McpErrors.guard(() -> switch (m) {
            case FLOWS -> flows(id, address, capped);
            case STATS -> rrMetrics.stats(id, w);
            case EXPECTATIONS -> requestReply.list(id);
            // Answers the question a model otherwise cannot: an empty flow list means
            // "nothing was sent", "nothing could be browsed", or "consumed faster than
            // the sampler ticks", and only this tells them apart.
            case DIAGNOSTICS -> requestReply.diagnostics(id);
        });
    }

    private McpViews.Page<McpViews.FlowRow> flows(UUID clusterId, String address, int limit) {
        PagedView<RrViews.FlowView> page =
                requestReply.flowPage(clusterId, new FlowQuery(null, address, null, null, null, 1, limit + 1));
        List<McpViews.FlowRow> rows = page.data().stream()
                .map(f -> new McpViews.FlowRow(
                        f.correlationId(),
                        f.requestDestination(),
                        f.replyDestination(),
                        f.requestedAt(),
                        f.latencyMs(),
                        f.state()))
                .toList();
        return McpViews.Page.of(rows, limit, "requestedAt desc");
    }
}
