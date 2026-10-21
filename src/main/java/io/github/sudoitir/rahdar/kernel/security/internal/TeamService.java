package io.github.sudoitir.rahdar.kernel.security.internal;

import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.AccessChanges;
import io.github.sudoitir.rahdar.kernel.security.AccessLoader;
import io.github.sudoitir.rahdar.kernel.security.AccessSnapshot;
import io.github.sudoitir.rahdar.kernel.security.AdministrationAudit;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.PatternKind;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ResourceNames;
import io.github.sudoitir.rahdar.kernel.security.ResourcePattern;
import io.github.sudoitir.rahdar.kernel.security.ScopeHierarchy;
import io.github.sudoitir.rahdar.kernel.security.TeamIndex;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamMemberEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamMemberRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamPatternEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamPatternRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamShareEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamShareRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserGroupRepository;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.MemberRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.MemberView;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PatternConflict;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PatternPreview;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PatternRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PatternView;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.PrincipalType;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.ShareRequest;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.ShareView;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.TeamRoleLookup;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.TeamSummary;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.TeamView;
import io.github.sudoitir.rahdar.kernel.security.web.TeamViews.UserLookup;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.PermissionView;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.RoleView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Teams: who owns which queue and address names on a cluster, who is a member, and what is shared
 * onward (team-access spec). A holder of {@code user:admin} does everything. A holder of
 * {@code team:admin} in a team (through the member's team role) may add, change and remove that team's
 * members, and only to roles whose permissions they hold in that team; they may not change its patterns or
 * shares. {@code team:admin} held through a global grant administers the members of every team. Every change
 * is audited and announced, so it applies on the next request.
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    private static final int EXAMPLES = 20;
    private static final String PATTERN = "pattern";
    private static final String CLUSTER_ID = "clusterId";
    private static final String MEMBER = "member";

    private static final String USER_ADMIN =
            "@perm.can(T(io.github.sudoitir.rahdar.kernel.security.Permissions).USER_ADMIN)";

    private final TeamRepository teams;
    private final TeamPatternRepository patterns;
    private final TeamMemberRepository members;
    private final TeamShareRepository shares;
    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final AppUserRepository users;
    private final PermissionResolver perm;
    private final AccessLoader access;
    private final TeamIndex index;
    private final AccessChanges accessChanges;
    private final AdministrationAudit audit;
    private final ScopeHierarchy clusters;
    private final IdentityProviderCatalog providers;
    private final ResourceNames names;
    private final UserGroupRepository userGroups;
    private final SessionTerminator sessions;
    private final JdbcTemplate jdbc;
    private final FeatureRegistry features;
    private final OperationGate gate;
    private final TransactionTemplate tx;

    // ---- teams --------------------------------------------------------------------------------------

    /** Every team to a user administrator; otherwise the teams the caller administers. */
    @Transactional(readOnly = true)
    public List<TeamSummary> list() {
        Authority authority = authority();
        return teams.findAllByOrderByName().stream()
                .filter(team -> authority.sees(team.getId()))
                .map(this::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamView get(UUID teamId) {
        TeamEntity team = requireTeam(teamId);
        if (!authority().sees(teamId)) {
            throw new NotFoundException("team", teamId);
        }
        return view(team);
    }

    @Gated("team.create")
    @PreAuthorize(USER_ADMIN)
    public TeamView create(String name) {
        String trimmed = name.strip();
        requireFreeName(trimmed, null);
        return gate.run(
                Operation.of(new TeamOperations.CreateTeam(trimmed)), GatedWrites.inTx(tx, () -> createNow(trimmed)));
    }

    private TeamView createNow(String trimmed) {
        requireFreeName(trimmed, null);
        TeamEntity team = teams.save(new TeamEntity(trimmed));
        audit.changed("TEAM_CREATE", "team", trimmed, null);
        accessChanges.changed();
        return view(team);
    }

    @Gated("team.rename")
    @PreAuthorize(USER_ADMIN)
    public TeamView rename(UUID teamId, String name) {
        String trimmed = name.strip();
        requireFreeName(trimmed, requireTeam(teamId));
        return gate.run(
                Operation.of(new TeamOperations.RenameTeam(teamId, trimmed)),
                GatedWrites.inTx(tx, () -> renameNow(teamId, trimmed)));
    }

    private TeamView renameNow(UUID teamId, String trimmed) {
        TeamEntity team = requireTeam(teamId);
        requireFreeName(trimmed, team);
        String before = team.getName();
        team.setName(trimmed);
        teams.save(team);
        audit.changed("TEAM_RENAME", "team", trimmed, Map.of("previousName", before));
        accessChanges.changed();
        return view(team);
    }

    /** Removes the team with its patterns, memberships and shares, in both directions. */
    @Gated("team.delete")
    @PreAuthorize(USER_ADMIN)
    public void delete(UUID teamId) {
        requireTeam(teamId);
        gate.run(
                Operation.of(new TeamOperations.DeleteTeam(teamId)), GatedWrites.inTxVoid(tx, () -> deleteNow(teamId)));
    }

    private void deleteNow(UUID teamId) {
        TeamEntity team = requireTeam(teamId);
        audit.changed(
                "TEAM_DELETE",
                "team",
                team.getName(),
                Map.of(
                        "patterns", patterns.findByTeamId(teamId).size(),
                        "members", members.countByTeamId(teamId)));
        teams.delete(team);
        accessChanges.changed();
    }

    // ---- lookups for who administers a team ---------------------------------------------------------

    private static final int LOOKUP_LIMIT = 20;
    /** Shorter prefixes would let a team admin page through every account a letter at a time. */
    private static final int LOOKUP_MIN_PREFIX = 2;

    /**
     * Enabled users whose name starts with {@code prefix} (two characters at least), at most twenty, for a team admin choosing a
     * member. Open to anyone who may administer some team, because {@code user:admin} would otherwise be
     * needed just to look a colleague up.
     */
    @Transactional(readOnly = true)
    public List<UserLookup> findUsers(String prefix) {
        requireTeamAdministrator();
        String stripped = prefix.strip();
        if (stripped.length() < LOOKUP_MIN_PREFIX) {
            return List.of();
        }
        return users.findTop20ByDisabledFalseAndUsernameStartingWithIgnoreCaseOrderByUsername(stripped).stream()
                .limit(LOOKUP_LIMIT)
                .map(u -> new UserLookup(u.getId(), u.getUsername()))
                .toList();
    }

    /** The team roles, with their permissions, and the catalogue entries those permissions name. */
    @Transactional(readOnly = true)
    public TeamRoleLookup teamRoles() {
        requireTeamAdministrator();
        List<RoleEntity> teamRoles = roles.findAllByOrderByName().stream()
                .filter(RoleEntity::isTeamAssignable)
                .toList();
        Map<UUID, Set<String>> held =
                teamRoles.stream().collect(Collectors.toMap(RoleEntity::getId, this::permissionsOf));
        List<RoleView> views = teamRoles.stream()
                .map(r -> new RoleView(
                        r.getId(),
                        r.getName(),
                        r.isBuiltin(),
                        held.get(r.getId()).stream().sorted().toList(),
                        r.isRequiresMfa(),
                        true))
                .toList();
        List<PermissionView> entries = features.catalogue().stream()
                .filter(e -> held.values().stream().anyMatch(permissions -> Grant.covers(permissions, e.action())))
                .map(e -> new PermissionView(
                        e.action(),
                        e.description(),
                        e.featureId(),
                        e.featureTitle(),
                        e.scope(),
                        e.resourceTypes().stream().sorted().toList(),
                        e.requires().stream().sorted().toList()))
                .toList();
        return new TeamRoleLookup(views, entries);
    }

    /** A user administrator, or a holder of {@code team:admin} globally or in some team; nobody else. */
    void requireTeamAdministrator() {
        Authority authority = authority();
        if (!authority.everything() && authority.administered().isEmpty()) {
            throw new AccessDeniedException("Only an administrator of a team can do this.");
        }
    }

    // ---- patterns -----------------------------------------------------------------------------------

    /**
     * Adds a name pattern the team owns on a cluster, refused when some name on that cluster and kind could
     * match both it and a pattern of another team.
     */
    @Gated("team.pattern.add")
    @PreAuthorize(USER_ADMIN)
    public PatternView addPattern(UUID teamId, PatternRequest request) {
        requireTeam(teamId);
        ResourcePattern pattern = ResourcePattern.parse(request.pattern().strip());
        requireCluster(request.clusterId());
        return gate.run(
                Operation.of(
                        new TeamOperations.AddPattern(teamId, request.clusterId(), request.kind(), pattern.text())),
                GatedWrites.inTx(tx, () -> addPatternNow(teamId, request)));
    }

    private PatternView addPatternNow(UUID teamId, PatternRequest request) {
        TeamEntity team = requireTeam(teamId);
        ResourcePattern pattern = ResourcePattern.parse(request.pattern().strip());
        requireCluster(request.clusterId());
        lockPatternsOf(request.clusterId());
        boolean duplicate = patterns.findByTeamId(teamId).stream()
                .anyMatch(p -> p.getClusterId().equals(request.clusterId())
                        && p.getKind().equals(request.kind().name())
                        && p.getPattern().equals(pattern.text()));
        if (duplicate) {
            throw new ConflictException(
                    "duplicate-team-pattern", "Team '" + team.getName() + "' already owns '" + pattern + "' there.");
        }
        List<PatternConflict> conflicts = conflicts(request.clusterId(), request.kind(), pattern, teamId);
        if (!conflicts.isEmpty()) {
            PatternConflict first = conflicts.getFirst();
            throw new ConflictException(
                    "team-pattern-overlap",
                    "Some names could match both '" + pattern + "' and pattern '" + first.pattern() + "' of team '"
                            + first.teamName() + "' on this cluster. Teams may not own overlapping names.");
        }
        TeamPatternEntity saved = patterns.save(new TeamPatternEntity(
                teamId, request.clusterId(), request.kind().name(), pattern.text()));
        audit.changed(
                "TEAM_PATTERN_ADD",
                "team",
                team.getName(),
                Map.of(
                        PATTERN,
                        pattern.text(),
                        "kind",
                        request.kind().name(),
                        CLUSTER_ID,
                        request.clusterId().toString()));
        accessChanges.changed();
        return patternView(saved);
    }

    @Gated("team.pattern.remove")
    @PreAuthorize(USER_ADMIN)
    public void removePattern(UUID teamId, UUID patternId) {
        requireTeam(teamId);
        requirePattern(teamId, patternId);
        gate.run(
                Operation.of(new TeamOperations.RemovePattern(teamId, patternId)),
                GatedWrites.inTxVoid(tx, () -> removePatternNow(teamId, patternId)));
    }

    private TeamPatternEntity requirePattern(UUID teamId, UUID patternId) {
        return patterns.findByIdAndTeamId(patternId, teamId)
                .orElseThrow(() -> new NotFoundException("team pattern", patternId));
    }

    private void removePatternNow(UUID teamId, UUID patternId) {
        TeamEntity team = requireTeam(teamId);
        TeamPatternEntity pattern = requirePattern(teamId, patternId);
        patterns.delete(pattern);
        audit.changed(
                "TEAM_PATTERN_REMOVE",
                "team",
                team.getName(),
                Map.of(
                        PATTERN,
                        pattern.getPattern(),
                        "kind",
                        pattern.getKind(),
                        CLUSTER_ID,
                        pattern.getClusterId().toString()));
        accessChanges.changed();
    }

    /**
     * What a pattern would cover on a cluster right now (counts and up to 20 names of each kind) and which
     * patterns of other teams it would clash with, so an administrator sees both before adding it.
     */
    @PreAuthorize(USER_ADMIN)
    @Transactional(readOnly = true)
    public PatternPreview preview(UUID teamId, UUID clusterId, PatternKind kind, String text) {
        requireTeam(teamId);
        ResourcePattern pattern = ResourcePattern.parse(text.strip());
        requireCluster(clusterId);
        List<String> queues = kind.types().contains(ResourceType.SUBSCRIPTION)
                ? matching(pattern, names.queues(clusterId))
                : List.of();
        List<String> addresses = kind.types().contains(ResourceType.DESTINATION)
                ? matching(pattern, names.addresses(clusterId))
                : List.of();
        return new PatternPreview(
                clusterId,
                kind,
                pattern.text(),
                queues.size(),
                addresses.size(),
                queues.stream().limit(EXAMPLES).toList(),
                addresses.stream().limit(EXAMPLES).toList(),
                conflicts(clusterId, kind, pattern, teamId));
    }

    /** The queue or address names of the cluster that no team owns: reachable only through platform grants. */
    @PreAuthorize(USER_ADMIN)
    @Transactional(readOnly = true)
    public List<String> unowned(UUID clusterId, ResourceType kind) {
        requireCluster(clusterId);
        List<String> known = kind == ResourceType.SUBSCRIPTION ? names.queues(clusterId) : names.addresses(clusterId);
        return index.unowned(clusterId, kind, known);
    }

    // ---- members ------------------------------------------------------------------------------------

    @Gated("team.member.add")
    public MemberView addMember(UUID teamId, MemberRequest request) {
        authorizeAddMember(teamId, request);
        return gate.run(
                Operation.of(new TeamOperations.AddMember(
                        teamId,
                        request.principalType(),
                        request.userId(),
                        request.providerId(),
                        request.groupName(),
                        request.roleId())),
                GatedWrites.inTx(tx, () -> addMemberNow(teamId, request)));
    }

    /** Who may add this member at this role; read-only, so the gate and the replay both run it. */
    private Authority authorizeAddMember(UUID teamId, MemberRequest request) {
        requireTeam(teamId);
        Authority authority = memberAuthority(teamId);
        RoleEntity role = requireTeamRole(request.roleId());
        authority.requireMayAssign(teamId, role, permissionsOf(role));
        if (request.principalType() == PrincipalType.GROUP && !authority.userAdmin()) {
            throw new AccessDeniedException(
                    "Only a user administrator can add a directory group as a member of a team.");
        }
        return authority;
    }

    private MemberView addMemberNow(UUID teamId, MemberRequest request) {
        authorizeAddMember(teamId, request);
        TeamEntity team = requireTeam(teamId);
        RoleEntity role = requireTeamRole(request.roleId());
        Candidate candidate = request.principalType() == PrincipalType.USER
                ? userCandidate(teamId, request, role)
                : groupCandidate(teamId, request, role);
        TeamMemberEntity member = candidate.member();
        String description = candidate.description();
        if (existing(teamId, member).isPresent()) {
            throw new ConflictException("team-member-exists", "The " + description + " is already a member.");
        }
        TeamMemberEntity saved = members.save(member);
        audit.changed("TEAM_MEMBER_ADD", "team", team.getName(), Map.of(MEMBER, description, "role", role.getName()));
        accessChanges.changed();
        endSessionsWhereASecondFactorIsNowRequired(saved, role);
        return memberView(saved, roleNames(), usernames(List.of(saved)));
    }

    /** A member about to be added, and how the audit trail names them. */
    private record Candidate(TeamMemberEntity member, String description) {}

    private Candidate userCandidate(UUID teamId, MemberRequest request, RoleEntity role) {
        AppUserEntity user = request.userId() == null
                ? null
                : users.findById(request.userId()).orElse(null);
        if (user == null) {
            throw new NotFoundException("user", request.userId());
        }
        return new Candidate(TeamMemberEntity.ofUser(teamId, user.getId(), role.getId()), "user " + user.getUsername());
    }

    private Candidate groupCandidate(UUID teamId, MemberRequest request, RoleEntity role) {
        String provider =
                request.providerId() == null ? "" : request.providerId().strip();
        String group = request.groupName() == null ? "" : request.groupName().strip();
        if (provider.isEmpty() || group.isEmpty()) {
            throw new IllegalArgumentException("A group member needs the identity provider and the group name.");
        }
        requireExternalProvider(provider);
        return new Candidate(
                TeamMemberEntity.ofGroup(teamId, provider, group, role.getId()), "group " + group + " of " + provider);
    }

    @Gated("team.member.role")
    public MemberView changeMemberRole(UUID teamId, UUID memberId, UUID roleId) {
        authorizeRoleChange(teamId, memberId, roleId);
        return gate.run(
                Operation.of(new TeamOperations.ChangeMemberRole(teamId, memberId, roleId)),
                GatedWrites.inTx(tx, () -> changeMemberRoleNow(teamId, memberId, roleId)));
    }

    /** Who may give the member this role; read-only, so the gate and the replay both run it. */
    private TeamMemberEntity authorizeRoleChange(UUID teamId, UUID memberId, UUID roleId) {
        requireTeam(teamId);
        Authority authority = memberAuthority(teamId);
        TeamMemberEntity member = members.findByIdAndTeamId(memberId, teamId)
                .orElseThrow(() -> new NotFoundException("team member", memberId));
        RoleEntity role = requireTeamRole(roleId);
        authority.requireMayManage(member);
        authority.requireMayAssign(teamId, role, permissionsOf(role));
        authority.requireMayAssign(teamId, roleOf(member), permissionsOf(roleOf(member)));
        return member;
    }

    private MemberView changeMemberRoleNow(UUID teamId, UUID memberId, UUID roleId) {
        TeamMemberEntity member = authorizeRoleChange(teamId, memberId, roleId);
        TeamEntity team = requireTeam(teamId);
        RoleEntity role = requireTeamRole(roleId);
        member.setRoleId(role.getId());
        members.save(member);
        audit.changed(
                "TEAM_MEMBER_ROLE",
                "team",
                team.getName(),
                Map.of(MEMBER, describe(member, usernames(List.of(member))), "role", role.getName()));
        accessChanges.changed();
        endSessionsWhereASecondFactorIsNowRequired(member, role);
        return memberView(member, roleNames(), usernames(List.of(member)));
    }

    @Gated("team.member.remove")
    public void removeMember(UUID teamId, UUID memberId) {
        authorizeRemoveMember(teamId, memberId);
        gate.run(
                Operation.of(new TeamOperations.RemoveMember(teamId, memberId)),
                GatedWrites.inTxVoid(tx, () -> removeMemberNow(teamId, memberId)));
    }

    /** Who may remove the member; read-only, so the gate and the replay both run it. */
    private TeamMemberEntity authorizeRemoveMember(UUID teamId, UUID memberId) {
        requireTeam(teamId);
        Authority authority = memberAuthority(teamId);
        TeamMemberEntity member = members.findByIdAndTeamId(memberId, teamId)
                .orElseThrow(() -> new NotFoundException("team member", memberId));
        authority.requireMayManage(member);
        authority.requireMayAssign(teamId, roleOf(member), permissionsOf(roleOf(member)));
        return member;
    }

    private void removeMemberNow(UUID teamId, UUID memberId) {
        TeamMemberEntity member = authorizeRemoveMember(teamId, memberId);
        TeamEntity team = requireTeam(teamId);
        members.delete(member);
        audit.changed(
                "TEAM_MEMBER_REMOVE",
                "team",
                team.getName(),
                Map.of(MEMBER, describe(member, usernames(List.of(member)))));
        accessChanges.changed();
    }

    // ---- shares -------------------------------------------------------------------------------------

    /**
     * Shares a pattern with another team at a team role. The pattern must lie within what the owner owns
     * on the cluster: one of the owner's patterns of each kind it covers must contain it as a whole (a share
     * that only a union of the owner's patterns would cover is refused).
     */
    @Gated("team.share.add")
    @PreAuthorize(USER_ADMIN)
    public ShareView addShare(UUID ownerTeamId, ShareRequest request) {
        requireTeam(ownerTeamId);
        requireTeam(request.targetTeamId());
        requireCluster(request.clusterId());
        requireTeamRole(request.roleId());
        return gate.run(
                Operation.of(new TeamShareOperations.AddShare(
                        ownerTeamId,
                        request.targetTeamId(),
                        request.clusterId(),
                        request.kind(),
                        request.pattern().strip(),
                        request.roleId())),
                GatedWrites.inTx(tx, () -> addShareNow(ownerTeamId, request)));
    }

    private ShareView addShareNow(UUID ownerTeamId, ShareRequest request) {
        TeamEntity owner = requireTeam(ownerTeamId);
        if (ownerTeamId.equals(request.targetTeamId())) {
            throw new ConflictException("share-with-self", "A team cannot share with itself.");
        }
        TeamEntity target = requireTeam(request.targetTeamId());
        ResourcePattern pattern = ResourcePattern.parse(request.pattern().strip());
        requireCluster(request.clusterId());
        RoleEntity role = requireTeamRole(request.roleId());
        if (!covered(ownerTeamId, request.clusterId(), request.kind(), pattern)) {
            throw new ConflictException(
                    "share-outside-owner",
                    "'" + pattern + "' does not lie within the "
                            + request.kind().name().toLowerCase()
                            + " patterns team '" + owner.getName() + "' owns on this cluster; "
                            + "it must be inside one of them.");
        }
        boolean duplicate = shares.findByOwnerTeamId(ownerTeamId).stream()
                .anyMatch(s -> s.getTargetTeamId().equals(target.getId())
                        && s.getClusterId().equals(request.clusterId())
                        && s.getKind().equals(request.kind().name())
                        && s.getPattern().equals(pattern.text()));
        if (duplicate) {
            throw new ConflictException(
                    "duplicate-team-share",
                    "'" + pattern + "' is already shared with team '" + target.getName() + "'.");
        }
        TeamShareEntity saved = shares.save(new TeamShareEntity(
                ownerTeamId, target.getId(), request.clusterId(), request.kind().name(), pattern.text(), role.getId()));
        audit.changed(
                "TEAM_SHARE_ADD",
                "team",
                owner.getName(),
                Map.of(
                        "with",
                        target.getName(),
                        PATTERN,
                        pattern.text(),
                        "kind",
                        request.kind().name(),
                        "role",
                        role.getName(),
                        CLUSTER_ID,
                        request.clusterId().toString()));
        accessChanges.changed();
        return shareView(saved, teamNames(), roleNames());
    }

    @Gated("team.share.remove")
    @PreAuthorize(USER_ADMIN)
    public void removeShare(UUID ownerTeamId, UUID shareId) {
        requireTeam(ownerTeamId);
        requireShare(ownerTeamId, shareId);
        gate.run(
                Operation.of(new TeamShareOperations.RemoveShare(ownerTeamId, shareId)),
                GatedWrites.inTxVoid(tx, () -> removeShareNow(ownerTeamId, shareId)));
    }

    private TeamShareEntity requireShare(UUID ownerTeamId, UUID shareId) {
        return shares.findByIdAndOwnerTeamId(shareId, ownerTeamId)
                .orElseThrow(() -> new NotFoundException("team share", shareId));
    }

    private void removeShareNow(UUID ownerTeamId, UUID shareId) {
        TeamEntity owner = requireTeam(ownerTeamId);
        TeamShareEntity share = requireShare(ownerTeamId, shareId);
        shares.delete(share);
        audit.changed(
                "TEAM_SHARE_REMOVE",
                "team",
                owner.getName(),
                Map.of(PATTERN, share.getPattern(), "kind", share.getKind()));
        accessChanges.changed();
    }

    // ---- rules --------------------------------------------------------------------------------------

    /** The other teams' patterns on the cluster, of an intersecting kind, that some name could match with this one. */
    private List<PatternConflict> conflicts(UUID clusterId, PatternKind kind, ResourcePattern pattern, UUID team) {
        List<TeamEntity> all = teams.findAll();
        Map<UUID, String> teamNames = all.stream().collect(Collectors.toMap(TeamEntity::getId, TeamEntity::getName));
        List<PatternConflict> found = new ArrayList<>();
        for (TeamPatternEntity other : patterns.findByClusterId(clusterId)) {
            PatternKind otherKind = PatternKind.valueOf(other.getKind());
            if (!other.getTeamId().equals(team)
                    && !Collections.disjoint(kind.types(), otherKind.types())
                    && ResourcePattern.overlaps(pattern, ResourcePattern.parse(other.getPattern()))) {
                found.add(new PatternConflict(
                        other.getTeamId(),
                        teamNames.getOrDefault(other.getTeamId(), "?"),
                        otherKind,
                        other.getPattern()));
            }
        }
        return found;
    }

    /**
     * Serialises the changes of one cluster's patterns until the transaction ends, so two teams adding
     * overlapping patterns at once cannot both pass the overlap check.
     */
    private void lockPatternsOf(UUID clusterId) {
        long key = UUID.nameUUIDFromBytes(("team-patterns|" + clusterId).getBytes(StandardCharsets.UTF_8))
                .getMostSignificantBits();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", key);
    }

    /** Whether, for every kind the share covers, one of the owner's patterns of that kind contains it. */
    private boolean covered(UUID ownerTeamId, UUID clusterId, PatternKind kind, ResourcePattern pattern) {
        List<TeamPatternEntity> owned = patterns.findByTeamId(ownerTeamId).stream()
                .filter(p -> p.getClusterId().equals(clusterId))
                .toList();
        return kind.types().stream()
                .allMatch(k -> owned.stream()
                        .filter(p -> PatternKind.valueOf(p.getKind()).types().contains(k))
                        .anyMatch(p -> ResourcePattern.covers(ResourcePattern.parse(p.getPattern()), pattern)));
    }

    private static List<String> matching(ResourcePattern pattern, List<String> candidates) {
        return candidates.stream().filter(pattern::matches).sorted().toList();
    }

    private void requireFreeName(String name, TeamEntity renaming) {
        boolean taken = teams.findAll().stream()
                .anyMatch(t -> t.getName().equalsIgnoreCase(name) && (renaming == null || !t.equals(renaming)));
        if (taken) {
            throw new ConflictException("duplicate-team-name", "A team named '" + name + "' already exists.");
        }
    }

    private TeamEntity requireTeam(UUID teamId) {
        return teams.findById(teamId).orElseThrow(() -> new NotFoundException("team", teamId));
    }

    private void requireCluster(UUID clusterId) {
        if (clusters.clusterName(clusterId) == null) {
            throw new NotFoundException("cluster", clusterId);
        }
    }

    private RoleEntity roleOf(TeamMemberEntity member) {
        return roles.findById(member.getRoleId()).orElseThrow(() -> new NotFoundException("role", member.getRoleId()));
    }

    /**
     * Sessions opened before the member held a role that requires a second factor never verified one, so
     * they end, and the next sign-in enforces it.
     */
    private void endSessionsWhereASecondFactorIsNowRequired(TeamMemberEntity member, RoleEntity role) {
        if (!role.isRequiresMfa()) {
            return;
        }
        List<UUID> affected = member.getUserId() != null
                ? List.of(member.getUserId())
                : userGroups.findByIdProviderIdAndIdGroupName(member.getProviderId(), member.getGroupName()).stream()
                        .map(g -> g.getId().getUserId())
                        .toList();
        sessions.endSessionsOf(users.findAllById(affected).stream()
                .map(AppUserEntity::getUsername)
                .toList());
    }

    private Set<String> permissionsOf(RoleEntity role) {
        return rolePermissions.findByIdRoleId(role.getId()).stream()
                .map(RolePermissionEntity::getAction)
                .collect(Collectors.toSet());
    }

    private RoleEntity requireTeamRole(UUID roleId) {
        RoleEntity role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role", roleId));
        if (!role.isTeamAssignable()) {
            throw new ConflictException(
                    "not-a-team-role",
                    "Role '" + role.getName() + "' is not a team role: mark it team-assignable first.");
        }
        return role;
    }

    private void requireExternalProvider(String providerId) {
        boolean known = !LoginService.DEFAULT_PROVIDER.equals(providerId)
                && providers.providers().stream().anyMatch(p -> p.id().equals(providerId));
        if (!known) {
            throw new NotFoundException("identity provider", providerId);
        }
    }

    private Optional<TeamMemberEntity> existing(UUID teamId, TeamMemberEntity candidate) {
        return members.findByTeamId(teamId).stream()
                .filter(m -> m.getPrincipalType().equals(candidate.getPrincipalType())
                        && Objects.equals(m.getUserId(), candidate.getUserId())
                        && Objects.equals(m.getProviderId(), candidate.getProviderId())
                        && Objects.equals(m.getGroupName(), candidate.getGroupName()))
                .findFirst();
    }

    // ---- who may do what ----------------------------------------------------------------------------

    /**
     * What the caller may do with teams: everything when they administer users or hold {@code team:admin}
     * globally; otherwise only the members of the teams where their own team role holds {@code team:admin}.
     */
    private record Authority(boolean userAdmin, boolean everything, Map<UUID, Set<String>> administered) {

        boolean sees(UUID teamId) {
            return everything || administered.containsKey(teamId);
        }

        /**
         * A directory group can admit users to Rahdar, so only a user administrator may add, change or remove
         * one; a team admin manages the team's user members.
         */
        void requireMayManage(TeamMemberEntity member) {
            if (!userAdmin && member.getPrincipalType().equals(TeamMemberEntity.GROUP)) {
                throw new AccessDeniedException(
                        "Only a user administrator can add, change or remove a directory group as a member.");
            }
        }

        /**
         * A team admin may not give, change or take away a role holding a permission they do not hold in the
         * team: that would let them raise someone above themselves, or remove someone above them.
         */
        void requireMayAssign(UUID teamId, RoleEntity role, Set<String> rolePermissions) {
            if (everything) {
                return;
            }
            Set<String> held = administered.getOrDefault(teamId, Set.of());
            List<String> beyond = rolePermissions.stream()
                    .filter(permission -> !Grant.covers(held, permission))
                    .sorted()
                    .toList();
            if (!beyond.isEmpty()) {
                throw new AccessDeniedException("Role '" + role.getName() + "' holds permissions you do not hold in"
                        + " this team: " + String.join(", ", beyond) + ".");
            }
        }
    }

    private Authority authority() {
        RahdarPrincipal principal = currentPrincipal();
        if (principal == null) {
            throw new AccessDeniedException("Sign in to manage teams.");
        }
        if (perm.can(Permissions.USER_ADMIN) || perm.can(Permissions.TEAM_ADMIN)) {
            return new Authority(perm.can(Permissions.USER_ADMIN), true, Map.of());
        }
        if (principal.pinned()) {
            return new Authority(false, false, Map.of());
        }
        AccessSnapshot snapshot = access.of(principal.userId());
        Map<UUID, Set<String>> administered = snapshot.teamPermissions().entrySet().stream()
                .filter(e -> Grant.covers(e.getValue(), Permissions.TEAM_ADMIN))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new Authority(false, false, administered);
    }

    /** The right to change {@code teamId}'s members; not found for a caller who may not even see the team. */
    private Authority memberAuthority(UUID teamId) {
        Authority authority = authority();
        if (!authority.sees(teamId)) {
            throw new NotFoundException("team", teamId);
        }
        return authority;
    }

    private static RahdarPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof RahdarPrincipal p ? p : null;
    }

    // ---- views --------------------------------------------------------------------------------------

    private TeamSummary summary(TeamEntity team) {
        return new TeamSummary(
                team.getId(),
                team.getName(),
                team.getCreatedAt(),
                (int) members.countByTeamId(team.getId()),
                patterns.findByTeamId(team.getId()).stream()
                        .map(TeamService::patternView)
                        .toList(),
                shares.findByOwnerTeamId(team.getId()).size(),
                shares.findByTargetTeamId(team.getId()).size());
    }

    private TeamView view(TeamEntity team) {
        Map<UUID, String> roleNames = roleNames();
        Map<UUID, String> teamNames = teamNames();
        List<TeamMemberEntity> memberRows = members.findByTeamId(team.getId());
        Map<UUID, String> usernames = usernames(memberRows);
        return new TeamView(
                team.getId(),
                team.getName(),
                team.getCreatedAt(),
                patterns.findByTeamId(team.getId()).stream()
                        .map(TeamService::patternView)
                        .toList(),
                memberRows.stream()
                        .map(m -> memberView(m, roleNames, usernames))
                        .toList(),
                shares.findByOwnerTeamId(team.getId()).stream()
                        .map(s -> shareView(s, teamNames, roleNames))
                        .toList(),
                shares.findByTargetTeamId(team.getId()).stream()
                        .map(s -> shareView(s, teamNames, roleNames))
                        .toList());
    }

    private static PatternView patternView(TeamPatternEntity p) {
        return new PatternView(p.getId(), p.getClusterId(), PatternKind.valueOf(p.getKind()), p.getPattern());
    }

    private MemberView memberView(TeamMemberEntity m, Map<UUID, String> roleNames, Map<UUID, String> usernames) {
        return new MemberView(
                m.getId(),
                PrincipalType.valueOf(m.getPrincipalType()),
                m.getUserId(),
                m.getUserId() == null ? null : usernames.get(m.getUserId()),
                m.getProviderId(),
                m.getGroupName(),
                m.getRoleId(),
                roleNames.getOrDefault(m.getRoleId(), "?"));
    }

    private ShareView shareView(TeamShareEntity s, Map<UUID, String> teamNames, Map<UUID, String> roleNames) {
        PatternKind kind = PatternKind.valueOf(s.getKind());
        return new ShareView(
                s.getId(),
                s.getOwnerTeamId(),
                teamNames.getOrDefault(s.getOwnerTeamId(), "?"),
                s.getTargetTeamId(),
                teamNames.getOrDefault(s.getTargetTeamId(), "?"),
                s.getClusterId(),
                kind,
                s.getPattern(),
                s.getRoleId(),
                roleNames.getOrDefault(s.getRoleId(), "?"),
                covered(s.getOwnerTeamId(), s.getClusterId(), kind, ResourcePattern.parse(s.getPattern())));
    }

    private Map<UUID, String> roleNames() {
        return roles.findAll().stream().collect(Collectors.toMap(RoleEntity::getId, RoleEntity::getName));
    }

    private Map<UUID, String> teamNames() {
        return teams.findAll().stream().collect(Collectors.toMap(TeamEntity::getId, TeamEntity::getName));
    }

    private Map<UUID, String> usernames(List<TeamMemberEntity> memberRows) {
        Set<UUID> ids = memberRows.stream()
                .map(TeamMemberEntity::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return users.findAllById(ids).stream()
                .collect(Collectors.toMap(AppUserEntity::getId, AppUserEntity::getUsername, (a, b) -> a));
    }

    private static String describe(TeamMemberEntity m, Map<UUID, String> usernames) {
        return m.getUserId() != null
                ? "user " + usernames.getOrDefault(m.getUserId(), m.getUserId().toString())
                : "group " + m.getGroupName() + " of " + m.getProviderId();
    }
}
