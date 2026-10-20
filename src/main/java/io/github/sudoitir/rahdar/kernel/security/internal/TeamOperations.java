package io.github.sudoitir.rahdar.kernel.security.internal;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.security.AccessOperation;
import io.github.sudoitir.rahdar.kernel.security.PatternKind;
import io.github.sudoitir.rahdar.kernel.security.ScopeHierarchy;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamMemberEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamMemberRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamPatternEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamPatternRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamRepository;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PrincipalType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The gated operations of {@link TeamService}. */
@Configuration(proxyBeanMethods = false)
class TeamOperations {

    private static final String LABEL_PATTERN = "Pattern";
    private static final String UNIT_MEMBER = "member";

    record CreateTeam(String name) {}

    record RenameTeam(UUID teamId, String name) {}

    record DeleteTeam(UUID teamId) {}

    record AddPattern(UUID teamId, UUID clusterId, PatternKind kind, String pattern) {}

    record RemovePattern(UUID teamId, UUID patternId) {}

    record AddMember(
            UUID teamId, PrincipalType principalType, UUID userId, String providerId, String groupName, UUID roleId) {}

    record ChangeMemberRole(UUID teamId, UUID memberId, UUID roleId) {}

    record RemoveMember(UUID teamId, UUID memberId) {}

    private final TeamRepository teams;
    private final TeamPatternRepository patterns;
    private final TeamMemberRepository members;
    private final RoleRepository roles;
    private final AppUserRepository users;
    private final ApproverAccess approvers;
    private final ScopeHierarchy clusters;

    TeamOperations(
            TeamRepository teams,
            TeamPatternRepository patterns,
            TeamMemberRepository members,
            RoleRepository roles,
            AppUserRepository users,
            ApproverAccess approvers,
            ScopeHierarchy clusters) {
        this.teams = teams;
        this.patterns = patterns;
        this.members = members;
        this.roles = roles;
        this.users = users;
        this.approvers = approvers;
        this.clusters = clusters;
    }

    @Bean
    GatedOperation<CreateTeam> teamCreateOperation(TeamService service) {
        return new AccessOperation<>("team.create", CreateTeam.class) {
            @Override
            public String summary(CreateTeam p) {
                return "Create team " + p.name();
            }

            @Override
            public List<DisplayRow> display(CreateTeam p) {
                return List.of(DisplayRow.of("Team", p.name()));
            }

            @Override
            public Effect estimate(CreateTeam p) {
                return new Effect(1, "team", etag(p.name().toLowerCase()), null);
            }

            @Override
            public void replay(CreateTeam p) {
                service.create(p.name());
            }
        };
    }

    @Bean
    GatedOperation<RenameTeam> teamRenameOperation(TeamService service) {
        return new AccessOperation<>("team.rename", RenameTeam.class) {
            @Override
            public String summary(RenameTeam p) {
                return "Rename team " + team(p.teamId()).getName() + " to " + p.name();
            }

            @Override
            public List<DisplayRow> display(RenameTeam p) {
                return List.of(new DisplayRow("Team", team(p.teamId()).getName(), p.name()));
            }

            @Override
            public Effect estimate(RenameTeam p) {
                return new Effect(1, "team", etag(p.teamId(), team(p.teamId()).getName()), null);
            }

            @Override
            public void replay(RenameTeam p) {
                service.rename(p.teamId(), p.name());
            }
        };
    }

    @Bean
    GatedOperation<DeleteTeam> teamDeleteOperation(TeamService service) {
        return new AccessOperation<>("team.delete", DeleteTeam.class) {
            @Override
            public Set<Trait> traits(DeleteTeam p) {
                return access(approvers.armed());
            }

            @Override
            public String summary(DeleteTeam p) {
                return "Delete team " + team(p.teamId()).getName();
            }

            @Override
            public List<DisplayRow> display(DeleteTeam p) {
                return List.of(
                        DisplayRow.of("Team", team(p.teamId()).getName()),
                        DisplayRow.of("Members", String.valueOf(members.countByTeamId(p.teamId()))),
                        DisplayRow.of(
                                "Patterns",
                                String.valueOf(patterns.findByTeamId(p.teamId()).size())));
            }

            @Override
            public Effect estimate(DeleteTeam p) {
                long count = members.countByTeamId(p.teamId());
                return new Effect(
                        count,
                        "members",
                        etag(
                                p.teamId(),
                                team(p.teamId()).getName(),
                                count,
                                patterns.findByTeamId(p.teamId()).size()),
                        "Removes the team's patterns, memberships and shares.");
            }

            @Override
            public void replay(DeleteTeam p) {
                service.delete(p.teamId());
            }
        };
    }

    @Bean
    GatedOperation<AddPattern> teamPatternAddOperation(TeamService service) {
        return new AccessOperation<>("team.pattern.add", AddPattern.class) {
            @Override
            public Set<Trait> traits(AddPattern p) {
                return access(approvers.armed());
            }

            @Override
            public String summary(AddPattern p) {
                return "Give team " + team(p.teamId()).getName() + " the "
                        + p.kind().name().toLowerCase() + " pattern " + p.pattern();
            }

            @Override
            public List<DisplayRow> display(AddPattern p) {
                return List.of(
                        DisplayRow.of("Team", team(p.teamId()).getName()),
                        DisplayRow.of("Kind", p.kind().name()),
                        DisplayRow.of(LABEL_PATTERN, p.pattern()),
                        DisplayRow.of("Cluster", clusterLabel(p.clusterId())));
            }

            @Override
            public Effect estimate(AddPattern p) {
                return new Effect(1, "pattern", etag(p.teamId(), p.clusterId(), p.kind(), p.pattern()), null);
            }

            @Override
            public void replay(AddPattern p) {
                service.addPattern(p.teamId(), new PatternRequest(p.clusterId(), p.kind(), p.pattern()));
            }
        };
    }

    @Bean
    GatedOperation<RemovePattern> teamPatternRemoveOperation(TeamService service) {
        return new AccessOperation<>("team.pattern.remove", RemovePattern.class) {
            @Override
            public Set<Trait> traits(RemovePattern p) {
                return access(approvers.armed());
            }

            @Override
            public String summary(RemovePattern p) {
                return "Take the pattern " + pattern(p).getPattern() + " from team "
                        + team(p.teamId()).getName();
            }

            @Override
            public List<DisplayRow> display(RemovePattern p) {
                TeamPatternEntity pattern = pattern(p);
                return List.of(
                        DisplayRow.of("Team", team(p.teamId()).getName()),
                        DisplayRow.of("Kind", pattern.getKind()),
                        new DisplayRow(LABEL_PATTERN, pattern.getPattern(), null),
                        DisplayRow.of("Cluster", clusterLabel(pattern.getClusterId())));
            }

            @Override
            public Effect estimate(RemovePattern p) {
                return new Effect(
                        1, "pattern", etag(p.teamId(), p.patternId(), pattern(p).getPattern()), null);
            }

            @Override
            public void replay(RemovePattern p) {
                service.removePattern(p.teamId(), p.patternId());
            }
        };
    }

    @Bean
    GatedOperation<AddMember> teamMemberAddOperation(TeamService service) {
        return new AccessOperation<>("team.member.add", AddMember.class) {
            @Override
            public Set<Trait> traits(AddMember p) {
                return access(approvers.armed());
            }

            @Override
            public String summary(AddMember p) {
                return "Add " + principal(p) + " to team " + team(p.teamId()).getName() + " as " + role(p.roleId());
            }

            @Override
            public List<DisplayRow> display(AddMember p) {
                return List.of(
                        DisplayRow.of("Team", team(p.teamId()).getName()),
                        DisplayRow.of("Member", principal(p)),
                        DisplayRow.of("Role", role(p.roleId())));
            }

            @Override
            public Effect estimate(AddMember p) {
                return new Effect(
                        1,
                        UNIT_MEMBER,
                        etag(p.teamId(), p.principalType(), p.userId(), p.providerId(), p.groupName(), p.roleId()),
                        null);
            }

            @Override
            public void replay(AddMember p) {
                service.addMember(
                        p.teamId(),
                        new MemberRequest(p.principalType(), p.userId(), p.providerId(), p.groupName(), p.roleId()));
            }
        };
    }

    @Bean
    GatedOperation<ChangeMemberRole> teamMemberRoleOperation(TeamService service) {
        return new AccessOperation<>("team.member.role", ChangeMemberRole.class) {
            @Override
            public Set<Trait> traits(ChangeMemberRole p) {
                return access(approvers.armed());
            }

            @Override
            public String summary(ChangeMemberRole p) {
                return "Make a member of team " + team(p.teamId()).getName() + " " + role(p.roleId());
            }

            @Override
            public List<DisplayRow> display(ChangeMemberRole p) {
                return List.of(
                        DisplayRow.of("Team", team(p.teamId()).getName()),
                        new DisplayRow(
                                "Role", role(member(p.teamId(), p.memberId()).getRoleId()), role(p.roleId())));
            }

            @Override
            public Effect estimate(ChangeMemberRole p) {
                return new Effect(
                        1,
                        UNIT_MEMBER,
                        etag(
                                p.teamId(),
                                p.memberId(),
                                member(p.teamId(), p.memberId()).getRoleId()),
                        null);
            }

            @Override
            public void replay(ChangeMemberRole p) {
                service.changeMemberRole(p.teamId(), p.memberId(), p.roleId());
            }
        };
    }

    @Bean
    GatedOperation<RemoveMember> teamMemberRemoveOperation(TeamService service) {
        return new AccessOperation<>("team.member.remove", RemoveMember.class) {
            @Override
            public Set<Trait> traits(RemoveMember p) {
                return access(approvers.armed());
            }

            @Override
            public String summary(RemoveMember p) {
                return "Remove a member from team " + team(p.teamId()).getName();
            }

            @Override
            public List<DisplayRow> display(RemoveMember p) {
                return List.of(
                        DisplayRow.of("Team", team(p.teamId()).getName()),
                        new DisplayRow(
                                "Role", role(member(p.teamId(), p.memberId()).getRoleId()), null));
            }

            @Override
            public Effect estimate(RemoveMember p) {
                return new Effect(
                        1,
                        UNIT_MEMBER,
                        etag(
                                p.teamId(),
                                p.memberId(),
                                member(p.teamId(), p.memberId()).getRoleId()),
                        null);
            }

            @Override
            public void replay(RemoveMember p) {
                service.removeMember(p.teamId(), p.memberId());
            }
        };
    }

    private TeamEntity team(UUID teamId) {
        return teams.findById(teamId).orElseThrow(() -> new NotFoundException("team", teamId));
    }

    private TeamMemberEntity member(UUID teamId, UUID memberId) {
        return members.findByIdAndTeamId(memberId, teamId)
                .orElseThrow(() -> new NotFoundException("team member", memberId));
    }

    private TeamPatternEntity pattern(RemovePattern p) {
        return patterns.findByIdAndTeamId(p.patternId(), p.teamId())
                .orElseThrow(() -> new NotFoundException("team pattern", p.patternId()));
    }

    private String role(UUID roleId) {
        return roles.findById(roleId)
                .orElseThrow(() -> new NotFoundException("role", roleId))
                .getName();
    }

    private String principal(AddMember p) {
        return p.principalType() == PrincipalType.USER
                ? "user "
                        + users.findById(p.userId())
                                .map(AppUserEntity::getUsername)
                                .orElse(String.valueOf(p.userId()))
                : "group " + p.groupName() + " of " + p.providerId();
    }

    /** The cluster's name for an approver to read, or its id when it no longer exists. */
    private String clusterLabel(UUID clusterId) {
        String name = clusters.clusterName(clusterId);
        return name != null ? name : clusterId.toString();
    }
}
