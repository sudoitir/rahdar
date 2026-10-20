package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.rahdar.feature.resources.mcp.ResourcesMcpReadTools.ListKind;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import java.util.List;

/** Cross-node live views and connection control. Module descriptor (ADR-0070). */
public final class ResourcesModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("resources")
            .title("Resources")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .permission(PermissionDef.cluster(
                    ResourcePermissions.CONNECTION_READ, "See client connections, sessions and consumers"))
            .permission(PermissionDef.cluster(
                    ResourcePermissions.CONNECTION_CLOSE,
                    "Close client connections, sessions and an address's consumers",
                    ResourcePermissions.CONNECTION_READ))
            .apiPrefix("/api/v1/clusters/{clusterId}/consumers")
            .apiPrefix("/api/v1/clusters/{clusterId}/sessions")
            .apiPrefix("/api/v1/clusters/{clusterId}/connections")
            .apiPrefix("/api/v1/clusters/{clusterId}/producers")
            .apiPrefix("/api/v1/clusters/{clusterId}/nodes/{nodeId}/connections")
            .apiPrefix("/api/v1/clusters/{clusterId}/nodes/{nodeId}/sessions")
            .apiPrefix("/api/v1/clusters/{clusterId}/nodes/{nodeId}/consumers")
            .apiPrefix("/api/v1/clusters/{clusterId}/addresses/{address}/consumers")
            .streamTopic(TopicDef.signal("consumers", ResourcePermissions.CONNECTION_READ))
            .streamTopic(TopicDef.signal("sessions", ResourcePermissions.CONNECTION_READ))
            .streamTopic(TopicDef.signal("connections", ResourcePermissions.CONNECTION_READ))
            .mcpTool(new McpToolDef(
                    "list_resources",
                    McpToolDef.Posture.READ,
                    "Merged cross-node listing of one kind of cluster resource.",
                    List.of(McpToolDef.Param.enumValues("kind", ListKind.values(), null))))
            .mcpTool(new McpToolDef(
                    "connection_action",
                    McpToolDef.Posture.MUTATE,
                    "Close a connection, a session, a consumer's connection, or an address's consumers.",
                    List.of(
                            McpToolDef.Param.enumValues("kind", ConnectionCloseKind.values(), null),
                            McpToolDef.Param.note(
                                    "target",
                                    "The connection, session or consumer id from list_resources, or the address "
                                            + "for address_consumers. Ids are node-local: nodeId is required for "
                                            + "every kind but address_consumers, and comes from the same row."),
                            McpToolDef.Param.note(
                                    "kind.ephemeral",
                                    "A close is not idempotent and is never retried. A target that has already "
                                            + "gone answers alreadyGone=true, which is a success — the requested "
                                            + "state holds. Do not re-issue it: the identifier may since have been "
                                            + "reissued to a different application."),
                            McpToolDef.Param.note("dryRun", McpToolDef.DRY_RUN),
                            McpToolDef.Param.note(
                                    "confirm",
                                    "The client id or remote address the preview reported, or the address for "
                                            + "address_consumers — never the opaque connection id. Preview first "
                                            + "to learn it."),
                            McpToolDef.Param.note("override", McpToolDef.OVERRIDE))))
            .build();

    private ResourcesModule() {}
}
