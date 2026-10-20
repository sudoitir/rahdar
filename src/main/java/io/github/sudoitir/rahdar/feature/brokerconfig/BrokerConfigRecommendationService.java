package io.github.sudoitir.rahdar.feature.brokerconfig;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigOperations.ReadScope;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigRecommendations.Recommendation;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigRecommendations.Recommendations;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.system.artemis.BrokerAccountRoles;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The capability probe's recommendations, and the one action that declares them
 * (ADR-0068).
 *
 * <p>Declaring is deliberately the whole of it. The recommendations become an
 * ordinary revision through {@link BrokerConfigService#save}, and applying that
 * revision is the ordinary apply with its ordinary gates — plan, hazards, canary,
 * typed confirmation. There is no shortcut that writes to a broker from here,
 * because a recommendation is a suggestion and ADR-0067 D8 only ever lets an
 * operator act.
 */
@Service
@RequiredArgsConstructor
public class BrokerConfigRecommendationService {

    private final ClusterService clusters;
    private final BrokerConfigReads reads;
    private final BrokerConnections connections;
    private final BrokerAccountRoles accountRoles;
    private final BrokerConfigService config;
    private final ClusterAccessGuard clusterAccess;

    /**
     * What the probe suggests, seeded from the first readable live node. Not transactional: the node is read
     * over the network, and no database connection is held while it is.
     */
    public Recommendations recommend(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        BrokerCapabilities capabilities = clusters.brokerCapabilities(clusterId);
        Seed seed = seed(clusterId);
        return BrokerConfigRecommendations.from(capabilities, seed.observed(), accountRoles(clusterId, seed.node()));
    }

    /**
     * Save the selected recommendations as a new revision, merged onto the current
     * declaration. Nothing is applied: the caller opens the plan next.
     *
     * @param capabilities which recommendations to take, by capability name; empty
     *     means every appliable one
     * @param roleOverrides the roles the operator chose for a security setting,
     *     keyed by match — the observed prefill is a starting point, not a decision
     *     Rahdar gets to make on its own (ADR-0068)
     */
    public BrokerConfigService.Declaration declare(
            UUID clusterId, Set<String> capabilities, Map<String, Set<String>> roleOverrides) {
        clusterAccess.requireCluster(clusterId, BrokerConfigPermissions.CONFIG_WRITE);
        Recommendations all = recommend(clusterId);
        List<Recommendation> selected = all.recommendations().stream()
                .filter(Recommendation::appliable)
                .filter(r -> capabilities == null || capabilities.isEmpty() || capabilities.contains(r.capability()))
                .map(r -> withRoles(r, roleOverrides))
                .toList();
        if (selected.isEmpty()) {
            throw new ConflictException(
                    "nothing-to-declare",
                    "None of this cluster's capability gaps can be closed from the declaration."
                            + " The remaining ones need a broker.xml edit and a restart.");
        }
        for (Recommendation r : selected) {
            if (r.section() == BrokerConfigRecommendations.Section.SECURITY_SETTING
                    && r.roles().values().stream().allMatch(Set::isEmpty)) {
                throw new ConflictException(
                        "roles-required",
                        "The security setting for " + r.match() + " would grant nobody anything."
                                + " Name at least one role before declaring it.");
            }
        }

        var current = config.current(clusterId);
        BrokerConfigDocument base =
                current.map(BrokerConfigService.CurrentRevision::document).orElseGet(BrokerConfigDocument::empty);
        return config.save(
                clusterId,
                BrokerConfigRecommendations.merge(base, selected),
                current.map(BrokerConfigService.CurrentRevision::revision).orElse(null),
                "Recommended configuration from the capability probe: "
                        + selected.stream().map(Recommendation::title).collect(Collectors.joining("; ")),
                BrokerConfigService.Source.RECOMMENDED,
                null);
    }

    private static Recommendation withRoles(Recommendation r, Map<String, Set<String>> overrides) {
        Set<String> chosen = overrides == null ? null : overrides.get(r.match());
        if (chosen == null || r.section() != BrokerConfigRecommendations.Section.SECURITY_SETTING) {
            return r;
        }
        Map<PermissionType, Set<String>> roles =
                r.roles().keySet().stream().collect(Collectors.toMap(t -> t, t -> Set.copyOf(chosen)));
        return new Recommendation(
                r.capability(),
                r.title(),
                r.rationale(),
                r.appliable(),
                r.section(),
                r.match(),
                r.values(),
                roles,
                r.accountRoles(),
                r.accountRolesSource(),
                r.keys(),
                r.manualSnippet());
    }

    /**
     * The roles of the account Rahdar connects to Core with, read once from the node the
     * recommendations are seeded from (ADR-0177).
     */
    private BrokerAccountRoles.Read accountRoles(UUID clusterId, ClusterNode node) {
        if (node == null) {
            return BrokerAccountRoles.Read.unreadable("no node of this cluster could be read");
        }
        return accountRoles.read(
                reads.client(clusterId, node),
                connections.coreSettingsFor(clusterId).username());
    }

    /** The seeding node and what it reported; both null when no node could be read. */
    private record Seed(ObservedNodeConfig observed, ClusterNode node) {}

    /**
     * The node the recommendations are seeded from: the same one the probe assessed,
     * read at the two matches a recommendation can touch. An unreadable node yields
     * an unseeded set, which the view reports rather than hiding.
     */
    private Seed seed(UUID clusterId) {
        ReadScope scope = new ReadScope(
                Set.of("#"), Set.of("#", "activemq.notifications"), Set.of(), Map.of(), Set.of(), List.of());
        for (ClusterNode node : reads.targets(clusterId)) {
            ObservedNodeConfig observed = reads.observe(clusterId, node, scope);
            if (observed.readable()) {
                return new Seed(observed, node);
            }
        }
        return new Seed(null, null);
    }
}
