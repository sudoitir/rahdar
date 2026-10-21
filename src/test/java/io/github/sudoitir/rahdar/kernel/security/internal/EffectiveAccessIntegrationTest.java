package io.github.sudoitir.rahdar.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.EffectivePermissionView;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** A user's effective permissions per scope and source role (authorization spec). */
class EffectiveAccessIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    EffectiveAccess effective;

    @Autowired
    RoleService roleService;

    @Autowired
    AppUserRepository users;

    @Autowired
    UserRoleRepository userRoles;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signInWith(String... permissions) {
        var principal = new RahdarPrincipal(
                UUID.randomUUID(),
                "effective-" + UUID.randomUUID(),
                Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of(permissions))),
                false);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    private AppUserEntity newUser() {
        String name = "effective-user-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(name, name + "@example.test", "{noop}unused");
        user.setMustChangePassword(false);
        return users.save(user);
    }

    @Test
    void listsEachPermissionWithItsScopeAndRoleExpandingWildcards() {
        signInWith(Permissions.USER_ADMIN);
        AppUserEntity user = newUser();
        var role = roleService.create(new RoleRequest(
                "effective-" + UUID.randomUUID(), List.of("subscription:*", Permissions.USER_ADMIN), false, false));
        UUID clusterId = UUID.randomUUID();
        userRoles.save(new UserRoleEntity(user.getId(), role.id(), "CLUSTER", clusterId));

        List<EffectivePermissionView> result = effective.of(user.getId());

        assertThat(result).allSatisfy(v -> {
            assertThat(v.scopeType()).isEqualTo("CLUSTER");
            assertThat(v.scopeId()).isEqualTo(clusterId);
            assertThat(v.roleName()).isEqualTo(role.name());
        });
        assertThat(result)
                .filteredOn(v -> v.via().equals("subscription:*"))
                .extracting(EffectivePermissionView::action)
                .contains("subscription:create", "subscription:delete")
                .allMatch(a -> a.startsWith("subscription:"));
        assertThat(result)
                .filteredOn(v -> v.action().equals(Permissions.USER_ADMIN))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.effective()).isFalse();
                    assertThat(v.reason()).isEqualTo(EffectiveAccess.GLOBAL_ONLY);
                });
    }

    @Test
    void aCallerWithoutUserAdminLearnsNothingAboutAnyUser() {
        signInWith(Permissions.USER_ADMIN);
        UUID existing = newUser().getId();
        signInWith(Permissions.CLUSTER_READ);

        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> effective.of(existing)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> effective.of(unknown)).isInstanceOf(AccessDeniedException.class);
    }
}
