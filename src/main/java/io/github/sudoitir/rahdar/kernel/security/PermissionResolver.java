package io.github.sudoitir.rahdar.kernel.security;

import io.github.sudoitir.rahdar.kernel.plugin.CatalogueEntry;
import io.github.sudoitir.rahdar.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.PermissionScope;
import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The {@code @perm} bean used from {@code @PreAuthorize}/{@code @PostFilter} SpEL, and the one decision
 * point for every permission check (authorization spec, ADR-0038).
 *
 * <p>What a permission needs depends on the scope its module declares for it in the catalogue
 * ({@link PermissionScope}): a {@code GLOBAL} permission takes effect only through a global grant, a
 * {@code CLUSTER} one through a global, environment or cluster grant, and a {@code RESOURCE} one through
 * those, or, for a named queue or address, through a team role in the team that owns the name or a share
 * that covers it. Grants only add. A permission that is not in the catalogue, because its module or plugin
 * is disabled or gone, grants nothing, wildcards included.
 *
 * <p>A signed-in user's grants and teams are read from their current {@link AccessSnapshot}, so a change
 * applies on their next request. An API key acts within what both its grants and its owner's current access
 * allow, teams included: it is asked what its grants say, and then what its owner holds. Any other principal
 * that carries its own grants is checked against those, and gets no team access.
 */
@Component("perm")
@PluginApi
@RequiredArgsConstructor
public class PermissionResolver {

    private final ScopeHierarchy environments;
    private final AccessLoader access;
    private final TeamIndex teams;
    private final FeatureRegistry features;

    /** Global-scope check, for operations with no cluster (settings, user admin, environment CRUD). */
    public boolean can(String permission) {
        return can(null, permission);
    }

    /**
     * Cluster-scoped check: global, or the cluster's environment, or the cluster itself. For a
     * {@code RESOURCE} permission this asks about the whole cluster and is satisfied by grants only,
     * never by a team: to ask about a queue or address, name it with {@link #can(UUID, ResourceRef, String)}.
     */
    public boolean can(UUID clusterId, String permission) {
        return can(currentPrincipal(), clusterId, permission);
    }

    /** The same check for a given principal rather than the current one. */
    public boolean can(RahdarPrincipal principal, UUID clusterId, String permission) {
        if (principal == null) {
            return false;
        }
        CatalogueEntry entry = features.permission(permission).orElse(null);
        if (entry == null) {
            return false;
        }
        if (principal instanceof TokenPrincipal token) {
            return tokenAllows(token, entry, clusterId, permission, g -> !g.limited())
                    && can(token.owner(), clusterId, permission);
        }
        return grantsAllow(principal, entry, clusterId, permission);
    }

    /**
     * Whether the current principal holds {@code action} on one queue or address of the cluster: through
     * a grant that reaches the cluster, a team role in the team that owns the name, or a share that
     * covers it.
     */
    public boolean can(UUID clusterId, ResourceRef resource, String action) {
        return can(currentPrincipal(), clusterId, resource, action);
    }

    /** The same check for a given principal rather than the current one. */
    public boolean can(RahdarPrincipal principal, UUID clusterId, ResourceRef resource, String action) {
        if (principal == null) {
            return false;
        }
        CatalogueEntry entry = features.permission(action).orElse(null);
        if (entry == null) {
            return false;
        }
        if (principal instanceof TokenPrincipal token) {
            return tokenAllows(
                            token,
                            entry,
                            clusterId,
                            action,
                            g -> !g.limited()
                                    || (g.kind() == resource.type()
                                            && g.pattern().matches(resource.name())))
                    && can(token.owner(), clusterId, resource, action);
        }
        if (grantsAllow(principal, entry, clusterId, action)) {
            return true;
        }
        if (!teamsApply(principal, entry, clusterId) || !entry.resourceTypes().contains(resource.type())) {
            return false;
        }
        AccessSnapshot snapshot = access.of(principal.userId());
        boolean owner = teams.ownerOf(clusterId, resource)
                .map(team -> snapshot.holdsInTeam(team, action))
                .orElse(false);
        return owner
                || teams.sharesCovering(clusterId, resource).stream()
                        .anyMatch(s -> snapshot.teamPermissions().containsKey(s.targetTeamId())
                                && Grant.covers(s.permissions(), action));
    }

    /**
     * Whether the current principal may hold {@code action} on some queue or address of the cluster:
     * through a grant that reaches it, or a team or share that has names there. It answers "should this
     * page or control be offered", and must never authorise a change: that is {@link #can(UUID, ResourceRef, String)}.
     */
    public boolean canAnywhere(UUID clusterId, String action) {
        return canAnywhere(currentPrincipal(), clusterId, action);
    }

    /** The same question for a given principal rather than the current one. */
    public boolean canAnywhere(RahdarPrincipal principal, UUID clusterId, String action) {
        if (principal == null) {
            return false;
        }
        CatalogueEntry entry = features.permission(action).orElse(null);
        if (entry == null) {
            return false;
        }
        if (principal instanceof TokenPrincipal token) {
            return tokenAllows(
                            token,
                            entry,
                            clusterId,
                            action,
                            g -> !g.limited() || entry.resourceTypes().contains(g.kind()))
                    && canAnywhere(token.owner(), clusterId, action);
        }
        if (grantsAllow(principal, entry, clusterId, action)) {
            return true;
        }
        if (!teamsApply(principal, entry, clusterId)) {
            return false;
        }
        AccessSnapshot snapshot = access.of(principal.userId());
        Set<UUID> member = snapshot.teamPermissions().keySet();
        return teams.ownedOn(clusterId).stream()
                        .anyMatch(o -> intersects(o.types(), entry.resourceTypes())
                                && snapshot.holdsInTeam(o.teamId(), action))
                || teams.sharedOn(clusterId).stream()
                        .anyMatch(s -> intersects(s.types(), entry.resourceTypes())
                                && member.contains(s.targetTeamId())
                                && Grant.covers(s.permissions(), action));
    }

    /**
     * Whether the current principal may see the cluster at all: they may read it, or may read some queue
     * or address on it. Seeing a cluster through a team grants nothing cluster-wide.
     */
    public boolean canSeeCluster(UUID clusterId) {
        return canSeeCluster(currentPrincipal(), clusterId);
    }

    /** The same question for a given principal rather than the current one. */
    public boolean canSeeCluster(RahdarPrincipal principal, UUID clusterId) {
        return can(principal, clusterId, Permissions.CLUSTER_READ)
                || canAnywhere(principal, clusterId, Permissions.SUBSCRIPTION_READ)
                || canAnywhere(principal, clusterId, Permissions.DESTINATION_READ);
    }

    /**
     * The queues or addresses of the cluster the current principal may read, for filtering a list before it
     * is sorted, paged and counted, and for stating what they may do with each row.
     */
    public ResourceFilter filter(UUID clusterId, ResourceType kind) {
        RahdarPrincipal principal = currentPrincipal();
        List<String> actions = features.catalogue().stream()
                .filter(e -> e.scope() == PermissionScope.RESOURCE
                        && e.resourceTypes().contains(kind))
                .map(CatalogueEntry::action)
                .sorted()
                .toList();
        Set<String> wholeCluster = principal == null
                ? Set.of()
                : actions.stream().filter(a -> can(principal, clusterId, a)).collect(Collectors.toSet());
        return new ResourceFilter(this, principal, clusterId, kind, Permissions.readOf(kind), actions, wholeCluster);
    }

    /**
     * Whether the current principal holds {@code action} on every name the pattern can match: through a
     * grant that reaches the cluster, or because one of their team's patterns or shares that carries the
     * action covers the whole pattern. A pattern only a union of several of them would cover is not
     * covered, and neither is a pattern that mixes a wildcard with other characters in a word, which only a
     * grant reaches. For a capture or a rule that watches a set of names, which must not reach names the
     * caller may not use.
     */
    public boolean canOnAll(UUID clusterId, ResourceType kind, String patternText, String action) {
        return canOnAll(currentPrincipal(), clusterId, kind, patternText, action);
    }

    private boolean canOnAll(
            RahdarPrincipal principal, UUID clusterId, ResourceType kind, String patternText, String action) {
        CatalogueEntry entry =
                principal == null ? null : features.permission(action).orElse(null);
        if (entry == null) {
            return false;
        }
        ResourcePattern pattern = parsedOrNull(patternText);
        if (principal instanceof TokenPrincipal token) {
            return tokenAllows(
                            token,
                            entry,
                            clusterId,
                            action,
                            g -> !g.limited()
                                    || (pattern != null
                                            && g.kind() == kind
                                            && ResourcePattern.covers(g.pattern(), pattern)))
                    && canOnAll(token.owner(), clusterId, kind, patternText, action);
        }
        if (grantsAllow(principal, entry, clusterId, action)) {
            return true;
        }
        if (pattern == null
                || !teamsApply(principal, entry, clusterId)
                || !entry.resourceTypes().contains(kind)) {
            return false;
        }
        AccessSnapshot snapshot = access.of(principal.userId());
        Set<UUID> member = snapshot.teamPermissions().keySet();
        return teams.ownedOn(clusterId).stream()
                        .anyMatch(o -> o.types().contains(kind)
                                && snapshot.holdsInTeam(o.teamId(), action)
                                && ResourcePattern.covers(o.pattern(), pattern))
                || teams.sharedOn(clusterId).stream()
                        .anyMatch(s -> s.types().contains(kind)
                                && member.contains(s.targetTeamId())
                                && Grant.covers(s.permissions(), action)
                                && ResourcePattern.covers(s.pattern(), pattern));
    }

    /**
     * The patterns of the cluster on which the current principal holds {@code action} through a team or a
     * share, as the text they were written in: where they may create names, for a refusal to say so.
     */
    public List<String> patternsHolding(UUID clusterId, ResourceType kind, String action) {
        RahdarPrincipal principal = currentPrincipal();
        CatalogueEntry entry =
                principal == null ? null : features.permission(action).orElse(null);
        if (entry == null
                || !teamsApply(principal, entry, clusterId)
                || !entry.resourceTypes().contains(kind)) {
            return List.of();
        }
        AccessSnapshot snapshot = access.of(principal.userId());
        Set<UUID> member = snapshot.teamPermissions().keySet();
        return Stream.concat(
                        teams.ownedOn(clusterId).stream()
                                .filter(o -> o.types().contains(kind) && snapshot.holdsInTeam(o.teamId(), action))
                                .map(o -> o.pattern().text()),
                        teams.sharedOn(clusterId).stream()
                                .filter(s -> s.types().contains(kind)
                                        && member.contains(s.targetTeamId())
                                        && Grant.covers(s.permissions(), action))
                                .map(s -> s.pattern().text()))
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The grants a principal acts on right now, for showing them: its own when it carries them, else the
     * user's current ones. An API key's are the whole-scope grants of the key that its owner also holds;
     * what a key is limited to by name is not a grant of a scope, and is left out.
     */
    public Set<Grant> grantsOf(RahdarPrincipal principal) {
        if (principal instanceof TokenPrincipal token) {
            return keyGrants(token);
        }
        return principal.pinned()
                ? principal.pinnedGrants()
                : access.of(principal.userId()).grants();
    }

    /**
     * What the key's grants and its owner's grants have in common: for each scope the key addresses that an
     * owner grant reaches, the permissions of the key that the owner holds there, and the owner's that a
     * wildcard of the key covers, so a wildcard allows whatever the owner holds that it matches.
     */
    private Set<Grant> keyGrants(TokenPrincipal token) {
        Set<Grant> owned = access.of(token.userId()).grants();
        Set<Grant> result = new HashSet<>();
        for (TokenGrant tg : token.grants()) {
            if (tg.limited()) {
                continue;
            }
            for (Grant og : owned) {
                if (!reaches(og, tg)) {
                    continue;
                }
                Set<String> shared = new HashSet<>();
                if (og.grants(tg.action())) {
                    shared.add(tg.action());
                }
                og.permissions().stream().filter(tg::covers).forEach(shared::add);
                if (!shared.isEmpty()) {
                    result.add(new Grant(tg.scopeType(), tg.scopeId(), Set.copyOf(shared)));
                }
            }
        }
        return result;
    }

    /**
     * Whether an owner grant reaches the scope a key grant addresses: global reaches everything, an
     * environment its clusters, a cluster itself. Never the other way, so a key cannot exceed its owner: a
     * cluster grant of the owner does not reach a global grant of the key.
     */
    private boolean reaches(Grant owner, TokenGrant token) {
        return switch (owner.scopeType()) {
            case GLOBAL -> true;
            case ENVIRONMENT ->
                switch (token.scopeType()) {
                    case GLOBAL -> false;
                    case ENVIRONMENT -> owner.scopeId().equals(token.scopeId());
                    case CLUSTER -> owner.scopeId().equals(environments.environmentOf(token.scopeId()));
                };
            case CLUSTER ->
                token.scopeType() == Grant.ScopeType.CLUSTER && owner.scopeId().equals(token.scopeId());
        };
    }

    /**
     * Whether one of the key's own grants allows {@code permission} on the cluster, at a scope that reaches it,
     * and is of the kind the question needs. The owner's side is asked separately.
     */
    private boolean tokenAllows(
            TokenPrincipal token, CatalogueEntry entry, UUID clusterId, String permission, Predicate<TokenGrant> fits) {
        UUID environmentId = clusterId == null || entry.scope() == PermissionScope.GLOBAL
                ? null
                : environments.environmentOf(clusterId);
        return token.grants().stream()
                .anyMatch(g -> g.covers(permission)
                        && fits.test(g)
                        && scopeMatches(g.scopeType(), g.scopeId(), entry, clusterId, environmentId));
    }

    private static boolean scopeMatches(
            Grant.ScopeType type, UUID scopeId, CatalogueEntry entry, UUID clusterId, UUID environmentId) {
        return switch (type) {
            case GLOBAL -> true;
            case ENVIRONMENT -> environmentId != null && environmentId.equals(scopeId);
            case CLUSTER -> entry.scope() != PermissionScope.GLOBAL && clusterId != null && clusterId.equals(scopeId);
        };
    }

    private boolean grantsAllow(RahdarPrincipal principal, CatalogueEntry entry, UUID clusterId, String permission) {
        UUID environmentId = clusterId == null || entry.scope() == PermissionScope.GLOBAL
                ? null
                : environments.environmentOf(clusterId);
        for (Grant grant : grantsOf(principal)) {
            if (scopeMatches(grant.scopeType(), grant.scopeId(), entry, clusterId, environmentId)
                    && grant.grants(permission)) {
                return true;
            }
        }
        return false;
    }

    private static boolean teamsApply(RahdarPrincipal principal, CatalogueEntry entry, UUID clusterId) {
        return clusterId != null && entry.scope() == PermissionScope.RESOURCE && !principal.pinned();
    }

    private static ResourcePattern parsedOrNull(String text) {
        try {
            return ResourcePattern.parse(text);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static boolean intersects(Set<ResourceType> a, Set<ResourceType> b) {
        return !Collections.disjoint(a, b);
    }

    private static RahdarPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof RahdarPrincipal p) {
            return p;
        }
        return null;
    }
}
