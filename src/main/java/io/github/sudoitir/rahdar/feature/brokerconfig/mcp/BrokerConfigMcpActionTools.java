package io.github.sudoitir.rahdar.feature.brokerconfig.mcp;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyOutcome;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyRequest;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigApplyService;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigDocument;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigService.Source;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigValidator;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerXmlCodec;
import io.github.sudoitir.rahdar.feature.brokerconfig.Plan;
import io.github.sudoitir.rahdar.feature.brokerconfig.Violation;
import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.DocumentView;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code artemis_config_change} MCP tool. *
 * <p>Mutating tools (ADR-0045). They go through the same services the REST layer calls, so
 * authorization, the bulk cap and the audit row are the existing ones; this class adds only
 * the model-facing gate: {@code dryRun} defaults to true, a real destructive run needs
 * {@code confirm} to equal the subject's name, and {@code override} is the separate bulk-cap
 * escape that {@code confirm} never satisfies.
 */
@Component
@RequiredArgsConstructor
public class BrokerConfigMcpActionTools {

    private final BrokerConfigService brokerConfig;
    private final BrokerConfigApplyService brokerConfigApply;

    public enum ConfigOp {
        DECLARE,
        APPLY
    }

    /**
     * Declare or apply a cluster's broker configuration (ADR-0067), as one tool with
     * an {@code op} discriminator.
     *
     * <p>{@code declare} takes the document as JSON (the same shape {@code artemis_config}
     * returns) or a {@code broker.xml} fragment as {@code xml}; a dry run validates and
     * saves nothing. {@code apply} dry-runs to a plan whose {@code acknowledge} list is
     * exactly what a real run must carry in {@code acknowledge}, alongside
     * {@code confirm} equal to the cluster's name. The permission check, the step cap,
     * the canary-then-halt run and the audit row are the HTTP ones: this method adds
     * the model-facing gate and nothing else.
     */
    @McpTool(
            name = "artemis_config_change",
            description =
                    "Declare or apply a cluster's broker configuration. Previews by default; apply is canary-first.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            // An apply can point an address-full policy at DROP: message loss
                            // without destroying a queue is still destructive.
                            destructiveHint = true,
                            // Re-running an apply converges (steps that match are ALREADY).
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult artemisConfigChange(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String op,
            @McpToolParam(required = false) String document,
            @McpToolParam(required = false) String xml,
            @McpToolParam(required = false) String nodeIds,
            @McpToolParam(required = false) Boolean removeUndeclared,
            @McpToolParam(required = false) String acknowledge,
            @McpToolParam(required = false) String expectedPlanHash,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm,
            @McpToolParam(required = false) Boolean override) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        ConfigOp what = McpArgs.enumOf(ConfigOp.class, "op", op, null);
        boolean dry = McpArgs.flag(dryRun, true);
        return McpErrors.guard(() -> switch (what) {
            case DECLARE -> declare(id, document, xml, dry, confirm);
            case APPLY ->
                applyConfig(
                        id,
                        applyRequest(
                                nodeIds,
                                McpArgs.flag(removeUndeclared, false),
                                acknowledge,
                                expectedPlanHash,
                                McpArgs.flag(override, false)),
                        dry,
                        confirm);
        });
    }

    private Object declare(UUID clusterId, String document, String xml, boolean dry, String confirm) {
        BrokerConfigDocument doc;
        Source source;
        List<String> notes = new ArrayList<>();
        if (xml != null && !xml.isBlank()) {
            BrokerXmlCodec.ParseResult parsed = brokerConfig.importXml(clusterId, xml);
            if (!parsed.errors().isEmpty()) {
                throw McpErrors.invalidParams("The XML could not be imported: "
                        + parsed.errors().stream()
                                .map(v -> v.path() + ": " + v.message())
                                .collect(Collectors.joining("; ")));
            }
            parsed.unsupported()
                    .forEach(u -> notes.add("Not applied, unsupported: " + u.path() + " (" + u.reason() + ")"));
            doc = parsed.document();
            source = Source.IMPORT_XML;
        } else if (document != null && !document.isBlank()) {
            doc = McpErrors.parse("document", document, DocumentView.class).toDocument();
            source = Source.MCP;
        } else {
            throw McpErrors.invalidParams("declare needs document (JSON) or xml (a broker.xml fragment).");
        }
        BrokerConfigService.Declaration current = brokerConfig.get(clusterId);
        if (dry) {
            List<Violation> violations = BrokerConfigValidator.validate(doc);
            return Map.of(
                    "dryRun",
                    true,
                    "valid",
                    violations.isEmpty(),
                    "violations",
                    violations,
                    "currentRevision",
                    current.revision(),
                    "notes",
                    notes,
                    "message",
                    violations.isEmpty()
                            ? "Nothing was saved. Re-run with dryRun=false and confirm=\""
                                    + current.clusterName() + "\" to save revision "
                                    + (current.revision() + 1) + "."
                            : "Nothing was saved. Fix the violations first.");
        }
        McpArgs.confirm(current.clusterName(), confirm);
        BrokerConfigService.Declaration saved =
                brokerConfig.save(clusterId, doc, current.revision(), "Declared over MCP", source);
        return Map.of(
                "dryRun",
                false,
                "revision",
                saved.revision(),
                "notes",
                notes,
                "message",
                "Saved revision " + saved.revision() + ". Nothing has been applied to a broker.");
    }

    private static BrokerConfigApplyRequest applyRequest(
            String nodeIds, boolean removeUndeclared, String acknowledge, String expectedPlanHash, boolean override) {
        Set<UUID> nodes = new LinkedHashSet<>();
        if (nodeIds != null && !nodeIds.isBlank()) {
            for (String n : nodeIds.split(",")) {
                nodes.add(McpArgs.uuid("nodeIds", n.trim()));
            }
        }
        List<String> acks = acknowledge == null || acknowledge.isBlank()
                ? List.of()
                : Arrays.stream(acknowledge.split(","))
                        .map(String::trim)
                        .filter(a -> !a.isEmpty())
                        .toList();
        return new BrokerConfigApplyRequest(
                null, nodes, null, removeUndeclared, acks, expectedPlanHash, override, Set.of());
    }

    private McpViews.ConfigApplyOutcome applyConfig(
            UUID clusterId, BrokerConfigApplyRequest request, boolean dry, String confirm) {
        if (!dry) {
            McpArgs.confirm(brokerConfig.get(clusterId).clusterName(), confirm);
        }
        BrokerConfigApplyOutcome outcome =
                dry ? brokerConfigApply.plan(clusterId, request) : brokerConfigApply.apply(clusterId, request);
        return configOutcome(outcome, brokerConfig.get(clusterId).clusterName());
    }

    private static String dryRunMessage(BrokerConfigApplyOutcome o, String clusterName, List<String> acknowledge) {
        Plan plan = o.plan();
        if (o.overCap()) {
            return "Nothing was changed. " + plan.stepCount() + " steps is over the cap of " + o.stepCap()
                    + "; a real run needs override=true.";
        }
        String acknowledgement =
                acknowledge.isEmpty() ? "." : " and acknowledge=\"" + String.join(",", acknowledge) + "\".";
        return "Nothing was changed. Re-run with dryRun=false, confirm=\"" + clusterName + "\", expectedPlanHash=\""
                + plan.planHash() + "\"" + acknowledgement;
    }

    static McpViews.ConfigApplyOutcome configOutcome(BrokerConfigApplyOutcome o, String clusterName) {
        Plan plan = o.plan();
        List<McpViews.ConfigHazard> hazards = plan.hazards().stream()
                .map(h -> new McpViews.ConfigHazard(h.id(), h.hazardClass().name(), h.nodeName(), h.message()))
                .toList();
        List<String> acknowledge = plan.highHazardIds();
        List<McpViews.ConfigNodeApply> nodes = o.nodes().stream()
                .map(n -> new McpViews.ConfigNodeApply(
                        n.nodeName(),
                        n.canary(),
                        n.note(),
                        n.steps().stream()
                                .map(s -> new McpViews.ConfigStep(
                                        s.stepId(),
                                        s.op().name(),
                                        s.section().name(),
                                        s.key(),
                                        s.status().name(),
                                        s.verified().name(),
                                        s.error()))
                                .toList()))
                .toList();
        String canary = plan.nodes().stream()
                .filter(n -> n.nodeId().equals(plan.canaryNodeId()))
                .map(Plan.NodePlan::nodeName)
                .findFirst()
                .orElse(null);
        String message = o.summary();
        if (o.dryRun()) {
            message = dryRunMessage(o, clusterName, acknowledge);
        }
        return new McpViews.ConfigApplyOutcome(
                o.dryRun(),
                o.outcome().name(),
                o.revision(),
                plan.planHash(),
                canary,
                plan.stepCount(),
                o.stepCap(),
                o.overCap(),
                hazards,
                acknowledge,
                nodes,
                message);
    }
}
