package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Kind;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.clusters.ClusterAlreadyRegisteredException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerIdentityClaims;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.KafkaConnectionUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.RegistrationCheck;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A Kafka cluster is registered from its bootstrap servers against a real broker (ADR-0213): the check names every
 * broker and the quorum and stores nothing, the registration stores the cluster, its brokers and its connection, the
 * same cluster is registered once, and a bootstrap server nobody listens on fails the check with its cause.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaRegisterRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.image", "apache/kafka:4.0.2");

    private static KafkaContainer kafka;

    @Autowired
    ClusterService service;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    KafkaConnectionRepository connections;

    @Autowired
    BrokerIdentityClaims claims;

    @Autowired
    BrokerGateway gateway;

    @Autowired
    JdbcClient jdbc;

    private final String run = "kafka-register-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void startKafka() {
        kafka = new KafkaContainer(IMAGE);
        kafka.start();
    }

    @AfterAll
    static void stopKafka() {
        kafka.stop();
    }

    @AfterEach
    void removeTheClusters() {
        clusters.findAll().stream()
                .filter(c -> c.getName().startsWith(run))
                .forEach(c -> clusters.deleteById(c.getId()));
    }

    private RegisterClusterRequest request(String name, String bootstrap) {
        return new RegisterClusterRequest(
                KafkaModule.SYSTEM,
                name == null ? null : run + "-" + name,
                null,
                null,
                false,
                new KafkaConnection(KafkaModule.SYSTEM, List.of(bootstrap), "PLAINTEXT", null, null, null, null));
    }

    private RegistrationCheck check(RegisterClusterRequest request) {
        return (RegistrationCheck) ((Attempt.Ok<?>) service.checkConnection(request)).value();
    }

    @Test
    void theCheckNamesEveryBrokerAndTheQuorumAndStoresNothing() {
        int before = clusters.findAll().size();

        RegistrationCheck check = check(request("check", kafka.getBootstrapServers()));

        assertThat(check.failure()).isNull();
        assertThat(check.supported()).isTrue();
        assertThat(check.clusterId()).isNotBlank();
        assertThat(check.nodes()).hasSize(1);
        assertThat(check.nodes().getFirst().outcome()).isEqualTo("OK");
        assertThat(check.nodes().getFirst().functions()).contains("BROKER");
        assertThat(check.quorum().available()).isTrue();
        assertThat(check.version().mode()).isEqualTo("KRaft");
        assertThat(check.access()).isNotEmpty();
        assertThat(clusters.findAll()).hasSize(before);
    }

    @Test
    void registeringStoresTheClusterItsBrokersAndItsConnection() {
        Attempt<ClusterDetail> registered = service.register(request("store", kafka.getBootstrapServers()));

        ClusterDetail detail = ((Attempt.Ok<ClusterDetail>) registered).value();
        assertThat(detail.system().id()).isEqualTo("kafka");
        UUID id = detail.id();
        assertThat(clusters.findById(id))
                .get()
                .satisfies(c -> assertThat(c.getSystem()).isEqualTo("kafka"));
        List<BrokerNodeEntity> brokers = nodes.findByClusterIdOrderByNameAsc(id);
        assertThat(brokers).extracting(BrokerNodeEntity::getName).containsExactly("broker-1");
        assertThat(connections.findById(id)).get().satisfies(c -> {
            assertThat(c.getSecurityProtocol()).isEqualTo("PLAINTEXT");
            assertThat(c.getBootstrapServers())
                    .isEqualTo(kafka.getBootstrapServers().replace("PLAINTEXT://", ""));
        });
        assertThat(jdbc.sql("SELECT count(*) FROM broker_identity WHERE cluster_id = ? AND kind = 'CLUSTER_ID'")
                        .param(id)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void theRegisteredClusterIsReachedThroughItsOwnSystemsDriver() {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("reach", kafka.getBootstrapServers())))
                .value()
                .id();

        ClusterConnection connection = gateway.cluster(id);

        assertThat(connection.system().id()).isEqualTo("kafka");
        assertThat(connection.resources().kinds())
                .extracting(Kind::id)
                .anyMatch(k -> k.value().equals("kafka.topic"));
    }

    @Test
    void theSameClusterIsRegisteredOnce() {
        RegisterClusterRequest first = request("once", kafka.getBootstrapServers());
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(first)).value().id();

        RegistrationCheck again = check(request("once-again", kafka.getBootstrapServers()));

        assertThat(again.alreadyRegistered()).isNotNull();
        assertThat(again.alreadyRegistered().id()).isEqualTo(id);
        assertThatThrownBy(() -> service.register(request("once-again", kafka.getBootstrapServers())))
                .isInstanceOf(ClusterAlreadyRegisteredException.class);
    }

    private UpdateClusterRequest edit(KafkaConnectionUpdate connection) {
        return new UpdateClusterRequest(null, null, null, null, null, null, null, connection);
    }

    @Test
    void anEditIsCheckedWithoutStoringAndThenSaved() {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("edit", kafka.getBootstrapServers())))
                .value()
                .id();
        KafkaConnectionUpdate renamed = new KafkaConnectionUpdate(null, null, null, null, null, "rahdar-edited");

        RegistrationCheck dryRun = (RegistrationCheck) service.checkUpdate(id, edit(renamed));

        assertThat(dryRun.failure()).isNull();
        assertThat(dryRun.alreadyRegistered()).isNull();
        assertThat(connections.findById(id))
                .get()
                .satisfies(c -> assertThat(c.getClientId()).isNull());

        service.updateConnection(id, edit(renamed));

        assertThat(connections.findById(id))
                .get()
                .satisfies(c -> assertThat(c.getClientId()).isEqualTo("rahdar-edited"));
    }

    @Test
    void anEditToAServerNobodyListensOnIsRefusedAndNothingChanges() {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("moved", kafka.getBootstrapServers())))
                .value()
                .id();
        String before = connections.findById(id).orElseThrow().getBootstrapServers();

        assertThatThrownBy(() -> service.updateConnection(
                        id, edit(new KafkaConnectionUpdate(List.of("localhost:1"), null, null, null, null, null))))
                .hasMessageContaining("Check the host and port");

        assertThat(connections.findById(id).orElseThrow().getBootstrapServers()).isEqualTo(before);
    }

    @Test
    void theClusterDetailShowsHowItIsReachedAndNeverASecret() {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("detail", kafka.getBootstrapServers())))
                .value()
                .id();

        ClusterDetail detail = service.get(id);

        assertThat(detail.connection().kafka().securityProtocol()).isEqualTo("PLAINTEXT");
        assertThat(detail.connection().kafka().secretStored()).isFalse();
        assertThat(detail.capabilities().managementRead().status()).isIn("AVAILABLE", "UNKNOWN");
        assertThat(detail.health().notes()).isEmpty();
        assertThat(detail.health().level()).isEqualTo("OK");
    }

    @Test
    void aBootstrapServerNobodyListensOnFailsTheCheckWithItsCause() {
        RegistrationCheck check = check(request("refused", "localhost:1"));

        assertThat(check.failure()).isNotNull();
        assertThat(check.failure().cause()).isIn("REFUSED", "TIMEOUT");
        assertThat(check.failure().next()).isNotBlank();
        assertThatThrownBy(() -> service.register(request("refused", "localhost:1")))
                .hasMessageContaining(check.failure().next());
    }

    @Test
    @Disabled("needs OpenBroker 0.5.0, which supports Kafka 3.0 and later")
    void aKafka3ClusterIsRegistered() {
        try (KafkaContainer older = new KafkaContainer("apache/kafka:3.9.1")) {
            older.start();

            RegistrationCheck check = check(request("three", older.getBootstrapServers()));

            assertThat(check.failure()).isNull();
            assertThat(check.supported()).isTrue();
        }
    }
}
