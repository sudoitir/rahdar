package io.github.sudoitir.rahdar.support;

import io.github.sudoitir.rahdar.kernel.security.PatternKind;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.internal.TeamService;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.ShareRequest;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/**
 * Teams and their users for tests of resource-scoped access: a team owning patterns on a cluster, and a user
 * whose only access is a role in that team. Creating them needs an administrator on the calling thread
 * ({@link AdminAuthenticationExtension}); signing a user in is the caller's, with
 * {@link #session(UUID)}.
 */
public final class TeamAccessFixture {

    private final TeamService teams;
    private final RoleRepository roles;
    private final AppUserRepository users;

    public TeamAccessFixture(TeamService teams, RoleRepository roles, AppUserRepository users) {
        this.teams = teams;
        this.roles = roles;
        this.users = users;
    }

    /** A team owning each pattern, as queues and addresses, on the cluster. */
    public UUID team(String name, UUID clusterId, String... patterns) {
        UUID team = teams.create(name + "-" + UUID.randomUUID()).id();
        for (String pattern : patterns) {
            teams.addPattern(team, new PatternRequest(clusterId, PatternKind.ANY, pattern));
        }
        return team;
    }

    /** A user who is a member of the team with a built-in team role such as {@code TEAM_OPERATOR}, and nothing else. */
    public UUID member(UUID team, String role) {
        String name = "member-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(name, null, "{noop}x");
        user.setMustChangePassword(false);
        UUID userId = users.save(user).getId();
        teams.addMember(
                team,
                new MemberRequest(
                        PrincipalType.USER,
                        userId,
                        null,
                        null,
                        roles.findByName(role).orElseThrow().getId()));
        return userId;
    }

    /** The team shares the pattern, as queues and addresses, with another team in a built-in team role. */
    public void share(UUID owner, UUID target, UUID clusterId, String pattern, String role) {
        teams.addShare(
                owner,
                new ShareRequest(
                        target,
                        clusterId,
                        PatternKind.ANY,
                        pattern,
                        roles.findByName(role).orElseThrow().getId()));
    }

    /** What a session of the user authenticates as. */
    public UsernamePasswordAuthenticationToken session(UUID userId) {
        RahdarPrincipal principal = RahdarPrincipal.live(userId, "member", false);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
