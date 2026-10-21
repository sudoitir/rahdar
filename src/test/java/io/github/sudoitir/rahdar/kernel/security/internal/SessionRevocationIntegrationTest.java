package io.github.sudoitir.rahdar.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.security.AccessLoader;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.UserAccounts;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamMemberEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamMemberRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserGroupEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserGroupRepository;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.GrantRequest;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Taking access away ends the affected users' sessions (identity-and-sessions spec, ADR-0123),
 * against the real JDBC session store: a session carries the grants resolved at sign-in, so a
 * surviving one would keep the old rights until it timed out.
 */
class SessionRevocationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    UserService userService;

    @Autowired
    RoleService roleService;

    @Autowired
    AppUserRepository users;

    @Autowired
    SessionTerminator terminator;

    @Autowired
    FindByIndexNameSessionRepository<? extends Session> sessions;

    @BeforeEach
    void signInAsAdministrator() {
        var admin = new RahdarPrincipal(
                UUID.randomUUID(),
                "revocation-admin",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of(Permissions.USER_ADMIN))),
                false);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    @Autowired
    AccessLoader access;

    @Autowired
    TeamService teams;

    @Autowired
    UserAccounts accounts;

    @Autowired
    UserGroupRepository userGroups;

    @Autowired
    TeamMemberRepository teamMembers;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void disablingAUserEndsTheirSessions() {
        AppUserEntity user = newUser("revoke-disabled");
        openSession(user.getUsername());
        openSession(user.getUsername());
        String bystander = openSession("revoke-bystander");

        userService.disable(user.getId());

        assertThat(sessions.findByPrincipalName(user.getUsername())).isEmpty();
        assertThat(sessions.findById(bystander)).isNotNull();
    }

    @Test
    void removingAGrantEndsTheUsersSessions() {
        AppUserEntity user = newUser("revoke-grant");
        var role = roleService.create(new RoleRequest("revoke-grant-role", List.of("cluster:read"), false, false));
        userService.addGrant(user.getId(), new GrantRequest(role.id(), "GLOBAL", null));
        openSession(user.getUsername());

        userService.removeGrant(user.getId(), role.id(), "GLOBAL", null);

        assertThat(sessions.findByPrincipalName(user.getUsername())).isEmpty();
    }

    @Test
    void changingARolesPermissionsLeavesItsMembersSessionsOpenBecauseTheChangeAppliesAtOnce() {
        AppUserEntity first = newUser("revoke-role-a");
        var role = roleService.create(new RoleRequest(
                "revoke-role", List.of("cluster:read", "subscription:read", "subscription:purge"), false, false));
        userService.addGrant(first.getId(), new GrantRequest(role.id(), "GLOBAL", null));
        openSession(first.getUsername());

        roleService.update(
                role.id(), new RoleRequest("revoke-role", List.of("cluster:read", "subscription:read"), false, false));

        assertThat(sessions.findByPrincipalName(first.getUsername())).hasSize(1);
        assertThat(access.of(first.getId()).grants())
                .flatExtracting(Grant::permissions)
                .containsExactlyInAnyOrder("cluster:read", "subscription:read");
    }

    @Test
    void endingAllButOneSessionKeepsThatSession() {
        String keep = openSession("revoke-except");
        openSession("revoke-except");
        String bystander = openSession("revoke-except-bystander");

        terminator.endSessionsOfExcept("revoke-except", Set.of(keep));

        assertThat(sessions.findByPrincipalName("revoke-except")).containsOnlyKeys(keep);
        assertThat(sessions.findById(bystander)).isNotNull();
    }

    @Test
    void addingAGrantLeavesSessionsAlone() {
        AppUserEntity user = newUser("revoke-add");
        var role = roleService.create(new RoleRequest("revoke-add-role", List.of("cluster:read"), false, false));
        openSession(user.getUsername());

        userService.addGrant(user.getId(), new GrantRequest(role.id(), "GLOBAL", null));

        assertThat(sessions.findByPrincipalName(user.getUsername())).hasSize(1);
    }

    @Test
    void grantingARoleThatRequiresMfaEndsTheUsersSessions() {
        AppUserEntity user = newUser("revoke-mfa-grant");
        var role = roleService.create(new RoleRequest("revoke-mfa-role", List.of("cluster:read"), true, false));
        openSession(user.getUsername());

        userService.addGrant(user.getId(), new GrantRequest(role.id(), "GLOBAL", null));

        assertThat(sessions.findByPrincipalName(user.getUsername())).isEmpty();
    }

    @Test
    void makingARoleRequireMfaEndsItsMembersSessions() {
        AppUserEntity user = newUser("revoke-mfa-switch");
        var role = roleService.create(new RoleRequest("revoke-mfa-switch-role", List.of("cluster:read"), false, false));
        userService.addGrant(user.getId(), new GrantRequest(role.id(), "GLOBAL", null));
        openSession(user.getUsername());

        var updated = roleService.update(
                role.id(), new RoleRequest("revoke-mfa-switch-role", List.of("cluster:read"), true, false));

        assertThat(updated.requiresMfa()).isTrue();
        assertThat(sessions.findByPrincipalName(user.getUsername())).isEmpty();
    }

    @Test
    void addingAMemberToATeamRoleThatRequiresMfaEndsTheirSessionsAndRequiresOne() {
        AppUserEntity user = newUser("revoke-team-mfa");
        UUID team = teams.create("mfa-" + UUID.randomUUID()).id();
        var role = roleService.create(new RoleRequest("team-mfa-role", List.of("subscription:read"), true, true));
        openSession(user.getUsername());
        assertThat(accounts.holdsMfaRole(user.getId())).isFalse();

        teams.addMember(team, new MemberRequest(PrincipalType.USER, user.getId(), null, null, role.id()));

        assertThat(sessions.findByPrincipalName(user.getUsername())).isEmpty();
        assertThat(accounts.holdsMfaRole(user.getId())).isTrue();
    }

    @Test
    void aTeamRoleOfAGroupTheUserIsInRequiresMfaToo() {
        AppUserEntity user = newUser("revoke-group-mfa");
        userGroups.save(new UserGroupEntity(user.getId(), "dir", "eng"));
        UUID team = teams.create("group-mfa-" + UUID.randomUUID()).id();
        var role = roleService.create(new RoleRequest("group-mfa-role", List.of("subscription:read"), true, true));
        openSession(user.getUsername());

        teamMembers.save(TeamMemberEntity.ofGroup(team, "dir", "eng", role.id()));

        assertThat(accounts.holdsMfaRole(user.getId())).isTrue();
        // The role is what requires the factor, so the requirement ends with it.
        roleService.update(role.id(), new RoleRequest("group-mfa-role", List.of("subscription:read"), false, true));
        assertThat(accounts.holdsMfaRole(user.getId())).isFalse();
    }

    @Test
    void makingATeamRoleRequireMfaEndsItsMembersSessions() {
        AppUserEntity direct = newUser("revoke-team-switch-direct");
        AppUserEntity viaGroup = newUser("revoke-team-switch-group");
        userGroups.save(new UserGroupEntity(viaGroup.getId(), "dir", "ops"));
        UUID team = teams.create("switch-" + UUID.randomUUID()).id();
        var role = roleService.create(new RoleRequest("team-switch-role", List.of("subscription:read"), false, true));
        teams.addMember(team, new MemberRequest(PrincipalType.USER, direct.getId(), null, null, role.id()));
        teamMembers.save(TeamMemberEntity.ofGroup(team, "dir", "ops", role.id()));
        openSession(direct.getUsername());
        openSession(viaGroup.getUsername());

        roleService.update(role.id(), new RoleRequest("team-switch-role", List.of("subscription:read"), true, true));

        assertThat(sessions.findByPrincipalName(direct.getUsername())).isEmpty();
        assertThat(sessions.findByPrincipalName(viaGroup.getUsername())).isEmpty();
    }

    @Test
    void theBuiltInAdministratorRoleRequiresMfaByDefault() {
        var admin = roleService.list().stream()
                .filter(r -> r.name().equals("ADMIN"))
                .findFirst()
                .orElseThrow();

        assertThat(admin.requiresMfa()).isTrue();
        assertThat(roleService.list().stream()
                        .filter(r -> r.builtin() && !r.name().equals("ADMIN")))
                .allMatch(r -> !r.requiresMfa());
    }

    @Test
    void aBuiltInRoleAcceptsAChangeToMfaAndNothingElse() {
        var viewer = roleService.list().stream()
                .filter(r -> r.name().equals("VIEWER"))
                .findFirst()
                .orElseThrow();
        try {
            var changed =
                    roleService.update(viewer.id(), new RoleRequest(viewer.name(), viewer.permissions(), true, false));
            assertThat(changed.requiresMfa()).isTrue();
            assertThat(changed.permissions()).containsExactlyInAnyOrderElementsOf(viewer.permissions());

            var renamed = new RoleRequest("RENAMED", viewer.permissions(), true, false);
            var everything = new RoleRequest(viewer.name(), List.of("*"), true, false);
            var viewerId = viewer.id();
            assertThatThrownBy(() -> roleService.update(viewerId, renamed)).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> roleService.update(viewerId, everything)).isInstanceOf(ConflictException.class);
        } finally {
            roleService.update(viewer.id(), new RoleRequest(viewer.name(), viewer.permissions(), false, false));
        }
    }

    private AppUserEntity newUser(String username) {
        AppUserEntity user = AppUserEntity.local(username, username + "@example.test", "{noop}unused");
        user.setMustChangePassword(false);
        return users.save(user);
    }

    private String openSession(String username) {
        return open(sessions, username);
    }

    private static <S extends Session> String open(FindByIndexNameSessionRepository<S> repository, String username) {
        S session = repository.createSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, username);
        repository.save(session);
        return session.getId();
    }
}
