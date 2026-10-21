package io.github.sudoitir.rahdar.feature.triage;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import java.util.List;

/** Cross-feature triage: consumer health over REST, diagnosis over MCP. Module descriptor (ADR-0070). */
public final class TriageModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("triage")
            .title("Triage")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .apiPrefix("/api/v1/clusters/{clusterId}/consumer-health")
            .require("resources")
            .require("metrics")
            .require("events")
            .mcpTool(new McpToolDef(
                    "diagnose",
                    McpToolDef.Posture.READ,
                    "Health of a cluster, or of one subscription end to end. Start here.",
                    List.of(McpToolDef.Param.note(
                            "subscription",
                            "Omit for the cluster: HA role per node, who is live, split-brain, replication "
                                    + "lag, clock skew and firing alerts. Give a subscription's id from "
                                    + "list_resources for that subscription: its consumer-health verdict with the depth, trend, enqueue and acknowledge "
                                    + "rates behind it, plus dead-letter hints and recent events."))))
            .mcpTool(new McpToolDef(
                    "activity_log",
                    McpToolDef.Posture.READ,
                    "Broker events, or Rahdar's own audit trail.",
                    List.of(McpToolDef.Param.values(
                            "source", List.of("broker_events", "audit"), "Default broker_events."))))
            .build();

    private TriageModule() {}
}
