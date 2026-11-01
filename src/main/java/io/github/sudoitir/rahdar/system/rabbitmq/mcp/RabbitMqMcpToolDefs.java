package io.github.sudoitir.rahdar.system.rabbitmq.mcp;

import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef.Param;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef.Posture;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqModule;
import java.util.List;

/**
 * The catalogue entries of the RabbitMQ tools (ADR-0054, ADR-0207): what each does and the detail its schema leaves
 * out. Each exists for RabbitMQ clusters only and only reads; changes stay in the UI and the REST API.
 */
public final class RabbitMqMcpToolDefs {

    private static final String CURSOR = "cursor";

    private static final String VHOST = "vhost";

    private static final String NEXT = "The next value of the previous page; absent for the first.";

    public static final List<McpToolDef> ALL = List.of(
            read("rabbitmq_nodes", "A RabbitMQ cluster's nodes with their version, alarms, partitions and listeners."),
            read(
                    "rabbitmq_list_vhosts",
                    "A page of a RabbitMQ cluster's virtual hosts with their queue and message counts.",
                    Param.note(CURSOR, NEXT)),
            read(
                    "rabbitmq_list_bindings",
                    "A page of a RabbitMQ cluster's bindings, filtered by virtual host, source or destination.",
                    Param.note(VHOST, "A virtual host's name."),
                    Param.note("source", "The exchange the binding starts at."),
                    Param.note("destination", "The queue or exchange the binding ends at."),
                    Param.note(CURSOR, NEXT)),
            read(
                    "rabbitmq_list_policies",
                    "A page of a RabbitMQ cluster's policies with their pattern, settings and how many queues and"
                            + " exchanges they match now.",
                    Param.note(VHOST, "A virtual host's name."),
                    Param.note(CURSOR, NEXT)),
            read(
                    "rabbitmq_list_permissions",
                    "A page of a RabbitMQ cluster's users' permissions on its virtual hosts.",
                    Param.note(VHOST, "A virtual host's name."),
                    Param.note("user", "A user's name."),
                    Param.note(CURSOR, NEXT)),
            read(
                    "rabbitmq_list_shovels",
                    "A RabbitMQ cluster's shovels and federation links; their addresses never carry a credential.",
                    Param.values("kind", List.of("shovel", "federation"), "Omit for the first page of both."),
                    Param.note(VHOST, "A virtual host's name."),
                    Param.note(CURSOR, "As above; it pages the one kind named in kind.")));

    private RabbitMqMcpToolDefs() {}

    private static McpToolDef read(String name, String summary, Param... params) {
        return new McpToolDef(name, Posture.READ, summary, List.of(params)).forSystem(RabbitMqModule.SYSTEM);
    }
}
