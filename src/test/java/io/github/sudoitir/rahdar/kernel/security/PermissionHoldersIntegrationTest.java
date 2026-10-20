package io.github.sudoitir.rahdar.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.support.OperatorFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who holds a permission is decided live, on enabled accounts, at the scope asked about. */
class PermissionHoldersIntegrationTest extends PostgresIntegrationTest {

    private static final int ALL = 10_000;
    private static final String SUBSCRIPTION_DELETE = "subscription:delete";

    @Autowired
    PermissionHolders holders;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    GrantLoader grants;

    @Autowired
    AccessChanges accessChanges;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private UUID onCluster(UUID cluster) {
        return OperatorFixture.signInOnCluster(
                users, roles, rolePermissions, userRoles, grants, cluster, SUBSCRIPTION_DELETE);
    }

    @Test
    void aClusterGrantMakesAHolderOnThatClusterOnly() {
        UUID cluster = UUID.randomUUID();
        UUID holder = onCluster(cluster);

        assertThat(holders.holders(cluster, SUBSCRIPTION_DELETE, ALL)).contains(holder);
        assertThat(holders.holders(UUID.randomUUID(), SUBSCRIPTION_DELETE, ALL)).doesNotContain(holder);
        assertThat(holders.holders(null, SUBSCRIPTION_DELETE, ALL)).doesNotContain(holder);
    }

    @Test
    void aGlobalGrantHoldsAtEveryScope() {
        UUID global = OperatorFixture.signIn(users, roles, rolePermissions, userRoles, grants);

        assertThat(holders.holders(null, SUBSCRIPTION_DELETE, ALL)).contains(global);
        assertThat(holders.holders(UUID.randomUUID(), SUBSCRIPTION_DELETE, ALL)).contains(global);
    }

    @Test
    void aDisabledUserHoldsNothing() {
        UUID cluster = UUID.randomUUID();
        UUID holder = onCluster(cluster);
        var user = users.findById(holder).orElseThrow();
        user.setDisabled(true);
        users.save(user);
        accessChanges.changedFor(holder);

        assertThat(holders.holders(cluster, SUBSCRIPTION_DELETE, ALL)).doesNotContain(holder);
    }

    @Test
    void theListStopsAtTheLimit() {
        UUID cluster = UUID.randomUUID();
        onCluster(cluster);
        onCluster(cluster);
        onCluster(cluster);

        assertThat(holders.holders(cluster, SUBSCRIPTION_DELETE, 2)).hasSize(2);
    }
}
