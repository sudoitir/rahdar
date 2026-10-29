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
 * A Kafka 2.8 cluster (Confluent Platform 6.2) is refused: the check fails naming what it found and the supported
 * range, and nothing is stored (ADR-0219).
 */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaBefore30RegisterRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.before30.image", "confluentinc/cp-kafka:6.2.15");

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
                "kafka-2-8",
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
        assertThat(check.failure().message()).contains("before 3.0").contains("[3.0.0,)");
        assertThat(check.supported()).isFalse();
        assertThatThrownBy(() -> service.register(request())).hasMessageContaining("before 3.0");
        assertThat(clusters.count()).isEqualTo(before);
    }
}
