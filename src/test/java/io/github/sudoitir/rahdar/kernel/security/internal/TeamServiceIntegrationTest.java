package io.github.sudoitir.rahdar.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.PatternKind;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ResourceNames;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamMemberEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamMemberRepository;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.ShareRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.TeamView;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The team service's rules (team-access spec): what a pattern may be, who may change what, which roles a team
 * may hold, and that every change is audited and applies to the next request.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class TeamServiceIntegrationTest extends PostgresIntegrationTest {

    @MockitoBean
    ResourceNames names;

    @Autowired
    TeamService teams;

    @Autowired
    RoleService roleService;

    @Autowired
    RoleRepository roles;

    @Autowired
    AppUserRepository users;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    PermissionResolver perm;

    @Autowired
    JdbcTemplate jdbc;

    UUID prod;
    UUID staging;

    @BeforeEach
    void setUp() {
        prod = clusters.save(new ClusterEntity("prod-" + UUID.randomUUID(), null, null))
                .getId();
        staging = clusters.save(new ClusterEntity("staging-" + UUID.randomUUID(), null, null))
                .getId();
    }

    private UUID team(String name) {
        return teams.create(name + "-" + UUID.randomUUID()).id();
    }

    private UUID role(String name) {
        return roles.findByName(name).orElseThrow().getId();
    }

    private UUID user() {
        String name = "member-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(name, null, "{noop}x");
        user.setMustChangePassword(false);
        return users.save(user).getId();
    }

    private static PatternRequest pattern(UUID cluster, PatternKind kind, String pattern) {
        return new PatternRequest(cluster, kind, pattern);
    }

    private void asUser(UUID userId) {
        RahdarPrincipal principal = RahdarPrincipal.live(userId, "member", false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private int audited(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ?", Integer.class, action);
    }

    // ---- teams --------------------------------------------------------------------------------------

    @Test
    void aTeamIsCreatedRenamedAndDeletedAndEachIsAudited() {
        int created = audited("TEAM_CREATE");
        UUID id = team("orders");

        teams.rename(id, "Renamed-" + id);
        assertThat(teams.get(id).name()).isEqualTo("Renamed-" + id);
        teams.delete(id);

        assertThat(audited("TEAM_CREATE")).isEqualTo(created + 1);
        assertThat(audited("TEAM_RENAME")).isPositive();
        assertThat(audited("TEAM_DELETE")).isPositive();
        assertThatThrownBy(() -> teams.get(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void teamNamesAreUniqueIgnoringCase() {
        UUID id = team("Orders");
        String name = teams.get(id).name();

        var value = name.toUpperCase();
        assertThatThrownBy(() -> teams.create(value))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).slug())
                .isEqualTo("duplicate-team-name");
    }

    @Test
    void onlyAUserAdministratorCreatesATeam() {
        asUser(user());

        var id = "sneaky-" + UUID.randomUUID();
        assertThatThrownBy(() -> teams.create(id)).isInstanceOf(AccessDeniedException.class);
    }

    // ---- patterns -----------------------------------------------------------------------------------

    @Test
    void aMalformedPatternIsRefusedNamingTheFault() {
        UUID id = team("t");

        var request = pattern(prod, PatternKind.ANY, "a..b");
        assertThatThrownBy(() -> teams.addPattern(id, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty word");
        var request2 = pattern(prod, PatternKind.ANY, "a*");
        assertThatThrownBy(() -> teams.addPattern(id, request2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mixes a wildcard");
    }

    @Test
    void aPatternThatOverlapsAnotherTeamIsRefusedNamingThatTeamAndPattern() {
        UUID orders = team("orders");
        UUID audit = team("audit");
        teams.addPattern(orders, pattern(prod, PatternKind.SUBSCRIPTION, "orders.#"));

        var request3 = pattern(prod, PatternKind.SUBSCRIPTION, "orders.audit.*");
        assertThatThrownBy(() -> teams.addPattern(audit, request3))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.slug()).isEqualTo("team-pattern-overlap");
                    assertThat(e.getMessage()).contains(teams.get(orders).name(), "orders.#");
                });
    }

    @Test
    void theSamePatternOnAnotherClusterOrAnotherKindOrDisjointNamesIsAllowed() {
        UUID orders = team("orders");
        UUID other = team("other");
        teams.addPattern(orders, pattern(prod, PatternKind.SUBSCRIPTION, "orders.#"));

        teams.addPattern(other, pattern(staging, PatternKind.SUBSCRIPTION, "orders.#"));
        teams.addPattern(other, pattern(prod, PatternKind.DESTINATION, "orders.#"));
        teams.addPattern(other, pattern(prod, PatternKind.SUBSCRIPTION, "billing.*"));

        assertThat(teams.get(other).patterns()).hasSize(3);
    }

    @Test
    void bothOverlapsEitherKind() {
        UUID orders = team("orders");
        UUID other = team("other");
        teams.addPattern(orders, pattern(prod, PatternKind.DESTINATION, "orders.#"));

        var request4 = pattern(prod, PatternKind.ANY, "orders.in");
        assertThatThrownBy(() -> teams.addPattern(other, request4)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aTeamMayOverlapItself() {
        UUID orders = team("orders");
        teams.addPattern(orders, pattern(prod, PatternKind.SUBSCRIPTION, "orders.#"));

        teams.addPattern(orders, pattern(prod, PatternKind.SUBSCRIPTION, "orders.in"));

        assertThat(teams.get(orders).patterns()).hasSize(2);
        var request5 = pattern(prod, PatternKind.SUBSCRIPTION, "orders.in");
        assertThatThrownBy(() -> teams.addPattern(orders, request5))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("duplicate-team-pattern"));
    }

    @Test
    void aPatternNeedsAnExistingCluster() {
        UUID id = team("t");

        var request6 = pattern(UUID.randomUUID(), PatternKind.ANY, "a.#");
        assertThatThrownBy(() -> teams.addPattern(id, request6)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void removingAPatternEndsTheAccessItGave() {
        UUID orders = team("orders");
        UUID alice = user();
        var added = teams.addPattern(orders, pattern(prod, PatternKind.SUBSCRIPTION, "orders.#"));
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, alice, null, null, role("TEAM_OPERATOR")));

        asUser(alice);
        assertThat(perm.can(prod, ResourceRef.subscription("orders.in"), "subscription:purge"))
                .isTrue();

        new AdminAuthenticationExtension().beforeEach(null);
        teams.removePattern(orders, added.id());

        asUser(alice);
        assertThat(perm.can(prod, ResourceRef.subscription("orders.in"), "subscription:purge"))
                .isFalse();
    }

    @Test
    void deletingATeamEndsItsMembersAccessAtOnce() {
        UUID orders = team("orders");
        UUID alice = user();
        teams.addPattern(orders, pattern(prod, PatternKind.ANY, "orders.#"));
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, alice, null, null, role("TEAM_VIEWER")));
        asUser(alice);
        assertThat(perm.canSeeCluster(prod)).isTrue();

        new AdminAuthenticationExtension().beforeEach(null);
        teams.delete(orders);

        asUser(alice);
        assertThat(perm.canSeeCluster(prod)).isFalse();
    }

    // ---- preview and unowned -------------------------------------------------------------------------

    @Test
    void thePreviewCountsWhatThePatternCoversAndShowsAtMostTwentyExamples() {
        UUID id = team("t");
        when(names.queues(prod))
                .thenReturn(IntStream.range(0, 30).mapToObj(i -> "orders.q" + i).toList());
        when(names.addresses(prod)).thenReturn(List.of("orders", "billing.in"));

        var preview = teams.preview(id, prod, PatternKind.ANY, "orders.#");

        assertThat(preview.subscriptionMatches()).isEqualTo(30);
        assertThat(preview.subscriptionExamples()).hasSize(20);
        assertThat(preview.destinationMatches()).isEqualTo(1);
        assertThat(preview.destinationExamples()).containsExactly("orders");
        assertThat(preview.conflicts()).isEmpty();
    }

    @Test
    void thePreviewNamesTheClashesAndOnlyCountsTheKindAsked() {
        UUID orders = team("orders");
        UUID other = team("other");
        teams.addPattern(orders, pattern(prod, PatternKind.SUBSCRIPTION, "orders.#"));
        when(names.queues(prod)).thenReturn(List.of("orders.in"));
        when(names.addresses(prod)).thenReturn(List.of("orders.in"));

        var preview = teams.preview(other, prod, PatternKind.SUBSCRIPTION, "orders.*");

        assertThat(preview.destinationMatches()).isZero();
        assertThat(preview.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.teamId()).isEqualTo(orders);
            assertThat(c.pattern()).isEqualTo("orders.#");
        });
    }

    @Test
    void unownedListsTheNamesNoTeamOwnsOfThatKind() {
        UUID orders = team("orders");
        teams.addPattern(orders, pattern(prod, PatternKind.SUBSCRIPTION, "orders.#"));
        when(names.queues(prod)).thenReturn(List.of("orders.in", "legacy.inbox"));
        when(names.addresses(prod)).thenReturn(List.of("orders.in", "legacy.inbox"));

        assertThat(teams.unowned(prod, ResourceType.SUBSCRIPTION)).containsExactly("legacy.inbox");
        assertThat(teams.unowned(prod, ResourceType.DESTINATION)).containsExactly("orders.in", "legacy.inbox");
    }

    // ---- members ------------------------------------------------------------------------------------

    @Test
    void aMemberMustHoldATeamRole() {
        UUID id = team("t");

        var member = new MemberRequest(PrincipalType.USER, user(), null, null, role("OPERATOR"));
        assertThatThrownBy(() -> teams.addMember(id, member))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("not-a-team-role"));
    }

    @Test
    void aUserIsAMemberOnceAndTheRoleCanChange() {
        UUID id = team("t");
        UUID alice = user();
        var member = teams.addMember(id, new MemberRequest(PrincipalType.USER, alice, null, null, role("TEAM_VIEWER")));

        var member2 = new MemberRequest(PrincipalType.USER, alice, null, null, role("TEAM_VIEWER"));
        assertThatThrownBy(() -> teams.addMember(id, member2))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("team-member-exists"));
        var changed = teams.changeMemberRole(id, member.id(), role("TEAM_OPERATOR"));

        assertThat(changed.roleName()).isEqualTo("TEAM_OPERATOR");
        teams.removeMember(id, member.id());
        assertThat(teams.get(id).members()).isEmpty();
    }

    @Test
    void aGroupMemberNeedsAKnownExternalProviderAndAGroup() {
        UUID id = team("t");

        var member3 = new MemberRequest(PrincipalType.GROUP, null, "no-such-provider", "orders", role("TEAM_VIEWER"));
        assertThatThrownBy(() -> teams.addMember(id, member3)).isInstanceOf(NotFoundException.class);
        var member4 = new MemberRequest(PrincipalType.GROUP, null, null, null, role("TEAM_VIEWER"));
        assertThatThrownBy(() -> teams.addMember(id, member4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTeamAdminManagesTheirTeamsMembersWithRolesNoWiderThanTheirOwn() {
        UUID orders = team("orders");
        UUID boss = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, boss, null, null, role("TEAM_ADMIN")));
        var wider = roleService.create(new RoleRequest(
                "wider-" + UUID.randomUUID(),
                List.of("subscription:read", "subscription:update", "capture:write"),
                false,
                true));
        UUID newcomer = user();

        asUser(boss);
        var added = teams.addMember(
                orders, new MemberRequest(PrincipalType.USER, newcomer, null, null, role("TEAM_VIEWER")));
        teams.changeMemberRole(orders, added.id(), role("TEAM_OPERATOR"));

        var id2 = added.id();
        var id3 = wider.id();
        assertThatThrownBy(() -> teams.changeMemberRole(orders, id2, id3))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("capture:write");
        assertThat(teams.get(orders).members()).hasSize(2);
        teams.removeMember(orders, added.id());
    }

    @Test
    void aTeamAdminCannotChangePatternsOrSharesOrSeeOtherTeams() {
        UUID orders = team("orders");
        UUID billing = team("billing");
        UUID boss = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, boss, null, null, role("TEAM_ADMIN")));

        asUser(boss);

        var request7 = pattern(prod, PatternKind.ANY, "a.#");
        assertThatThrownBy(() -> teams.addPattern(orders, request7)).isInstanceOf(AccessDeniedException.class);
        var share = new ShareRequest(billing, prod, PatternKind.ANY, "a.#", role("TEAM_VIEWER"));
        assertThatThrownBy(() -> teams.addShare(orders, share)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> teams.get(billing)).isInstanceOf(NotFoundException.class);
        var member5 = new MemberRequest(PrincipalType.USER, user(), null, null, role("TEAM_VIEWER"));
        assertThatThrownBy(() -> teams.addMember(billing, member5)).isInstanceOf(NotFoundException.class);
        assertThat(teams.list()).extracting(s -> s.id()).containsExactly(orders);
    }

    // ---- lookups for who administers a team ---------------------------------------------------------

    private AppUserEntity namedUser(String name, boolean disabled) {
        AppUserEntity user = AppUserEntity.local(name, null, "{noop}x");
        user.setMustChangePassword(false);
        user.setDisabled(disabled);
        return users.save(user);
    }

    private UUID teamAdminOf(UUID team) {
        UUID boss = user();
        teams.addMember(team, new MemberRequest(PrincipalType.USER, boss, null, null, role("TEAM_ADMIN")));
        return boss;
    }

    @Test
    void aTeamAdminLooksUpEnabledUsersByNamePrefixAndNeverSeesADisabledOne() {
        String tag = "lookup" + UUID.randomUUID().toString().substring(0, 8);
        namedUser(tag + "-ada", false);
        namedUser(tag + "-ben", true);
        namedUser("other-" + tag, false);
        UUID boss = teamAdminOf(team("orders"));

        asUser(boss);

        assertThat(teams.findUsers(tag.toUpperCase()))
                .extracting(u -> u.username())
                .containsExactly(tag + "-ada");
        assertThat(teams.findUsers(tag + "-b")).isEmpty();
    }

    @Test
    void aLookupNeedsTwoCharactersSoNobodyCanListEveryAccount() {
        String tag = "short" + UUID.randomUUID().toString().substring(0, 8);
        namedUser(tag, false);
        UUID boss = teamAdminOf(team("orders"));

        asUser(boss);

        assertThat(teams.findUsers("")).isEmpty();
        assertThat(teams.findUsers(" s ")).isEmpty();
        assertThat(teams.findUsers(tag.substring(0, 2))).isNotEmpty();
    }

    @Test
    void aLookupReturnsAtMostTwentyUsers() {
        String tag = "many" + UUID.randomUUID().toString().substring(0, 8);
        IntStream.range(0, 25).forEach(i -> namedUser("%s-%02d".formatted(tag, i), false));
        UUID boss = teamAdminOf(team("orders"));

        asUser(boss);

        assertThat(teams.findUsers(tag)).hasSize(20);
    }

    @Test
    void aLookupTreatsWildcardCharactersInThePrefixAsText() {
        String tag = "wild" + UUID.randomUUID().toString().substring(0, 8);
        namedUser(tag + "-x", false);
        UUID boss = teamAdminOf(team("orders"));

        asUser(boss);

        assertThat(teams.findUsers("%" + tag)).isEmpty();
    }

    @Test
    void theLookupsOfARoleListHoldOnlyTeamRolesAndTheCatalogueEntriesTheyUse() {
        roleService.create(
                new RoleRequest("not-a-team-role-" + UUID.randomUUID(), List.of("user:admin"), false, false));
        UUID boss = teamAdminOf(team("orders"));

        asUser(boss);
        var lookup = teams.teamRoles();

        assertThat(lookup.roles()).isNotEmpty().allMatch(r -> r.teamAssignable());
        assertThat(lookup.roles()).extracting(r -> r.name()).contains("TEAM_VIEWER", "TEAM_OPERATOR", "TEAM_ADMIN");
        assertThat(lookup.permissions())
                .extracting(p -> p.action())
                .contains("subscription:read", "team:admin")
                .doesNotContain("user:admin");
        assertThat(lookup.roles()).flatExtracting(r -> r.permissions()).contains("subscription:read");
    }

    @Test
    void aMemberWhoAdministersNoTeamCannotUseTheLookups() {
        UUID viewer = user();
        teams.addMember(team("orders"), new MemberRequest(PrincipalType.USER, viewer, null, null, role("TEAM_VIEWER")));

        asUser(viewer);

        assertThatThrownBy(() -> teams.findUsers("")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> teams.teamRoles()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aUserAdministratorUsesTheLookupsToo() {
        namedUser("admin-lookup-" + UUID.randomUUID(), false);

        assertThat(teams.findUsers("admin-lookup-")).isNotEmpty();
        assertThat(teams.teamRoles().roles()).isNotEmpty();
    }

    @Test
    void theTeamListSummarisesEachTeamsPatternsWithTheirCluster() {
        UUID orders = team("orders");
        teams.addPattern(orders, pattern(prod, PatternKind.SUBSCRIPTION, "orders.#"));
        teams.addPattern(orders, pattern(staging, PatternKind.DESTINATION, "orders.*"));

        var summary = teams.list().stream()
                .filter(t -> t.id().equals(orders))
                .findFirst()
                .orElseThrow();

        assertThat(summary.patterns())
                .extracting(p -> p.clusterId() + " " + p.kind() + " " + p.pattern())
                .containsExactlyInAnyOrder(prod + " SUBSCRIPTION orders.#", staging + " DESTINATION orders.*");
    }

    @Test
    void aMemberWhoIsNotATeamAdminCannotManageMembers() {
        UUID orders = team("orders");
        UUID viewer = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, viewer, null, null, role("TEAM_OPERATOR")));

        asUser(viewer);

        var member6 = new MemberRequest(PrincipalType.USER, user(), null, null, role("TEAM_VIEWER"));
        assertThatThrownBy(() -> teams.addMember(orders, member6)).isInstanceOf(NotFoundException.class);
        assertThat(teams.list()).isEmpty();
    }

    // ---- shares -------------------------------------------------------------------------------------

    @Test
    void aSharedPatternMustLieWithinOneOfTheOwnersPatterns() {
        UUID orders = team("orders");
        UUID billing = team("billing");
        teams.addPattern(orders, pattern(prod, PatternKind.ANY, "orders.#"));
        teams.addPattern(orders, pattern(prod, PatternKind.SUBSCRIPTION, "audit.*"));
        UUID viewer = role("TEAM_VIEWER");

        var share = teams.addShare(orders, new ShareRequest(billing, prod, PatternKind.ANY, "orders.events.#", viewer));

        assertThat(share.covered()).isTrue();
        assertThat(teams.get(billing).sharesIn()).hasSize(1);
        var share2 = new ShareRequest(billing, prod, PatternKind.ANY, "billing.#", viewer);
        assertThatThrownBy(() -> teams.addShare(orders, share2))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("share-outside-owner"));
        var share3 = new ShareRequest(billing, prod, PatternKind.ANY, "audit.x", viewer);
        assertThatThrownBy(() -> teams.addShare(orders, share3))
                .as("the owner owns audit.* for queues only")
                .isInstanceOf(ConflictException.class);
        var share4 = new ShareRequest(billing, staging, PatternKind.ANY, "orders.events.#", viewer);
        assertThatThrownBy(() -> teams.addShare(orders, share4))
                .as("owned on prod, not on staging")
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void aShareNeedsAnotherTeamAndATeamRole() {
        UUID orders = team("orders");
        UUID billing = team("billing");
        teams.addPattern(orders, pattern(prod, PatternKind.ANY, "orders.#"));

        var share5 = new ShareRequest(orders, prod, PatternKind.ANY, "orders.#", role("TEAM_VIEWER"));
        assertThatThrownBy(() -> teams.addShare(orders, share5))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("share-with-self"));
        var share6 = new ShareRequest(billing, prod, PatternKind.ANY, "orders.#", role("VIEWER"));
        assertThatThrownBy(() -> teams.addShare(orders, share6))
                .isInstanceOfSatisfying(
                        ConflictException.class, e -> assertThat(e.slug()).isEqualTo("not-a-team-role"));
        var share7 = new ShareRequest(UUID.randomUUID(), prod, PatternKind.ANY, "orders.#", role("TEAM_VIEWER"));
        assertThatThrownBy(() -> teams.addShare(orders, share7)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aShareGivesTheReceivingMembersItsRoleAndEndsWhenRemoved() {
        UUID orders = team("orders");
        UUID billing = team("billing");
        UUID carol = user();
        teams.addPattern(orders, pattern(prod, PatternKind.ANY, "orders.#"));
        teams.addMember(billing, new MemberRequest(PrincipalType.USER, carol, null, null, role("TEAM_OPERATOR")));
        var share = teams.addShare(
                orders, new ShareRequest(billing, prod, PatternKind.ANY, "orders.events.#", role("TEAM_VIEWER")));

        asUser(carol);
        assertThat(perm.can(prod, ResourceRef.subscription("orders.events.a"), "subscription:read"))
                .isTrue();
        assertThat(perm.can(prod, ResourceRef.subscription("orders.events.a"), "subscription:purge"))
                .isFalse();

        new AdminAuthenticationExtension().beforeEach(null);
        teams.removeShare(orders, share.id());

        asUser(carol);
        assertThat(perm.can(prod, ResourceRef.subscription("orders.events.a"), "subscription:read"))
                .isFalse();
    }

    @Test
    void aShareThatTheOwnersPatternsNoLongerCoverIsShownAsNotCovered() {
        UUID orders = team("orders");
        UUID billing = team("billing");
        var owned = teams.addPattern(orders, pattern(prod, PatternKind.ANY, "orders.#"));
        teams.addShare(
                orders, new ShareRequest(billing, prod, PatternKind.ANY, "orders.events.#", role("TEAM_VIEWER")));

        teams.removePattern(orders, owned.id());

        TeamView view = teams.get(orders);
        assertThat(view.sharesOut())
                .singleElement()
                .satisfies(s -> assertThat(s.covered()).isFalse());
    }

    @Test
    void everyChangeIsAudited() {
        UUID orders = team("orders");
        UUID billing = team("billing");
        var owned = teams.addPattern(orders, pattern(prod, PatternKind.ANY, "orders.#"));
        var member =
                teams.addMember(orders, new MemberRequest(PrincipalType.USER, user(), null, null, role("TEAM_VIEWER")));
        var share = teams.addShare(
                orders, new ShareRequest(billing, prod, PatternKind.ANY, "orders.#", role("TEAM_VIEWER")));
        teams.changeMemberRole(orders, member.id(), role("TEAM_OPERATOR"));
        teams.removeShare(orders, share.id());
        teams.removeMember(orders, member.id());
        teams.removePattern(orders, owned.id());

        for (String action : List.of(
                "TEAM_PATTERN_ADD",
                "TEAM_MEMBER_ADD",
                "TEAM_SHARE_ADD",
                "TEAM_MEMBER_ROLE",
                "TEAM_SHARE_REMOVE",
                "TEAM_MEMBER_REMOVE",
                "TEAM_PATTERN_REMOVE")) {
            assertThat(audited(action)).as(action).isPositive();
        }
    }

    @Autowired
    TeamMemberRepository teamMembers;

    // ---- the limits of a team admin ------------------------------------------------------------------

    private UUID widerRole() {
        return roleService
                .create(new RoleRequest(
                        "wider-" + UUID.randomUUID(),
                        List.of("subscription:read", "subscription:update", "capture:write"),
                        false,
                        true))
                .id();
    }

    @Test
    void aTeamAdminCannotAddAChangeOrRemoveADirectoryGroupButAUserAdministratorCan() {
        UUID orders = team("orders");
        UUID boss = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, boss, null, null, role("TEAM_ADMIN")));
        UUID group = teamMembers
                .save(TeamMemberEntity.ofGroup(orders, "dir", "eng", role("TEAM_VIEWER")))
                .getId();

        asUser(boss);

        var member7 = new MemberRequest(PrincipalType.GROUP, null, "dir", "ops", role("TEAM_VIEWER"));
        assertThatThrownBy(() -> teams.addMember(orders, member7))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("user administrator");
        var role = role("TEAM_OPERATOR");
        assertThatThrownBy(() -> teams.changeMemberRole(orders, group, role)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> teams.removeMember(orders, group)).isInstanceOf(AccessDeniedException.class);

        new AdminAuthenticationExtension().beforeEach(null);
        teams.changeMemberRole(orders, group, role("TEAM_OPERATOR"));
        teams.removeMember(orders, group);
    }

    @Test
    void aTeamAdminCannotDemoteOrRemoveAMemberWhoseRoleHoldsMoreThanTheirs() {
        UUID orders = team("orders");
        UUID boss = user();
        UUID senior = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, boss, null, null, role("TEAM_ADMIN")));
        var member = teams.addMember(orders, new MemberRequest(PrincipalType.USER, senior, null, null, widerRole()));

        asUser(boss);

        var id4 = member.id();
        var role2 = role("TEAM_VIEWER");
        assertThatThrownBy(() -> teams.changeMemberRole(orders, id4, role2))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("capture:write");
        var id5 = member.id();
        assertThatThrownBy(() -> teams.removeMember(orders, id5)).isInstanceOf(AccessDeniedException.class);
        assertThat(teams.get(orders).members()).hasSize(2);

        new AdminAuthenticationExtension().beforeEach(null);
        teams.changeMemberRole(orders, member.id(), role("TEAM_VIEWER"));
        teams.removeMember(orders, member.id());
    }

    @Test
    void aTeamAdminMayStillChangeAndRemoveMembersWithinTheirOwnRights() {
        UUID orders = team("orders");
        UUID boss = user();
        UUID peer = user();
        teams.addMember(orders, new MemberRequest(PrincipalType.USER, boss, null, null, role("TEAM_ADMIN")));
        var member =
                teams.addMember(orders, new MemberRequest(PrincipalType.USER, peer, null, null, role("TEAM_ADMIN")));

        asUser(boss);
        teams.changeMemberRole(orders, member.id(), role("TEAM_VIEWER"));
        teams.removeMember(orders, member.id());

        assertThat(teams.get(orders).members()).hasSize(1);
    }

    // ---- concurrency ---------------------------------------------------------------------------------

    @Test
    void twoTeamsAddingOverlappingPatternsAtOnceCannotBothSucceed() throws Exception {
        for (int round = 0; round < 8; round++) {
            UUID a = team("race-a");
            UUID b = team("race-b");
            UUID cluster = clusters.save(new ClusterEntity("race-" + UUID.randomUUID(), null, null))
                    .getId();
            CountDownLatch start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> addWhenReleased(start, a, cluster, "race.#"));
                var second = pool.submit(() -> addWhenReleased(start, b, cluster, "race.in.*"));
                start.countDown();
                int succeeded = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
                assertThat(succeeded).as("round " + round).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private boolean addWhenReleased(CountDownLatch start, UUID team, UUID cluster, String pattern)
            throws InterruptedException {
        start.await();
        new AdminAuthenticationExtension().beforeEach(null);
        try {
            teams.addPattern(team, pattern(cluster, PatternKind.SUBSCRIPTION, pattern));
            return true;
        } catch (ConflictException _) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
