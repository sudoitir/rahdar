package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ConnectionCheck;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A Kafka 3.5 cluster in ZooKeeper mode is registered: its version is a lower bound with its mode, and the
 * controller quorum is unavailable with the reason, never read as healthy (ADR-0213, ADR-0215). Kafka 3.5 is the
 * Confluent Platform 7.5 release; the broker and its ZooKeeper are started by {@link ZooKeeperCluster}.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaZooKeeperRegisterRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.zookeeper.image", "confluentinc/cp-kafka:7.5.0");

    private static ZooKeeperCluster kafka;

    @Autowired
    ClusterService service;

    @Autowired
    ClusterRepository clusters;

    private final String run = "kafka-zk-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void startKafka() {
        kafka = new ZooKeeperCluster(IMAGE).start();
    }

    @AfterAll
    static void stopKafka() {
        kafka.close();
    }

    @AfterEach
    void removeTheClusters() {
        clusters.findAll().stream()
                .filter(c -> c.getName().startsWith(run))
                .forEach(c -> clusters.deleteById(c.getId()));
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
    void theClusterIsSupportedItsModeIsZooKeeperAndItsQuorumIsUnavailableWithTheReason() {
        ConnectionCheck check = service.checkConnection(request());

        assertThat(check.failure()).isNull();
        assertThat(check.supported()).isTrue();
        assertThat(check.version().mode()).isEqualTo("ZooKeeper");
        assertThat(check.version().text()).matches("(at least )?3\\..*");
        assertThat(check.quorum().available()).isFalse();
        assertThat(check.quorum().reason()).isNotBlank();
        assertThat(check.nodes())
                .singleElement()
                .satisfies(node -> assertThat(node.outcome()).isEqualTo("OK"));
    }

    @Test
    void itIsRegisteredAndItsHealthSaysItsQuorumCannotBeRead() {
        ClusterDetail detail = ((Attempt.Ok<ClusterDetail>) service.register(request())).value();

        assertThat(detail.system().id()).isEqualTo("kafka");
        assertThat(detail.health().level()).isEqualTo("OK");
        assertThat(detail.health().notes()).anyMatch(note -> note.contains("controller quorum cannot be read"));
    }
}
