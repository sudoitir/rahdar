package io.github.sudoitir.rahdar.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.AccessChanges;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.PatternKind;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ResourceNames;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.kernel.security.web.AccessViews.AccessCheckView;
import io.github.sudoitir.rahdar.kernel.security.web.AccessViews.SourceType;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.ShareRequest;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.EnvironmentEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.EnvironmentRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The access check (authorization spec): for a user, a cluster and optionally a queue or address, every
 * permission with each source that allows it. It must give the answer the resolver gives, with the reason.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class AccessCheckIntegrationTest extends PostgresIntegrationTest {

    @MockitoBean
    ResourceNames names;

    @Autowired
    EffectiveAccess effective;

    @Autowired
    TeamService teams;

    @Autowired
    RoleService roleService;

    @Autowired
    RoleRepository roles;

    @Autowired
    AppUserRepository users;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    EnvironmentRepository environments;

    @Autowired
    ClusterEnvironmentIndex environmentIndex;

    @Autowired
    AccessChanges accessChanges;

    private AppUserEntity user() {
        AppUserEntity user = AppUserEntity.local("check-" + UUID.randomUUID(), null, "{noop}x");
        user.setMustChangePassword(false);
        return users.save(user);
    }

    private UUID role(String name) {
        return roles.findByName(name).orElseThrow().getId();
    }

    private UUID cluster(UUID environmentId) {
        UUID id = clusters.save(
                        new ClusterEntity(ArtemisModule.SYSTEM, "check-" + UUID.randomUUID(), null, environmentId))
                .getId();
        environmentIndex.invalidate();
        return id;
    }

    private UUID team(String name) {
        return teams.create(name + "-" + UUID.randomUUID()).id();
    }

    private static AccessCheckView of(List<AccessCheckView> result, String action) {
        return result.stream()
                .filter(v -> v.action().equals(action))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aRoleGrantNamesItsRoleAndScope() {
        UUID live = environments
                .save(new EnvironmentEntity("live-" + UUID.randomUUID(), null, 1))
                .getId();
        UUID inLive = cluster(live);
        UUID elsewhere = cluster(null);
        var role = roleService.create(new RoleRequest(
                "check-reader-" + UUID.randomUUID(), List.of("subscription:read", "user:admin"), false, false));
        AppUserEntity someone = user();
        userRoles.save(new UserRoleEntity(someone.getId(), role.id(), "ENVIRONMENT", live));

        var here = effective.check(someone.getId(), inLive, null, null);
        var there = effective.check(someone.getId(), elsewhere, null, null);

        assertThat(of(here, "subscription:read").allowed()).isTrue();
        assertThat(of(here, "subscription:read").sources()).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo(SourceType.ROLE_GRANT);
            assertThat(s.roleName()).isEqualTo(role.name());
            assertThat(s.scopeType()).isEqualTo("ENVIRONMENT");
            assertThat(s.scopeId()).isEqualTo(live);
        });
        assertThat(of(here, "subscription:purge").allowed()).isFalse();
        // a global-scope permission does not take effect through an environment grant
        assertThat(of(here, "user:admin").allowed()).isFalse();
        assertThat(of(there, "subscription:read").allowed()).isFalse();
    }

    @Test
    void aGlobalGrantReachesEveryClusterAndAWildcardIsExpanded() {
        var role = roleService.create(
                new RoleRequest("check-all-" + UUID.randomUUID(), List.of("subscription:*"), false, false));
        AppUserEntity someone = user();
        userRoles.save(new UserRoleEntity(someone.getId(), role.id(), "GLOBAL", ScopeIds.GLOBAL));

        var result = effective.check(someone.getId(), cluster(null), null, null);

        assertThat(of(result, "subscription:purge").sources()).singleElement().satisfies(s -> {
            assertThat(s.scopeType()).isEqualTo("GLOBAL");
            assertThat(s.scopeId()).isNull();
        });
        assertThat(of(result, "cluster:read").allowed()).isFalse();
    }

    @Test
    void aTeamRoleAllowsOnlyWhatTheTeamOwnsAndSaysWhichTeam() {
        UUID prod = cluster(null);
        UUID orders = team("orders");
        teams.addPattern(orders, new PatternRequest(prod, PatternKind.SUBSCRIPTION, "orders.#"));
        AppUserEntity operator = user();
        teams.addMember(
                orders, new MemberRequest(PrincipalType.USER, operator.getId(), null, null, role("TEAM_OPERATOR")));

        var owned = effective.check(operator.getId(), prod, ResourceType.SUBSCRIPTION, "orders.in");
        var foreign = effective.check(operator.getId(), prod, ResourceType.SUBSCRIPTION, "billing.in");
        var clusterWide = effective.check(operator.getId(), prod, null, null);

        assertThat(of(owned, "subscription:purge").sources()).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo(SourceType.TEAM);
            assertThat(s.teamId()).isEqualTo(orders);
            assertThat(s.roleName()).isEqualTo("TEAM_OPERATOR");
        });
        assertThat(of(foreign, "subscription:purge").allowed()).isFalse();
        // a queue permission does not reach an address, and a team gives nothing cluster-wide
        assertThat(of(
                                effective.check(operator.getId(), prod, ResourceType.DESTINATION, "orders.in"),
                                "subscription:purge")
                        .allowed())
                .isFalse();
        assertThat(of(clusterWide, "subscription:purge").allowed()).isFalse();
    }

    @Test
    void aShareNamesTheOwnerAndTheRoleItGives() {
        UUID prod = cluster(null);
        UUID orders = team("orders");
        UUID billing = team("billing");
        teams.addPattern(orders, new PatternRequest(prod, PatternKind.SUBSCRIPTION, "orders.#"));
        teams.addShare(
                orders,
                new ShareRequest(billing, prod, PatternKind.SUBSCRIPTION, "orders.events.#", role("TEAM_VIEWER")));
        AppUserEntity biller = user();
        teams.addMember(
                billing, new MemberRequest(PrincipalType.USER, biller.getId(), null, null, role("TEAM_OPERATOR")));

        var result = effective.check(biller.getId(), prod, ResourceType.SUBSCRIPTION, "orders.events.created");

        assertThat(of(result, "subscription:read").sources()).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo(SourceType.SHARE);
            assertThat(s.roleName()).isEqualTo("TEAM_VIEWER");
            assertThat(s.teamId()).isEqualTo(billing);
            assertThat(s.ownerTeamName()).startsWith("orders-");
        });
        assertThat(of(result, "subscription:purge").allowed()).isFalse();
        assertThat(of(
                                effective.check(biller.getId(), prod, ResourceType.SUBSCRIPTION, "orders.audit.x"),
                                "subscription:read")
                        .allowed())
                .isFalse();
    }

    @Test
    void aDisabledUserHoldsNothing() {
        var role = roleService.create(
                new RoleRequest("check-off-" + UUID.randomUUID(), List.of("subscription:*"), false, false));
        AppUserEntity someone = user();
        userRoles.save(new UserRoleEntity(someone.getId(), role.id(), "GLOBAL", ScopeIds.GLOBAL));
        someone.setDisabled(true);
        users.save(someone);

        var result = effective.check(someone.getId(), cluster(null), null, null);

        assertThat(result).noneMatch(AccessCheckView::allowed);
    }

    @Test
    void theKindAndNameComeTogetherAndNeedACluster() {
        UUID someone = user().getId();
        UUID prod = cluster(null);

        assertThatThrownBy(() -> effective.check(someone, prod, ResourceType.SUBSCRIPTION, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> effective.check(someone, prod, null, "orders.in"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> effective.check(someone, null, ResourceType.SUBSCRIPTION, "orders.in"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownUserIsNotFoundAndACallerWithoutUserAdminIsRefusedFirst() {
        UUID someone = user().getId();
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> effective.check(unknown, null, null, null)).isInstanceOf(NotFoundException.class);

        RahdarPrincipal viewer = new RahdarPrincipal(
                UUID.randomUUID(),
                "viewer",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of("cluster:read"))),
                false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(viewer, null, viewer.getAuthorities()));

        assertThatThrownBy(() -> effective.check(someone, null, null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> effective.check(unknown, null, null, null)).isInstanceOf(AccessDeniedException.class);
    }
}
