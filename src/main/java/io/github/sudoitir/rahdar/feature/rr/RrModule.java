package io.github.sudoitir.rahdar.feature.rr;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import java.util.List;

/** Request-reply flows, expectations and latency. Module descriptor (ADR-0070). */
public final class RrModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("rr")
            .title("Request-reply tracing")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .permission(PermissionDef.cluster(
                    RrPermissions.RR_WRITE, "Create, change and delete request-reply expectations"))
            .apiPrefix("/api/v1/clusters/{clusterId}/rr")
            .settingKey(RrSettings.DEFAULT_DEADLINE_MS)
            .settingKey(RrSettings.PAYLOAD_CAPTURE_BYTES)
            .settingKey(RrSettings.SWEEP_INTERVAL)
            .settingKey(RrSettings.SAMPLE_INTERVAL)
            .streamTopic(TopicDef.signal("rr", Permissions.DESTINATION_READ))
            .mcpTool(new McpToolDef(
                    "trace_request_reply",
                    McpToolDef.Posture.READ,
                    "Request-reply tracing: flows, latency and timeouts, expectations, or why there are none.",
                    List.of(
                            McpToolDef.Param.values(
                                    "mode", List.of("flows", "stats", "expectations", "diagnostics"), "Default flows."),
                            McpToolDef.Param.note("window", "e.g. 15m. Defaults to 15m."))))
            .build();

    private RrModule() {}
}
