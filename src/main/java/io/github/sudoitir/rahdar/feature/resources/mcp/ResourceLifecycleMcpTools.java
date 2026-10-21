package io.github.sudoitir.rahdar.feature.resources.mcp;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.resources.LifecycleKind;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateDestinationRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateSubscriptionRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.UpdateResourceRequest;
import io.github.sudoitir.rahdar.feature.resources.QueueLifecycleService;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpLifecycle;
import io.github.sudoitir.rahdar.platform.mcp.McpResources;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code resource_lifecycle} MCP tool.
 *
 * <p>Mutating tools (ADR-0045). They go through the same services the REST layer calls, so
 * authorization, the bulk cap and the audit row are the existing ones; this class adds only
 * the model-facing gate: {@code dryRun} defaults to true, a real destructive run needs
 * {@code confirm} to equal the subject's name, and {@code override} is the separate bulk-cap
 * escape that {@code confirm} never satisfies.
 */
@Component
@RequiredArgsConstructor
public class ResourceLifecycleMcpTools {

    private static final String RESOURCE = "resource";

    private final QueueLifecycleService lifecycle;
    private final McpResources resources;

    /** The configuration a create or update carries. Every field optional; checked per kind. */
    public record ResourceConfigBody(
            String destination,
            String routingType,
            Boolean durable,
            String filter,
            Integer maxConsumers,
            Boolean purgeOnNoConsumers,
            Boolean exclusive,
            Boolean nonDestructive,
            Long ringSize) {}

    /**
     * Subscription and destination lifecycle, as <b>one</b> tool discriminated by
     * {@code kind} rather than eight — the tool-count budget the MCP capability sets is
     * a real constraint, and eight near-identical verbs would spend it for nothing.
     *
     * <p>Delegates to {@link QueueLifecycleService},
     * so the permission check, the bulk cap and the audit row are the same ones the
     * HTTP API gets. Nothing here implements authorization, capping or auditing of
     * its own.
     */
    @McpTool(
            name = "resource_lifecycle",
            description = "Subscription and destination lifecycle, cluster-wide. Previews first.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            // Some kinds destroy a subscription and its messages irrecoverably, and a
                            // host gates the whole tool on one flag — so the honest value for a
                            // tool that can delete is true.
                            destructiveHint = true,
                            // Every kind but the counter reset converges on re-run: that is what
                            // makes retrying after a partial fan-out safe (D2).
                            idempotentHint = true,
                            openWorldHint = false))
    // One parameter per tool argument: the MCP schema is the parameter list.
    @SuppressWarnings("java:S107")
    public McpSchema.CallToolResult resourceLifecycle(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String kind,
            @McpToolParam(required = true) String resource,
            @McpToolParam(required = false) String config,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm,
            @McpToolParam(required = false) Boolean override,
            @McpToolParam(required = false) Boolean disconnectConsumers) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        LifecycleKind op = McpArgs.enumOf(LifecycleKind.class, "kind", kind, null);
        boolean dry = McpArgs.flag(dryRun, true);
        boolean over = McpArgs.flag(override, false);
        boolean disconnect = McpArgs.flag(disconnectConsumers, false);
        ResourceConfigBody body = parseConfig(config);
        return McpErrors.guard(() -> {
            ResourceId target = target(id, op, resource);
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
                        case CREATE_SUBSCRIPTION -> lifecycle.createQueue(id, createRequest(id, target, body), dry);
                        case UPDATE_RESOURCE -> lifecycle.updateQueue(id, subject, updateRequest(body), dry);
                        case DELETE_SUBSCRIPTION -> lifecycle.deleteQueue(id, subject, dry, over, disconnect);
                        case PAUSE_SUBSCRIPTION -> lifecycle.setPaused(id, subject, true, dry);
                        case RESUME_SUBSCRIPTION -> lifecycle.setPaused(id, subject, false, dry);
                        case RESET_COUNTERS -> lifecycle.resetCounter(id, subject, dry);
                        case CREATE_DESTINATION -> lifecycle.createAddress(id, destinationRequest(target, body), dry);
                        case DELETE_DESTINATION -> lifecycle.deleteAddress(id, subject, dry);
                    });
        });
    }

    /** The resource the kind acts on: one that exists, or for a create the id of the one to make. */
    private ResourceId target(UUID clusterId, LifecycleKind op, String resource) {
        return switch (op) {
            case CREATE_SUBSCRIPTION -> resources.newId(clusterId, RESOURCE, resource, ResourceClass.SUBSCRIPTION);
            case CREATE_DESTINATION -> resources.newId(clusterId, RESOURCE, resource, ResourceClass.DESTINATION);
            case DELETE_DESTINATION ->
                resources
                        .require(clusterId, RESOURCE, resource, ResourceClass.DESTINATION)
                        .id();
            case UPDATE_RESOURCE, DELETE_SUBSCRIPTION, PAUSE_SUBSCRIPTION, RESUME_SUBSCRIPTION, RESET_COUNTERS ->
                resources
                        .require(clusterId, RESOURCE, resource, ResourceClass.SUBSCRIPTION)
                        .id();
        };
    }

    private static ResourceConfigBody parseConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ResourceConfigBody(null, null, null, null, null, null, null, null, null);
        }
        return McpErrors.parse("config", raw, ResourceConfigBody.class);
    }

    private CreateSubscriptionRequest createRequest(UUID clusterId, ResourceId target, ResourceConfigBody body) {
        if (body.destination() == null || body.routingType() == null) {
            throw McpErrors.invalidParams(
                    "create_subscription needs config with at least destination (a destination id) and routingType.");
        }
        ResourceId destination =
                resources.newId(clusterId, "config.destination", body.destination(), ResourceClass.DESTINATION);
        Map<String, Object> attributes = attributes(body);
        attributes.put("routingType", body.routingType());
        attributes.put("durable", body.durable());
        return new CreateSubscriptionRequest(target.kind().value(), target.name(), destination.format(), attributes);
    }

    private static UpdateResourceRequest updateRequest(ResourceConfigBody body) {
        return new UpdateResourceRequest(attributes(body));
    }

    private static CreateDestinationRequest destinationRequest(ResourceId target, ResourceConfigBody body) {
        return new CreateDestinationRequest(
                target.kind().value(),
                target.name(),
                Map.of("routingTypes", body.routingType() == null ? "ANYCAST" : body.routingType()));
    }

    /** The body's tuning fields that were sent. */
    private static Map<String, Object> attributes(ResourceConfigBody body) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        putIfSet(attributes, "filter", body.filter());
        putIfSet(attributes, "maxConsumers", body.maxConsumers());
        putIfSet(attributes, "purgeOnNoConsumers", body.purgeOnNoConsumers());
        putIfSet(attributes, "exclusive", body.exclusive());
        putIfSet(attributes, "nonDestructive", body.nonDestructive());
        putIfSet(attributes, "ringSize", body.ringSize());
        return attributes;
    }

    private static void putIfSet(Map<String, Object> attributes, String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }
}
