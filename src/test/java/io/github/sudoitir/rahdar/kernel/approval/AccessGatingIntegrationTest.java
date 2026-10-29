package io.github.sudoitir.rahdar.kernel.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.GateDecision;
import io.github.sudoitir.rahdar.kernel.gate.HeldState;
import io.github.sudoitir.rahdar.kernel.gate.OperationDeniedException;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.plugin.IdentityProviderListing;
import io.github.sudoitir.rahdar.kernel.security.PatternKind;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.internal.GroupMappingService;
import io.github.sudoitir.rahdar.kernel.security.internal.RoleService;
import io.github.sudoitir.rahdar.kernel.security.internal.TeamService;
import io.github.sudoitir.rahdar.kernel.security.internal.UserService;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.GroupMappingRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.rahdar.kernel.security.web.GroupMappingViews.GroupMappingRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.ShareRequest;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.CreateUserRequest;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.GrantRequest;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Access control behind the approval gate (ADR-0179, threat model rows 16 to 22): every user, role, team, group mapping
 * and default role change is held while a provider holds, changes nothing until approved, and then runs once, as its
 * requester, with the parameters it was asked with. Changes that could take the approvers away carry GATE_INTEGRITY.
 */
class AccessGatingIntegrationTest extends GatedAccessTestBase {

    @Autowired
    UserService userService;

    @Autowired
    RoleService roleService;

    @Autowired
    TeamService teamService;

    @Autowired
    GroupMappingService mappingService;

    @Autowired
    GroupMappingRepository mappings;

    @Autowired
    ClusterRepository clusters;

    private Person alice;
    private Person bob;

    /** Signs the requester in and builds the fixtures; the provider is armed only once the test is ready to ask. */
    private void setUp() {
        alice = requester();
        bob = approver();
        approversAre(bob);
        signIn(alice);
    }

    /** Asks, expects a hold with nothing changed, approves as the second person and expects the change, once. */
    private UUID heldThenRun(String type, Runnable request, BooleanSupplier changed) {
        assertThat(changed.getAsBoolean()).as("not changed before the request").isFalse();
        arm();
        UUID id = hold(alice, request);
        assertThat(changed.getAsBoolean()).as("not changed while held").isFalse();
        assertThat(jdbc.queryForObject("SELECT type FROM held_operation WHERE id = ?", String.class, id))
                .isEqualTo(type);
        approve(bob, id);
        awaitState(id, HeldState.SUCCEEDED);
        assertThat(changed.getAsBoolean()).as("changed once approved").isTrue();
        return id;
    }

    private AppUserEntity target() {
        AppUserEntity user =
                AppUserEntity.local("target-" + UUID.randomUUID().toString().substring(0, 8), null, "{noop}x");
        user.setMustChangePassword(false);
        return users.save(user);
    }

    private boolean disabled(UUID userId) {
        return users.findById(userId).orElseThrow().isDisabled();
    }

    private UUID aRole(String... permissions) {
        return roleService
                .create(new RoleRequest("gate-role-" + UUID.randomUUID(), List.of(permissions), false, false))
                .id();
    }

    private boolean audited(String action, String target) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE action = ? AND target_name = ? AND outcome = 'SUCCESS'",
                        Long.class,
                        action,
                        target)
                > 0;
    }

    // ---- users --------------------------------------------------------------------------------------

    @Test
    void creatingAUserIsHeldAndTheInitialPasswordIsNeverStoredOutsideTheSeal() {
        setUp();
        String name = "created-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "Zx9-initial-secret-" + UUID.randomUUID();

        UUID id = heldThenRun(
                "user.create",
                () -> userService.create(new CreateUserRequest(name, name + "@example.test", password)),
                () -> users.existsByUsernameIgnoreCase(name));

        assertThat(jdbc.queryForObject("SELECT params::text FROM held_operation WHERE id = ?", String.class, id))
                .doesNotContain(password)
                .contains("[redacted]");
        assertThat(jdbc.queryForObject("SELECT sealed_payload FROM held_operation WHERE id = ?", byte[].class, id))
                .as("the sealed payload is wiped at the terminal state")
                .isNull();
        assertThat(lastRequest("user.create").params()).doesNotContain(password);
        assertThat(lastRequest("user.create").display().toString()).doesNotContain(password);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE audit_event::text LIKE ?",
                        Long.class,
                        "%" + password + "%"))
                .isZero();
        assertTraits("user.create", Trait.ACCESS_CONTROL);
    }

    @Test
    void disablingAUserIsHeldThenRuns() {
        setUp();
        AppUserEntity user = target();

        heldThenRun("user.disable", () -> userService.disable(user.getId()), () -> disabled(user.getId()));

        // Approvers may hold the permission through a team, so disabling anyone could remove one.
        assertTraits("user.disable", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
    }

    @Test
    void enablingAUserIsItsOwnType() {
        setUp();
        AppUserEntity user = target();
        user.setDisabled(true);
        users.save(user);

        heldThenRun("user.enable", () -> userService.enable(user.getId()), () -> !disabled(user.getId()));
    }

    @Test
    void unlockingAUserIsHeldThenRuns() {
        setUp();
        AppUserEntity user = target();

        heldThenRun(
                "user.unlock",
                () -> userService.unlock(user.getId()),
                () -> audited("ACCOUNT_UNLOCK", user.getUsername()));
    }

    @Test
    void resettingSecondFactorsIsHeldAndNeedsNoSessionToReplay() {
        setUp();
        AppUserEntity user = target();

        heldThenRun(
                "user.reset-second-factors",
                () -> userService.resetSecondFactors(user.getId()),
                () -> audited("MFA_RESET", user.getUsername()));
    }

    @Test
    void grantingAndRevokingARoleAreHeldThenRun() {
        setUp();
        AppUserEntity user = target();
        UUID role = aRole(Permissions.SUBSCRIPTION_READ);
        BooleanSupplier granted = () -> userRoles.findByIdUserId(user.getId()).stream()
                .anyMatch(ur -> ur.getRoleId().equals(role));

        heldThenRun(
                "user.grant",
                () -> userService.addGrant(user.getId(), new GrantRequest(role, "GLOBAL", null)),
                granted);
        assertThat(granted.getAsBoolean()).isTrue();

        provider.requests.clear();
        UUID revoke = hold(alice, () -> userService.removeGrant(user.getId(), role, "GLOBAL", null));
        assertThat(granted.getAsBoolean()).isTrue();
        approve(bob, revoke);
        awaitState(revoke, HeldState.SUCCEEDED);
        assertThat(granted.getAsBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT type FROM held_operation WHERE id = ?", String.class, revoke))
                .isEqualTo("user.revoke");
    }

    @Test
    void theRefusalsAReadOnlyCheckGivesComeBeforeAnythingIsHeld() {
        setUp();
        arm();

        UUID unknownUser = UUID.randomUUID();
        UUID aliceId = alice.id();

        assertThatThrownBy(() -> userService.disable(unknownUser)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> userService.resetSecondFactors(aliceId)).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM held_operation WHERE requester_id = ?", Long.class, aliceId))
                .isZero();
    }

    @Test
    void aStateChangedWhileHeldIsRefusedWhenItWouldRun() {
        setUp();
        AppUserEntity user = target();
        arm();
        UUID id = hold(alice, () -> userService.disable(user.getId()));
        assertThat(lastRequest("user.disable").effect().unit()).isEqualTo("user");
        user.setDisabled(true);
        users.save(user);

        approve(bob, id);

        awaitState(id, HeldState.REFUSED);
    }

    @Test
    void anApprovedChangeIsLoggedAsTheRequesterAndDoesNotBlockTheirNextRequest() {
        setUp();
        AppUserEntity first = target();
        AppUserEntity second = target();
        heldThenRun("user.disable", () -> userService.disable(first.getId()), () -> disabled(first.getId()));

        // The replay logged an access change by the requester, dated before the next request was made.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM access_change_log WHERE actor_id = ? AND subject_id = ?",
                        Long.class,
                        alice.id(),
                        first.getId()))
                .isEqualTo(1);
        UUID next = hold(alice, () -> userService.disable(second.getId()));
        approve(bob, next);
        awaitState(next, HeldState.SUCCEEDED);
        assertThat(disabled(second.getId())).isTrue();
    }

    // ---- roles --------------------------------------------------------------------------------------

    @Test
    void creatingUpdatingAndDeletingARoleAreHeldThenRun() {
        setUp();
        String name = "gate-new-" + UUID.randomUUID().toString().substring(0, 8);
        heldThenRun(
                "role.create",
                () -> roleService.create(new RoleRequest(name, List.of(Permissions.SUBSCRIPTION_READ), false, false)),
                () -> roles.findByName(name).isPresent());
        RoleEntity role = roles.findByName(name).orElseThrow();
        BooleanSupplier widened =
                () -> rolePermissions.findByIdRoleId(role.getId()).size() == 2;

        heldThenRun(
                "role.update",
                () -> roleService.update(
                        role.getId(),
                        new RoleRequest(
                                name,
                                List.of(Permissions.SUBSCRIPTION_READ, Permissions.DESTINATION_READ),
                                false,
                                false)),
                widened);

        UUID delete = hold(alice, () -> roleService.delete(role.getId()));
        assertThat(roles.findById(role.getId())).isPresent();
        approve(bob, delete);
        awaitState(delete, HeldState.SUCCEEDED);
        assertThat(roles.findById(role.getId())).isEmpty();
    }

    @Test
    void aRoleGainingTheApproverPermissionCarriesGateIntegrityAndOneThatDoesNotDoesNot() {
        setUp();
        UUID role = aRole(Permissions.SUBSCRIPTION_READ);
        arm();

        hold(
                alice,
                () -> roleService.update(
                        role,
                        new RoleRequest(
                                "gate-role-" + UUID.randomUUID(),
                                List.of(Permissions.DESTINATION_READ),
                                false,
                                false)));
        assertTraits("role.update", Trait.ACCESS_CONTROL);

        hold(
                alice,
                () -> roleService.update(
                        role,
                        new RoleRequest(
                                "gate-role-" + UUID.randomUUID(),
                                List.of(Permissions.SUBSCRIPTION_READ, GateTestKit.APPROVER_PERMISSION),
                                false,
                                false)));
        assertTraits("role.update", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);

        hold(
                alice,
                () -> roleService.create(new RoleRequest(
                        "gate-role-" + UUID.randomUUID(), List.of(Permissions.WILDCARD), false, false)));
        assertTraits("role.create", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
    }

    @Test
    void grantingARoleThatHoldsTheApproverPermissionCarriesGateIntegrity() {
        setUp();
        AppUserEntity user = target();
        UUID approverRole = aRole(GateTestKit.APPROVER_PERMISSION);
        UUID plainRole = aRole(Permissions.SUBSCRIPTION_READ);
        arm();

        hold(alice, () -> userService.addGrant(user.getId(), new GrantRequest(plainRole, "GLOBAL", null)));
        assertTraits("user.grant", Trait.ACCESS_CONTROL);
        hold(alice, () -> userService.addGrant(user.getId(), new GrantRequest(approverRole, "GLOBAL", null)));
        assertTraits("user.grant", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        hold(alice, () -> userService.removeGrant(user.getId(), approverRole, "GLOBAL", null));
        assertTraits("user.revoke", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);

        UserRoleEntity held = userRoles.save(new UserRoleEntity(user.getId(), approverRole, "GLOBAL", ScopeIds.GLOBAL));
        assertThat(held).isNotNull();
        hold(alice, () -> userService.disable(user.getId()));
        assertTraits("user.disable", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
    }

    // ---- teams --------------------------------------------------------------------------------------

    @Test
    void teamsPatternsMembersAndSharesAreHeldThenRun() {
        setUp();
        UUID cluster = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "gate-" + UUID.randomUUID(), null, null))
                .getId();
        String name = "gate-team-" + UUID.randomUUID().toString().substring(0, 8);
        String renamed = name + "-renamed";
        AppUserEntity user = target();
        UUID teamRole = roles.findByName("TEAM_OPERATOR").orElseThrow().getId();
        UUID other = teamService.create("gate-other-" + UUID.randomUUID()).id();

        heldThenRun("team.create", () -> teamService.create(name), () -> teamNamed(name));
        UUID team = teamIdOf(name);
        heldThenRun("team.rename", () -> teamService.rename(team, renamed), () -> teamNamed(renamed));
        heldThenRun(
                "team.pattern.add",
                () -> teamService.addPattern(team, new PatternRequest(cluster, PatternKind.ANY, "orders.#")),
                () -> patternCount(team) == 1);
        heldThenRun(
                "team.member.add",
                () -> teamService.addMember(
                        team, new MemberRequest(PrincipalType.USER, user.getId(), null, null, teamRole)),
                () -> memberCount(team) == 1);
        heldThenRun(
                "team.share.add",
                () -> teamService.addShare(
                        team, new ShareRequest(other, cluster, PatternKind.ANY, "orders.#", teamRole)),
                () -> shareCount(team) == 1);

        UUID pattern = jdbc.queryForObject("SELECT id FROM team_pattern WHERE team_id = ?", UUID.class, team);
        UUID member = jdbc.queryForObject("SELECT id FROM team_member WHERE team_id = ?", UUID.class, team);
        UUID share = jdbc.queryForObject("SELECT id FROM team_share WHERE owner_team_id = ?", UUID.class, team);
        UUID adminRole = roles.findByName("TEAM_ADMIN").orElseThrow().getId();
        heldThenRun(
                "team.member.role",
                () -> teamService.changeMemberRole(team, member, adminRole),
                () -> jdbc.queryForObject("SELECT role_id FROM team_member WHERE id = ?", UUID.class, member)
                        .equals(adminRole));
        heldThenRun("team.share.remove", () -> teamService.removeShare(team, share), () -> shareCount(team) == 0);
        heldThenRun("team.member.remove", () -> teamService.removeMember(team, member), () -> memberCount(team) == 0);
        heldThenRun(
                "team.pattern.remove", () -> teamService.removePattern(team, pattern), () -> patternCount(team) == 0);
        assertTraits("team.member.remove", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        assertTraits("team.share.remove", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        heldThenRun("team.delete", () -> teamService.delete(team), () -> !teamNamed(renamed));
        assertTraits("team.delete", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
    }

    private boolean teamNamed(String name) {
        return jdbc.queryForObject("SELECT count(*) FROM team WHERE name = ?", Long.class, name) > 0;
    }

    private UUID teamIdOf(String name) {
        return jdbc.queryForObject("SELECT id FROM team WHERE name = ?", UUID.class, name);
    }

    private long patternCount(UUID team) {
        return jdbc.queryForObject("SELECT count(*) FROM team_pattern WHERE team_id = ?", Long.class, team);
    }

    private long memberCount(UUID team) {
        return jdbc.queryForObject("SELECT count(*) FROM team_member WHERE team_id = ?", Long.class, team);
    }

    private long shareCount(UUID team) {
        return jdbc.queryForObject("SELECT count(*) FROM team_share WHERE owner_team_id = ?", Long.class, team);
    }

    // ---- group mappings and the default role --------------------------------------------------------

    @Test
    void groupMappingsAndTheDefaultRoleAreHeldThenRunAndGateIntegrityFollowsTheRole() {
        setUp();
        doReturn(List.of(
                        new IdentityProviderListing.Entry("gate-sso", IdentityProviderListing.REDIRECT, "SSO", "/sso")))
                .when(catalog)
                .providers();
        UUID plain = aRole(Permissions.SUBSCRIPTION_READ);
        UUID approverRole = aRole(GateTestKit.APPROVER_PERMISSION);

        heldThenRun(
                "group-mapping.create",
                () -> mappingService.create("gate-sso", new GroupMappingRequest("ops", plain, "GLOBAL", null)),
                () -> !mappings.findByProviderId("gate-sso").isEmpty());
        assertTraits("group-mapping.create", Trait.ACCESS_CONTROL);
        UUID mapping = mappings.findByProviderId("gate-sso").getFirst().getId();

        heldThenRun(
                "default-role.set",
                () -> mappingService.setDefaultRole("gate-sso", plain),
                () -> jdbc.queryForObject(
                                "SELECT count(*) FROM identity_provider_default_role WHERE provider_id = 'gate-sso'",
                                Long.class)
                        > 0);
        assertTraits("default-role.set", Trait.ACCESS_CONTROL);

        hold(
                alice,
                () -> mappingService.create(
                        "gate-sso", new GroupMappingRequest("admins", approverRole, "GLOBAL", null)));
        assertTraits("group-mapping.create", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        hold(alice, () -> mappingService.setDefaultRole("gate-sso", approverRole));
        assertTraits("default-role.set", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);

        heldThenRun(
                "group-mapping.delete",
                () -> mappingService.delete("gate-sso", mapping),
                () -> mappings.findById(mapping).isEmpty());
    }

    @Test
    void withNoProviderArmedNothingIsHeldAndTheChangeRunsAtOnce() {
        setUp();
        AppUserEntity user = target();

        userService.disable(user.getId());

        assertThat(disabled(user.getId())).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM held_operation WHERE requester_id = ?", Long.class, alice.id()))
                .isZero();
    }

    @Test
    void aDeniedAccessChangeChangesNothing() {
        setUp();
        AppUserEntity user = target();
        arm();
        provider.decide = request -> new GateDecision.Deny("Not today");

        UUID userId = user.getId();

        assertThatThrownBy(() -> userService.disable(userId)).isInstanceOf(OperationDeniedException.class);
        assertThat(disabled(userId)).isFalse();
    }
}
