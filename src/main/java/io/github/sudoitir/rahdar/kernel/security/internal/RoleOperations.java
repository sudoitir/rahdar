package io.github.sudoitir.rahdar.kernel.security.internal;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.security.AccessOperation;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.RoleRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The gated operations of {@link RoleService}. */
@Configuration(proxyBeanMethods = false)
class RoleOperations {

    private static final String LABEL_PERMISSIONS = "Permissions";

    record CreateRole(String name, List<String> permissions, boolean requiresMfa, boolean teamAssignable) {}

    record UpdateRole(
            UUID roleId, String name, List<String> permissions, boolean requiresMfa, boolean teamAssignable) {}

    record DeleteRole(UUID roleId) {}

    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final ApproverAccess approvers;

    RoleOperations(RoleRepository roles, RolePermissionRepository rolePermissions, ApproverAccess approvers) {
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.approvers = approvers;
    }

    @Bean
    GatedOperation<CreateRole> roleCreateOperation(RoleService service) {
        return new AccessOperation<>("role.create", CreateRole.class) {
            @Override
            public Set<Trait> traits(CreateRole p) {
                return access(approvers.grantsApproval(p.permissions()));
            }

            @Override
            public String summary(CreateRole p) {
                return "Create role " + p.name();
            }

            @Override
            public List<DisplayRow> display(CreateRole p) {
                return List.of(
                        DisplayRow.of("Role", p.name()),
                        DisplayRow.of(LABEL_PERMISSIONS, join(p.permissions())),
                        DisplayRow.of("Requires a second factor", String.valueOf(p.requiresMfa())),
                        DisplayRow.of("Team role", String.valueOf(p.teamAssignable())));
            }

            @Override
            public Effect estimate(CreateRole p) {
                return new Effect(1, "role", etag(p.name()), null);
            }

            @Override
            public void replay(CreateRole p) {
                service.create(new RoleRequest(p.name(), p.permissions(), p.requiresMfa(), p.teamAssignable()));
            }
        };
    }

    @Bean
    GatedOperation<UpdateRole> roleUpdateOperation(RoleService service) {
        return new AccessOperation<>("role.update", UpdateRole.class) {
            @Override
            public Set<Trait> traits(UpdateRole p) {
                return access(approvers.grantsApproval(permissionsOf(p.roleId()))
                        || approvers.grantsApproval(p.permissions()));
            }

            @Override
            public String summary(UpdateRole p) {
                return "Change role " + role(p.roleId()).getName();
            }

            @Override
            public List<DisplayRow> display(UpdateRole p) {
                RoleEntity now = role(p.roleId());
                return List.of(
                        new DisplayRow("Name", now.getName(), p.name()),
                        new DisplayRow(LABEL_PERMISSIONS, join(permissionsOf(p.roleId())), join(p.permissions())),
                        new DisplayRow(
                                "Requires a second factor",
                                String.valueOf(now.isRequiresMfa()),
                                String.valueOf(p.requiresMfa())),
                        new DisplayRow(
                                "Team role",
                                String.valueOf(now.isTeamAssignable()),
                                String.valueOf(p.teamAssignable())));
            }

            @Override
            public Effect estimate(UpdateRole p) {
                RoleEntity now = role(p.roleId());
                return new Effect(
                        1,
                        "role",
                        etag(
                                p.roleId(),
                                now.getName(),
                                permissionsOf(p.roleId()),
                                now.isRequiresMfa(),
                                now.isTeamAssignable()),
                        null);
            }

            @Override
            public void replay(UpdateRole p) {
                service.update(
                        p.roleId(), new RoleRequest(p.name(), p.permissions(), p.requiresMfa(), p.teamAssignable()));
            }
        };
    }

    @Bean
    GatedOperation<DeleteRole> roleDeleteOperation(RoleService service) {
        return new AccessOperation<>("role.delete", DeleteRole.class) {
            @Override
            public Set<Trait> traits(DeleteRole p) {
                return access(approvers.roleGrantsApproval(p.roleId()));
            }

            @Override
            public String summary(DeleteRole p) {
                return "Delete role " + role(p.roleId()).getName();
            }

            @Override
            public List<DisplayRow> display(DeleteRole p) {
                return List.of(
                        DisplayRow.of("Role", role(p.roleId()).getName()),
                        DisplayRow.of(LABEL_PERMISSIONS, join(permissionsOf(p.roleId()))));
            }

            @Override
            public Effect estimate(DeleteRole p) {
                return new Effect(1, "role", etag(p.roleId(), permissionsOf(p.roleId())), null);
            }

            @Override
            public void replay(DeleteRole p) {
                service.delete(p.roleId());
            }
        };
    }

    private RoleEntity role(UUID roleId) {
        return roles.findById(roleId).orElseThrow(() -> new NotFoundException("role", roleId));
    }

    private List<String> permissionsOf(UUID roleId) {
        return rolePermissions.findByIdRoleId(roleId).stream()
                .map(RolePermissionEntity::getAction)
                .sorted()
                .toList();
    }

    private static String join(List<String> permissions) {
        return permissions.isEmpty()
                ? "none"
                : String.join(", ", permissions.stream().sorted().toList());
    }
}
