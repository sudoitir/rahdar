package io.github.sudoitir.rahdar.system.rabbitmq.mcp;

import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqBindings;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqFederation;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqNodes;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqPermissions;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqPolicies;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqShovels;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqVhosts;
import io.github.sudoitir.rahdar.system.rabbitmq.mcp.RabbitMqMcpViews.Links;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The read tools of a RabbitMQ cluster (ADR-0223): the services the REST layer calls, so the permission checks are
 * theirs, and a cluster of another system is refused by the catalogue before a tool runs (ADR-0207).
 */
@Component
@RequiredArgsConstructor
public class RabbitMqMcpReadTools {

    private static final String CLUSTER_ID = "clusterId";

    private static final String SHOVEL = "shovel";

    private static final String FEDERATION = "federation";

    private final RabbitMqNodes nodes;
    private final RabbitMqVhosts vhosts;
    private final RabbitMqBindings bindings;
    private final RabbitMqPolicies policies;
    private final RabbitMqPermissions permissions;
    private final RabbitMqShovels shovels;
    private final RabbitMqFederation federation;

    @McpTool(
            name = "rabbitmq_nodes",
            description = "A RabbitMQ cluster's nodes with their version, alarms, partitions and listeners.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult rabbitmqNodes(@McpToolParam(required = true) String clusterId) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        return McpErrors.guard(() -> nodes.list(id));
    }

    @McpTool(
            name = "rabbitmq_list_vhosts",
            description = "A page of a RabbitMQ cluster's virtual hosts with their queue and message counts.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult rabbitmqListVhosts(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String cursor,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        return McpErrors.guard(() -> vhosts.list(id, cursor, RabbitMqMcpViews.limit(limit)));
    }

    @McpTool(
            name = "rabbitmq_list_bindings",
            description = "A page of a RabbitMQ cluster's bindings, filtered by virtual host, source or destination.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult rabbitmqListBindings(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String vhost,
            @McpToolParam(required = false) String source,
            @McpToolParam(required = false) String destination,
            @McpToolParam(required = false) String cursor,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        return McpErrors.guard(
                () -> bindings.list(id, vhost, source, destination, cursor, RabbitMqMcpViews.limit(limit)));
    }

    @McpTool(
            name = "rabbitmq_list_policies",
            description = "A page of a RabbitMQ cluster's policies with their pattern, settings and matches.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult rabbitmqListPolicies(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String vhost,
            @McpToolParam(required = false) String cursor,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        return McpErrors.guard(() -> policies.list(id, vhost, cursor, RabbitMqMcpViews.limit(limit)));
    }

    @McpTool(
            name = "rabbitmq_list_permissions",
            description = "A page of a RabbitMQ cluster's users' permissions on its virtual hosts.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult rabbitmqListPermissions(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String vhost,
            @McpToolParam(required = false) String user,
            @McpToolParam(required = false) String cursor,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        return McpErrors.guard(() -> permissions.list(id, vhost, user, cursor, RabbitMqMcpViews.limit(limit)));
    }

    @McpTool(
            name = "rabbitmq_list_shovels",
            description =
                    "A RabbitMQ cluster's shovels and federation links; their addresses never carry a credential.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult rabbitmqListShovels(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String kind,
            @McpToolParam(required = false) String vhost,
            @McpToolParam(required = false) String cursor,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        Integer size = RabbitMqMcpViews.limit(limit);
        String which = kind == null || kind.isBlank() ? null : kind.strip().toLowerCase(Locale.ROOT);
        if (which != null && !SHOVEL.equals(which) && !FEDERATION.equals(which)) {
            throw McpErrors.invalidParams("kind must be shovel or federation.");
        }
        return McpErrors.guard(() -> {
            if (SHOVEL.equals(which)) {
                return RabbitMqMcpViews.shovels(shovels.list(id, vhost, cursor, size));
            }
            if (FEDERATION.equals(which)) {
                return RabbitMqMcpViews.federation(federation.list(id, vhost, cursor, size));
            }
            return new Links(
                    RabbitMqMcpViews.shovels(shovels.list(id, vhost, null, size)),
                    RabbitMqMcpViews.federation(federation.list(id, vhost, null, size)));
        });
    }
}
