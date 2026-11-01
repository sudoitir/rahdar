package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ConnectionCheck;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A Kafka cluster before 2.8 (Confluent Platform 6.0 carries Kafka 2.6) is refused: the check fails naming what it
 * found and the supported range, and nothing is stored (ADR-0219, ADR-0032). Kafka 2.8 itself is supported; see
 * {@link Kafka28RegisterRealBrokerTest}.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaBefore28RegisterRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.before28.image", "confluentinc/cp-kafka:6.0.14");

    private static ZooKeeperCluster kafka;

    @Autowired
    ClusterService service;

    @Autowired
    ClusterRepository clusters;

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
                "kafka-2-6",
                null,
                null,
                false,
                new KafkaConnection(
                        KafkaModule.SYSTEM, List.of(kafka.bootstrapServers()), "PLAINTEXT", null, null, null, null));
    }

    @Test
    void theCheckNamesTheVersionItFoundAndTheSupportedRangeAndNothingIsStored() {
        long before = clusters.count();

        ConnectionCheck check = service.checkConnection(request());

        assertThat(check.failure()).isNotNull();
        assertThat(check.failure().cause()).isEqualTo("UNSUPPORTED_VERSION");
        assertThat(check.failure().message()).contains("before 2.8").contains("[2.8.0,)");
        assertThat(check.supported()).isFalse();
        assertThatThrownBy(() -> service.register(request())).hasMessageContaining("before 2.8");
        assertThat(clusters.count()).isEqualTo(before);
    }
}
