package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ConnectionCheck;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A Kafka 2.8 cluster (Confluent Platform 6.2) is registered: it reports no {@code metadata.version}, so the driver
 * names it from the API keys it serves, and the transaction listing it lacks is a declared capability with its
 * reason rather than a refused cluster. A cluster below 2.8 is still refused (ADR-0032); see
 * {@link KafkaBefore28RegisterRealBrokerTest}.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class Kafka28RegisterRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.28.image", "confluentinc/cp-kafka:6.2.15");

    private static ZooKeeperCluster kafka;

    @Autowired
    ClusterService service;

    private final String run = "kafka-2-8-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void startKafka() {
        kafka = new ZooKeeperCluster(IMAGE).start();
    }

    @AfterAll
    static void stopKafka() {
        kafka.close();
    }

    private RegisterClusterRequest request() {
        return new RegisterClusterRequest(
                KafkaModule.SYSTEM,
                run,
                null,
                null,
                false,
                new KafkaConnection(
                        KafkaModule.SYSTEM, List.of(kafka.bootstrapServers()), "PLAINTEXT", null, null, null, null));
    }

    @Test
    void theCheckAcceptsKafka28AndDeclaresTheTransactionListingItLacks() {
        ConnectionCheck check = service.checkConnection(request());

        assertThat(check.failure()).isNull();
        assertThat(check.supported()).isTrue();
        assertThat(check.version().mode()).isEqualTo("ZooKeeper");
        assertThat(check.version().text()).isEqualTo("at least 2.8");
        assertThat(check.version().exact()).isFalse();
        assertThat(check.access())
                .filteredOn(finding -> finding.capability().equals("kafka.transaction-listing"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.status()).isEqualTo("UNAVAILABLE");
                    assertThat(finding.reason()).contains("Kafka 3.0 or later");
                });
        assertThat(check.nodes())
                .singleElement()
                .satisfies(node -> assertThat(node.outcome()).isEqualTo("OK"));
    }
}
