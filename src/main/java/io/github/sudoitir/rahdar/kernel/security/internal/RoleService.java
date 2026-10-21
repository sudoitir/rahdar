package io.github.sudoitir.rahdar.kernel.security.internal;

import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.plugin.CatalogueEntry;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionScope;
import io.github.sudoitir.rahdar.kernel.security.AccessChanges;
import io.github.sudoitir.rahdar.kernel.security.AdministrationAudit;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamMemberRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamShareRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.PermissionView;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.RoleView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Custom role CRUD. Built-in roles ({@code ADMIN}/{@code OPERATOR}/{@code VIEWER},
 * {@code role.builtin = true}) can be granted to users but never renamed, given other permissions
 * or deleted (authorization spec, design.md decision 4) — the permission model is
 * fully dynamic, so this immutability is the only thing stopping an operator
 * from quietly hollowing out a built-in role's meaning. The one thing an administrator may change
 * on a built-in role is whether it requires a second factor (ADR-0143).
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final FeatureRegistry features;
    private final UserRoleRepository userRoles;
    private final TeamMemberRepository teamMembers;
    private final TeamShareRepository teamShares;
    private final AdministrationAudit audit;
    private final AppUserRepository users;
    private final AccessChanges accessChanges;
    private final SessionTerminator sessions;
    private final OperationGate gate;
    private final TransactionTemplate tx;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.kernel.security.Permissions).USER_ADMIN)")
    @Transactional(readOnly = true)
    public List<RoleView> list() {
        return roles.findAllByOrderByName().stream().map(this::toView).toList();
    }

    /**
     * Every permission Rahdar and its active plugins declare, with what each means. Any signed-in user may read it:
     * it names permissions, not who holds them, and minting an API key narrows a key to permissions from it. Roles
     * and grants stay behind {@code user:admin}.
     */
    @PreAuthorize("isAuthenticated()")
    public List<PermissionView> catalogue() {
        return features.catalogue().stream()
                .map(e -> new PermissionView(
                        e.action(),
                        e.description(),
                        e.featureId(),
                        e.featureTitle(),
                        e.scope(),
                        e.resourceTypes().stream().sorted().toList(),
                        e.requires().stream().sorted().toList()))
                .toList();
    }

    @Gated("role.create")
    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.kernel.security.Permissions).USER_ADMIN)")
    public RoleView create(RoleRequest request) {
        requireNewRole(request);
        return gate.run(
                Operation.of(new RoleOperations.CreateRole(
                        request.name(), request.permissions(), request.requiresMfa(), request.teamAssignable())),
                GatedWrites.inTx(tx, () -> createNow(request)));
    }

    private void requireNewRole(RoleRequest request) {
        if (roles.findByName(request.name()).isPresent()) {
            throw new ConflictException("duplicate-role-name", "A role named '" + request.name() + "' already exists.");
        }
        requireConsistent(request);
    }

    private RoleView createNow(RoleRequest request) {
        requireNewRole(request);
        RoleEntity role = new RoleEntity(request.name(), false);
        role.setRequiresMfa(request.requiresMfa());
        role.setTeamAssignable(request.teamAssignable());
        role = roles.save(role);
        savePermissions(role.getId(), request.permissions());
        audit.changed("ROLE_CREATE", "role", role.getName(), null);
        accessChanges.changed();
        return toView(role);
    }

    @Gated("role.update")
    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.kernel.security.Permissions).USER_ADMIN)")
    public RoleView update(UUID roleId, RoleRequest request) {
        requireUpdatable(roleId, request);
        return gate.run(
                Operation.of(new RoleOperations.UpdateRole(
                        roleId,
                        request.name(),
                        request.permissions(),
                        request.requiresMfa(),
                        request.teamAssignable())),
                GatedWrites.inTx(tx, () -> updateNow(roleId, request)));
    }

    private RoleEntity requireUpdatable(UUID roleId, RoleRequest request) {
        RoleEntity role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role", roleId));
        if (role.isBuiltin()) {
            requireOnlyMfaChanges(role, request);
        } else {
            requireConsistent(request);
            if (role.isTeamAssignable() && !request.teamAssignable() && usedByATeam(roleId)) {
                throw new ConflictException(
                        "role-in-use",
                        "This role is still the role of a team member or a share, so it must stay a team role.");
            }
        }
        return role;
    }

    private RoleView updateNow(UUID roleId, RoleRequest request) {
        RoleEntity role = requireUpdatable(roleId, request);
        boolean mfaChanged = role.isRequiresMfa() != request.requiresMfa();
        if (!role.isBuiltin()) {
            role.setName(request.name());
            role.setTeamAssignable(request.teamAssignable());
            rolePermissions.deleteByIdRoleId(roleId);
            savePermissions(roleId, request.permissions());
        }
        role.setRequiresMfa(request.requiresMfa());
        roles.save(role);
        audit.changed("ROLE_UPDATE", "role", role.getName(), null);
        accessChanges.changed();
        // Members who were signed in without the factor the role now requires must sign in again; what the
        // role grants applies to their next request without it.
        if (mfaChanged) {
            sessions.endSessionsOf(Stream.concat(
                            userRoles.findByIdRoleId(roleId).stream().map(UserRoleEntity::getUserId),
                            teamMembers.findUserIdsHoldingRole(roleId).stream())
                    .distinct()
                    .flatMap(id -> users.findById(id).stream())
                    .map(AppUserEntity::getUsername)
                    .toList());
        }
        return toView(role);
    }

    @Gated("role.delete")
    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.kernel.security.Permissions).USER_ADMIN)")
    public void delete(UUID roleId) {
        requireDeletable(roleId);
        gate.run(
                Operation.of(new RoleOperations.DeleteRole(roleId)), GatedWrites.inTxVoid(tx, () -> deleteNow(roleId)));
    }

    private RoleEntity requireDeletable(UUID roleId) {
        RoleEntity role = requireEditable(roleId);
        if (userRoles.countByIdRoleId(roleId) > 0 || usedByATeam(roleId)) {
            throw new ConflictException(
                    "role-in-use", "This role is still granted to a user, or is the role of a team member or share.");
        }
        return role;
    }

    private void deleteNow(UUID roleId) {
        RoleEntity role = requireDeletable(roleId);
        roles.delete(role); // cascades role_permission
        audit.changed("ROLE_DELETE", "role", role.getName(), null);
        accessChanges.changed();
    }

    private boolean usedByATeam(UUID roleId) {
        return teamMembers.existsByRoleId(roleId) || teamShares.existsByRoleId(roleId);
    }

    /**
     * A role holds every permission its permissions require, and a team role holds only permissions that
     * act on a resource, plus team administration. A wildcard satisfies what it covers, and is checked for
     * the catalogued permissions it stands for.
     */
    private void requireConsistent(RoleRequest request) {
        Set<String> held = new HashSet<>(request.permissions());
        List<CatalogueEntry> catalogue = features.catalogue();
        List<String> missing = new ArrayList<>();
        for (CatalogueEntry entry : catalogue) {
            if (Grant.covers(held, entry.action())) {
                entry.requires().stream()
                        .filter(required -> !Grant.covers(held, required))
                        .forEach(required -> missing.add(entry.action() + " needs " + required));
            }
        }
        if (!missing.isEmpty()) {
            throw new ConflictException(
                    "role-missing-requirements",
                    "The role lacks permissions that the ones it holds require: "
                            + missing.stream().distinct().sorted().collect(Collectors.joining(", "))
                            + ".");
        }
        if (request.teamAssignable()) {
            List<String> notForTeams = held.stream()
                    .filter(action -> !Permissions.TEAM_ADMIN.equals(action))
                    .filter(action -> features.permission(action)
                            .map(entry -> entry.scope() != PermissionScope.RESOURCE)
                            .orElse(true))
                    .sorted()
                    .toList();
            if (!notForTeams.isEmpty()) {
                throw new ConflictException(
                        "team-role-permissions",
                        "A team role may hold only permissions that act on a queue or address, and team:admin;"
                                + " remove "
                                + String.join(", ", notForTeams)
                                + ".");
            }
        }
    }

    private void savePermissions(UUID roleId, List<String> permissions) {
        for (String action : permissions) {
            rolePermissions.save(new RolePermissionEntity(roleId, action));
        }
    }

    private RoleEntity requireEditable(UUID roleId) {
        RoleEntity role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role", roleId));
        if (role.isBuiltin()) {
            throw new ConflictException("builtin-role", "Built-in roles cannot be deleted.");
        }
        return role;
    }

    private void requireOnlyMfaChanges(RoleEntity role, RoleRequest request) {
        Set<String> current = rolePermissions.findByIdRoleId(role.getId()).stream()
                .map(RolePermissionEntity::getAction)
                .collect(Collectors.toSet());
        if (!role.getName().equals(request.name())
                || !current.equals(new HashSet<>(request.permissions()))
                || role.isTeamAssignable() != request.teamAssignable()) {
            throw new ConflictException(
                    "builtin-role",
                    "Built-in roles keep their name, permissions and use as a team role; only whether they require"
                            + " two-step verification can change.");
        }
    }

    private RoleView toView(RoleEntity role) {
        List<String> permissions = rolePermissions.findByIdRoleId(role.getId()).stream()
                .map(RolePermissionEntity::getAction)
                .toList();
        return new RoleView(
                role.getId(),
                role.getName(),
                role.isBuiltin(),
                permissions,
                role.isRequiresMfa(),
                role.isTeamAssignable());
    }
}
