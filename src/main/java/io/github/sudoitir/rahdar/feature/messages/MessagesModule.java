package io.github.sudoitir.rahdar.feature.messages;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.MessageKind;
import java.util.List;

/** Message browse, send, move and delete; dead letters. Module descriptor (ADR-0070). */
public final class MessagesModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("messages")
            .title("Messages")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .permission(PermissionDef.resource(
                    MessagePermissions.MESSAGE_READ,
                    "Browse messages",
                    ResourceType.SUBSCRIPTION,
                    Permissions.SUBSCRIPTION_READ))
            .permission(PermissionDef.resource(
                    MessagePermissions.MESSAGE_SEND,
                    "Send messages",
                    ResourceType.DESTINATION,
                    Permissions.DESTINATION_READ))
            .permission(PermissionDef.resource(
                    MessagePermissions.MESSAGE_MOVE,
                    "Move or retry messages",
                    ResourceType.SUBSCRIPTION,
                    Permissions.SUBSCRIPTION_READ))
            .permission(PermissionDef.resource(
                    MessagePermissions.MESSAGE_DELETE,
                    "Delete or expire messages",
                    ResourceType.SUBSCRIPTION,
                    Permissions.SUBSCRIPTION_READ))
            .permission(PermissionDef.resource(
                    MessagePermissions.SUBSCRIPTION_PURGE,
                    "Purge subscriptions",
                    ResourceType.SUBSCRIPTION,
                    Permissions.SUBSCRIPTION_READ))
            .apiPrefix("/api/v1/clusters/{clusterId}/dead-letters")
            .mcpTool(new McpToolDef(
                    "browse_messages",
                    McpToolDef.Posture.READ,
                    "Message headers on a subscription, or the full body of one message.",
                    List.of(
                            McpToolDef.Param.note(
                                    "subscription",
                                    "The subscription's id from list_resources, such as artemis.queue/orders."),
                            McpToolDef.Param.note(
                                    "position",
                                    "Omit for a capped page of headers. Give the position from a header row "
                                            + "for that message's full body and properties."),
                            McpToolDef.Param.note("filter", "Broker filter expression, e.g. JMSPriority > 5."))))
            .mcpTool(new McpToolDef(
                    "message_action",
                    McpToolDef.Posture.MUTATE,
                    "Move, retry, delete, expire or purge messages on a subscription.",
                    List.of(
                            McpToolDef.Param.note(
                                    "subscription",
                                    "The subscription's id from list_resources, such as artemis.queue/orders. "
                                            + "confirm must equal its name."),
                            McpToolDef.Param.enumValues("action", MessageAction.values(), "purge is also accepted."),
                            McpToolDef.Param.note(
                                    "positions",
                                    "Comma-separated positions from browse_messages, or use filter instead. move "
                                            + "additionally needs target, the id of the subscription to move into."),
                            McpToolDef.Param.note("dryRun", McpToolDef.DRY_RUN),
                            McpToolDef.Param.note("confirm", McpToolDef.CONFIRM),
                            McpToolDef.Param.note("override", McpToolDef.OVERRIDE))))
            .mcpTool(new McpToolDef(
                    "send_message",
                    McpToolDef.Posture.MUTATE,
                    "Enqueue one message on a subscription. Adds, never removes.",
                    List.of(
                            McpToolDef.Param.note(
                                    "subscription",
                                    "The subscription's id from list_resources, such as artemis.queue/orders."),
                            McpToolDef.Param.enumValues(
                                    "kind", MessageKind.values(), "Default TEXT. BYTES: body is base64."),
                            McpToolDef.Param.note("durable", "Default true."),
                            McpToolDef.Param.note("dryRun", McpToolDef.DRY_RUN))))
            .build();

    private MessagesModule() {}
}
