package io.github.sudoitir.rahdar.feature.messages.mcp;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.rahdar.feature.messages.MessageAction;
import io.github.sudoitir.rahdar.feature.messages.MessageService;
import io.github.sudoitir.rahdar.feature.messages.MessageService.Outcome;
import io.github.sudoitir.rahdar.feature.messages.web.MessageRequests.SendMessageRequest;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.MessageKind;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpResources;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code message_action} and {@code send_message} MCP tools.
 *
 * <p>Mutating tools (ADR-0045). They go through the same services the REST layer calls, so
 * authorization, the bulk cap and the audit row are the existing ones; this class adds only
 * the model-facing gate: {@code dryRun} defaults to true, a real destructive run needs
 * {@code confirm} to equal the subject's name, and {@code override} is the separate bulk-cap
 * escape that {@code confirm} never satisfies.
 */
@Component
@RequiredArgsConstructor
public class MessagesMcpActionTools {

    private static final String SUBSCRIPTION = "subscription";

    private final MessageService messages;
    private final McpResources resources;

    /** The subscription-scoped mutations, as one tool. */
    public enum SubscriptionActionKind {
        MOVE,
        RETRY,
        DELETE,
        EXPIRE,
        PURGE
    }

    /**
     * Named for its target, not its neighbour (ADR-0054). It acts on the
     * messages <em>in</em> a subscription, where {@code resource_lifecycle} acts on the
     * subscription itself. They stay separate tools — identical postures,
     * but thirteen operations across two disjoint argument clusters would make the
     * most dangerous schema in the product unusable without fetching detail first.
     */
    @McpTool(
            name = "message_action",
            description = "Move, retry, delete, expire or purge messages on a subscription. "
                    + "Select with positions or filter.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    // One parameter per tool argument: the MCP schema is the parameter list.
    @SuppressWarnings("java:S107")
    public McpSchema.CallToolResult messageAction(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String subscription,
            @McpToolParam(required = true) String action,
            @McpToolParam(required = false) String positions,
            @McpToolParam(required = false) String filter,
            @McpToolParam(required = false) String target,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm,
            @McpToolParam(required = false) Boolean override) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        McpArgs.required(SUBSCRIPTION, subscription);
        SubscriptionActionKind kind = McpArgs.enumOf(SubscriptionActionKind.class, "action", action, null);
        boolean dry = McpArgs.flag(dryRun, true);
        boolean over = McpArgs.flag(override, false);
        return McpErrors.guard(() -> {
            String q = resources
                    .require(id, SUBSCRIPTION, subscription, ResourceClass.SUBSCRIPTION)
                    .name();
            if (!dry) {
                McpArgs.confirm(q, confirm);
            }
            Attempt<Outcome> attempt = kind == SubscriptionActionKind.PURGE
                    ? messages.purge(id, q, null, dry, over)
                    : messages.execute(
                            id,
                            q,
                            null,
                            MessageAction.valueOf(kind.name()),
                            messages.actionRequest(id, parseIds(positions), filter, targetName(id, target)),
                            dry,
                            over);
            return outcome(kind.name().toLowerCase(Locale.ROOT), q, attempt);
        });
    }

    /** The name of the subscription to move into, when the call names one. */
    private String targetName(UUID clusterId, String target) {
        return target == null || target.isBlank()
                ? null
                : resources
                        .require(clusterId, "target", target, ResourceClass.SUBSCRIPTION)
                        .name();
    }

    @McpTool(
            name = "send_message",
            description = "Enqueue one message on a subscription. Dry-runs by default; adds, never removes.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            // Additive: nothing existing is lost, so a host must not
                            // gate this behind the same warning as a purge.
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    public McpSchema.CallToolResult sendMessage(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String subscription,
            @McpToolParam(required = true) String body,
            @McpToolParam(required = false) String kind,
            @McpToolParam(required = false) Boolean durable,
            @McpToolParam(required = false) Boolean dryRun) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        McpArgs.required(SUBSCRIPTION, subscription);
        String payload = McpArgs.required("body", body);
        MessageKind messageKind = McpArgs.enumOf(MessageKind.class, "kind", kind, MessageKind.TEXT);
        boolean dry = McpArgs.flag(dryRun, true);
        return McpErrors.guard(() -> {
            String q = resources
                    .require(id, SUBSCRIPTION, subscription, ResourceClass.SUBSCRIPTION)
                    .name();
            Attempt<Outcome> attempt = messages.send(
                    id,
                    q,
                    null,
                    new SendMessageRequest(messageKind, McpArgs.flag(durable, true), payload, Map.of(), Map.of()),
                    dry);
            return outcome("send", q, attempt);
        });
    }

    private static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException _) {
                throw McpErrors.invalidParams("positions must be comma-separated numbers; got \"" + trimmed + "\".");
            }
        }
        return ids;
    }

    /** A broker that could not be reached is a failed operation, not a failed call. */
    private static McpViews.MutationOutcome outcome(String action, String subject, Attempt<Outcome> attempt) {
        return switch (attempt) {
            case Attempt.Failed<Outcome>(var kind, var detail) -> throw new BrokerConnectionException(kind, detail);
            case Attempt.Ok<Outcome>(var value) ->
                switch (value) {
                    case Outcome.DryRun(var count, var cap, var overCap, var node) ->
                        new McpViews.MutationOutcome(
                                action,
                                subject,
                                true,
                                count,
                                cap,
                                overCap,
                                String.valueOf(node),
                                overCap
                                        ? "Nothing was changed. A real run is over the bulk cap and would need override=true."
                                        : "Nothing was changed. Re-run with dryRun=false and confirm=\"" + subject
                                                + "\".");
                    case Outcome.Affected(var count, var node) ->
                        new McpViews.MutationOutcome(
                                action, subject, false, count, null, false, String.valueOf(node), "Applied.");
                    // Neither applied nor failed: affected is 0 only because nothing is confirmed.
                    case Outcome.InDoubt(var detail, var node) ->
                        new McpViews.MutationOutcome(
                                action, subject, false, 0, null, false, String.valueOf(node), "In doubt. " + detail);
                    case Outcome.Partial(var count, var notDone, var error, var node) ->
                        new McpViews.MutationOutcome(
                                action,
                                subject,
                                false,
                                count,
                                null,
                                false,
                                String.valueOf(node),
                                "Partially applied: " + count + " message(s) were acted on before it stopped ("
                                        + error + "). " + notDone.size()
                                        + " id(s) were not done, starting with "
                                        + notDone.getFirst()
                                        + ".");
                };
        };
    }
}
