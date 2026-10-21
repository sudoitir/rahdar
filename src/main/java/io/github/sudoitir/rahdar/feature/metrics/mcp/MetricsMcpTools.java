package io.github.sudoitir.rahdar.feature.metrics.mcp;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.metrics.MetricQuery;
import io.github.sudoitir.rahdar.feature.metrics.MetricQueryService;
import io.github.sudoitir.rahdar.feature.metrics.web.MetricViews;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpResources;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code metric_series} MCP tool.
 *
 * <p>Reads only: nothing here needs the dry-run/confirm contract, which is the review
 * boundary ADR-0045 draws between read and mutating tool classes. Every method is a thin
 * adapter over the services the REST layer already uses.
 */
@Component
@RequiredArgsConstructor
public class MetricsMcpTools {

    private final MetricQueryService metrics;
    private final McpResources resources;

    @McpTool(
            name = "metric_series",
            description = "A server-bucketed timeseries for one metric, cluster-wide or for one subscription.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult metricSeries(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String metric,
            @McpToolParam(required = false) String resource,
            @McpToolParam(required = false) String window) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        String m = McpArgs.required("metric", metric);
        Duration lookback = McpArgs.window(window);
        return McpErrors.guard(() -> series(id, m, resource, lookback, window));
    }

    private McpViews.MetricSeries series(
            UUID clusterId, String metric, String resource, Duration window, String label) {
        Instant to = Instant.now();
        boolean perResource = resource != null && !resource.isBlank();
        ResourceId subject = perResource
                ? resources
                        .require(clusterId, "resource", resource, ResourceClass.SUBSCRIPTION)
                        .id()
                : null;
        MetricViews.MetricSeriesResponse response = metrics.query(
                clusterId,
                new MetricQuery(
                        List.of(metric),
                        perResource ? ResourceRef.subscription(subject.name()) : null,
                        to.minus(window),
                        to,
                        null,
                        null));
        List<McpViews.MetricPoint> points = response.series().isEmpty()
                ? List.of()
                : response.series().get(0).points().stream()
                        .map(p -> new McpViews.MetricPoint(p.ts(), p.value()))
                        .toList();
        // The step is what the server actually bucketed at, which is not always what
        // the window implies — saying so keeps a model from over-reading resolution.
        return new McpViews.MetricSeries(
                clusterId,
                metric,
                perResource ? subject.format() : "cluster",
                (label == null ? "1h" : label) + " @ " + response.step(),
                points);
    }
}
