package io.github.sudoitir.rahdar.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.app.RahdarFeatures;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionDef;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionScope;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The built-in roles cover the core catalogue (authorization spec). A new core permission fails
 * {@link #everyCorePermissionIsInABuiltInRoleOrDeliberatelyExcluded} until a changeset adds it to
 * the roles that should hold it, or it is listed in {@link #DELIBERATELY_EXCLUDED} with the reason.
 * Administrator's wildcard counts for nothing here: it holds everything by definition.
 */
class BuiltInRolesCoverageTest extends PostgresIntegrationTest {

    /** Core permissions only Administrator (or a custom role) holds, each with why no other built-in role does. */
    private static final Map<String, String> DELIBERATELY_EXCLUDED = Map.ofEntries(
            Map.entry("user:admin", "manages users, roles and grants"),
            Map.entry("token:admin", "sees and revokes every user's tokens"),
            Map.entry("diagnostics:bundle", "support bundles carry configuration"),
            Map.entry("audit:export", "takes the whole audit trail out of Rahdar as a file"),
            Map.entry("data:write", "changes retention and quotas"),
            Map.entry("environment:write", "changes how clusters are grouped and granted"),
            Map.entry("governance:write", "changes masking rules and findings"),
            Map.entry("config:write", "edits declared broker configuration"),
            Map.entry("config:apply", "applies declared configuration to brokers"),
            Map.entry("settings:write", "changes Rahdar settings and rotates stored broker credentials"),
            Map.entry("message:clear", "shows sensitive message values unmasked"));

    private static final Set<PermissionDef> CORE = RahdarFeatures.descriptors().stream()
            .flatMap(d -> d.permissions().stream())
            .collect(Collectors.toUnmodifiableSet());

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    /** Every built-in role except Administrator, by name. */
    private Map<String, Set<String>> builtIns() {
        Map<String, Set<String>> held = new HashMap<>();
        for (RoleEntity role : roles.findAllByOrderByName()) {
            if (role.isBuiltin() && !role.getName().equals("ADMIN")) {
                held.put(
                        role.getName(),
                        rolePermissions.findByIdRoleId(role.getId()).stream()
                                .map(RolePermissionEntity::getAction)
                                .collect(Collectors.toSet()));
            }
        }
        return held;
    }

    @Test
    void everyCorePermissionIsInABuiltInRoleOrDeliberatelyExcluded() {
        Set<String> held = builtIns().values().stream().flatMap(Set::stream).collect(Collectors.toSet());

        Set<String> undecided = CORE.stream()
                .map(PermissionDef::action)
                .filter(action -> !held.contains(action) && !DELIBERATELY_EXCLUDED.containsKey(action))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(undecided)
                .as("add each to the built-in roles that should hold it in a new kernel-security changeset,"
                        + " or to DELIBERATELY_EXCLUDED with the reason")
                .isEmpty();
    }

    @Test
    void theExclusionListNamesOnlyCorePermissionsNoBuiltInRoleHolds() {
        Set<String> held = builtIns().values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        Set<String> core = CORE.stream().map(PermissionDef::action).collect(Collectors.toSet());

        assertThat(DELIBERATELY_EXCLUDED.keySet())
                .as("an excluded permission that is not a core permission, or is now held, is stale")
                .allMatch(core::contains)
                .noneMatch(held::contains);
    }

    @Test
    void builtInRolesHoldOnlyCorePermissionsAndEveryOneTheyRequire() {
        Map<String, PermissionDef> core = CORE.stream().collect(Collectors.toMap(PermissionDef::action, def -> def));
        Set<String> problems = new HashSet<>();
        builtIns().forEach((role, actions) -> {
            for (String action : actions) {
                PermissionDef def = core.get(action);
                if (def == null) {
                    problems.add(role + " holds " + action + ", which is not a core permission");
                    continue;
                }
                def.requires().stream()
                        .filter(required -> !actions.contains(required))
                        .forEach(required -> problems.add(role + " holds " + action + " without " + required));
            }
        });

        assertThat(problems).isEmpty();
    }

    @Test
    void teamRolesHoldOnlyResourcePermissionsAndTeamAdmin() {
        Map<String, PermissionDef> core = CORE.stream().collect(Collectors.toMap(PermissionDef::action, def -> def));
        Map<String, Set<String>> builtIns = builtIns();

        assertThat(roles.findAllByOrderByName())
                .filteredOn(role -> role.isBuiltin() && role.isTeamAssignable())
                .extracting(RoleEntity::getName)
                .containsExactlyInAnyOrder("TEAM_VIEWER", "TEAM_OPERATOR", "TEAM_ADMIN");
        for (String role : Set.of("TEAM_VIEWER", "TEAM_OPERATOR", "TEAM_ADMIN")) {
            assertThat(builtIns.get(role))
                    .as(role)
                    .allMatch(action -> action.equals(Permissions.TEAM_ADMIN)
                            || core.get(action).scope() == PermissionScope.RESOURCE);
        }
        assertThat(builtIns.get("TEAM_VIEWER")).doesNotContain(Permissions.TEAM_ADMIN);
        assertThat(builtIns.get("TEAM_OPERATOR")).doesNotContain(Permissions.TEAM_ADMIN);
        assertThat(builtIns.get("TEAM_ADMIN")).contains(Permissions.TEAM_ADMIN);
    }

    @Test
    void roleLaddersOnlyGrow() {
        Map<String, Set<String>> builtIns = builtIns();

        assertThat(builtIns.get("TEAM_OPERATOR")).containsAll(builtIns.get("TEAM_VIEWER"));
        assertThat(builtIns.get("TEAM_ADMIN")).containsAll(builtIns.get("TEAM_OPERATOR"));
        assertThat(builtIns.get("OPERATOR")).containsAll(builtIns.get("VIEWER"));
    }

    @Test
    void destroyingAnAddressIsHeldByTheRolesThatDestroyQueues() {
        Map<String, Set<String>> builtIns = builtIns();

        for (String role : Set.of("OPERATOR", "TEAM_OPERATOR", "TEAM_ADMIN")) {
            assertThat(builtIns.get(role)).as(role).contains("subscription:delete", "destination:delete");
        }
        for (String role : Set.of("VIEWER", "TEAM_VIEWER")) {
            assertThat(builtIns.get(role)).as(role).doesNotContain("destination:delete");
        }
    }
}
