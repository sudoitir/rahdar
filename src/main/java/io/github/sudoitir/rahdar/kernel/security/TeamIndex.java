package io.github.sudoitir.rahdar.kernel.security;

import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamPatternEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamPatternRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamShareEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.TeamShareRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who owns which queue and address names on each cluster, and what has been shared onward: every team
 * pattern compiled, and every share reduced to the part its owner still owns. Built from the database on
 * first use and dropped by {@link AccessChanges}, so a decision is a few word comparisons and no query.
 *
 * <p>A share counts only where one of its owner's patterns, of the same kind, covers the shared pattern
 * ({@link ResourcePattern#covers}): when the owner's patterns shrink below it, it grants nothing until
 * they cover it again.
 */
@Component
@RequiredArgsConstructor
public class TeamIndex {

    /** A pattern a team owns, for the types in {@code types}. */
    public record Owned(ResourcePattern pattern, Set<ResourceType> types, UUID teamId) {}

    /** A shared pattern as it takes effect: the types still covered by the owner, the role and its permissions. */
    public record Shared(
            ResourcePattern pattern,
            Set<ResourceType> types,
            UUID ownerTeamId,
            UUID targetTeamId,
            UUID roleId,
            Set<String> permissions) {}

    private record ClusterTeams(List<Owned> owned, List<Shared> shared) {
        static final ClusterTeams NONE = new ClusterTeams(List.of(), List.of());
    }

    private final TeamRepository teams;
    private final TeamPatternRepository patterns;
    private final TeamShareRepository shares;
    private final RolePermissionRepository rolePermissions;

    /** How long a built index may be used with no announced change: a backstop for a missed message. */
    private static final long EXPIRY_NANOS = Duration.ofMinutes(1).toNanos();

    private record Built(Map<UUID, ClusterTeams> clusters, Map<UUID, String> teamNames, long at) {}

    private final Object lock = new Object();
    private long generation;
    private final AtomicReference<Built> index = new AtomicReference<>();

    /** The team that owns the name on the cluster; at most one, because teams may not overlap on a cluster. */
    public Optional<UUID> ownerOf(UUID clusterId, ResourceRef ref) {
        return ownerOf(of(clusterId), ref);
    }

    private static Optional<UUID> ownerOf(ClusterTeams cluster, ResourceRef ref) {
        return cluster.owned().stream()
                .filter(o -> o.types().contains(ref.type()) && o.pattern().matches(ref.name()))
                .map(Owned::teamId)
                .findFirst();
    }

    /** The team that owns the name on the cluster, with its name. */
    public Optional<TeamRef> ownerTeamOf(UUID clusterId, ResourceRef ref) {
        Built built = built();
        return ownerOf(built.clusters().getOrDefault(clusterId, ClusterTeams.NONE), ref)
                .filter(built.teamNames()::containsKey)
                .map(id -> new TeamRef(id, built.teamNames().get(id)));
    }

    /** The shares that cover the name on the cluster. */
    public List<Shared> sharesCovering(UUID clusterId, ResourceRef ref) {
        return of(clusterId).shared().stream()
                .filter(s -> s.types().contains(ref.type()) && s.pattern().matches(ref.name()))
                .toList();
    }

    /** Every pattern owned on the cluster. */
    public List<Owned> ownedOn(UUID clusterId) {
        return of(clusterId).owned();
    }

    /** Every share that takes effect on the cluster. */
    public List<Shared> sharedOn(UUID clusterId) {
        return of(clusterId).shared();
    }

    /** The names of {@code names} that no team owns on the cluster, for the kind. */
    public List<String> unowned(UUID clusterId, ResourceType kind, List<String> names) {
        List<Owned> owned = of(clusterId).owned().stream()
                .filter(o -> o.types().contains(kind))
                .toList();
        return names.stream()
                .filter(name -> owned.stream().noneMatch(o -> o.pattern().matches(name)))
                .toList();
    }

    void invalidate() {
        synchronized (lock) {
            generation++;
            index.set(null);
        }
    }

    private ClusterTeams of(UUID clusterId) {
        return built().clusters().getOrDefault(clusterId, ClusterTeams.NONE);
    }

    private Built built() {
        Built current = index.get();
        if (current == null || System.nanoTime() - current.at() > EXPIRY_NANOS) {
            long startedAt;
            synchronized (lock) {
                startedAt = generation;
            }
            current = build();
            synchronized (lock) {
                // A change announced while this was being read from the database may be missing from it:
                // use what was read for this call, but do not keep it.
                if (generation == startedAt) {
                    index.set(current);
                }
            }
        }
        return current;
    }

    private Built build() {
        Map<UUID, List<Owned>> owned = new HashMap<>();
        Map<String, List<Owned>> byTeamAndCluster = new HashMap<>();
        for (TeamPatternEntity p : patterns.findAll()) {
            Owned entry = new Owned(
                    ResourcePattern.parse(p.getPattern()),
                    PatternKind.valueOf(p.getKind()).types(),
                    p.getTeamId());
            owned.computeIfAbsent(p.getClusterId(), c -> new ArrayList<>()).add(entry);
            byTeamAndCluster
                    .computeIfAbsent(p.getTeamId() + "|" + p.getClusterId(), k -> new ArrayList<>())
                    .add(entry);
        }
        Map<UUID, Set<String>> rolePermissionsById = new HashMap<>();
        Map<UUID, List<Shared>> shared = new HashMap<>();
        for (TeamShareEntity s : shares.findAll()) {
            ResourcePattern pattern = ResourcePattern.parse(s.getPattern());
            List<Owned> ownersPatterns =
                    byTeamAndCluster.getOrDefault(s.getOwnerTeamId() + "|" + s.getClusterId(), List.of());
            Set<ResourceType> covered = EnumSet.noneOf(ResourceType.class);
            for (ResourceType kind : PatternKind.valueOf(s.getKind()).types()) {
                if (ownersPatterns.stream()
                        .anyMatch(o -> o.types().contains(kind) && ResourcePattern.covers(o.pattern(), pattern))) {
                    covered.add(kind);
                }
            }
            if (!covered.isEmpty()) {
                Set<String> permissions = rolePermissionsById.computeIfAbsent(
                        s.getRoleId(),
                        role -> rolePermissions.findByIdRoleId(role).stream()
                                .map(RolePermissionEntity::getAction)
                                .collect(Collectors.toSet()));
                shared.computeIfAbsent(s.getClusterId(), c -> new ArrayList<>())
                        .add(new Shared(
                                pattern, covered, s.getOwnerTeamId(), s.getTargetTeamId(), s.getRoleId(), permissions));
            }
        }
        Map<UUID, ClusterTeams> built = new HashMap<>();
        Set<UUID> clusters = new HashSet<>(owned.keySet());
        clusters.addAll(shared.keySet());
        for (UUID cluster : clusters) {
            built.put(
                    cluster,
                    new ClusterTeams(
                            List.copyOf(owned.getOrDefault(cluster, List.of())),
                            List.copyOf(shared.getOrDefault(cluster, List.of()))));
        }
        Map<UUID, String> teamNames =
                teams.findAll().stream().collect(Collectors.toMap(TeamEntity::getId, TeamEntity::getName));
        return new Built(built, teamNames, System.nanoTime());
    }
}
