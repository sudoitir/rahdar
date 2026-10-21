package io.github.sudoitir.rahdar.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.kernel.security.ScopedGrants;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The team tables of changeset kernel-security 0010 hold what the team-access spec needs and nothing else. */
class TeamSchemaIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RoleRepository roles;

    @Autowired
    AppUserRepository users;

    @Autowired
    ScopedGrants scopedGrants;

    private UUID team(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO team (id, name) VALUES (?, ?)", id, name + "-" + id);
        return id;
    }

    private void pattern(UUID team, UUID cluster, String kind, String pattern) {
        jdbc.update(
                "INSERT INTO team_pattern (team_id, cluster_id, kind, pattern) VALUES (?, ?, ?, ?)",
                team,
                cluster,
                kind,
                pattern);
    }

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    @Test
    void teamNamesAreUniqueIgnoringCase() {
        String name = "Orders-" + UUID.randomUUID();
        jdbc.update("INSERT INTO team (name) VALUES (?)", name);

        var value = name.toUpperCase();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO team (name) VALUES (?)", value))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPatternKindIsQueueAddressOrBoth() {
        UUID team = team("kinds");
        UUID cluster = UUID.randomUUID();
        pattern(team, cluster, "SUBSCRIPTION", "a.#");
        pattern(team, cluster, "DESTINATION", "a.#");
        pattern(team, cluster, "ANY", "b.#");

        assertThatThrownBy(() -> pattern(team, cluster, "TOPIC", "c.#"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> pattern(team, cluster, "SUBSCRIPTION", "a.#"))
                .as("the same pattern twice")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aMemberIsOneUserOrOneProviderGroupWithOneRolePerTeam() {
        UUID team = team("members");
        UUID role = roles.findByName("TEAM_VIEWER").orElseThrow().getId();
        UUID user = users.save(AppUserEntity.local("member-" + UUID.randomUUID(), null, "{noop}x"))
                .getId();
        String addUser = "INSERT INTO team_member (principal_type, user_id, team_id, role_id) VALUES ('USER', ?, ?, ?)";
        String addGroup = "INSERT INTO team_member (principal_type, provider_id, group_name, team_id, role_id)"
                + " VALUES ('GROUP', ?, ?, ?, ?)";

        jdbc.update(addUser, user, team, role);
        jdbc.update(addGroup, "ldap", "orders", team, role);

        assertThatThrownBy(() -> jdbc.update(addUser, user, team, role))
                .as("a second role for the same user")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(addGroup, "ldap", "orders", team, role))
                .as("a second role for the same group")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO team_member (principal_type, team_id, role_id) VALUES ('USER', ?, ?)", team, role))
                .as("a user member without a user")
                .isInstanceOf(DataIntegrityViolationException.class);
        var value2 = "INSERT INTO team_member (principal_type, user_id, group_name, provider_id, team_id, role_id)"
                + " VALUES ('GROUP', ?, 'g', 'ldap', ?, ?)";
        assertThatThrownBy(() -> jdbc.update(value2, user, team, role))
                .as("a group member that is also a user")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aTeamShareIsBetweenTwoTeams() {
        UUID owner = team("owner");
        UUID role = roles.findByName("TEAM_VIEWER").orElseThrow().getId();
        String share = "INSERT INTO team_share (owner_team_id, target_team_id, cluster_id, kind, pattern, role_id)"
                + " VALUES (?, ?, ?, 'ANY', 'a.#', ?)";

        jdbc.update(share, owner, team("target"), UUID.randomUUID(), role);

        var id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(share, owner, owner, id, role))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingATeamRemovesItsPatternsMembersAndSharesBothWays() {
        UUID team = team("doomed");
        UUID other = team("other");
        UUID cluster = UUID.randomUUID();
        UUID role = roles.findByName("TEAM_OPERATOR").orElseThrow().getId();
        pattern(team, cluster, "ANY", "orders.#");
        jdbc.update(
                "INSERT INTO team_member (principal_type, provider_id, group_name, team_id, role_id)"
                        + " VALUES ('GROUP', 'ldap', 'orders', ?, ?)",
                team,
                role);
        String share = "INSERT INTO team_share (owner_team_id, target_team_id, cluster_id, kind, pattern, role_id)"
                + " VALUES (?, ?, ?, 'ANY', 'orders.in', ?)";
        jdbc.update(share, team, other, cluster, role);
        jdbc.update(share, other, team, cluster, role);

        jdbc.update("DELETE FROM team WHERE id = ?", team);

        assertThat(count("team_pattern", "team_id = ?", team)).isZero();
        assertThat(count("team_member", "team_id = ?", team)).isZero();
        assertThat(count("team_share", "owner_team_id = ? OR target_team_id = ?", team, team))
                .isZero();
    }

    @Test
    void deletingAUserRemovesTheirMemberships() {
        UUID team = team("with-user");
        UUID role = roles.findByName("TEAM_VIEWER").orElseThrow().getId();
        AppUserEntity user = users.save(AppUserEntity.local("leaver-" + UUID.randomUUID(), null, "{noop}x"));
        jdbc.update(
                "INSERT INTO team_member (principal_type, user_id, team_id, role_id) VALUES ('USER', ?, ?, ?)",
                user.getId(),
                team,
                role);

        users.delete(user);
        users.flush();

        assertThat(count("team_member", "team_id = ?", team)).isZero();
    }

    @Test
    void revokingAClusterDropsItsTeamPatternsAndSharesOnly() {
        UUID team = team("cluster-gone");
        UUID other = team("neighbour");
        UUID gone = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        UUID role = roles.findByName("TEAM_VIEWER").orElseThrow().getId();
        pattern(team, gone, "ANY", "a.#");
        pattern(team, kept, "ANY", "a.#");
        String share = "INSERT INTO team_share (owner_team_id, target_team_id, cluster_id, kind, pattern, role_id)"
                + " VALUES (?, ?, ?, 'ANY', 'a.b', ?)";
        jdbc.update(share, team, other, gone, role);
        jdbc.update(share, team, other, kept, role);

        scopedGrants.revoke("CLUSTER", gone);

        assertThat(count("team_pattern", "cluster_id = ?", gone)).isZero();
        assertThat(count("team_share", "cluster_id = ?", gone)).isZero();
        assertThat(count("team_pattern", "cluster_id = ?", kept)).isOne();
        assertThat(count("team_share", "cluster_id = ?", kept)).isOne();
    }

    @Test
    void revokingAnEnvironmentLeavesTeamRowsAlone() {
        UUID team = team("env");
        UUID id = UUID.randomUUID();
        pattern(team, id, "ANY", "a.#");

        scopedGrants.revoke("ENVIRONMENT", id);

        assertThat(count("team_pattern", "cluster_id = ?", id)).isOne();
    }
}
