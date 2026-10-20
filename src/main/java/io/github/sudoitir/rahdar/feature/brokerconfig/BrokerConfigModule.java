package io.github.sudoitir.rahdar.feature.brokerconfig;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.plugin.TopicDef;
import java.util.List;

/** Declared configuration, apply, drift and config diff. Module descriptor (ADR-0070). */
public final class BrokerConfigModule {

    public static final FeatureDescriptor DESCRIPTOR = FeatureDescriptor.builder()
            .id("brokerconfig")
            .title("Broker configuration")
            .kind(FeatureDescriptor.Kind.FEATURE)
            .require("resources")
            .require("routing")
            .permission(PermissionDef.cluster(
                    BrokerConfigPermissions.CONFIG_WRITE, "Edit a cluster's declared broker configuration"))
            .permission(PermissionDef.cluster(
                    BrokerConfigPermissions.CONFIG_APPLY,
                    "Apply declared broker configuration to brokers",
                    BrokerConfigPermissions.CONFIG_WRITE))
            .apiPrefix("/api/v1/clusters/{clusterId}/artemis/config")
            .apiPrefix("/api/v1/clusters/{clusterId}/artemis/config-diff")
            .apiPrefix("/api/v1/clusters/{clusterId}/artemis/nodes/{nodeId}/config")
            .settingKey(BrokerConfigSettings.DRIFT_INTERVAL)
            .settingKey(BrokerConfigSettings.APPLY_STEP_CAP)
            .streamTopic(TopicDef.data(BrokerConfigDriftService.SSE_TOPIC, null))
            .mcpTool(new McpToolDef(
                    "config_diff",
                    McpToolDef.Posture.READ,
                    "Every node's configuration against the majority: the drifting keys and the nodes that differ.",
                    List.of(McpToolDef.Param.note(
                            "nodes",
                            "Comma-separated node ids, at least two, from cluster://{clusterId}/topology. "
                                    + "Omit to compare every node."))))
            .mcpTool(new McpToolDef(
                    "broker_config",
                    McpToolDef.Posture.READ,
                    "A cluster's declared broker configuration, its drift per node, its XML fragment or its applies.",
                    List.of(McpToolDef.Param.values(
                            "kind",
                            List.of("declaration", "drift", "xml", "applies"),
                            "Default declaration. drift is the last stored evaluation per node; it "
                                    + "compares every node with the declaration, not nodes with each other "
                                    + "(that is config_diff)."))))
            .mcpTool(new McpToolDef(
                    "broker_config_change",
                    McpToolDef.Posture.MUTATE,
                    "Declare a cluster's broker configuration, or apply it to every live node canary-first.",
                    List.of(
                            McpToolDef.Param.values("op", List.of("declare", "apply"), null),
                            McpToolDef.Param.shape(
                                    "document",
                                    "{ version: 1, addresses: [{ name, routingTypes, queues: [{ name, routingType, "
                                            + "filter, durable, maxConsumers, purgeOnNoConsumers, exclusive, "
                                            + "nonDestructive, ringSize }] }], addressSettings: [{ match, "
                                            + "values: { <key>: value } }], securitySettings: [{ match, "
                                            + "permissions: { <permission>: [role] } }], diverts: [{ name, address, "
                                            + "forwardingAddress, filter, exclusive, routingType }] }",
                                    "declare only: the same shape broker_config kind=declaration returns. Keys "
                                            + "come from the address-setting catalogue; an unknown key is refused, "
                                            + "never ignored. Alternatively send xml, a broker.xml <core> fragment; "
                                            + "unsupported elements are listed and not applied. declare saves a "
                                            + "revision and applies nothing."),
                            McpToolDef.Param.note(
                                    "op.apply",
                                    "A dry run returns the plan: steps per live node with before and after, the "
                                            + "hazards with their identifiers and class, the canary and planHash. "
                                            + "The real run must send that planHash as expectedPlanHash and every "
                                            + "High hazard id in acknowledge (comma-separated). It applies to the "
                                            + "canary, reads it back, then continues node by node and halts on the "
                                            + "first failure; the rest report NOT_ATTEMPTED, nothing is rolled "
                                            + "back, re-running converges. Rahdar never destroys a queue or "
                                            + "address this way and removes only settings it applied unless "
                                            + "removeUndeclared=true."),
                            McpToolDef.Param.note(
                                    "nodeIds",
                                    "Comma-separated node ids to target; omit for every live node. The canary "
                                            + "is the first targeted live node by name; choosing another is a "
                                            + "UI-only option."),
                            McpToolDef.Param.note("dryRun", McpToolDef.DRY_RUN),
                            McpToolDef.Param.note(
                                    "confirm", "Required to turn dryRun off; must equal the cluster's name."),
                            McpToolDef.Param.note("override", McpToolDef.OVERRIDE))))
            .build();

    private BrokerConfigModule() {}
}
