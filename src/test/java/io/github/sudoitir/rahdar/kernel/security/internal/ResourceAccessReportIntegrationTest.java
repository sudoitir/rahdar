package io.github.sudoitir.rahdar.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.PatternKind;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ResourceNames;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.security.TeamRef;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.GrantSource;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.ResourceAccessView;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.ShareRequest;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The resource access report: the owning team's roles with their member counts, and every share that covers
 * the name, for a user administrator or a team administrator and nobody else.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class ResourceAccessReportIntegrationTest extends PostgresIntegrationTest {

    @MockitoBean
    ResourceNames names;

    @Autowired
    ResourceAccessReport report;

    @Autowired
    TeamService teams;

    @Autowired
    RoleRepository roles;

    @Autowired
    AppUserRepository users;

    @Autowired
    ClusterRepository clusters;

    private UUID prod;
    private UUID orders;
    private UUID billing;

    @BeforeEach
    void setUp() {
        prod = clusters.save(new ClusterEntity("report-" + UUID.randomUUID(), null, null))
                .getId();
        orders = teams.create("orders-" + UUID.randomUUID()).id();
        billing = teams.create("billing-" + UUID.randomUUID()).id();
        teams.addPattern(orders, new PatternRequest(prod, PatternKind.ANY, "orders.#"));
        teams.addShare(
                orders,
                new ShareRequest(billing, prod, PatternKind.SUBSCRIPTION, "orders.events.#", role("TEAM_VIEWER")));
    }

    private UUID user() {
        AppUserEntity user = AppUserEntity.local("report-" + UUID.randomUUID(), null, "{noop}x");
        user.setMustChangePassword(false);
        return users.save(user).getId();
    }

    private UUID role(String name) {
        return roles.findByName(name).orElseThrow().getId();
    }

    private void member(UUID team, UUID user, String role) {
        teams.addMember(team, new MemberRequest(PrincipalType.USER, user, null, null, role(role)));
    }

    private void asUser(UUID userId) {
        RahdarPrincipal principal = RahdarPrincipal.live(userId, "member", false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    @Test
    void aUserAdministratorSeesTheOwnerAndEveryShareWithMemberCounts() {
        member(orders, user(), "TEAM_ADMIN");
        member(orders, user(), "TEAM_VIEWER");
        member(orders, user(), "TEAM_VIEWER");
        member(billing, user(), "TEAM_OPERATOR");
        member(billing, user(), "TEAM_VIEWER");

        ResourceAccessView view = report.of(prod, ResourceRef.subscription("orders.events.created"));

        assertThat(view.ownerTeam())
                .isEqualTo(new TeamRef(orders, teams.get(orders).name()));
        assertThat(view.grants())
                .satisfiesExactly(
                        owner -> {
                            assertThat(owner.source()).isEqualTo(GrantSource.OWNER);
                            assertThat(owner.teamId()).isEqualTo(orders);
                            assertThat(owner.roleName()).isEqualTo("TEAM_ADMIN");
                            assertThat(owner.memberCount()).isEqualTo(1);
                            assertThat(owner.sharedByTeamName()).isNull();
                            assertThat(owner.pattern()).isNull();
                        },
                        owner -> {
                            assertThat(owner.source()).isEqualTo(GrantSource.OWNER);
                            assertThat(owner.roleName()).isEqualTo("TEAM_VIEWER");
                            assertThat(owner.memberCount()).isEqualTo(2);
                        },
                        share -> {
                            assertThat(share.source()).isEqualTo(GrantSource.SHARE);
                            assertThat(share.teamId()).isEqualTo(billing);
                            assertThat(share.teamName())
                                    .isEqualTo(teams.get(billing).name());
                            assertThat(share.roleName()).isEqualTo("TEAM_VIEWER");
                            assertThat(share.sharedByTeamName())
                                    .isEqualTo(teams.get(orders).name());
                            assertThat(share.pattern()).isEqualTo("orders.events.#");
                            assertThat(share.memberCount()).isEqualTo(2);
                        });
    }

    @Test
    void aNameOutsideTheSharedPatternHasTheOwnerOnlyAndAnUnownedNameNothing() {
        member(orders, user(), "TEAM_VIEWER");

        ResourceAccessView outsideShare = report.of(prod, ResourceRef.subscription("orders.in"));
        ResourceAccessView addressKind = report.of(prod, ResourceRef.destination("orders.events.created"));
        ResourceAccessView unowned = report.of(prod, ResourceRef.subscription("other"));

        assertThat(outsideShare.grants()).extracting(g -> g.source()).containsOnly(GrantSource.OWNER);
        assertThat(addressKind.grants()).extracting(g -> g.source()).containsOnly(GrantSource.OWNER);
        assertThat(unowned.ownerTeam()).isNull();
        assertThat(unowned.grants()).isEmpty();
    }

    @Test
    void aRenamedOwnerShowsUnderItsNewName() {
        teams.rename(orders, "Renamed-" + orders);

        assertThat(report.of(prod, ResourceRef.subscription("orders.in")).ownerTeam())
                .isEqualTo(new TeamRef(orders, "Renamed-" + orders));
    }

    @Test
    void aTeamAdministratorMayAskButAMemberWithoutTeamAdminMayNot() {
        UUID boss = user();
        member(billing, boss, "TEAM_ADMIN");
        UUID operator = user();
        member(orders, operator, "TEAM_OPERATOR");

        asUser(boss);
        assertThat(report.of(prod, ResourceRef.subscription("orders.in"))
                        .ownerTeam()
                        .id())
                .isEqualTo(orders);

        asUser(operator);
        ResourceRef ordersIn = ResourceRef.subscription("orders.in");
        assertThatThrownBy(() -> report.of(prod, ordersIn)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anUnknownClusterIsNotFound() {
        UUID unknown = UUID.randomUUID();
        ResourceRef ordersIn = ResourceRef.subscription("orders.in");
        assertThatThrownBy(() -> report.of(unknown, ordersIn)).isInstanceOf(NotFoundException.class);
    }
}
