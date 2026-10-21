package io.github.sudoitir.rahdar.kernel.security.internal;

import io.github.sudoitir.rahdar.kernel.plugin.CatalogueEntry;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionScope;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamMemberRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamRepository;
import io.github.sudoitir.rahdar.kernel.security.web.AccessViews.AccessSummary;
import io.github.sudoitir.rahdar.kernel.security.web.AccessViews.CreatePatterns;
import io.github.sudoitir.rahdar.kernel.security.web.AccessViews.MyResourceAccess;
import io.github.sudoitir.rahdar.kernel.security.web.AccessViews.TeamMembership;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the signed-in caller holds, answered by the same {@link PermissionResolver} that enforces it, so the
 * console never has to re-derive scope rules (environment grants, teams, shares) and cannot drift from the
 * server. It only informs what to offer; every operation is still checked when it is attempted.
 */
@Service
@RequiredArgsConstructor
public class MyAccess {

    /** Catalogued by the queues feature; the kernel only asks where the caller holds them. */
    private static final String SUBSCRIPTION_CREATE = "subscription:create";

    private static final String DESTINATION_CREATE = "destination:create";

    private final PermissionResolver perm;
    private final FeatureRegistry features;
    private final TeamMemberRepository members;
    private final TeamRepository teams;
    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;

    /**
     * The caller's permissions on {@code clusterId}, or globally when it is null, with the resource permissions
     * they hold somewhere on the cluster and the teams they belong to.
     */
    @Transactional(readOnly = true)
    public AccessSummary of(UUID clusterId) {
        List<CatalogueEntry> catalogue = features.catalogue();
        List<String> held = catalogue.stream()
                .map(CatalogueEntry::action)
                .filter(action -> perm.can(clusterId, action))
                .sorted()
                .toList();
        List<String> anywhere = clusterId == null
                ? List.of()
                : catalogue.stream()
                        .filter(e -> e.scope() == PermissionScope.RESOURCE)
                        .map(CatalogueEntry::action)
                        .filter(action -> perm.canAnywhere(clusterId, action))
                        .sorted()
                        .toList();
        CreatePatterns createPatterns = clusterId == null
                ? new CreatePatterns(List.of(), List.of())
                : new CreatePatterns(
                        perm.patternsHolding(clusterId, ResourceType.SUBSCRIPTION, SUBSCRIPTION_CREATE),
                        perm.patternsHolding(clusterId, ResourceType.DESTINATION, DESTINATION_CREATE));
        return new AccessSummary(
                held, anywhere, clusterId == null ? null : perm.canSeeCluster(clusterId), teams(), createPatterns);
    }

    /**
     * The actions the caller holds on one queue or address. A resource they may not read has none, whether or
     * not it exists.
     */
    public MyResourceAccess onResource(UUID clusterId, ResourceType kind, String name) {
        ResourceFilter filter = perm.filter(clusterId, kind);
        return new MyResourceAccess(filter.readable(name) ? filter.allowedActions(name) : List.of());
    }

    /** The teams the caller belongs to, directly or through a directory group; none for an API key. */
    private List<TeamMembership> teams() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof RahdarPrincipal principal) || principal.pinned()) {
            return List.of();
        }
        Map<UUID, String> names =
                teams.findAll().stream().collect(Collectors.toMap(TeamEntity::getId, TeamEntity::getName));
        Map<UUID, RoleEntity> roleById =
                roles.findAll().stream().collect(Collectors.toMap(RoleEntity::getId, Function.identity()));
        return members.findHeldBy(principal.userId()).stream()
                .filter(m -> names.containsKey(m.getTeamId()) && roleById.containsKey(m.getRoleId()))
                .map(m -> {
                    RoleEntity role = roleById.get(m.getRoleId());
                    Set<String> permissions = rolePermissions.findByIdRoleId(role.getId()).stream()
                            .map(RolePermissionEntity::getAction)
                            .collect(Collectors.toSet());
                    return new TeamMembership(
                            m.getTeamId(),
                            names.get(m.getTeamId()),
                            role.getId(),
                            role.getName(),
                            Grant.covers(permissions, Permissions.TEAM_ADMIN));
                })
                .sorted(Comparator.comparing(TeamMembership::teamName).thenComparing(TeamMembership::roleName))
                .toList();
    }
}
