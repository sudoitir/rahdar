package io.github.sudoitir.rahdar.platform.mcp;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditScope;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.AuthKind;
import io.github.sudoitir.rahdar.kernel.gate.GateContext;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.TokenPrincipal;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one point every MCP request passes through, built-in and plugin tools alike (ADR-0137). It
 * wraps the SDK's request handler, so it sees each JSON-RPC request on the servlet thread where the
 * caller's security context lives:
 *
 * <ul>
 *   <li>{@code initialize}: the instructions name only what this caller is offered;
 *   <li>{@code tools/list}: tools outside the token's allow-list, and mutating tools while the
 *       installation is read-only, are left out;
 *   <li>{@code tools/call}: a tool outside the allow-list answers exactly as a tool that does not
 *       exist; a mutating tool in read-only mode is refused whatever its confirmation; every call is
 *       audited under the owner and token, and the audit rows the call's own work writes nest
 *       under it.
 * </ul>
 *
 * <p>It only narrows what the token's grants permit and adds attribution. Every permission check
 * stays where ADR-0046 put it.
 */
@Component
@RequiredArgsConstructor
class McpGate {

    static final String AUDIT_ACTION = "MCP_TOOL_CALL";

    /**
     * Arguments whose values identify what a call acted on. Anything else (message content, broker
     * XML, settings values, confirmations) is recorded by name only, never by value.
     */
    private static final Set<String> AUDITED = Set.of(
            "clusterId",
            "nodeId",
            "resource",
            "subscription",
            "destination",
            "target",
            "role",
            "name",
            "op",
            "action",
            "kind",
            "topic",
            "dryRun",
            "override",
            "limit",
            "id",
            "ruleId",
            "connectionId",
            "messageIds",
            "metric",
            "window",
            "from",
            "to");

    private static final int MAX_AUDITED_VALUE = 200;

    private final McpToolCatalog catalog;
    private final AuditService audit;
    private final ActorResolver actors;
    private final JsonMapper json;

    McpStatelessServerHandler wrap(McpStatelessServerHandler delegate) {
        return new McpStatelessServerHandler() {
            @Override
            public Mono<JSONRPCResponse> handleRequest(McpTransportContext context, JSONRPCRequest request) {
                return switch (request.method()) {
                    case McpSchema.METHOD_INITIALIZE ->
                        Mono.justOrEmpty(initialize(
                                delegate.handleRequest(context, request).block()));
                    case McpSchema.METHOD_TOOLS_LIST ->
                        Mono.justOrEmpty(
                                list(delegate.handleRequest(context, request).block()));
                    case McpSchema.METHOD_TOOLS_CALL -> Mono.justOrEmpty(call(delegate, context, request));
                    default -> delegate.handleRequest(context, request);
                };
            }

            @Override
            public Mono<Void> handleNotification(
                    McpTransportContext context, McpSchema.JSONRPCNotification notification) {
                return delegate.handleNotification(context, notification);
            }
        };
    }

    private JSONRPCResponse initialize(JSONRPCResponse response) {
        if (response == null || !(response.result() instanceof McpSchema.InitializeResult r)) {
            return response;
        }
        return new JSONRPCResponse(
                response.jsonrpc(),
                response.id(),
                new McpSchema.InitializeResult(
                        r.protocolVersion(), r.capabilities(), r.serverInfo(), catalog.instructions(), r.meta()),
                null);
    }

    private JSONRPCResponse list(JSONRPCResponse response) {
        if (response == null
                || !(response.result() instanceof McpSchema.ListToolsResult(var tools, var nextCursor, var meta))) {
            return response;
        }
        return new JSONRPCResponse(
                response.jsonrpc(),
                response.id(),
                new McpSchema.ListToolsResult(
                        tools.stream().filter(t -> catalog.offered(t.name())).toList(), nextCursor, meta),
                null);
    }

    private JSONRPCResponse call(
            McpStatelessServerHandler delegate, McpTransportContext context, JSONRPCRequest request) {
        Map<?, ?> params = request.params() instanceof Map<?, ?> m ? m : json.convertValue(request.params(), Map.class);
        String tool = String.valueOf(params.get("name"));
        Map<?, ?> arguments = params.get("arguments") instanceof Map<?, ?> a ? a : Map.of();
        AuditEvent event = audit.begin(
                actors.resolve(),
                AUDIT_ACTION,
                "mcp-tool",
                tool,
                clusterId(arguments),
                null,
                audited(arguments),
                false);
        if (!catalog.allowedByToken(tool)) {
            markDenied();
            audit.fail(event, "The token's tool allow-list does not include this tool");
            // Word for word what the SDK answers for a tool that does not exist.
            return new JSONRPCResponse(
                    McpSchema.JSONRPC_VERSION,
                    request.id(),
                    null,
                    new JSONRPCError(
                            McpSchema.ErrorCodes.INVALID_PARAMS,
                            "Unknown tool: invalid_tool_name",
                            "Tool not found: " + tool));
        }
        if (catalog.refusedAsReadOnly(tool)) {
            markDenied();
            audit.fail(event, "The agent surface is read-only on this installation");
            return new JSONRPCResponse(
                    McpSchema.JSONRPC_VERSION,
                    request.id(),
                    McpErrors.error("The agent surface is read-only on this installation, and " + tool
                            + " changes state. An administrator can turn read-only mode off in Settings → MCP server."),
                    null);
        }
        UUID cluster = clusterId(arguments);
        String otherSystem = catalog.refusedOnCluster(tool, cluster);
        if (otherSystem != null) {
            audit.fail(event, otherSystem);
            return new JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), McpErrors.error(otherSystem), null);
        }
        try {
            // Every tool call is an agent's, whatever the token behind it: the approval gate holds it as one.
            JSONRPCResponse response = ScopedValue.where(AuditScope.PARENT, event.getId())
                    .where(GateContext.ORIGIN, AuthKind.AGENT)
                    .call(() -> delegate.handleRequest(context, request).block());
            String error = errorOf(response);
            if (error == null) {
                audit.succeed(event, 0);
            } else {
                audit.fail(event, error);
            }
            return response;
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    private static String errorOf(JSONRPCResponse response) {
        if (response == null) {
            return "No response";
        }
        if (response.error() != null) {
            return response.error().message();
        }
        if (response.result() instanceof McpSchema.CallToolResult r && Boolean.TRUE.equals(r.isError())) {
            return r.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .findFirst()
                    .orElse("The tool reported an error");
        }
        return null;
    }

    private static UUID clusterId(Map<?, ?> arguments) {
        try {
            return arguments.get("clusterId") instanceof String s ? UUID.fromString(s) : null;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    /** Identifying scalar arguments by value, cut to a short length; every other argument by name only. */
    private static Map<String, Object> audited(Map<?, ?> arguments) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> others = new ArrayList<>();
        arguments.forEach((k, v) -> {
            String key = String.valueOf(k);
            if (!AUDITED.contains(key) || v instanceof Map || v instanceof Iterable) {
                others.add(key);
                return;
            }
            String value = String.valueOf(v);
            out.put(key, value.length() > MAX_AUDITED_VALUE ? value.substring(0, MAX_AUDITED_VALUE) + "…" : v);
        });
        if (!others.isEmpty()) {
            out.put("otherArguments", others.stream().sorted().toList());
        }
        return out;
    }

    private static void markDenied() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.setAttribute(TokenPrincipal.DENIED_ATTRIBUTE, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        }
    }
}
