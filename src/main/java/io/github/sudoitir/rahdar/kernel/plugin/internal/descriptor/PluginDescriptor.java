package io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor;

import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionScope;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mirrors {@code META-INF/rahdar/plugin.json} (design.md §1), validated against
 * {@code META-INF/rahdar/plugin.schema.json}. One field per JSON property; unknown
 * properties are rejected by {@link PluginDescriptorParser}, not silently dropped.
 */
public record PluginDescriptor(
        int schemaVersion,
        String id,
        String name,
        String version,
        Vendor vendor,
        String description,
        String license,
        String changeNotes,
        String basePackage,
        String configuration,
        int contract,
        Rahdar rahdar,
        List<String> requires,
        boolean ui,
        Boolean requiresLicense,
        Activation activation,
        String updateUrl,
        String title,
        List<Permission> permissions,
        List<String> settingKeys,
        List<String> streamTopics,
        List<McpTool> mcpTools,
        List<Metric> metrics,
        List<AlertRule> alertRules,
        List<IdentityProvider> identityProviders,
        ApprovalProvider approvalProvider,
        AuditAccess audit) {

    public PluginDescriptor {
        requires = requires == null ? List.of() : List.copyOf(requires);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        settingKeys = settingKeys == null ? List.of() : List.copyOf(settingKeys);
        streamTopics = streamTopics == null ? List.of() : List.copyOf(streamTopics);
        mcpTools = mcpTools == null ? List.of() : List.copyOf(mcpTools);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        alertRules = alertRules == null ? List.of() : List.copyOf(alertRules);
        identityProviders = identityProviders == null ? List.of() : List.copyOf(identityProviders);
        audit = audit == null ? new AuditAccess(false, false) : audit;
    }

    /** Whether the plugin needs a license file; a plugin that does not say needs none. */
    public boolean isRequiresLicense() {
        return Boolean.TRUE.equals(requiresLicense);
    }

    /**
     * Access to the audit log beyond what any plugin may read.
     *
     * @param receive receives every audit event, unfiltered; absent means false
     * @param retentionHold may hold audit records back from retention; absent means false
     */
    public record AuditAccess(Boolean receive, Boolean retentionHold) {

        public AuditAccess {
            receive = Boolean.TRUE.equals(receive);
            retentionHold = Boolean.TRUE.equals(retentionHold);
        }
    }

    public record Vendor(String name, String url, String email) {}

    public record Rahdar(String since, String until) {}

    /**
     * @param scope {@code global}, {@code cluster} or {@code resource}: where the permission takes effect
     * @param resourceTypes {@code subscription} and {@code destination}: what a {@code resource} permission acts on;
     *     required for that scope and refused for the others
     * @param requires permissions a role must hold with this one
     * @param globalOnly replaced by {@code scope}; read only so that the validator can refuse it by name
     */
    public record Permission(
            String action,
            String description,
            String scope,
            List<String> resourceTypes,
            List<String> requires,
            Boolean globalOnly) {

        public Permission {
            resourceTypes = resourceTypes == null ? List.of() : List.copyOf(resourceTypes);
            requires = requires == null ? List.of() : List.copyOf(requires);
        }

        /** @throws IllegalArgumentException when the scope or a resource type is not one Rahdar knows (the validator refuses it first) */
        public PermissionDef toDef() {
            return new PermissionDef(
                    action,
                    description,
                    PermissionScope.valueOf(scope.toUpperCase(Locale.ROOT)),
                    resourceTypes.stream()
                            .map(kind -> ResourceType.valueOf(kind.toUpperCase(Locale.ROOT)))
                            .collect(Collectors.toSet()),
                    Set.copyOf(requires));
        }
    }

    /**
     * An assistant tool the plugin registers. Rahdar checks {@code permission} before the tool runs:
     * on the queue or address named by its {@code resourceArg} on the cluster named by its
     * {@code clusterId} argument when {@code scope} is {@code resource}, on that cluster when it is
     * {@code cluster}, otherwise globally.
     *
     * @param posture {@code read} or {@code write}
     * @param scope {@code resource}, {@code cluster} or {@code global}; it matches the scope of the permission
     * @param permission one of the plugin's declared permission actions
     * @param resourceArg a {@code resource} tool's required string argument holding the queue or address name
     * @param resourceType {@code subscription} or {@code destination}: what that argument names
     * @param params the detail the tool's schema leaves out, shown by {@code rahdar_help}
     */
    public record McpTool(
            String name,
            String posture,
            String scope,
            String permission,
            String resourceArg,
            String resourceType,
            String description,
            List<McpParam> params) {

        public McpTool {
            params = params == null ? List.of() : List.copyOf(params);
        }

        /** This tool as the MCP catalogue lists it: {@code write} is the catalogue's {@code MUTATE}. */
        public McpToolDef toCatalogueEntry() {
            return new McpToolDef(
                    name,
                    "read".equals(posture) ? McpToolDef.Posture.READ : McpToolDef.Posture.MUTATE,
                    description,
                    params.stream()
                            .map(p -> new McpToolDef.Param(p.name(), p.values(), p.shape(), p.note()))
                            .toList(),
                    new McpToolDef.Access(permission, scope, resourceArg, resourceType));
        }
    }

    /**
     * @param values the values a discriminator accepts
     * @param shape the JSON or YAML shape a body argument takes
     * @param note anything a model gets wrong without being told
     */
    public record McpParam(String name, List<String> values, String shape, String note) {}

    /**
     * A metric the plugin publishes through a {@code PluginMetricSource} (ADR-0113).
     *
     * @param name {@code <id>:<name>}, the identifier the source returns
     * @param unit {@code count}, {@code per_second}, {@code ms} or {@code ratio}
     * @param subject what the values are keyed by, for example "job"
     * @param permission the plugin permission that reads the metric's series
     */
    public record Metric(String name, String description, String unit, String subject, String permission) {}

    /** A default threshold rule on one of the plugin's metrics, seeded once per cluster (ADR-0113). */
    public record AlertRule(
            String key,
            String name,
            String metric,
            String comparator,
            double threshold,
            int forSeconds,
            String severity) {}

    /**
     * A username-and-password sign-in the plugin offers through a {@code PluginCredentialProvider}
     * bean (ADR-0156). Declared here so the login screen and the group mappings list it without
     * calling plugin code, and so the install review can say the plugin will receive passwords.
     *
     * @param id {@code <plugin id>:<name>}, the provider id Rahdar keys accounts and mappings by
     * @param label what the login screen shows, 1 to 64 characters
     */
    public record IdentityProvider(String id, String label) {}

    /**
     * The plugin is the approval provider (ADR-0179): it exposes one {@code ApprovalProvider} bean, and
     * Rahdar asks it whether gated operations may run.
     *
     * @param approverPermission one of the plugin's declared permission actions, which its approvers hold
     */
    public record ApprovalProvider(String approverPermission) {}

    public enum Activation {
        AUTO,
        RESTART
    }
}
