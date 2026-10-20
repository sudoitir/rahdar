package io.github.sudoitir.rahdar.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigRecommendations.Recommendation;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigRecommendations.Recommendations;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigRecommendations.RolesSourceKind;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities;
import io.github.sudoitir.rahdar.platform.broker.BrokerCapabilities.CapabilityAssessment;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.BrokerAccountRoles;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Where the recommendations' account roles come from (ADR-0177): the node the
 * recommendations are seeded from, read as the cluster's Core account.
 */
class BrokerConfigRecommendationServiceTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private final ClusterService clusters = mock(ClusterService.class);
    private final BrokerConfigReads reads = mock(BrokerConfigReads.class);
    private final BrokerConnections connections = mock(BrokerConnections.class);
    private final BrokerAccountRoles accountRoles = mock(BrokerAccountRoles.class);
    private final BrokerConfigService config = mock(BrokerConfigService.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);

    private final BrokerConfigRecommendationService service =
            new BrokerConfigRecommendationService(clusters, reads, connections, accountRoles, config, access);

    private final ClusterNode node = mock(ClusterNode.class);

    private static BrokerCapabilities notificationsGap() {
        CapabilityAssessment gap = CapabilityAssessment.unavailable("not configured");
        CapabilityAssessment ok = CapabilityAssessment.available("ok");
        return new BrokerCapabilities(ok, ok, gap, ok, gap);
    }

    private static ObservedNodeConfig observed(boolean readable) {
        return new ObservedNodeConfig(
                NODE,
                "primary",
                readable,
                Map.of(),
                Map.of(),
                Map.of("#", Map.of()),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null);
    }

    @Test
    void theAccountIsReadOnTheSeedingNodeAsTheCoreUsername() {
        ArtemisNode client = mock(ArtemisNode.class);
        when(clusters.brokerCapabilities(CLUSTER)).thenReturn(notificationsGap());
        when(reads.targets(CLUSTER)).thenReturn(List.of(node));
        when(reads.observe(eq(CLUSTER), eq(node), any())).thenReturn(observed(true));
        when(reads.client(CLUSTER, node)).thenReturn(client);
        when(connections.coreSettingsFor(CLUSTER))
                .thenReturn(new CoreConnectionSettings(CLUSTER, "rahdar-core", "secret", null, true));
        when(accountRoles.read(client, "rahdar-core")).thenReturn(BrokerAccountRoles.Read.of(List.of("ops")));

        Recommendations r = service.recommend(CLUSTER);

        verify(accountRoles).read(client, "rahdar-core");
        assertThat(securityRecommendations(r))
                .allSatisfy(
                        rec -> assertThat(rec.accountRolesSource().kind()).isEqualTo(RolesSourceKind.BROKER_ACCOUNT));
    }

    @Test
    void noReadableNodeMeansTheAccountIsNotRead() {
        when(clusters.brokerCapabilities(CLUSTER)).thenReturn(notificationsGap());
        when(reads.targets(CLUSTER)).thenReturn(List.of(node));
        when(reads.observe(eq(CLUSTER), eq(node), any())).thenReturn(observed(false));

        Recommendations r = service.recommend(CLUSTER);

        verify(accountRoles, never()).read(any(), anyString());
        assertThat(securityRecommendations(r))
                .allSatisfy(rec ->
                        assertThat(rec.accountRolesSource().kind()).isNotEqualTo(RolesSourceKind.BROKER_ACCOUNT));
    }

    private static List<Recommendation> securityRecommendations(Recommendations r) {
        return r.recommendations().stream()
                .filter(rec -> rec.accountRolesSource() != null)
                .toList();
    }
}
