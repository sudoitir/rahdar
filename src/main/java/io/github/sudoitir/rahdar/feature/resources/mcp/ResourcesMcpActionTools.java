package io.github.sudoitir.rahdar.feature.resources.mcp;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.rahdar.feature.resources.ConnectionCloseKind;
import io.github.sudoitir.rahdar.feature.resources.ConnectionControlService;
import io.github.sudoitir.rahdar.feature.resources.ConnectionControlService.CloseResult;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.platform.mcp.McpResources;
import io.github.sudoitir.rahdar.platform.mcp.McpViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code connection_action} MCP tool. *
 * <p>Mutating tools (ADR-0045). They go through the same services the REST layer calls, so
 * authorization, the bulk cap and the audit row are the existing ones; this class adds only
 * the model-facing gate: {@code dryRun} defaults to true, a real destructive run needs
 * {@code confirm} to equal the subject's name, and {@code override} is the separate bulk-cap
 * escape that {@code confirm} never satisfies.
 */
@Component
@RequiredArgsConstructor
public class ResourcesMcpActionTools {

    private final ConnectionControlService connectionControl;
    private final McpResources resources;

    /**
     * Closing a connection, a channel, the connection behind a consumer, or every
     * consumer connection on a destination — as one tool discriminated by {@code kind}.
     *
     * <p>Declared not idempotent, and it means it: a connection identifier is
     * node-local and can be reissued, so a retried close may land on a different
     * application. Nothing here retries, and a host must not either.
     *
     * <p>The confirmation gate is against something a human recognises — the client
     * id the broker reports, or the destination — never the opaque connection id. For a
     * by-id kind the identity is not knowable until the target has been read, so a
     * real run previews first and compares the confirmation against what came back.
     * A target that has already gone needs no confirmation: there is nothing left to
     * close, and the answer says so.
     */
    @McpTool(
            name = "connection_action",
            description = "Close a connection, a channel, a consumer's connection, or a destination's consumers.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            // A connection id is node-local and can be reissued, so a
                            // repeat may close a different application. Never retry.
                            idempotentHint = false,
                            openWorldHint = false))
    public McpSchema.CallToolResult connectionAction(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String kind,
            @McpToolParam(required = true) String target,
            @McpToolParam(required = false) String nodeId,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm,
            @McpToolParam(required = false) Boolean override) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        ConnectionCloseKind op = McpArgs.enumOf(ConnectionCloseKind.class, "kind", kind, null);
        boolean dry = McpArgs.flag(dryRun, true);
        boolean over = McpArgs.flag(override, false);
        UUID node = op.nodeScoped() ? McpArgs.uuid("nodeId", nodeId) : null;

        return McpErrors.guard(() -> {
            String subject =
                    resources.require(id, "target", target, resourceClass(op)).name();
            if (op.nodeScoped()) {
                CloseResult preview = result(closeById(id, node, op, subject, true));
                if (dry || preview.target() == null) {
                    // Already gone, or a preview was all that was asked for. Either way
                    // nothing was closed and there is nothing to confirm against.
                    return closeOutcome(op, subject, preview);
                }
                McpArgs.confirm(preview.target().label(), confirm);
                return closeOutcome(op, subject, result(closeById(id, node, op, subject, false)));
            }
            if (!dry) {
                McpArgs.confirm(subject, confirm);
            }
            return closeOutcome(op, subject, result(connectionControl.closeAddressConsumers(id, subject, dry, over)));
        });
    }

    /** The class of resource the kind closes, which the target's id must name. */
    private static ResourceClass resourceClass(ConnectionCloseKind kind) {
        return ResourceClass.valueOf(kind.name());
    }

    private Attempt<CloseResult> closeById(
            UUID clusterId, UUID nodeId, ConnectionCloseKind kind, String subject, boolean dryRun) {
        return switch (kind) {
            case CONNECTION -> connectionControl.closeConnection(clusterId, nodeId, subject, dryRun);
            case CHANNEL -> connectionControl.closeSession(clusterId, nodeId, subject, dryRun);
            case CONSUMER -> connectionControl.closeConsumerConnection(clusterId, nodeId, subject, dryRun);
            case DESTINATION -> throw new IllegalStateException("not a by-id close");
        };
    }

    private static CloseResult result(Attempt<CloseResult> attempt) {
        return switch (attempt) {
            case Attempt.Ok<CloseResult>(var value) -> value;
            case Attempt.Failed<CloseResult>(var kind, var detail) -> throw new BrokerConnectionException(kind, detail);
        };
    }

    /**
     * The result an agent reads. {@code alreadyGone} is the field that stops a model
     * treating a vanished target as a failure and retrying onto whatever now holds
     * that identifier.
     */
    private static McpViews.CloseOutcome closeOutcome(ConnectionCloseKind kind, String subject, CloseResult result) {
        LifecycleOutcome outcome = result.outcome();
        boolean gone = kind != ConnectionCloseKind.DESTINATION && result.target() == null;
        List<McpViews.LifecycleNode> nodes = outcome.nodes().stream()
                .map(n -> new McpViews.LifecycleNode(n.nodeName(), n.status().name(), n.affected(), n.error()))
                .toList();
        return new McpViews.CloseOutcome(
                kind.name().toLowerCase(Locale.ROOT),
                subject,
                outcome.dryRun(),
                gone,
                result.target() == null ? null : result.target().label(),
                result.target() == null ? null : result.target().sessionCount(),
                result.target() == null ? null : result.target().consumerCount(),
                result.target() == null ? null : result.target().messagesInTransit(),
                outcome.cap(),
                outcome.overCap(),
                nodes,
                closeMessage(kind, outcome, gone, result));
    }

    private static String closeMessage(
            ConnectionCloseKind kind, LifecycleOutcome outcome, boolean gone, CloseResult result) {
        if (gone) {
            return "Nothing to close — that target is no longer on the node. The requested state holds; "
                    + "do not retry, because the identifier may since have been reissued.";
        }
        if (outcome.dryRun()) {
            if (outcome.overCap()) {
                return "Nothing was closed. A real run is over the bulk cap and would need override=true.";
            }
            String token = kind == ConnectionCloseKind.DESTINATION
                    ? result.subject()
                    : result.target().label();
            return "Nothing was closed. Re-run with dryRun=false and confirm=\"" + token + "\".";
        }
        if (outcome.anyFailed()) {
            return "Failed on every node it reached.";
        }
        return "Closed. In-flight messages have returned to their queues with an increased delivery count.";
    }
}
