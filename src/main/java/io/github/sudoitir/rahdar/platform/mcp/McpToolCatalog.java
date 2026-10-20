package io.github.sudoitir.rahdar.platform.mcp;

import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef.Posture;
import io.github.sudoitir.rahdar.kernel.security.TokenPrincipal;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The one description of the MCP surface (ADR-0054), assembled from the tools the enabled
 * modules declare in their descriptors (ADR-0070).
 *
 * <p>Everything the server says about itself is generated from here: the
 * {@code rahdar_help} tool, the {@code rahdar://tools} resource, and the
 * {@code instructions} block sent at initialisation. Before this existed the
 * instructions were retyped by hand in {@code application.yml} and had drifted —
 * they omitted {@code resource_lifecycle} and {@code message_body}, so the one text a
 * tool-searching host is guaranteed to read asserted, by omission, that two
 * capabilities did not exist. That is non-negotiable #5 broken by a duplicate, and
 * the fix is to have no duplicate.
 *
 * <p>{@code McpToolSchemaBudgetTest} fails the build when a registered tool is missing
 * from the catalogue, which is the same argument ADR-0045 made about listing size:
 * review does not catch drift, a build failure does.
 *
 * <p>What it describes is narrowed to the caller (ADR-0137): a token's MCP tool allow-list and the
 * installation's read-only mode decide what is {@linkplain #offered offered}, and so does the broker
 * system: a tool that exists for one system (such as {@code artemis_config}) is offered only to a caller who
 * can read a cluster that runs it (ADR-0207). Every description
 * below — index, detail, instructions — names only offered tools, so a restricted token learns
 * nothing about the rest. {@code rahdar_help} is always offered.
 *
 * <p>This is documentation, not validation. Every discriminator is still checked in
 * {@link McpArgs} and every rejection names both the accepted values and
 * {@code rahdar_help}, so a model that reads none of this is inconvenienced, never
 * wrong.
 */
@Component
class McpToolCatalog {

    /** The discovery tool's own name, referenced from rejection messages. */
    static final String HELP_TOOL = "rahdar_help";

    /** The tool that returns resource ids, referenced from rejection messages. */
    static final String LIST_TOOL = "list_resources";

    private final List<McpToolDef> builtin;
    private final Map<String, List<McpToolDef>> pluginEntries = new ConcurrentHashMap<>();
    private final FeatureRegistry features;
    private final ObjectProvider<SettingsService> settings;
    private final McpSystems systems;
    private volatile List<McpToolDef> entries;

    McpToolCatalog(FeatureRegistry features, ObjectProvider<SettingsService> settings, McpSystems systems) {
        this.features = features;
        this.systems = systems;
        this.builtin =
                features.enabled().stream().flatMap(d -> d.mcpTools().stream()).toList();
        this.settings = settings;
        this.entries = builtin;
    }

    /** Every registered tool, whoever asks. */
    List<McpToolDef> entries() {
        return entries;
    }

    /** The tools offered to the current caller. */
    List<McpToolDef> visible() {
        return entries.stream().filter(e -> offered(e.name())).toList();
    }

    /**
     * Whether the current caller is offered this tool: allowed by its token, not refused as read-only,
     * and, for a tool of one broker system, able to read a cluster that runs it (ADR-0207).
     */
    boolean offered(String tool) {
        return allowedByToken(tool) && !refusedAsReadOnly(tool) && appliesToCaller(tool);
    }

    private boolean appliesToCaller(String tool) {
        McpToolDef def = definition(tool);
        return def == null || def.system() == null || systems.readsAny(def.system());
    }

    /** The tool error for calling {@code tool} on a cluster that runs another broker system, or {@code null}. */
    String refusedOnCluster(String tool, UUID clusterId) {
        McpToolDef def = definition(tool);
        return def == null ? null : systems.refusal(def, clusterId);
    }

    /** The help tool always; anything else when the caller's token has no allow-list or lists it. */
    boolean allowedByToken(String tool) {
        if (HELP_TOOL.equals(tool)) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !(auth.getPrincipal() instanceof TokenPrincipal token) || token.mayCallTool(tool);
    }

    /** A mutating tool while the installation's agent surface is read-only. */
    boolean refusedAsReadOnly(String tool) {
        if (!readOnly()) {
            return false;
        }
        McpToolDef def = definition(tool);
        return def != null && def.posture() == Posture.MUTATE;
    }

    /** With the MCP module disabled its setting does not exist, and there is no surface to make read-only. */
    boolean readOnly() {
        return features.isEnabled(McpModule.DESCRIPTOR.id())
                && settings.getObject().bool(McpSettings.READ_ONLY);
    }

    private McpToolDef definition(String tool) {
        return entries.stream()
                .filter(e -> e.name().equalsIgnoreCase(tool))
                .findFirst()
                .orElse(null);
    }

    /**
     * Adds a plugin's declared MCP tools to the live catalogue (design.md, task 6.5): reads
     * happen on every call, so {@code rahdar_help} and {@code rahdar://tools} are always current —
     * there is no {@code list_changed} on the stateless server, so this is the only way a client
     * that asks again ever finds out.
     */
    synchronized void addPlugin(String pluginId, List<McpToolDef> defs) {
        pluginEntries.put(pluginId, defs);
        recompute();
    }

    synchronized void removePlugin(String pluginId) {
        pluginEntries.remove(pluginId);
        recompute();
    }

    private void recompute() {
        List<McpToolDef> next = new ArrayList<>(builtin);
        pluginEntries.values().forEach(next::addAll);
        entries = List.copyOf(next);
    }

    List<String> toolNames() {
        return visible().stream().map(McpToolDef::name).toList();
    }

    /** One offered tool's detail, or {@code null} when the topic names nothing the caller is offered. */
    McpToolDef find(String tool) {
        McpToolDef def = definition(tool);
        return def != null && offered(def.name()) ? def : null;
    }

    /**
     * The compact index: what exists, grouped by posture, without any parameter
     * detail. This is what a model reads to choose a tool; it reads {@link #find}
     * only once it has chosen.
     */
    Map<String, List<Map<String, String>>> index() {
        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        List<McpToolDef> offered = visible();
        for (Posture posture : Posture.values()) {
            out.put(
                    posture == Posture.READ ? "read" : "mutate",
                    offered.stream()
                            .filter(e -> e.posture() == posture)
                            .map(McpToolCatalog::indexEntry)
                            .toList());
        }
        return out;
    }

    /** A plugin tool also names what it needs, which Rahdar checks before it runs (ADR-0114). */
    private static Map<String, String> indexEntry(McpToolDef e) {
        if (e.access() == null) {
            return Map.of("tool", e.name(), "summary", e.summary());
        }
        return Map.of(
                "tool", e.name(),
                "summary", e.summary(),
                "permission", e.access().permission(),
                "scope", e.access().scope());
    }

    /**
     * The {@code instructions} sent at initialisation, generated rather than
     * retyped and built for the caller. Under a host that searches tools instead of
     * dumping {@code tools/list}, this is the only text guaranteed to be read, so it
     * names every offered tool and where the detail is.
     */
    String instructions() {
        List<McpToolDef> offered = visible();
        String reads = offered.stream()
                .filter(e -> e.posture() == Posture.READ)
                .map(McpToolDef::name)
                .collect(Collectors.joining(", "));
        String mutations = offered.stream()
                .filter(e -> e.posture() == Posture.MUTATE)
                .map(McpToolDef::name)
                .collect(Collectors.joining(", "));
        String mutating = mutatingSentence(mutations);
        return "Rahdar: cluster-wide management and observability for message brokers. "
                + "Read tools: " + reads + ". "
                + mutating
                + "Call " + HELP_TOOL + " for the accepted values and JSON body shapes the tool schemas leave "
                + "out; the schemas are deliberately terse and " + HELP_TOOL + " is the complete reference. "
                + "Resources are named by the role they play (destination, subscription, route, connection, channel, "
                + "consumer, producer) and every tool takes their canonical ids, such as artemis.queue/orders, "
                + "as " + LIST_TOOL + " returns them; a tool prefixed with a system's id applies to that system's "
                + "clusters only. "
                + "Resources mirror the same detail for hosts that read them: rahdar://clusters (the source of "
                + "cluster ids this key can see), rahdar://permissions (what it may do), rahdar://tools, "
                + "cluster://{id}/topology, cluster://{id}/capabilities, "
                + "cluster://{id}/nodes/{nodeId}/settings. "
                + "Start from rahdar://clusters or " + HELP_TOOL + ". "
                + "Installed plugins may add their own tools, prefixed with the plugin's id; call " + HELP_TOOL
                + " to see the current index, which always reflects what is installed right now.";
    }

    private String mutatingSentence(String mutations) {
        if (readOnly()) {
            return "The agent surface is read-only on this installation: no mutating tool is offered. ";
        }
        if (mutations.isEmpty()) {
            return "This key is offered no mutating tools. ";
        }
        return "Mutating tools: " + mutations + " — all default to dryRun=true, and a real destructive "
                + "run needs `confirm` to equal the subject's name. ";
    }
}
