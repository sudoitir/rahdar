package io.github.sudoitir.rahdar.feature.brokerconfig.mcp;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyService;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService;
import io.github.sudoitir.rahdar.feature.brokerconfig.ConfigDiffService;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.ConfigViews;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code artemis_config} and {@code artemis_config_diff} MCP tools. *
 * <p>Reads only: nothing here needs the dry-run/confirm contract, which is the review
 * boundary ADR-0045 draws between read and mutating tool classes. Every method is a thin
 * adapter over the services the REST layer already uses.
 */
@Component
@RequiredArgsConstructor
public class BrokerConfigMcpReadTools {

    private final BrokerConfigService brokerConfig;
    private final BrokerConfigApplyService brokerConfigApply;
    private final ConfigDiffService configDiff;

    public enum ConfigReadKind {
        DECLARATION,
        DRIFT,
        XML,
        APPLIES
    }

    /**
     * A cluster's declared configuration and what the brokers make of it (ADR-0067):
     * the declaration, the last drift evaluation per node, the {@code broker.xml}
     * fragment, or the apply history — one tool, one {@code kind}.
     */
    @McpTool(
            name = "artemis_config",
            description =
                    "A cluster's declared broker configuration: declaration, drift per node, XML fragment, or applies.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult artemisConfig(
            @McpToolParam(required = true) String clusterId, @McpToolParam(required = false) String kind) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        ConfigReadKind what = McpArgs.enumOf(ConfigReadKind.class, "kind", kind, ConfigReadKind.DECLARATION);
        return McpErrors.guard(() -> switch (what) {
            case DECLARATION -> declaration(brokerConfig.get(id), true);
            case DRIFT -> declaration(brokerConfig.get(id), false);
            case XML -> brokerConfig.exportXml(id, null);
            case APPLIES ->
                brokerConfigApply.history(id, ResourceQuery.ofPage(1, 20)).stream()
                        .map(a -> new McpViews.ConfigApply(
                                a.getId(),
                                a.getStartedAt(),
                                a.isDryRun(),
                                a.getOutcome(),
                                a.getSummary(),
                                a.getActor(),
                                a.getAuditEventId()))
                        .toList();
        });
    }

    static McpViews.ConfigDeclaration declaration(BrokerConfigService.Declaration d, boolean withDocument) {
        List<McpViews.ConfigNodeState> nodes = d.nodes().stream()
                .map(n -> new McpViews.ConfigNodeState(
                        n.nodeName(),
                        n.live(),
                        n.state().name(),
                        n.detail(),
                        n.evaluatedAt(),
                        n.findings().stream()
                                .map(f -> new McpViews.ConfigFinding(
                                        f.kind().name(), f.section().name(), f.key(), f.detail()))
                                .toList()))
                .toList();
        return new McpViews.ConfigDeclaration(
                d.declared(),
                d.revision(),
                d.applyMode() == null ? null : d.applyMode().name(),
                d.updatedBy(),
                d.updatedAt(),
                withDocument ? d.document() : null,
                nodes);
    }

    @McpTool(
            name = "artemis_config_diff",
            description = "Every node's broker configuration against the majority: the keys that drift, per node.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult artemisConfigDiff(
            @McpToolParam(required = true) String clusterId, @McpToolParam(required = false) String nodes) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        Set<UUID> only = new LinkedHashSet<>();
        if (nodes != null && !nodes.isBlank()) {
            for (String node : nodes.split(",")) {
                only.add(McpArgs.uuid("nodes", node.strip()));
            }
        }
        return McpErrors.guard(() -> diff(id, only));
    }

    private McpViews.ConfigDiff diff(UUID clusterId, Set<UUID> only) {
        ConfigViews.ConfigDiffView view = configDiff.compare(clusterId, only);
        List<McpViews.ConfigDrift> items = new ArrayList<>();
        for (ConfigViews.ConfigSectionView section : view.sections()) {
            for (ConfigViews.ConfigKeyView key : section.keys()) {
                // Only the drifting keys: a model does not need the hundreds that agree,
                // and shipping them is the single largest response-size risk in this tool.
                if (key.drift()) {
                    List<ConfigViews.ConfigNodeValueView> differing =
                            key.majority() == null ? key.values() : key.outliers();
                    items.add(new McpViews.ConfigDrift(
                            section.section() + key.key(),
                            key.state(),
                            key.majority(),
                            differing.stream()
                                    .map(v -> new McpViews.ConfigNodeValue(v.nodeName(), v.value()))
                                    .toList()));
                }
            }
        }
        return new McpViews.ConfigDiff(
                clusterId,
                view.nodes().stream()
                        .filter(ConfigViews.ConfigNodeView::available)
                        .map(ConfigViews.ConfigNodeView::nodeName)
                        .toList(),
                view.nodes().stream()
                        .filter(n -> !n.available())
                        .map(n -> new McpViews.ConfigUnavailable(
                                n.nodeName(), n.unavailableKind(), n.unavailableReason()))
                        .toList(),
                view.summary().driftKeys(),
                view.summary().driftNodes(),
                view.summary().expectedKeys(),
                items,
                view.notes());
    }
}
