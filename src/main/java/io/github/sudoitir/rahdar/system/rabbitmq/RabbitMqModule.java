package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.system.rabbitmq.mcp.RabbitMqMcpToolDefs;

/** What only RabbitMQ has: its registration, health and the RabbitMQ namespace (ADR-0223). Module descriptor (ADR-0070). */
public final class RabbitMqModule {

    /** The broker SDK's id of the system, which prefixes its kinds. */
    public static final String SYSTEM = "rabbitmq";

    public static final String NAMESPACE_READ = "namespace:read";

    public static final String NAMESPACE_WRITE = "namespace:write";

    public static final String NAMESPACE_DELETE = "namespace:delete";

    public static final String POLICY_READ = "policy:read";

    public static final String POLICY_WRITE = "policy:write";

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id(SYSTEM)
            .title("RabbitMQ")
            .kind(FeatureDescriptor.Kind.PLATFORM)
            .required(true)
            .permission(PermissionDef.cluster(
                    NAMESPACE_READ, "See a RabbitMQ cluster's virtual hosts", Permissions.CLUSTER_READ))
            .permission(PermissionDef.cluster(
                    NAMESPACE_WRITE, "Create and change a RabbitMQ cluster's virtual hosts", NAMESPACE_READ))
            .permission(PermissionDef.cluster(
                    NAMESPACE_DELETE, "Delete a RabbitMQ virtual host and every queue in it", NAMESPACE_READ))
            .permission(
                    PermissionDef.cluster(POLICY_READ, "See a RabbitMQ cluster's policies", Permissions.CLUSTER_READ))
            .permission(PermissionDef.cluster(POLICY_WRITE, "Set and clear a RabbitMQ cluster's policies", POLICY_READ))
            .mcpTools(RabbitMqMcpToolDefs.ALL)
            .build();

    private RabbitMqModule() {}
}
