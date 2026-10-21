package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.rahdar.feature.resources.mcp.ResourcesMcpReadTools.ListRole;
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
            .apiPrefix("/api/v1/clusters/{clusterId}/subscriptions")
            .apiPrefix("/api/v1/clusters/{clusterId}/destinations")
            .apiPrefix("/api/v1/clusters/{clusterId}/consumers")
            .apiPrefix("/api/v1/clusters/{clusterId}/channels")
            .apiPrefix("/api/v1/clusters/{clusterId}/connections")
            .apiPrefix("/api/v1/clusters/{clusterId}/producers")
            .apiPrefix("/api/v1/clusters/{clusterId}/resources")
            .apiPrefix("/api/v1/subscriptions")
            .streamTopic(TopicDef.signal("consumers", ResourcePermissions.CONNECTION_READ))
            .streamTopic(TopicDef.signal("channels", ResourcePermissions.CONNECTION_READ))
            .streamTopic(TopicDef.signal("connections", ResourcePermissions.CONNECTION_READ))
            .mcpTool(new McpToolDef(
                    "list_resources",
                    McpToolDef.Posture.READ,
                    "Merged cross-node listing of one role of cluster resource.",
                    List.of(
                            McpToolDef.Param.enumValues(
                                    "role",
                                    ListRole.values(),
                                    "The role a resource plays whatever system it is of. Every row carries its id, "
                                            + "which the other tools take, and its kind and kindLabel."),
                            McpToolDef.Param.note(
                                    "kind",
                                    "Keeps the rows of one kind, such as artemis.queue. Omit for every kind of "
                                            + "the role."))))
            .mcpTool(new McpToolDef(
                    "connection_action",
                    McpToolDef.Posture.MUTATE,
                    "Close a connection, a channel, a consumer's connection, or a destination's consumers.",
                    List.of(
                            McpToolDef.Param.enumValues(
                                    "kind",
                                    ConnectionCloseKind.values(),
                                    "destination closes every consumer connection of a destination."),
                            McpToolDef.Param.note(
                                    "target",
                                    "The id of the connection, channel, consumer or destination, from "
                                            + "list_resources, whose role matches kind. Connection, channel and "
                                            + "consumer ids are node-local: nodeId is required for every kind but "
                                            + "destination, and comes from the same row."),
                            McpToolDef.Param.note(
                                    "kind.ephemeral",
                                    "A close is not idempotent and is never retried. A target that has already "
                                            + "gone answers alreadyGone=true, which is a success — the requested "
                                            + "state holds. Do not re-issue it: the identifier may since have been "
                                            + "reissued to a different application."),
                            McpToolDef.Param.note("dryRun", McpToolDef.DRY_RUN),
                            McpToolDef.Param.note(
                                    "confirm",
                                    "The client id or remote address the preview reported, or the destination's "
                                            + "name for destination — never the opaque connection id. Preview first "
                                            + "to learn it."),
                            McpToolDef.Param.note("override", McpToolDef.OVERRIDE))))
            .mcpTool(new McpToolDef(
                    "resource_lifecycle",
                    McpToolDef.Posture.MUTATE,
                    "Create, update, pause, resume or destroy a subscription or destination, cluster-wide.",
                    List.of(
                            McpToolDef.Param.enumValues("kind", LifecycleKind.values(), null),
                            McpToolDef.Param.note(
                                    "resource",
                                    "The id of the subscription or destination the kind acts on, such as "
                                            + "artemis.queue/orders; for a create, the id of the one to make. "
                                            + "confirm must equal its name."),
                            McpToolDef.Param.shape(
                                    "config",
                                    "{ destination, routingType (ANYCAST|MULTICAST), durable, filter, maxConsumers, "
                                            + "purgeOnNoConsumers, exclusive, nonDestructive, ringSize }",
                                    "create_subscription needs at least destination, the id of the destination "
                                            + "it binds to, and routingType. On update_resource only the fields "
                                            + "you send change; destination, routingType, name and durable "
                                            + "cannot change on a live subscription. create_destination reads "
                                            + "routingType only."),
                            McpToolDef.Param.note(
                                    "kind.fanout",
                                    "A command targets the cluster and fans out to every live node. The result "
                                            + "is a per-node list: a node already in the requested state reports "
                                            + "ALREADY, one that was not live reports SKIPPED_NOT_LIVE, and one whose "
                                            + "Artemis release lacks the operation reports UNSUPPORTED_VERSION, rather "
                                            + "than a failure. Re-running after a partial application converges."),
                            McpToolDef.Param.note(
                                    "disconnectConsumers",
                                    "delete_subscription only, default false. A node where the subscription has "
                                            + "consumers refuses the delete without it; with it they are closed. A "
                                            + "route that forwards into the subscription's destination, where it is "
                                            + "the last subscription, is removed with it, and the preview names it "
                                            + "per node."),
                            McpToolDef.Param.note("dryRun", McpToolDef.DRY_RUN),
                            McpToolDef.Param.note("confirm", McpToolDef.CONFIRM))))
            .build();

    private ResourcesModule() {}
}
