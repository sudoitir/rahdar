package io.github.sudoitir.rahdar.feature.routing;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import java.util.List;

/** Diverts and bridges. Module descriptor (ADR-0070). */
public final class RoutingModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("routing")
            .title("Routing")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .require("resources")
            .apiPrefix("/api/v1/clusters/{clusterId}/routes")
            .apiPrefix("/api/v1/clusters/{clusterId}/artemis/diverts")
            .mcpTool(new McpToolDef(
                    "divert_lifecycle",
                    McpToolDef.Posture.MUTATE,
                    "Create or delete a divert, cluster-wide.",
                    List.of(
                            McpToolDef.Param.enumValues("kind", DivertKind.values(), null),
                            McpToolDef.Param.shape(
                                    "config",
                                    "{ address, forwardingAddress, routingName, exclusive, filter, routingType, "
                                            + "acknowledgeCaptureShadowing }",
                                    "create_divert needs at least address and forwardingAddress; a divert "
                                            + "created this way persists across broker restarts and will not appear "
                                            + "in the broker's own configuration, and there is no update_divert — "
                                            + "change one by deleting it and creating the replacement."),
                            McpToolDef.Param.note(
                                    "kind.fanout",
                                    "A command targets the cluster and fans out to every live node. The result "
                                            + "is a per-node list: a node already in the requested state reports "
                                            + "ALREADY, one that was not live reports SKIPPED_NOT_LIVE, rather "
                                            + "than a failure. Re-running after a partial application converges."),
                            McpToolDef.Param.note("dryRun", McpToolDef.DRY_RUN),
                            McpToolDef.Param.note("confirm", McpToolDef.CONFIRM))))
            .build();

    private RoutingModule() {}
}
