package io.github.sudoitir.rahdar.kernel.security.internal;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.core.SecretRedactor;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.security.AccessOperation;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.CreateUserRequest;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.GrantRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The gated operations of {@link UserService}. */
@Configuration(proxyBeanMethods = false)
class UserOperations {

    record CreateUser(String username, String email, String password) {

        /** The password is never printed. */
        @Override
        public String toString() {
            return "CreateUser[username=" + username + ", email=" + email + ", password=" + SecretRedactor.MASK + "]";
        }
    }

    record DisableUser(UUID userId) {}

    record EnableUser(UUID userId) {}

    record UnlockUser(UUID userId) {}

    record ResetSecondFactors(UUID userId) {}

    record GrantRole(UUID userId, UUID roleId, String scopeType, UUID scopeId) {}

    record RevokeRole(UUID userId, UUID roleId, String scopeType, UUID scopeId) {}

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final ApproverAccess approvers;

    UserOperations(AppUserRepository users, RoleRepository roles, ApproverAccess approvers) {
        this.users = users;
        this.roles = roles;
        this.approvers = approvers;
    }

    @Bean
    GatedOperation<CreateUser> userCreateOperation(UserService service) {
        return new AccessOperation<>("user.create", CreateUser.class) {
            @Override
            public String summary(CreateUser p) {
                return "Create user " + p.username();
            }

            @Override
            public List<DisplayRow> display(CreateUser p) {
                return List.of(
                        DisplayRow.of("Username", p.username()),
                        DisplayRow.of("Email", p.email()),
                        DisplayRow.of("Initial password", "(hidden)"));
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of("/password");
            }

            @Override
            public Effect estimate(CreateUser p) {
                return new Effect(1, "user", etag(p.username().toLowerCase()), null);
            }

            @Override
            public void replay(CreateUser p) {
                service.create(new CreateUserRequest(p.username(), p.email(), p.password()));
            }
        };
    }

    @Bean
    GatedOperation<DisableUser> userDisableOperation(UserService service) {
        return new AccessOperation<>("user.disable", DisableUser.class) {
            @Override
            public Set<Trait> traits(DisableUser p) {
                return access(approvers.armed());
            }

            @Override
            public String summary(DisableUser p) {
                return "Disable user " + user(p.userId()).getUsername();
            }

            @Override
            public List<DisplayRow> display(DisableUser p) {
                return List.of(
                        DisplayRow.of("User", user(p.userId()).getUsername()),
                        new DisplayRow("Account", "enabled", "disabled"));
            }

            @Override
            public Effect estimate(DisableUser p) {
                return new Effect(1, "user", etag(p.userId(), user(p.userId()).isDisabled()), "Ends their sessions.");
            }

            @Override
            public void replay(DisableUser p) {
                service.disable(p.userId());
            }
        };
    }

    @Bean
    GatedOperation<EnableUser> userEnableOperation(UserService service) {
        return new AccessOperation<>("user.enable", EnableUser.class) {
            @Override
            public Set<Trait> traits(EnableUser p) {
                return access(approvers.userHoldsApproval(p.userId()));
            }

            @Override
            public String summary(EnableUser p) {
                return "Enable user " + user(p.userId()).getUsername();
            }

            @Override
            public List<DisplayRow> display(EnableUser p) {
                return List.of(
                        DisplayRow.of("User", user(p.userId()).getUsername()),
                        new DisplayRow("Account", "disabled", "enabled"));
            }

            @Override
            public Effect estimate(EnableUser p) {
                return new Effect(1, "user", etag(p.userId(), user(p.userId()).isDisabled()), null);
            }

            @Override
            public void replay(EnableUser p) {
                service.enable(p.userId());
            }
        };
    }

    @Bean
    GatedOperation<UnlockUser> userUnlockOperation(UserService service) {
        return new AccessOperation<>("user.unlock", UnlockUser.class) {
            @Override
            public String summary(UnlockUser p) {
                return "Unlock user " + user(p.userId()).getUsername();
            }

            @Override
            public List<DisplayRow> display(UnlockUser p) {
                return List.of(DisplayRow.of("User", user(p.userId()).getUsername()));
            }

            @Override
            public Effect estimate(UnlockUser p) {
                return new Effect(1, "user", etag(p.userId()), null);
            }

            @Override
            public void replay(UnlockUser p) {
                service.unlock(p.userId());
            }
        };
    }

    @Bean
    GatedOperation<ResetSecondFactors> userResetSecondFactorsOperation(UserService service) {
        return new AccessOperation<>("user.reset-second-factors", ResetSecondFactors.class) {
            @Override
            public String summary(ResetSecondFactors p) {
                return "Reset the second factors of " + user(p.userId()).getUsername();
            }

            @Override
            public List<DisplayRow> display(ResetSecondFactors p) {
                return List.of(DisplayRow.of("User", user(p.userId()).getUsername()));
            }

            @Override
            public Effect estimate(ResetSecondFactors p) {
                return new Effect(
                        1,
                        "user",
                        etag(p.userId()),
                        "Removes their authenticator, passkeys, recovery codes and trusted devices, revokes their API"
                                + " tokens and ends their sessions.");
            }

            @Override
            public void replay(ResetSecondFactors p) {
                service.resetSecondFactors(p.userId());
            }
        };
    }

    @Bean
    GatedOperation<GrantRole> userGrantOperation(UserService service) {
        return new AccessOperation<>("user.grant", GrantRole.class) {
            @Override
            public Set<Trait> traits(GrantRole p) {
                return access(approvers.roleGrantsApproval(p.roleId()));
            }

            @Override
            public String summary(GrantRole p) {
                return "Give " + user(p.userId()).getUsername() + " the role " + role(p.roleId());
            }

            @Override
            public List<DisplayRow> display(GrantRole p) {
                return List.of(
                        DisplayRow.of("User", user(p.userId()).getUsername()),
                        DisplayRow.of("Role", role(p.roleId())),
                        DisplayRow.of("Scope", p.scopeType()));
            }

            @Override
            public Effect estimate(GrantRole p) {
                return new Effect(1, "grant", etag(p.userId(), p.roleId(), p.scopeType(), p.scopeId()), null);
            }

            @Override
            public void replay(GrantRole p) {
                service.addGrant(p.userId(), new GrantRequest(p.roleId(), p.scopeType(), p.scopeId()));
            }
        };
    }

    @Bean
    GatedOperation<RevokeRole> userRevokeOperation(UserService service) {
        return new AccessOperation<>("user.revoke", RevokeRole.class) {
            @Override
            public Set<Trait> traits(RevokeRole p) {
                return access(approvers.roleGrantsApproval(p.roleId()));
            }

            @Override
            public String summary(RevokeRole p) {
                return "Remove the role " + role(p.roleId()) + " from "
                        + user(p.userId()).getUsername();
            }

            @Override
            public List<DisplayRow> display(RevokeRole p) {
                return List.of(
                        DisplayRow.of("User", user(p.userId()).getUsername()),
                        new DisplayRow("Role", role(p.roleId()), null),
                        DisplayRow.of("Scope", p.scopeType()));
            }

            @Override
            public Effect estimate(RevokeRole p) {
                return new Effect(1, "grant", etag(p.userId(), p.roleId(), p.scopeType(), p.scopeId()), null);
            }

            @Override
            public void replay(RevokeRole p) {
                service.removeGrant(p.userId(), p.roleId(), p.scopeType(), p.scopeId());
            }
        };
    }

    private AppUserEntity user(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("user", userId));
    }

    private String role(UUID roleId) {
        return roles.findById(roleId)
                .orElseThrow(() -> new NotFoundException("role", roleId))
                .getName();
    }
}
