package io.github.sudoitir.rahdar.feature.routing.mcp;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.routing.CreateDivertRequest;
import io.github.sudoitir.rahdar.feature.routing.DivertKind;
import io.github.sudoitir.rahdar.feature.routing.DivertLifecycleService;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpLifecycle;
import io.github.sudoitir.rahdar.platform.mcp.McpResources;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code artemis_divert_lifecycle} MCP tool (ADR-0045): the same service the REST layer calls, so
 * authorization and the audit row are the existing ones. This class adds only the model-facing gate:
 * {@code dryRun} defaults to true, and a real delete needs {@code confirm} to equal the divert's name.
 */
@Component
@RequiredArgsConstructor
public class DivertMcpTools {

    private static final String DIVERT_KIND = "artemis.divert";

    private final DivertLifecycleService lifecycle;
    private final McpResources resources;

    /** The divert a create carries. Every field optional here; {@code address} and {@code forwardingAddress} are required. */
    public record DivertConfigBody(
            String address,
            String forwardingAddress,
            String routingName,
            Boolean exclusive,
            String filter,
            String routingType,
            Boolean acknowledgeCaptureShadowing) {}

    /**
     * Divert lifecycle, as <b>one</b> tool discriminated by {@code kind}. A divert created this way
     * persists across broker restarts and is absent from the broker's own configuration (ADR-0065), so an
     * agent acting on Rahdar's behalf must not report it as temporary.
     */
    @McpTool(
            name = "artemis_divert_lifecycle",
            description = "Divert creation and deletion, cluster-wide. Previews first.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            // Deleting a divert silently changes routing for every producer on its source.
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult artemisDivertLifecycle(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String kind,
            @McpToolParam(required = true) String resource,
            @McpToolParam(required = false) String config,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        DivertKind op = McpArgs.enumOf(DivertKind.class, "kind", kind, null);
        boolean dry = McpArgs.flag(dryRun, true);
        DivertConfigBody body = parseConfig(config);
        return McpErrors.guard(() -> {
            ResourceId target = op == DivertKind.CREATE_DIVERT
                    ? resources.newId(id, "resource", resource, ResourceClass.ROUTE)
                    : resources
                            .require(id, "resource", resource, ResourceClass.ROUTE)
                            .id();
            if (!DIVERT_KIND.equals(target.kind().value())) {
                throw McpErrors.invalidParams("resource must be a divert, such as " + DIVERT_KIND + "/orders-copy; got "
                        + target.format() + ".");
            }
            String subject = target.name();
            if (!dry && op.destructive()) {
                McpArgs.confirm(subject, confirm);
            }
            return McpLifecycle.summary(
                    op.name(),
                    subject,
                    op.destructive(),
                    dry,
                    switch (op) {
                        case CREATE_DIVERT -> lifecycle.createDivert(id, createRequest(subject, body), dry);
                        case DELETE_DIVERT -> lifecycle.deleteDivert(id, subject, dry);
                    });
        });
    }

    private static DivertConfigBody parseConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return new DivertConfigBody(null, null, null, null, null, null, null);
        }
        return McpErrors.parse("config", raw, DivertConfigBody.class);
    }

    private static CreateDivertRequest createRequest(String name, DivertConfigBody body) {
        if (body.address() == null || body.forwardingAddress() == null) {
            throw McpErrors.invalidParams("create_divert needs config with at least address and forwardingAddress.");
        }
        return new CreateDivertRequest(
                name,
                body.routingName(),
                body.address(),
                body.forwardingAddress(),
                body.exclusive(),
                body.filter(),
                body.routingType(),
                body.acknowledgeCaptureShadowing());
    }
}
