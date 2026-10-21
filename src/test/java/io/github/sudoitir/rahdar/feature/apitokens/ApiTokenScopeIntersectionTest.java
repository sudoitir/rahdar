package io.github.sudoitir.rahdar.feature.apitokens;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ResourcePattern;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.ScopedGrants;
import io.github.sudoitir.rahdar.kernel.security.TokenGrant;
import io.github.sudoitir.rahdar.kernel.security.TokenPrincipal;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A key acts within its grants and its owner's grants at the time (ADR-0039). The
 * question this fixes is which owner grant reaches which token grant.
 *
 * <p>Scope-id equality alone made narrowing impossible for the people who most
 * need it: an administrator's grants are global, so a key scoped to one cluster
 * intersected to nothing and authenticated with no permissions at all. That is
 * the opposite of what minting a narrow key is for, and it is why the API-key
 * dialog's scope picker exists (ADR-0046).
 *
 * <p>The widening runs one way only, which is the half worth guarding: a
 * cluster-scoped owner must never be able to mint a global key.
 */
class ApiTokenScopeIntersectionTest extends PostgresIntegrationTest {

    @Autowired
    ApiTokenService tokens;

    @Autowired
    PermissionResolver perm;

    @Autowired
    ScopedGrants scopedGrants;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    ClusterRepository clusters;

    private UUID owner(Grant.ScopeType scopeType, UUID scopeId, String... permissions) {
        String username = "scope-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(username, username + "@example.test", "{noop}unused");
        user.setMustChangePassword(false);
        users.save(user);
        RoleEntity role = roles.save(new RoleEntity("role-" + UUID.randomUUID(), false));
        for (String p : permissions) {
            rolePermissions.save(new RolePermissionEntity(role.getId(), p));
        }
        userRoles.save(new UserRoleEntity(
                user.getId(),
                role.getId(),
                scopeType.name(),
                scopeType == Grant.ScopeType.GLOBAL ? ScopeIds.GLOBAL : scopeId));
        return user.getId();
    }

    private RahdarPrincipal authenticateKeyFor(UUID userId, TokenGrant tokenGrant) {
        var minted = tokens.mint(
                userId,
                "k-" + UUID.randomUUID(),
                Instant.now().plusSeconds(3600),
                List.of(tokenGrant),
                List.of(),
                false);
        return tokens.authenticate(minted.plaintext());
    }

    @Test
    void aGlobalOwnerCanMintAKeyNarrowedToOneCluster() {
        UUID cluster = clusters.save(new ClusterEntity("narrow-" + UUID.randomUUID(), null, null))
                .getId();
        UUID user = owner(Grant.ScopeType.GLOBAL, null, Permissions.CLUSTER_READ);

        RahdarPrincipal principal =
                authenticateKeyFor(user, TokenGrant.of(Grant.ScopeType.CLUSTER, cluster, Permissions.CLUSTER_READ));

        assertThat(principal).isNotNull();
        assertThat(perm.grantsOf(principal))
                .describedAs("a global owner narrowing a key to one cluster used to get an empty grant set")
                .containsExactly(new Grant(Grant.ScopeType.CLUSTER, cluster, Set.of(Permissions.CLUSTER_READ)));

        clusters.deleteById(cluster);
    }

    @Test
    void aClusterScopedOwnerCannotMintAGlobalKey() {
        UUID cluster = clusters.save(new ClusterEntity("bounded-" + UUID.randomUUID(), null, null))
                .getId();
        UUID user = owner(Grant.ScopeType.CLUSTER, cluster, Permissions.CLUSTER_READ);

        RahdarPrincipal principal = authenticateKeyFor(
                user, TokenGrant.of(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Permissions.CLUSTER_READ));

        assertThat(principal).isNotNull();
        assertThat(perm.grantsOf(principal))
                .describedAs("widening must not run in this direction — a key cannot exceed its owner")
                .isEmpty();

        clusters.deleteById(cluster);
    }

    @Test
    void aClusterScopedOwnerCannotReachAnotherCluster() {
        UUID mine = clusters.save(new ClusterEntity("mine-" + UUID.randomUUID(), null, null))
                .getId();
        UUID theirs = clusters.save(new ClusterEntity("theirs-" + UUID.randomUUID(), null, null))
                .getId();
        UUID user = owner(Grant.ScopeType.CLUSTER, mine, Permissions.CLUSTER_READ);

        RahdarPrincipal principal =
                authenticateKeyFor(user, TokenGrant.of(Grant.ScopeType.CLUSTER, theirs, Permissions.CLUSTER_READ));

        assertThat(perm.grantsOf(principal)).isEmpty();

        clusters.deleteById(mine);
        clusters.deleteById(theirs);
    }

    @Test
    void aPermissionTheOwnerLacksIsStillStripped() {
        UUID cluster = clusters.save(new ClusterEntity("strip-" + UUID.randomUUID(), null, null))
                .getId();
        UUID user = owner(Grant.ScopeType.GLOBAL, null, Permissions.CLUSTER_READ);

        RahdarPrincipal principal = authenticateKeyFor(
                user, TokenGrant.of(Grant.ScopeType.CLUSTER, cluster, MessagePermissions.SUBSCRIPTION_PURGE));

        assertThat(perm.grantsOf(principal))
                .describedAs("the scope walk widens the scope, never the permission set")
                .isEmpty();

        clusters.deleteById(cluster);
    }

    @Test
    void aGrantLimitedToAPatternIsKeptAndIsTheKeysOwn() {
        UUID cluster = clusters.save(new ClusterEntity("pattern-" + UUID.randomUUID(), null, null))
                .getId();
        UUID user = owner(Grant.ScopeType.GLOBAL, null, Permissions.WILDCARD);
        TokenGrant limited = new TokenGrant(
                Grant.ScopeType.CLUSTER,
                cluster,
                MessagePermissions.MESSAGE_READ,
                ResourceType.SUBSCRIPTION,
                ResourcePattern.parse("orders.#"));

        var minted = tokens.mint(
                user, "k-" + UUID.randomUUID(), Instant.now().plusSeconds(3600), List.of(limited), List.of(), false);

        assertThat(tokens.grantsOf(minted.entity().getId())).containsExactly(limited);
        assertThat(((TokenPrincipal) tokens.authenticate(minted.plaintext())).grants())
                .containsExactly(limited);

        clusters.deleteById(cluster);
    }

    @Test
    void revokingAScopeRemovesTheGrantsOfKeysScopedToIt() {
        UUID cluster = clusters.save(new ClusterEntity("gone-" + UUID.randomUUID(), null, null))
                .getId();
        UUID user = owner(Grant.ScopeType.GLOBAL, null, Permissions.WILDCARD);
        TokenGrant here = TokenGrant.of(Grant.ScopeType.CLUSTER, cluster, Permissions.CLUSTER_READ);
        TokenGrant everywhere = TokenGrant.of(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Permissions.CLUSTER_READ);
        var minted = tokens.mint(
                user,
                "k-" + UUID.randomUUID(),
                Instant.now().plusSeconds(3600),
                List.of(here, everywhere),
                List.of(),
                false);

        scopedGrants.revoke("CLUSTER", cluster);

        assertThat(tokens.grantsOf(minted.entity().getId())).containsExactly(everywhere);

        clusters.deleteById(cluster);
    }
}
