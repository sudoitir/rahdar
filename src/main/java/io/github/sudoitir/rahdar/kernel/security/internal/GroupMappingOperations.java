package io.github.sudoitir.rahdar.kernel.security.internal;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.security.AccessOperation;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.DefaultRoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.DefaultRoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.GroupMappingEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.GroupMappingRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.web.GroupMappingViews.GroupMappingRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The gated operations of {@link GroupMappingService}. */
@Configuration(proxyBeanMethods = false)
class GroupMappingOperations {

    private static final String LABEL_PROVIDER = "Identity provider";

    record CreateMapping(String providerId, String groupName, UUID roleId, String scopeType, UUID scopeId) {}

    record DeleteMapping(String providerId, UUID mappingId) {}

    /** A {@code null} role clears the default. */
    record SetDefaultRole(String providerId, UUID roleId) {}

    private final RoleRepository roles;
    private final GroupMappingRepository mappings;
    private final DefaultRoleRepository defaultRoles;
    private final ApproverAccess approvers;

    GroupMappingOperations(
            RoleRepository roles,
            GroupMappingRepository mappings,
            DefaultRoleRepository defaultRoles,
            ApproverAccess approvers) {
        this.roles = roles;
        this.mappings = mappings;
        this.defaultRoles = defaultRoles;
        this.approvers = approvers;
    }

    @Bean
    GatedOperation<CreateMapping> groupMappingCreateOperation(GroupMappingService service) {
        return new AccessOperation<>("group-mapping.create", CreateMapping.class) {
            @Override
            public Set<Trait> traits(CreateMapping p) {
                return access(approvers.roleGrantsApproval(p.roleId()));
            }

            @Override
            public String summary(CreateMapping p) {
                return "Map group " + p.groupName() + " of " + p.providerId() + " to role " + role(p.roleId());
            }

            @Override
            public List<DisplayRow> display(CreateMapping p) {
                return List.of(
                        DisplayRow.of(LABEL_PROVIDER, p.providerId()),
                        DisplayRow.of("Group", p.groupName()),
                        DisplayRow.of("Role", role(p.roleId())),
                        DisplayRow.of("Scope", p.scopeType()));
            }

            @Override
            public Effect estimate(CreateMapping p) {
                return new Effect(
                        1,
                        "mapping",
                        etag(p.providerId(), p.groupName(), p.roleId(), p.scopeType(), p.scopeId()),
                        null);
            }

            @Override
            public void replay(CreateMapping p) {
                service.create(
                        p.providerId(), new GroupMappingRequest(p.groupName(), p.roleId(), p.scopeType(), p.scopeId()));
            }
        };
    }

    @Bean
    GatedOperation<DeleteMapping> groupMappingDeleteOperation(GroupMappingService service) {
        return new AccessOperation<>("group-mapping.delete", DeleteMapping.class) {
            @Override
            public Set<Trait> traits(DeleteMapping p) {
                return access(approvers.roleGrantsApproval(mapping(p).getRoleId()));
            }

            @Override
            public String summary(DeleteMapping p) {
                GroupMappingEntity mapping = mapping(p);
                return "Remove the mapping of group " + mapping.getGroupName() + " of " + p.providerId();
            }

            @Override
            public List<DisplayRow> display(DeleteMapping p) {
                GroupMappingEntity mapping = mapping(p);
                return List.of(
                        DisplayRow.of(LABEL_PROVIDER, p.providerId()),
                        DisplayRow.of("Group", mapping.getGroupName()),
                        new DisplayRow("Role", role(mapping.getRoleId()), null));
            }

            @Override
            public Effect estimate(DeleteMapping p) {
                GroupMappingEntity mapping = mapping(p);
                return new Effect(
                        1,
                        "mapping",
                        etag(p.providerId(), p.mappingId(), mapping.getGroupName(), mapping.getRoleId()),
                        null);
            }

            @Override
            public void replay(DeleteMapping p) {
                service.delete(p.providerId(), p.mappingId());
            }
        };
    }

    @Bean
    GatedOperation<SetDefaultRole> defaultRoleSetOperation(GroupMappingService service) {
        return new AccessOperation<>("default-role.set", SetDefaultRole.class) {
            @Override
            public Set<Trait> traits(SetDefaultRole p) {
                return access(approvers.roleGrantsApproval(p.roleId())
                        || approvers.roleGrantsApproval(currentDefault(p.providerId())));
            }

            @Override
            public String summary(SetDefaultRole p) {
                return p.roleId() == null
                        ? "Clear the default role of " + p.providerId()
                        : "Set the default role of " + p.providerId() + " to " + role(p.roleId());
            }

            @Override
            public List<DisplayRow> display(SetDefaultRole p) {
                UUID now = currentDefault(p.providerId());
                return List.of(
                        DisplayRow.of(LABEL_PROVIDER, p.providerId()),
                        new DisplayRow(
                                "Default role",
                                now == null ? null : role(now),
                                p.roleId() == null ? null : role(p.roleId())));
            }

            @Override
            public Effect estimate(SetDefaultRole p) {
                return new Effect(1, "default role", etag(p.providerId(), currentDefault(p.providerId())), null);
            }

            @Override
            public void replay(SetDefaultRole p) {
                service.setDefaultRole(p.providerId(), p.roleId());
            }
        };
    }

    private GroupMappingEntity mapping(DeleteMapping p) {
        return mappings.findByIdAndProviderId(p.mappingId(), p.providerId())
                .orElseThrow(() -> new NotFoundException("group mapping", p.mappingId()));
    }

    private UUID currentDefault(String providerId) {
        return defaultRoles
                .findById(providerId)
                .map(DefaultRoleEntity::getRoleId)
                .orElse(null);
    }

    private String role(UUID roleId) {
        return roles.findById(roleId)
                .orElseThrow(() -> new NotFoundException("role", roleId))
                .getName();
    }
}
