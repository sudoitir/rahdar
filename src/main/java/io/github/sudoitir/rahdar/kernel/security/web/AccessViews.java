package io.github.sudoitir.rahdar.kernel.security.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.kernel.plugin.PermissionScope;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** What the caller holds, and why a user may or may not do something (authorization spec). */
public final class AccessViews {

    private AccessViews() {}

    @Schema(description = "A team the caller belongs to, with the team role they hold in it.")
    public record TeamMembership(
            @Schema(requiredMode = REQUIRED) UUID teamId,
            @Schema(requiredMode = REQUIRED) String teamName,
            @Schema(requiredMode = REQUIRED) UUID roleId,
            @Schema(requiredMode = REQUIRED) String roleName,

            @Schema(requiredMode = REQUIRED, description = "Whether the team role holds team:admin.")
            boolean teamAdmin) {}

    @Schema(
            description = "What the caller holds, for the console to offer or withhold controls. The server stays "
                    + "the enforcement point.")
    public record AccessSummary(
            @Schema(
                    requiredMode = REQUIRED,
                    description = "Every catalogue permission the caller holds on the cluster, or globally when no "
                            + "cluster is asked about: through a role grant at global, environment or cluster scope.")
            List<String> permissions,

            @Schema(
                    requiredMode = REQUIRED,
                    description =
                            "Resource permissions the caller holds on some subscription or destination of the cluster, "
                                    + "through a grant, a team or a share. Empty when no cluster is asked about.")
            List<String> anywhere,

            @Schema(
                    nullable = true,
                    description = "Whether the caller may see the cluster at all; null when no cluster is asked about.")
            Boolean canSeeCluster,

            @Schema(requiredMode = REQUIRED) List<TeamMembership> teams,

            @Schema(
                    requiredMode = REQUIRED,
                    description =
                            "Patterns on which the caller may create a subscription or destination through a team or a "
                                    + "share. A grant that reaches the cluster allows any name and shows in permissions.")
            CreatePatterns createPatterns) {}

    @Schema(description = "Name patterns, as written, that the caller may create under. Empty when none.")
    public record CreatePatterns(
            @Schema(requiredMode = REQUIRED) List<String> subscription,
            @Schema(requiredMode = REQUIRED) List<String> destination) {}

    @Schema(description = "What the caller may do with one subscription or destination: the actions they hold on it.")
    public record MyResourceAccess(
            @Schema(
                    requiredMode = REQUIRED,
                    description = "The resource permissions the caller holds on it, through a grant, a team or a "
                            + "share, including plugins'. Empty for a resource they may not read.")
            List<String> actions) {}

    public enum SourceType {
        ROLE_GRANT,
        TEAM,
        SHARE
    }

    @Schema(
            description = "One way a user holds a permission: a role granted at a scope, a team role on the team that "
                    + "owns the resource, or a share from the owning team.")
    public record AccessSource(
            @Schema(requiredMode = REQUIRED) SourceType type,
            @Schema(requiredMode = REQUIRED) String roleName,

            @Schema(nullable = true, description = "ROLE_GRANT only: GLOBAL, ENVIRONMENT or CLUSTER.")
            String scopeType,

            @Schema(nullable = true, description = "ROLE_GRANT only: the environment or cluster of the grant.")
            UUID scopeId,

            @Schema(nullable = true, description = "TEAM and SHARE: the team whose role the user holds.")
            UUID teamId,

            @Schema(nullable = true) String teamName,

            @Schema(nullable = true, description = "SHARE only: the team that shared the pattern.")
            String ownerTeamName) {}

    @Schema(description = "One catalogue permission for a user, here, with every source that allows it.")
    public record AccessCheckView(
            @Schema(requiredMode = REQUIRED) String action,
            @Schema(requiredMode = REQUIRED) String description,
            @Schema(requiredMode = REQUIRED) PermissionScope scope,
            @Schema(requiredMode = REQUIRED) boolean allowed,
            @Schema(requiredMode = REQUIRED) List<AccessSource> sources) {}
}
