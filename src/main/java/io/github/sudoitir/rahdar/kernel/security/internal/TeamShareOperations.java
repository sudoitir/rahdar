package io.github.sudoitir.rahdar.kernel.security.internal;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.security.AccessOperation;
import io.github.sudoitir.rahdar.kernel.security.PatternKind;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamShareEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamShareRepository;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.ShareRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The gated operations of {@link TeamService} that share a team's patterns with another team. */
@Configuration(proxyBeanMethods = false)
class TeamShareOperations {

    record AddShare(
            UUID ownerTeamId, UUID targetTeamId, UUID clusterId, PatternKind kind, String pattern, UUID roleId) {}

    record RemoveShare(UUID ownerTeamId, UUID shareId) {}

    private static final String LABEL_PATTERN = "Pattern";
    private static final String LABEL_ROLE = "Role";

    private final TeamRepository teams;
    private final TeamShareRepository shares;
    private final RoleRepository roles;
    private final ApproverAccess approvers;

    TeamShareOperations(
            TeamRepository teams, TeamShareRepository shares, RoleRepository roles, ApproverAccess approvers) {
        this.teams = teams;
        this.shares = shares;
        this.roles = roles;
        this.approvers = approvers;
    }

    @Bean
    GatedOperation<AddShare> teamShareAddOperation(TeamService service) {
        return new AccessOperation<>("team.share.add", AddShare.class) {
            @Override
            public Set<Trait> traits(AddShare p) {
                return access(approvers.armed());
            }

            @Override
            public String summary(AddShare p) {
                return "Share " + p.pattern() + " of team "
                        + team(p.ownerTeamId()).getName() + " with team "
                        + team(p.targetTeamId()).getName();
            }

            @Override
            public List<DisplayRow> display(AddShare p) {
                return List.of(
                        DisplayRow.of("Owner team", team(p.ownerTeamId()).getName()),
                        DisplayRow.of("Shared with", team(p.targetTeamId()).getName()),
                        DisplayRow.of("Kind", p.kind().name()),
                        DisplayRow.of(LABEL_PATTERN, p.pattern()),
                        DisplayRow.of(LABEL_ROLE, role(p.roleId())));
            }

            @Override
            public Effect estimate(AddShare p) {
                return new Effect(
                        1,
                        "share",
                        etag(p.ownerTeamId(), p.targetTeamId(), p.clusterId(), p.kind(), p.pattern(), p.roleId()),
                        null);
            }

            @Override
            public void replay(AddShare p) {
                service.addShare(
                        p.ownerTeamId(),
                        new ShareRequest(p.targetTeamId(), p.clusterId(), p.kind(), p.pattern(), p.roleId()));
            }
        };
    }

    @Bean
    GatedOperation<RemoveShare> teamShareRemoveOperation(TeamService service) {
        return new AccessOperation<>("team.share.remove", RemoveShare.class) {
            @Override
            public Set<Trait> traits(RemoveShare p) {
                return access(approvers.armed());
            }

            @Override
            public String summary(RemoveShare p) {
                return "Stop sharing " + share(p).getPattern() + " of team "
                        + team(p.ownerTeamId()).getName();
            }

            @Override
            public List<DisplayRow> display(RemoveShare p) {
                TeamShareEntity share = share(p);
                return List.of(
                        DisplayRow.of("Owner team", team(p.ownerTeamId()).getName()),
                        DisplayRow.of(
                                "Shared with", team(share.getTargetTeamId()).getName()),
                        new DisplayRow(LABEL_PATTERN, share.getPattern(), null),
                        DisplayRow.of(LABEL_ROLE, role(share.getRoleId())));
            }

            @Override
            public Effect estimate(RemoveShare p) {
                return new Effect(1, "share", etag(p.ownerTeamId(), p.shareId(), share(p).getPattern()), null);
            }

            @Override
            public void replay(RemoveShare p) {
                service.removeShare(p.ownerTeamId(), p.shareId());
            }
        };
    }

    private TeamShareEntity share(RemoveShare p) {
        return shares.findByIdAndOwnerTeamId(p.shareId(), p.ownerTeamId())
                .orElseThrow(() -> new NotFoundException("team share", p.shareId()));
    }

    private TeamEntity team(UUID teamId) {
        return teams.findById(teamId).orElseThrow(() -> new NotFoundException("team", teamId));
    }

    private String role(UUID roleId) {
        return roles.findById(roleId)
                .orElseThrow(() -> new NotFoundException("role", roleId))
                .getName();
    }
}
