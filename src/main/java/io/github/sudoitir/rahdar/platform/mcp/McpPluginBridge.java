package io.github.sudoitir.rahdar.platform.mcp;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.ResourceForbiddenException;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.PluginBridge;
import io.github.sudoitir.rahdar.kernel.plugin.PluginHandle;
import io.github.sudoitir.rahdar.kernel.plugin.PluginRuntimeStatus;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers a plugin's own {@code @McpTool}/{@code @McpResource}/{@code @McpPrompt} beans with the
 * real, running {@link McpStatelessSyncServer} on activation, and removes them on deactivation
 * (design.md, task 6.5). The server is stateless and has no {@code list_changed}, so
 * {@link McpToolCatalog#addPlugin} is what keeps {@code rahdar_help} and {@code rahdar://tools}
 * current — every tool call goes through the catalogue's live reads, never a snapshot taken here.
 *
 * <p>Each tool's {@code callHandler} is wrapped so a call reaching a plugin mid Brief-maintenance
 * window gets the same {@code 503 plugin-updating} answer the gateway gives, and so every call runs
 * with the plugin's own thread context classloader set, counted as in-flight against its unload
 * drain — {@link PluginHandle#runInPlugin} is the one sanctioned way in.
 *
 * <p>Each tool declares the permission it needs and whether it is checked on a queue or address, on
 * a cluster or globally (ADR-0114). This bridge checks it before the plugin's code runs, on the
 * calling thread where the key's security context lives: a cluster denial hides the cluster exactly
 * as the built-in tools do ({@link McpErrors#CLUSTER_DENIED}), a resource denial hides the queue or
 * address ({@link McpErrors#RESOURCE_DENIED}), a global one names the permission.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class McpPluginBridge implements PluginBridge {

    /** Same ceiling {@code McpToolSchemaBudgetTest} enforces for every built-in tool. */
    private static final int PER_TOOL_TOKEN_BUDGET = 190;

    private static final String CLUSTER_ID = "clusterId";
    private static final String SCOPE_CLUSTER = "cluster";
    private static final String SCOPE_RESOURCE = "resource";
    private static final String POSTURE_READ = "read";

    private final ObjectProvider<McpStatelessSyncServer> server;
    private final McpToolCatalog catalog;
    private final PluginRuntimeStatus runtimeStatus;
    private final JsonMapper json;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver permissions;

    private final Map<String, Registered> byPlugin = new ConcurrentHashMap<>();

    /**
     * {@code handle} is the owner this registration belongs to: the Instant activation class
     * attaches a new version before the old one detaches, so both briefly hold the same plugin id
     * here, and {@link #detach} must undo only the registration it itself made.
     */
    private record Registered(
            PluginHandle handle, List<String> toolNames, List<String> resourceUris, List<String> promptNames) {}

    @Override
    public void attach(PluginHandle handle) {
        McpStatelessSyncServer mcp = server.getIfAvailable();
        if (mcp == null) {
            catalog.addPlugin(handle.id(), toolCatalogueEntries(handle.descriptor()));
            // The MCP feature is disabled on this installation (spring.ai.mcp.server.enabled=false):
            // there is no server to register against, and nothing calls a plugin tool through it.
            byPlugin.put(handle.id(), new Registered(handle, List.of(), List.of(), List.of()));
            return;
        }

        String prefix = idSnake(handle.id()) + "_";
        List<Object> beans = mcpAnnotatedBeans(handle.applicationContext());

        List<SyncToolSpecification> tools = SyncMcpAnnotationProviders.statelessToolSpecifications(beans);
        Map<String, PluginDescriptor.McpTool> declared = handle.descriptor().mcpTools().stream()
                .collect(Collectors.toMap(PluginDescriptor.McpTool::name, t -> t));
        Set<String> registered = tools.stream().map(t -> t.tool().name()).collect(Collectors.toSet());
        if (!registered.equals(declared.keySet())) {
            throw new IllegalStateException("Plugin '" + handle.id() + "' registers MCP tools " + sorted(registered)
                    + " but declares " + sorted(declared.keySet())
                    + " in plugin.json; every tool must be declared, with its posture, scope and permission");
        }
        for (SyncToolSpecification tool : tools) {
            checkDeclaration(handle.id(), tool.tool(), declared.get(tool.tool().name()));
            if (!tool.tool().name().startsWith(prefix)) {
                throw new IllegalStateException("Plugin '" + handle.id() + "' registers MCP tool '"
                        + tool.tool().name() + "', which is not namespaced under '" + prefix + "'");
            }
            int cost = json.writeValueAsString(tool.tool()).length() / 4;
            if (cost > PER_TOOL_TOKEN_BUDGET) {
                throw new IllegalStateException(
                        "Plugin '" + handle.id() + "' tool '" + tool.tool().name()
                                + "' costs about " + cost + " tokens to describe, over the " + PER_TOOL_TOKEN_BUDGET
                                + "-token budget every tool must fit");
            }
        }
        List<SyncResourceSpecification> resources = SyncMcpAnnotationProviders.statelessResourceSpecifications(beans);
        List<SyncPromptSpecification> prompts = SyncMcpAnnotationProviders.statelessPromptSpecifications(beans);

        // Only once every tool passed its checks: a refused activation must leave no catalogue entry.
        catalog.addPlugin(handle.id(), toolCatalogueEntries(handle.descriptor()));
        List<String> toolNames = new ArrayList<>();
        for (SyncToolSpecification tool : tools) {
            PluginDescriptor.McpTool declaration = declared.get(tool.tool().name());
            SyncToolSpecification wrapped = new SyncToolSpecification(
                    tool.tool(),
                    (ctx, request) -> callThroughPlugin(handle, declaration, tool.callHandler(), ctx, request));
            mcp.addTool(wrapped);
            toolNames.add(tool.tool().name());
        }
        List<String> resourceUris = new ArrayList<>();
        for (SyncResourceSpecification resource : resources) {
            mcp.addResource(resource);
            resourceUris.add(resource.resource().uri());
        }
        List<String> promptNames = new ArrayList<>();
        for (SyncPromptSpecification prompt : prompts) {
            mcp.addPrompt(prompt);
            promptNames.add(prompt.prompt().name());
        }
        byPlugin.put(handle.id(), new Registered(handle, toolNames, resourceUris, promptNames));
    }

    /**
     * A no-op when {@code handle} is not the current owner of its plugin id: a newer version's
     * {@link #attach} already superseded it, and that newer registration (catalogue entry, MCP
     * tools/resources/prompts) must not be torn down by the old version's detach.
     */
    @Override
    public void detach(PluginHandle handle) {
        Registered registered = byPlugin.get(handle.id());
        if (registered == null || registered.handle() != handle || !byPlugin.remove(handle.id(), registered)) {
            return;
        }
        catalog.removePlugin(handle.id());
        McpStatelessSyncServer mcp = server.getIfAvailable();
        if (mcp == null) {
            return;
        }
        registered.toolNames().forEach(mcp::removeTool);
        registered.resourceUris().forEach(mcp::removeResource);
        registered.promptNames().forEach(mcp::removePrompt);
    }

    /**
     * Every call goes through here rather than the raw {@code callHandler}: an updating plugin
     * answers the same retry message the gateway would, and a live call is wrapped in
     * {@link PluginHandle#runInPlugin} so it counts as in-flight and runs with the plugin's own
     * TCCL. A listener that throws is logged and turned into an error result, never a protocol
     * failure — the same rule {@code McpErrors} applies to every built-in tool.
     */
    private io.modelcontextprotocol.spec.McpSchema.CallToolResult callThroughPlugin(
            PluginHandle handle,
            PluginDescriptor.McpTool declaration,
            BiFunction<
                            io.modelcontextprotocol.common.McpTransportContext,
                            io.modelcontextprotocol.spec.McpSchema.CallToolRequest,
                            io.modelcontextprotocol.spec.McpSchema.CallToolResult>
                    callHandler,
            io.modelcontextprotocol.common.McpTransportContext ctx,
            io.modelcontextprotocol.spec.McpSchema.CallToolRequest request) {
        Duration retryAfter = runtimeStatus.updating(handle.id()).orElse(null);
        if (retryAfter != null) {
            return McpErrors.error("plugin " + handle.id() + " is updating, retry in " + retryAfter.toSeconds() + "s");
        }
        io.modelcontextprotocol.spec.McpSchema.CallToolResult denied = checkAccess(declaration, request);
        if (denied != null) {
            return denied;
        }
        try {
            return handle.runInPlugin(() -> callHandler.apply(ctx, request));
        } catch (io.modelcontextprotocol.spec.McpError e) {
            // The protocol's own shape: a plugin tool refusing a malformed call (-32602) says so, as
            // Rahdar's own tools do through McpErrors.
            throw e;
        } catch (Exception e) {
            log.warn("Plugin '{}' MCP tool call failed", handle.id(), e);
            return McpErrors.error("That call failed inside plugin '" + handle.id() + "'.");
        }
    }

    /**
     * The declared permission, checked before the plugin runs; {@code null} when the call may go on. A
     * missing or malformed {@code clusterId} is a malformed call (-32602), as for a built-in tool.
     */
    private io.modelcontextprotocol.spec.McpSchema.CallToolResult checkAccess(
            PluginDescriptor.McpTool declaration, io.modelcontextprotocol.spec.McpSchema.CallToolRequest request) {
        if (SCOPE_RESOURCE.equals(declaration.scope())) {
            return checkResource(declaration, request);
        }
        if (SCOPE_CLUSTER.equals(declaration.scope())) {
            UUID clusterId = clusterIdOf(request);
            try {
                clusterAccess.requireCluster(clusterId, declaration.permission());
            } catch (NotFoundException _) {
                return McpErrors.error(McpErrors.CLUSTER_DENIED);
            }
            return null;
        }
        if (!permissions.can(declaration.permission())) {
            return McpErrors.error("This key lacks " + declaration.permission() + ", which " + declaration.name()
                    + " needs. Read rahdar://permissions to see what it holds.");
        }
        return null;
    }

    /**
     * A resource tool: the permission is needed on the queue or address its {@code resourceArg} names. A
     * name the key may not read is hidden whether or not it exists; one it may read and may not use names
     * the permission and the resource, which it can already see.
     */
    private io.modelcontextprotocol.spec.McpSchema.CallToolResult checkResource(
            PluginDescriptor.McpTool declaration, io.modelcontextprotocol.spec.McpSchema.CallToolRequest request) {
        UUID clusterId = clusterIdOf(request);
        Object raw = request.arguments() == null ? null : request.arguments().get(declaration.resourceArg());
        if (!(raw instanceof String name) || name.isBlank()) {
            throw McpErrors.invalidParams(declaration.resourceArg() + " must be the name of a "
                    + declaration.resourceType() + " on the cluster.");
        }
        ResourceType kind = ResourceType.valueOf(declaration.resourceType().toUpperCase(Locale.ROOT));
        try {
            clusterAccess.requireResource(clusterId, new ResourceRef(kind, name), declaration.permission());
        } catch (ResourceForbiddenException e) {
            return McpErrors.error("This key lacks " + e.permission() + " on " + e.kind() + " " + e.name() + ".");
        } catch (NotFoundException _) {
            // Whether the cluster or the resource is what the key may not see, the answer names neither.
            return McpErrors.error(
                    permissions.canSeeCluster(clusterId) ? McpErrors.RESOURCE_DENIED : McpErrors.CLUSTER_DENIED);
        }
        return null;
    }

    /** A missing or malformed {@code clusterId} is a malformed call (-32602), as for a built-in tool. */
    private static UUID clusterIdOf(io.modelcontextprotocol.spec.McpSchema.CallToolRequest request) {
        Object raw = request.arguments() == null ? null : request.arguments().get(CLUSTER_ID);
        try {
            return UUID.fromString(String.valueOf(raw));
        } catch (IllegalArgumentException _) {
            throw McpErrors.invalidParams("clusterId must be a cluster id; read rahdar://clusters for them.");
        }
    }

    /**
     * What plugin.json says must match what the tool is: a cluster-scoped tool takes a required
     * {@code clusterId}, and a {@code read} tool is annotated read-only (and a {@code write} one is
     * not), because a host decides from that annotation whether to ask the operator first.
     */
    private static void checkDeclaration(
            String pluginId, io.modelcontextprotocol.spec.McpSchema.Tool tool, PluginDescriptor.McpTool declared) {
        if (SCOPE_CLUSTER.equals(declared.scope()) || SCOPE_RESOURCE.equals(declared.scope())) {
            requireStringArgument(pluginId, tool, CLUSTER_ID, declared.scope());
        }
        if (SCOPE_RESOURCE.equals(declared.scope())) {
            requireStringArgument(pluginId, tool, declared.resourceArg(), declared.scope());
        }
        boolean readOnly = tool.annotations() != null
                && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
        if (readOnly != POSTURE_READ.equals(declared.posture())) {
            throw new IllegalStateException("Plugin '" + pluginId + "' tool '" + tool.name() + "' is declared '"
                    + declared.posture() + "' but annotated readOnlyHint = " + readOnly
                    + "; a read tool is read-only and a write tool is not");
        }
    }

    /** A cluster or resource tool takes the argument its scope is checked on, required and a string. */
    private static void requireStringArgument(
            String pluginId, io.modelcontextprotocol.spec.McpSchema.Tool tool, String argument, String scope) {
        Map<String, Object> schema = tool.inputSchema() == null ? Map.of() : tool.inputSchema();
        boolean required = schema.get("required") instanceof List<?> r && r.contains(argument);
        boolean string = schema.get("properties") instanceof Map<?, ?> props
                && props.get(argument) instanceof Map<?, ?> p
                && "string".equals(p.get("type"));
        if (!required || !string) {
            throw new IllegalStateException("Plugin '" + pluginId + "' tool '" + tool.name() + "' is " + scope
                    + "-scoped but takes no required string argument '" + argument + "'");
        }
    }

    private static List<McpToolDef> toolCatalogueEntries(PluginDescriptor descriptor) {
        return descriptor.mcpTools().stream()
                .map(PluginDescriptor.McpTool::toCatalogueEntry)
                .toList();
    }

    private static List<String> sorted(Set<String> names) {
        return names.stream().sorted().toList();
    }

    private static String idSnake(String pluginId) {
        return pluginId.replace('-', '_');
    }

    private static List<Object> mcpAnnotatedBeans(ApplicationContext ctx) {
        List<Object> beans = new ArrayList<>();
        for (String name : ctx.getBeanDefinitionNames()) {
            Class<?> type = ctx.getType(name);
            if (type != null && hasMcpAnnotatedMethod(type)) {
                beans.add(ctx.getBean(name));
            }
        }
        return beans;
    }

    private static boolean hasMcpAnnotatedMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.isAnnotationPresent(McpTool.class)
                    || method.isAnnotationPresent(McpResource.class)
                    || method.isAnnotationPresent(McpPrompt.class)) {
                return true;
            }
        }
        return false;
    }
}
