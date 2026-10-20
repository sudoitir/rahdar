package io.github.sudoitir.rahdar.feature.resources.mcp;

import io.github.sudoitir.rahdar.feature.resources.LifecycleKind;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateAddressRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateQueueRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.UpdateQueueRequest;
import io.github.sudoitir.rahdar.feature.resources.QueueLifecycleService;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpLifecycle;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code queue_lifecycle} MCP tool.
 *
 * <p>Mutating tools (ADR-0045). They go through the same services the REST layer calls, so
 * authorization, the bulk cap and the audit row are the existing ones; this class adds only
 * the model-facing gate: {@code dryRun} defaults to true, a real destructive run needs
 * {@code confirm} to equal the subject's name, and {@code override} is the separate bulk-cap
 * escape that {@code confirm} never satisfies.
 */
@Component
@RequiredArgsConstructor
public class QueuesMcpTools {

    private final QueueLifecycleService lifecycle;

    /** The queue configuration a create or update carries. Every field optional; checked per kind. */
    public record QueueConfigBody(
            String address,
            String routingType,
            Boolean durable,
            String filter,
            Integer maxConsumers,
            Boolean purgeOnNoConsumers,
            Boolean exclusive,
            Boolean nonDestructive,
            Long ringSize) {}

    /**
     * Queue and address lifecycle, as <b>one</b> tool discriminated by
     * {@code kind} rather than eight — the tool-count budget the MCP capability sets is
     * a real constraint, and eight near-identical verbs would spend it for nothing.
     *
     * <p>Delegates to {@link QueueLifecycleService},
     * so the permission check, the bulk cap and the audit row are the same ones the
     * HTTP API gets. Nothing here implements authorization, capping or auditing of
     * its own.
     */
    @McpTool(
            name = "queue_lifecycle",
            description = "Queue and address lifecycle, cluster-wide. Previews first.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            // Some kinds destroy a queue and its messages irrecoverably, and a
                            // host gates the whole tool on one flag — so the honest value for a
                            // tool that can delete is true.
                            destructiveHint = true,
                            // Every kind but the counter reset converges on re-run: that is what
                            // makes retrying after a partial fan-out safe (D2).
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult queueLifecycle(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String kind,
            @McpToolParam(required = true) String name,
            @McpToolParam(required = false) String config,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm,
            @McpToolParam(required = false) Boolean override,
            @McpToolParam(required = false) Boolean disconnectConsumers) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        String subject = McpArgs.required("name", name);
        LifecycleKind op = McpArgs.enumOf(LifecycleKind.class, "kind", kind, null);
        boolean dry = McpArgs.flag(dryRun, true);
        boolean over = McpArgs.flag(override, false);
        boolean disconnect = McpArgs.flag(disconnectConsumers, false);
        if (!dry && op.destructive()) {
            McpArgs.confirm(subject, confirm);
        }
        QueueConfigBody body = parseConfig(config);
        return McpErrors.guard(() -> McpLifecycle.summary(
                op.name(),
                subject,
                op.destructive(),
                dry,
                switch (op) {
                    case CREATE_QUEUE -> lifecycle.createQueue(id, createRequest(subject, body), dry);
                    case UPDATE_QUEUE -> lifecycle.updateQueue(id, subject, updateRequest(body), dry);
                    case DELETE_QUEUE -> lifecycle.deleteQueue(id, subject, dry, over, disconnect);
                    case PAUSE_QUEUE -> lifecycle.setPaused(id, subject, true, dry);
                    case RESUME_QUEUE -> lifecycle.setPaused(id, subject, false, dry);
                    case RESET_QUEUE_COUNTER -> lifecycle.resetCounter(id, subject, dry);
                    case CREATE_ADDRESS -> lifecycle.createAddress(id, addressRequest(subject, body), dry);
                    case DELETE_ADDRESS -> lifecycle.deleteAddress(id, subject, dry);
                }));
    }

    private static QueueConfigBody parseConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return new QueueConfigBody(null, null, null, null, null, null, null, null, null);
        }
        return McpErrors.parse("config", raw, QueueConfigBody.class);
    }

    private static CreateQueueRequest createRequest(String name, QueueConfigBody body) {
        if (body.address() == null || body.routingType() == null) {
            throw McpErrors.invalidParams("create_queue needs config with at least address and routingType.");
        }
        return new CreateQueueRequest(
                body.address(),
                name,
                body.routingType(),
                body.durable(),
                body.filter(),
                body.maxConsumers(),
                body.purgeOnNoConsumers(),
                body.exclusive(),
                body.nonDestructive(),
                body.ringSize(),
                null);
    }

    private static UpdateQueueRequest updateRequest(QueueConfigBody body) {
        return new UpdateQueueRequest(
                body.filter(),
                body.maxConsumers(),
                body.purgeOnNoConsumers(),
                body.exclusive(),
                body.nonDestructive(),
                body.ringSize());
    }

    private static CreateAddressRequest addressRequest(String name, QueueConfigBody body) {
        return new CreateAddressRequest(name, body.routingType() == null ? "ANYCAST" : body.routingType());
    }
}
