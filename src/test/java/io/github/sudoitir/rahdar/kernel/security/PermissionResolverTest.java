package io.github.sudoitir.rahdar.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.app.RahdarFeatures;
import io.github.sudoitir.rahdar.feature.alerting.AlertPermissions;
import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.kernel.plugin.CatalogueEntry;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamPatternEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamPatternRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamShareEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamShareRepository;
import io.github.sudoitir.rahdar.platform.clusters.ClusterPermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The decision every permission check goes through: the scope walk and wildcards of role grants, what a
 * permission's catalogue scope allows, and the team and share paths of a resource check (authorization and
 * team-access specs, ADR-0038). The catalogue is the real core one; the database is stubbed.
 */
class PermissionResolverTest {

    static final String SUBSCRIPTION_PURGE = MessagePermissions.SUBSCRIPTION_PURGE;
    static final String SUBSCRIPTION_READ = Permissions.SUBSCRIPTION_READ;
    static final String MESSAGE_SEND = MessagePermissions.MESSAGE_SEND;
    static final String SUBSCRIPTION_CREATE = "subscription:create";

    static final Set<String> TEAM_VIEWER =
            Set.of(SUBSCRIPTION_READ, Permissions.DESTINATION_READ, MessagePermissions.MESSAGE_READ);
    static final Set<String> TEAM_OPERATOR = Set.of(
            SUBSCRIPTION_READ,
            Permissions.DESTINATION_READ,
            MessagePermissions.MESSAGE_READ,
            SUBSCRIPTION_PURGE,
            MESSAGE_SEND,
            MessagePermissions.MESSAGE_DELETE);

    final ScopeHierarchy environments = mock(ScopeHierarchy.class);
    final AccessLoader access = mock(AccessLoader.class);
    final FeatureRegistry features = mock(FeatureRegistry.class);
    final TeamPatternRepository patterns = mock(TeamPatternRepository.class);
    final TeamShareRepository shares = mock(TeamShareRepository.class);
    final RolePermissionRepository rolePermissions = mock(RolePermissionRepository.class);

    final List<TeamPatternEntity> ownedRows = new ArrayList<>();
    final List<TeamShareEntity> shareRows = new ArrayList<>();
    final Map<UUID, Set<String>> rolePermissionRows = new HashMap<>();

    final UUID clusterId = UUID.randomUUID();
    final UUID otherClusterId = UUID.randomUUID();
    final UUID environmentId = UUID.randomUUID();
    final UUID userId = UUID.randomUUID();
    final UUID orders = UUID.randomUUID();
    final UUID billing = UUID.randomUUID();

    PermissionResolver resolver;
    Map<String, CatalogueEntry> catalogue;

    @BeforeEach
    void setUp() {
        catalogue = RahdarFeatures.descriptors().stream()
                .flatMap(d -> d.permissions().stream()
                        .map(p -> new CatalogueEntry(
                                p.action(), p.label(), d.id(), d.title(), p.scope(), p.resourceTypes(), p.requires())))
                .collect(Collectors.toMap(CatalogueEntry::action, e -> e));
        when(features.permission(anyString()))
                .thenAnswer(i -> Optional.ofNullable(catalogue.get(i.<String>getArgument(0))));
        when(features.catalogue()).thenAnswer(i -> List.copyOf(catalogue.values()));
        when(patterns.findAll()).thenAnswer(i -> List.copyOf(ownedRows));
        when(shares.findAll()).thenAnswer(i -> List.copyOf(shareRows));
        when(rolePermissions.findByIdRoleId(ArgumentMatchers.any()))
                .thenAnswer(i -> rolePermissionRows.getOrDefault(i.<UUID>getArgument(0), Set.of()).stream()
                        .map(action -> new RolePermissionEntity(i.getArgument(0), action))
                        .toList());
        resolver = new PermissionResolver(
                environments,
                access,
                new TeamIndex(mock(TeamRepository.class), patterns, shares, rolePermissions),
                features);
        access(Set.of(), Map.of());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** The signed-in user's access: their role grants and the permissions their team roles give them. */
    private void access(Set<Grant> grants, Map<UUID, Set<String>> teamPermissions) {
        RahdarPrincipal principal = RahdarPrincipal.live(userId, "u", false);
        when(access.of(userId)).thenReturn(new AccessSnapshot(grants, teamPermissions));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private static Grant global(String... permissions) {
        return new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of(permissions));
    }

    private void owns(UUID team, UUID cluster, String kind, String pattern) {
        ownedRows.add(new TeamPatternEntity(team, cluster, kind, pattern));
    }

    private void shares(UUID owner, UUID target, UUID cluster, String kind, String pattern, Set<String> role) {
        UUID roleId = UUID.randomUUID();
        rolePermissionRows.put(roleId, role);
        shareRows.add(new TeamShareEntity(owner, target, cluster, kind, pattern, roleId));
    }

    // ---- role grants --------------------------------------------------------------------------------

    @Test
    void noAuthenticationDeniesEverything() {
        SecurityContextHolder.clearContext();

        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("a"), SUBSCRIPTION_READ))
                .isFalse();
        assertThat(resolver.canSeeCluster(clusterId)).isFalse();
    }

    @Test
    void aGlobalGrantCoversAnyCluster() {
        access(Set.of(global(Permissions.CLUSTER_READ)), Map.of());

        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(otherClusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(clusterId, ClusterPermissions.CLUSTER_WRITE)).isFalse();
    }

    @Test
    void anEnvironmentGrantCoversOnlyItsMembers() {
        when(environments.environmentOf(clusterId)).thenReturn(environmentId);
        when(environments.environmentOf(otherClusterId)).thenReturn(null);
        access(
                Set.of(new Grant(Grant.ScopeType.ENVIRONMENT, environmentId, Set.of(Permissions.CLUSTER_READ))),
                Map.of());

        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(otherClusterId, Permissions.CLUSTER_READ)).isFalse();
    }

    @Test
    void aClusterGrantDoesNotLeakToASiblingClusterInTheSameEnvironment() {
        when(environments.environmentOf(clusterId)).thenReturn(environmentId);
        when(environments.environmentOf(otherClusterId)).thenReturn(environmentId);
        access(Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(Permissions.CLUSTER_READ))), Map.of());

        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(otherClusterId, Permissions.CLUSTER_READ)).isFalse();
    }

    @Test
    void wildcardsGrantWhatTheyCover() {
        access(Set.of(global(Permissions.WILDCARD)), Map.of());
        assertThat(resolver.can(clusterId, SUBSCRIPTION_PURGE)).isTrue();
        assertThat(resolver.can(Permissions.USER_ADMIN)).isTrue();

        access(Set.of(global("message:*")), Map.of());
        assertThat(resolver.can(clusterId, MESSAGE_SEND)).isTrue();
        assertThat(resolver.can(clusterId, MessagePermissions.MESSAGE_DELETE)).isTrue();
        assertThat(resolver.can(clusterId, SUBSCRIPTION_PURGE)).isFalse();
    }

    @Test
    void rolesAddUpAcrossGrants() {
        access(
                Set.of(
                        new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(Permissions.CLUSTER_READ)),
                        new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(MESSAGE_SEND))),
                Map.of());

        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
        assertThat(resolver.can(clusterId, MESSAGE_SEND)).isTrue();
        assertThat(resolver.can(clusterId, SUBSCRIPTION_PURGE)).isFalse();
    }

    // ---- scope ---------------------------------------------------------------------------------------

    @Test
    void aGlobalPermissionTakesEffectOnlyThroughAGlobalGrant() {
        when(environments.environmentOf(clusterId)).thenReturn(environmentId);
        access(
                Set.of(
                        new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(Permissions.USER_ADMIN)),
                        new Grant(Grant.ScopeType.ENVIRONMENT, environmentId, Set.of(Permissions.USER_ADMIN))),
                Map.of());

        assertThat(resolver.can(clusterId, Permissions.USER_ADMIN)).isFalse();
        assertThat(resolver.can(Permissions.USER_ADMIN)).isFalse();

        access(Set.of(global(Permissions.USER_ADMIN)), Map.of());
        assertThat(resolver.can(clusterId, Permissions.USER_ADMIN)).isTrue();
        assertThat(resolver.can(Permissions.USER_ADMIN)).isTrue();
    }

    @Test
    void aPermissionOutsideTheCatalogueGrantsNothingNotEvenThroughAWildcard() {
        catalogue.remove(AlertPermissions.ALERT_READ);
        access(Set.of(global(Permissions.WILDCARD, AlertPermissions.ALERT_READ, "alert:*")), Map.of());

        assertThat(resolver.can(clusterId, AlertPermissions.ALERT_READ)).isFalse();
        assertThat(resolver.can(clusterId, "nothing:declared")).isFalse();
        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isTrue();
    }

    @Test
    void aResourcePermissionAskedAboutAClusterAsAWholeIsAnsweredByGrantsOnly() {
        UUID team = orders;
        owns(team, clusterId, "ANY", "orders.#");
        access(Set.of(), Map.of(team, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, SUBSCRIPTION_PURGE)).isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_PURGE))
                .isTrue();
    }

    // ---- owning team ---------------------------------------------------------------------------------

    @Test
    void aTeamMemberActsOnWhatTheTeamOwnsAndNothingElse() {
        owns(orders, clusterId, "ANY", "orders.#");
        owns(billing, clusterId, "ANY", "billing.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_PURGE))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders"), SUBSCRIPTION_PURGE))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("billing.in"), SUBSCRIPTION_PURGE))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("legacy.inbox"), SUBSCRIPTION_READ))
                .isFalse();
    }

    @Test
    void theTeamRoleLimitsWhatTheMemberMayDo() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_VIEWER));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_READ))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_PURGE))
                .isFalse();
    }

    @Test
    void aTeamsNamesOnOneClusterSayNothingOnAnother() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));

        assertThat(resolver.can(otherClusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_PURGE))
                .isFalse();
        assertThat(resolver.canSeeCluster(otherClusterId)).isFalse();
    }

    @Test
    void aPatternOfOneKindDoesNotReachTheOther() {
        owns(orders, clusterId, "SUBSCRIPTION", "orders.#");
        owns(billing, clusterId, "DESTINATION", "billing.#");
        owns(billing, clusterId, "ANY", "both.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR, billing, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_PURGE))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.destination("orders.in"), MESSAGE_SEND))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.destination("billing.in"), MESSAGE_SEND))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("billing.in"), SUBSCRIPTION_PURGE))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("both.x"), SUBSCRIPTION_PURGE))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.destination("both.x"), MESSAGE_SEND))
                .isTrue();
    }

    @Test
    void aPermissionIsOnlyCheckedOnTheKindsItActsOn() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));

        // message:send acts on an address, so a team right on a queue named the same does not carry it
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), MESSAGE_SEND))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.destination("orders.in"), MESSAGE_SEND))
                .isTrue();
    }

    @Test
    void teamsRightsAddUpAcrossTeams() {
        owns(orders, clusterId, "ANY", "orders.#");
        owns(billing, clusterId, "ANY", "billing.#");
        access(Set.of(), Map.of(orders, TEAM_VIEWER, billing, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("billing.x"), SUBSCRIPTION_PURGE))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.x"), SUBSCRIPTION_PURGE))
                .isFalse();
    }

    // ---- shares --------------------------------------------------------------------------------------

    @Test
    void aShareGivesTheReceivingTeamItsRoleOnWhatItCovers() {
        owns(orders, clusterId, "ANY", "orders.#");
        shares(orders, billing, clusterId, "ANY", "orders.events.#", TEAM_VIEWER);
        access(Set.of(), Map.of(billing, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.events.paid"), SUBSCRIPTION_READ))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.events.paid"), SUBSCRIPTION_PURGE))
                .as("the share's role, not the member's own team role")
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_READ))
                .isFalse();
    }

    @Test
    void aShareDoesNotReachTheTeamsThatDidNotReceiveIt() {
        UUID audit = UUID.randomUUID();
        owns(orders, clusterId, "ANY", "orders.#");
        shares(orders, billing, clusterId, "ANY", "orders.events.#", TEAM_VIEWER);
        access(Set.of(), Map.of(audit, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.events.paid"), SUBSCRIPTION_READ))
                .isFalse();
    }

    @Test
    void aShareOutsideWhatTheOwnerOwnsGrantsNothing() {
        owns(orders, clusterId, "ANY", "orders.#");
        shares(orders, billing, clusterId, "ANY", "billing.#", TEAM_VIEWER);
        shares(orders, billing, clusterId, "ANY", "orders.#.x", TEAM_VIEWER);
        access(Set.of(), Map.of(billing, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("billing.in"), SUBSCRIPTION_READ))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.a.x"), SUBSCRIPTION_READ))
                .isTrue();
    }

    @Test
    void aShareOfAKindTheOwnerOnlyOwnsInPartGrantsOnlyThatPart() {
        owns(orders, clusterId, "SUBSCRIPTION", "orders.#");
        shares(orders, billing, clusterId, "ANY", "orders.events.#", TEAM_VIEWER);
        access(Set.of(), Map.of(billing, TEAM_VIEWER));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.events.a"), SUBSCRIPTION_READ))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.destination("orders.events.a"), Permissions.DESTINATION_READ))
                .isFalse();
    }

    // ---- platform and team ---------------------------------------------------------------------------

    @Test
    void aPlatformGrantCoversEveryResourceOnTheClusterOwnedOrNot() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(SUBSCRIPTION_PURGE))), Map.of());

        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_PURGE))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("legacy.inbox"), SUBSCRIPTION_PURGE))
                .isTrue();
        assertThat(resolver.can(otherClusterId, ResourceRef.subscription("legacy.inbox"), SUBSCRIPTION_PURGE))
                .isFalse();
    }

    @Test
    void platformGrantsAndTeamRolesAdd() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(
                Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(SUBSCRIPTION_READ))),
                Map.of(orders, TEAM_OPERATOR));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("legacy.inbox"), SUBSCRIPTION_READ))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("legacy.inbox"), SUBSCRIPTION_PURGE))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_PURGE))
                .isTrue();
    }

    @Test
    void aGlobalPermissionIsNotReachedThroughATeamRole() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(Set.of(), Map.of(orders, Set.of(Permissions.USER_ADMIN, Permissions.CLUSTER_READ)));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), Permissions.USER_ADMIN))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), Permissions.CLUSTER_READ))
                .isFalse();
    }

    @Test
    void aTeamRolePermissionOutsideTheCatalogueGrantsNothing() {
        owns(orders, clusterId, "ANY", "orders.#");
        catalogue.remove(SUBSCRIPTION_PURGE);
        access(Set.of(), Map.of(orders, Set.of(Permissions.WILDCARD)));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_PURGE))
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_READ))
                .isTrue();
    }

    @Test
    void aPrincipalThatCarriesItsOwnGrantsGetsNoTeamAccess() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));
        RahdarPrincipal key = new RahdarPrincipal(
                userId,
                "key",
                Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(SUBSCRIPTION_READ))),
                false,
                "ci");

        assertThat(resolver.can(key, clusterId, ResourceRef.subscription("legacy.q"), SUBSCRIPTION_READ))
                .isTrue();
        assertThat(resolver.can(key, clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_PURGE))
                .isFalse();
    }

    // ---- gating --------------------------------------------------------------------------------------

    @Test
    void canAnywhereOffersWhatSomeTeamOrShareOnTheClusterAllows() {
        owns(orders, clusterId, "SUBSCRIPTION", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));

        assertThat(resolver.canAnywhere(clusterId, SUBSCRIPTION_PURGE)).isTrue();
        assertThat(resolver.canAnywhere(clusterId, MESSAGE_SEND))
                .as("the team owns no address here")
                .isFalse();
        assertThat(resolver.canAnywhere(clusterId, MessagePermissions.MESSAGE_MOVE))
                .as("the team role lacks it")
                .isFalse();
        assertThat(resolver.canAnywhere(otherClusterId, SUBSCRIPTION_PURGE)).isFalse();
    }

    @Test
    void canAnywhereCountsASharedPattern() {
        owns(orders, clusterId, "ANY", "orders.#");
        shares(orders, billing, clusterId, "ANY", "orders.events.#", TEAM_VIEWER);
        access(Set.of(), Map.of(billing, TEAM_OPERATOR));

        assertThat(resolver.canAnywhere(clusterId, SUBSCRIPTION_READ)).isTrue();
        assertThat(resolver.canAnywhere(clusterId, SUBSCRIPTION_PURGE)).isFalse();
    }

    @Test
    void aClusterIsSeenThroughAnyReadOfItsResourcesButGrantsNothingClusterWide() {
        owns(orders, clusterId, "DESTINATION", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_VIEWER));

        assertThat(resolver.canSeeCluster(clusterId)).isTrue();
        assertThat(resolver.can(clusterId, Permissions.CLUSTER_READ)).isFalse();
        assertThat(resolver.canSeeCluster(otherClusterId)).isFalse();

        access(Set.of(new Grant(Grant.ScopeType.CLUSTER, otherClusterId, Set.of(Permissions.CLUSTER_READ))), Map.of());
        assertThat(resolver.canSeeCluster(otherClusterId)).isTrue();
    }

    @Test
    void aUserWithNoAccessSeesNothing() {
        owns(orders, clusterId, "ANY", "orders.#");
        when(access.of(userId)).thenReturn(AccessSnapshot.NONE);

        assertThat(resolver.canSeeCluster(clusterId)).isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_READ))
                .isFalse();
    }

    // ---- filtering a list ---------------------------------------------------------------------------

    @Test
    void aFilterLetsThroughOnlyTheNamesTheCallersTeamsCover() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_VIEWER));

        ResourceFilter filter = resolver.filter(clusterId, ResourceType.SUBSCRIPTION);

        assertThat(filter.everything()).isFalse();
        assertThat(filter.readable("orders.in")).isTrue();
        assertThat(filter.readable("billing.in")).isFalse();
        assertThat(filter.readable(null)).isFalse();
    }

    @Test
    void aFilterOfACallerWhoReadsTheWholeClusterNeedsNoPerNameCheck() {
        access(Set.of(global(SUBSCRIPTION_READ, SUBSCRIPTION_PURGE)), Map.of());

        ResourceFilter filter = resolver.filter(clusterId, ResourceType.SUBSCRIPTION);

        assertThat(filter.everything()).isTrue();
        assertThat(filter.readable("anything")).isTrue();
        assertThat(filter.allowedActions("anything")).containsExactly(SUBSCRIPTION_PURGE, SUBSCRIPTION_READ);
    }

    @Test
    void aRowCarriesTheActionsOfItsKindThatTheCallerHoldsOnIt() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));

        ResourceFilter queues = resolver.filter(clusterId, ResourceType.SUBSCRIPTION);
        ResourceFilter addresses = resolver.filter(clusterId, ResourceType.DESTINATION);

        assertThat(queues.allowedActions("orders.in"))
                .contains(SUBSCRIPTION_READ, SUBSCRIPTION_PURGE, MessagePermissions.MESSAGE_DELETE)
                .doesNotContain(MESSAGE_SEND, Permissions.DESTINATION_READ);
        assertThat(addresses.allowedActions("orders.in"))
                .contains(Permissions.DESTINATION_READ, MESSAGE_SEND)
                .doesNotContain(SUBSCRIPTION_PURGE);
        assertThat(queues.allowedActions("billing.in")).isEmpty();
    }

    @Test
    void aShareAddsItsActionsToTheRowsItCovers() {
        owns(billing, clusterId, "ANY", "billing.#");
        shares(billing, orders, clusterId, "ANY", "billing.in", TEAM_VIEWER);
        access(Set.of(), Map.of(orders, TEAM_VIEWER));

        ResourceFilter queues = resolver.filter(clusterId, ResourceType.SUBSCRIPTION);

        assertThat(queues.readable("billing.in")).isTrue();
        assertThat(queues.readable("billing.payments")).isFalse();
        assertThat(queues.allowedActions("billing.in"))
                .containsExactly(MessagePermissions.MESSAGE_READ, SUBSCRIPTION_READ);
    }

    // ---- where a caller may create ------------------------------------------------------------------

    @Test
    void thePatternsACallerMayCreateUnderAreThoseOfTheirTeamsThatTheirRoleAllowsIt() {
        owns(orders, clusterId, "ANY", "orders.#");
        owns(billing, clusterId, "ANY", "billing.#");
        shares(
                billing,
                orders,
                clusterId,
                "SUBSCRIPTION",
                "billing.shared.#",
                Set.of(SUBSCRIPTION_READ, SUBSCRIPTION_CREATE));
        access(Set.of(), Map.of(orders, Set.of(SUBSCRIPTION_READ, SUBSCRIPTION_CREATE)));

        assertThat(resolver.patternsHolding(clusterId, ResourceType.SUBSCRIPTION, SUBSCRIPTION_CREATE))
                .containsExactly("billing.shared.#", "orders.#");
        assertThat(resolver.patternsHolding(clusterId, ResourceType.DESTINATION, SUBSCRIPTION_CREATE))
                .isEmpty();
        assertThat(resolver.patternsHolding(otherClusterId, ResourceType.SUBSCRIPTION, SUBSCRIPTION_CREATE))
                .isEmpty();
    }

    // ---- patterns --------------------------------------------------------------------------------------

    @Test
    void aPatternIsHeldWhenOneOfTheCallersPatternsCoversEveryNameItCanMatch() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));

        assertThat(resolver.canOnAll(clusterId, ResourceType.SUBSCRIPTION, "orders.#", SUBSCRIPTION_PURGE))
                .isTrue();
        assertThat(resolver.canOnAll(clusterId, ResourceType.SUBSCRIPTION, "orders.*.in", SUBSCRIPTION_PURGE))
                .isTrue();
        assertThat(resolver.canOnAll(clusterId, ResourceType.SUBSCRIPTION, "orders.in", SUBSCRIPTION_PURGE))
                .isTrue();
        assertThat(resolver.canOnAll(clusterId, ResourceType.SUBSCRIPTION, "#", SUBSCRIPTION_PURGE))
                .isFalse();
        assertThat(resolver.canOnAll(clusterId, ResourceType.SUBSCRIPTION, "*.in", SUBSCRIPTION_PURGE))
                .isFalse();
        assertThat(resolver.canOnAll(clusterId, ResourceType.SUBSCRIPTION, "billing.in", SUBSCRIPTION_PURGE))
                .isFalse();
    }

    @Test
    void aPatternNeedsThePermissionInTheRoleOfThePatternThatCoversIt() {
        owns(orders, clusterId, "ANY", "orders.#");
        shares(orders, billing, clusterId, "ANY", "orders.events.#", TEAM_VIEWER);
        access(Set.of(), Map.of(billing, TEAM_OPERATOR));

        assertThat(resolver.canOnAll(clusterId, ResourceType.SUBSCRIPTION, "orders.events.*", SUBSCRIPTION_READ))
                .isTrue();
        assertThat(resolver.canOnAll(clusterId, ResourceType.SUBSCRIPTION, "orders.events.*", SUBSCRIPTION_PURGE))
                .as("the share's role does not purge")
                .isFalse();
    }

    @Test
    void aWildcardInsideAWordIsHeldOnlyThroughAGrantThatReachesTheCluster() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_OPERATOR));

        assertThat(resolver.canOnAll(clusterId, ResourceType.SUBSCRIPTION, "orders*", SUBSCRIPTION_READ))
                .isFalse();

        access(Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(SUBSCRIPTION_READ))), Map.of());

        assertThat(resolver.canOnAll(clusterId, ResourceType.SUBSCRIPTION, "orders*", SUBSCRIPTION_READ))
                .isTrue();
    }

    // ---- API keys -----------------------------------------------------------------------------------

    /** The signed-in context becomes an API key of the user whose access was last set with {@link #access}. */
    private void keyOf(TokenGrant... grants) {
        TokenPrincipal key = new TokenPrincipal(userId, "u", Set.of(grants), UUID.randomUUID(), "ci", Set.of());
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(key, null, key.getAuthorities()));
    }

    @Test
    void aKeyCannotDoWhatItsGrantsDoNotSay() {
        access(Set.of(global(Permissions.WILDCARD)), Map.of());
        keyOf(TokenGrant.of(Grant.ScopeType.CLUSTER, clusterId, SUBSCRIPTION_READ));

        assertThat(resolver.can(clusterId, SUBSCRIPTION_READ)).isTrue();
        assertThat(resolver.can(clusterId, SUBSCRIPTION_PURGE)).isFalse();
        assertThat(resolver.can(otherClusterId, SUBSCRIPTION_READ)).isFalse();
    }

    @Test
    void aKeyCannotDoWhatItsOwnerNoLongerHolds() {
        access(Set.of(global(SUBSCRIPTION_READ)), Map.of());
        keyOf(TokenGrant.of(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, SUBSCRIPTION_READ));
        assertThat(resolver.can(clusterId, SUBSCRIPTION_READ)).isTrue();

        when(access.of(userId)).thenReturn(new AccessSnapshot(Set.of(), Map.of()));

        assertThat(resolver.can(clusterId, SUBSCRIPTION_READ)).isFalse();
    }

    @Test
    void aWildcardOfAKeyAllowsWhatTheOwnerHoldsThatItMatches() {
        access(Set.of(global(MessagePermissions.MESSAGE_READ)), Map.of());
        keyOf(TokenGrant.of(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, "message:*"));

        assertThat(resolver.can(clusterId, MessagePermissions.MESSAGE_READ)).isTrue();
        assertThat(resolver.can(clusterId, MESSAGE_SEND)).isFalse();
        assertThat(resolver.grantsOf(currentKey()))
                .containsExactly(
                        new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of(MessagePermissions.MESSAGE_READ)));
    }

    @Test
    void aWildcardOfTheOwnerDoesNotWidenAKeyThatNamesAPermission() {
        access(Set.of(global("message:*")), Map.of());
        keyOf(TokenGrant.of(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, MessagePermissions.MESSAGE_READ));

        assertThat(resolver.can(clusterId, MessagePermissions.MESSAGE_READ)).isTrue();
        assertThat(resolver.can(clusterId, MESSAGE_SEND)).isFalse();
    }

    @Test
    void aKeyReachesWhatItsOwnersTeamsOwnAndNothingMore() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_VIEWER));
        keyOf(TokenGrant.of(Grant.ScopeType.CLUSTER, clusterId, SUBSCRIPTION_READ));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_READ))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("billing.in"), SUBSCRIPTION_READ))
                .isFalse();
        assertThat(resolver.can(clusterId, SUBSCRIPTION_READ))
                .as("a team grants nothing on the cluster as a whole")
                .isFalse();
    }

    @Test
    void aKeyLimitedToAPatternReachesOnlyTheNamesItMatches() {
        access(Set.of(global(Permissions.WILDCARD)), Map.of());
        keyOf(new TokenGrant(
                Grant.ScopeType.CLUSTER,
                clusterId,
                MessagePermissions.MESSAGE_READ,
                ResourceType.SUBSCRIPTION,
                ResourcePattern.parse("orders.#")));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), MessagePermissions.MESSAGE_READ))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders2.in"), MessagePermissions.MESSAGE_READ))
                .as("the owner reads it, the key is not for it")
                .isFalse();
        assertThat(resolver.can(clusterId, ResourceRef.destination("orders.in"), MessagePermissions.MESSAGE_READ))
                .as("a pattern of queues is not one of addresses")
                .isFalse();
        assertThat(resolver.can(clusterId, MessagePermissions.MESSAGE_READ))
                .as("limited to names, it is not held on the cluster as a whole")
                .isFalse();
        assertThat(resolver.canAnywhere(clusterId, MessagePermissions.MESSAGE_READ))
                .isTrue();
    }

    @Test
    void aKeyLimitedToAPatternStillNeedsItsOwnerToHoldThePermissionOnTheName() {
        owns(orders, clusterId, "ANY", "orders.#");
        access(Set.of(), Map.of(orders, TEAM_VIEWER));
        keyOf(new TokenGrant(
                Grant.ScopeType.CLUSTER,
                clusterId,
                SUBSCRIPTION_READ,
                ResourceType.SUBSCRIPTION,
                ResourcePattern.parse("#")));

        assertThat(resolver.can(clusterId, ResourceRef.subscription("orders.in"), SUBSCRIPTION_READ))
                .isTrue();
        assertThat(resolver.can(clusterId, ResourceRef.subscription("billing.in"), SUBSCRIPTION_READ))
                .isFalse();
    }

    @Test
    void aKeyAdministersNoTeamAndHoldsNoTeamOfItsOwn() {
        access(Set.of(), Map.of(orders, TEAM_VIEWER));
        keyOf(TokenGrant.of(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, "*"));

        assertThat(resolver.can(Permissions.USER_ADMIN)).isFalse();
        assertThat(currentKey().pinned()).isTrue();
    }

    private TokenPrincipal currentKey() {
        return (TokenPrincipal)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
