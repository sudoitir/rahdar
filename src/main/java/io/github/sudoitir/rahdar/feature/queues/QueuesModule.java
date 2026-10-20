package io.github.sudoitir.rahdar.feature.queues;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import java.util.List;

/** Queue and address lifecycle. Module descriptor (ADR-0070). */
public final class QueuesModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("queues")
            .title("Queues")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .permission(PermissionDef.resource(
                    Permissions.SUBSCRIPTION_READ,
                    "See subscriptions and their configuration",
                    ResourceType.SUBSCRIPTION))
            .permission(PermissionDef.resource(
                    Permissions.DESTINATION_READ,
                    "See destinations, their bindings and routes",
                    ResourceType.DESTINATION))
            .permission(PermissionDef.resource(
                    QueuePermissions.DESTINATION_CREATE,
                    "Create destinations",
                    ResourceType.DESTINATION,
                    Permissions.DESTINATION_READ))
            .permission(PermissionDef.resource(
                    QueuePermissions.DESTINATION_DELETE,
                    "Destroy destinations",
                    ResourceType.DESTINATION,
                    Permissions.DESTINATION_READ))
            .permission(PermissionDef.resource(
                    QueuePermissions.SUBSCRIPTION_CREATE,
                    "Create subscriptions",
                    ResourceType.SUBSCRIPTION,
                    Permissions.SUBSCRIPTION_READ))
            .permission(PermissionDef.resource(
                    QueuePermissions.SUBSCRIPTION_DELETE,
                    "Destroy subscriptions",
                    ResourceType.SUBSCRIPTION,
                    Permissions.SUBSCRIPTION_READ))
            .permission(PermissionDef.resource(
                    QueuePermissions.SUBSCRIPTION_UPDATE,
                    "Change a subscription's configuration",
                    ResourceType.SUBSCRIPTION,
                    Permissions.SUBSCRIPTION_READ))
            .permission(PermissionDef.resource(
                    QueuePermissions.SUBSCRIPTION_PAUSE,
                    "Pause and resume subscriptions",
                    ResourceType.SUBSCRIPTION,
                    Permissions.SUBSCRIPTION_READ))
            .permission(PermissionDef.resource(
                    QueuePermissions.ROUTE_WRITE,
                    "Create and delete routes",
                    ResourceType.DESTINATION,
                    Permissions.DESTINATION_READ))
            .apiPrefix("/api/v1/clusters/{clusterId}/queues")
            .apiPrefix("/api/v1/clusters/{clusterId}/addresses")
            .mcpTool(new McpToolDef(
                    "queue_lifecycle",
                    McpToolDef.Posture.MUTATE,
                    "Create, update, pause, resume or destroy a subscription, destination or route, cluster-wide.",
                    List.of(
                            McpToolDef.Param.enumValues("kind", LifecycleKind.values(), null),
                            McpToolDef.Param.shape(
                                    "config",
                                    "{ address, routingType (ANYCAST|MULTICAST), durable, filter, maxConsumers, "
                                            + "purgeOnNoConsumers, exclusive, nonDestructive, ringSize, "
                                            + "forwardingAddress, routingName }",
                                    "create_queue needs at least address and routingType. On update_queue only "
                                            + "the fields you send change; address, routingType, name and durable "
                                            + "cannot change on a live queue. create_address reads routingType only. "
                                            + "create_divert needs at least address and forwardingAddress; a divert "
                                            + "created this way persists across broker restarts and will not appear "
                                            + "in the broker's own configuration, and there is no update_divert — "
                                            + "change one by deleting it and creating the replacement."),
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

    private QueuesModule() {}
}
