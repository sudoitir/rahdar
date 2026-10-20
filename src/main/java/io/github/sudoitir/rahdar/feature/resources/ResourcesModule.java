package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.rahdar.feature.resources.mcp.ResourcesMcpReadTools.ListKind;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import java.util.List;

/** Cross-node live views, connection control and queue and address lifecycle. Module descriptor (ADR-0070). */
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
            .permission(PermissionDef.resource(
                    Permissions.SUBSCRIPTION_READ,
                    "See subscriptions and their configuration",
                    ResourceType.SUBSCRIPTION))
            .permission(PermissionDef.resource(
                    Permissions.DESTINATION_READ,
                    "See destinations, their bindings and routes",
                    ResourceType.DESTINATION))
            .permission(PermissionDef.resource(
                    ResourcePermissions.DESTINATION_CREATE,
                    "Create destinations",
                    ResourceType.DESTINATION,
                    Permissions.DESTINATION_READ))
            .permission(PermissionDef.resource(
                    ResourcePermissions.DESTINATION_DELETE,
                    "Destroy destinations",
                    ResourceType.DESTINATION,
                    Permissions.DESTINATION_READ))
            .permission(PermissionDef.resource(
                    ResourcePermissions.SUBSCRIPTION_CREATE,
                    "Create subscriptions",
                    ResourceType.SUBSCRIPTION,
                    Permissions.SUBSCRIPTION_READ))
            .permission(PermissionDef.resource(
                    ResourcePermissions.SUBSCRIPTION_DELETE,
                    "Destroy subscriptions",
                    ResourceType.SUBSCRIPTION,
                    Permissions.SUBSCRIPTION_READ))
            .permission(PermissionDef.resource(
                    ResourcePermissions.SUBSCRIPTION_UPDATE,
                    "Change a subscription's configuration",
                    ResourceType.SUBSCRIPTION,
                    Permissions.SUBSCRIPTION_READ))
            .permission(PermissionDef.resource(
                    ResourcePermissions.SUBSCRIPTION_PAUSE,
                    "Pause and resume subscriptions",
                    ResourceType.SUBSCRIPTION,
                    Permissions.SUBSCRIPTION_READ))
            .permission(PermissionDef.resource(
                    ResourcePermissions.ROUTE_WRITE,
                    "Create and delete routes",
                    ResourceType.DESTINATION,
                    Permissions.DESTINATION_READ))
            .apiPrefix("/api/v1/clusters/{clusterId}/queues")
            .apiPrefix("/api/v1/clusters/{clusterId}/addresses")
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
            .mcpTool(new McpToolDef(
                    "queue_lifecycle",
                    McpToolDef.Posture.MUTATE,
                    "Create, update, pause, resume or destroy a subscription or destination, cluster-wide.",
                    List.of(
                            McpToolDef.Param.enumValues("kind", LifecycleKind.values(), null),
                            McpToolDef.Param.shape(
                                    "config",
                                    "{ address, routingType (ANYCAST|MULTICAST), durable, filter, maxConsumers, "
                                            + "purgeOnNoConsumers, exclusive, nonDestructive, ringSize }",
                                    "create_queue needs at least address and routingType. On update_queue only "
                                            + "the fields you send change; address, routingType, name and durable "
                                            + "cannot change on a live queue. create_address reads routingType only."),
                            McpToolDef.Param.note(
                                    "kind.fanout",
                                    "A command targets the cluster and fans out to every live node. The result "
                                            + "is a per-node list: a node already in the requested state reports "
                                            + "ALREADY, one that was not live reports SKIPPED_NOT_LIVE, and one whose "
                                            + "Artemis release lacks the operation reports UNSUPPORTED_VERSION, rather "
                                            + "than a failure. Re-running after a partial application converges."),
                            McpToolDef.Param.note(
                                    "disconnectConsumers",
                                    "delete_queue only, default false. A node where the queue has consumers "
                                            + "refuses the delete without it; with it they are closed. A divert "
                                            + "that forwards into the queue's address, where it is the last queue, "
                                            + "is removed with it, and the preview names it per node."),
                            McpToolDef.Param.note("dryRun", McpToolDef.DRY_RUN),
                            McpToolDef.Param.note("confirm", McpToolDef.CONFIRM))))
            .build();

    private ResourcesModule() {}
}
