package io.github.sudoitir.rahdar.feature.metrics;

import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import java.util.List;

/** Historical metric queries. Module descriptor (ADR-0070). */
public final class MetricsModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("metrics")
            .title("Metrics")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .apiPrefix("/api/v1/clusters/{clusterId}/metrics")
            .mcpTool(new McpToolDef(
                    "metric_series",
                    McpToolDef.Posture.READ,
                    "A server-bucketed timeseries for one metric.",
                    List.of(
                            McpToolDef.Param.values(
                                    "metric",
                                    List.of(
                                            MetricKeys.BACKLOG.name(),
                                            MetricKeys.CONSUMER_COUNT.name(),
                                            MetricKeys.IN_FLIGHT.name(),
                                            MetricKeys.ENQUEUED.name(),
                                            MetricKeys.ACKNOWLEDGED.name(),
                                            MetricKeys.EXPIRED.name()),
                                    null),
                            McpToolDef.Param.note(
                                    "resource", "A subscription's id from list_resources. Omit for the whole cluster."),
                            McpToolDef.Param.note("window", "e.g. 15m, 6h, 2d. Units s, m, h, d. Defaults to 1h."))))
            .build();

    private MetricsModule() {}
}
