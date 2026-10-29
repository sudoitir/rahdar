package io.github.sudoitir.rahdar.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Registrations;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Adoption as a step of registration, against a real Artemis: with the offer on the cluster and its
 * first revision are saved together, and with it off no revision exists (ADR-0176).
 */
@ExtendWith(AdminAuthenticationExtension.class)
class RegisterAdoptRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    ClusterService clusterService;

    @Autowired
    BrokerConfigService configs;

    private UUID registered;

    /** The brokers belong to one cluster at a time, so each test gives them back. */
    @AfterEach
    void removeTheCluster() {
        clusterService.delete(registered);
    }

    private UUID register(boolean adopt) {
        var attempt = clusterService.register(Registrations.artemis(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "adopt-real-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                new RegisterClusterRequest.Credentials(
                        ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                null,
                null,
                null,
                null,
                adopt));
        if (!(attempt instanceof Attempt.Ok<ClusterDetail> ok)) {
            throw new IllegalStateException("could not register the container broker: " + attempt);
        }
        registered = ok.value().id();
        return registered;
    }

    @Test
    void aClusterRegisteredWithTheAdoptionOnStartsWithAnAdoptedRevisionOne() {
        UUID clusterId = register(true);

        BrokerConfigService.Declaration declaration = configs.get(clusterId);

        assertThat(declaration.declared()).isTrue();
        assertThat(declaration.revision()).isEqualTo(1);
        assertThat(declaration.source()).isEqualTo(BrokerConfigService.Source.ADOPT);
        assertThat(declaration.updatedBy()).isEqualTo("test-admin");
        assertThat(declaration.document().addressSettings()).isNotEmpty();
    }

    @Test
    void aClusterRegisteredWithTheAdoptionOffHasNoDeclaration() {
        UUID clusterId = register(false);

        assertThat(configs.get(clusterId).declared()).isFalse();
    }
}
