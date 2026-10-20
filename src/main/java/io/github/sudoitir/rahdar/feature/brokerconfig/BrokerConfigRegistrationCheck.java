package io.github.sudoitir.rahdar.feature.brokerconfig;

import io.github.sudoitir.rahdar.feature.brokerconfig.web.BrokerConfigViews.RecommendationsView;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.clusters.RegistrationCheckContributor;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import io.github.sudoitir.rahdar.system.artemis.BrokerAccountRoles;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The recommended configuration a registration check previews (ADR-0068), so the recommendations
 * shown before registration are the same ones shown after it: seeded from what the checked node is
 * running, not generic.
 */
@Component
@RequiredArgsConstructor
class BrokerConfigRegistrationCheck implements RegistrationCheckContributor {

    private final BrokerConfigOperations operations;
    private final BrokerAccountRoles accountRoles;
    private final ArtemisNodes artemisNodes;

    @Override
    public String featureId() {
        return BrokerConfigModule.DESCRIPTOR.id();
    }

    @Override
    public RecommendationsView contribute(BrokerCapabilities capabilities, BrokerTarget target, String coreUsername) {
        ArtemisNode seed = artemisNodes.on(target);
        return RecommendationsView.of(
                BrokerConfigRecommendations.from(capabilities, observe(seed), accountRoles.read(seed, coreUsername)));
    }

    /**
     * A read that fails costs the seed and nothing else: the panel then says it could not read the
     * node rather than showing a replace nobody can check.
     */
    private ObservedNodeConfig observe(ArtemisNode seed) {
        try {
            return operations.read(
                    seed,
                    new UUID(0, 0),
                    "the checked node",
                    new BrokerConfigOperations.ReadScope(
                            Set.of("#"),
                            Set.of("#", "activemq.notifications"),
                            Set.of(),
                            Map.of(),
                            Set.of(),
                            List.of()));
        } catch (RuntimeException _) {
            return null;
        }
    }
}
