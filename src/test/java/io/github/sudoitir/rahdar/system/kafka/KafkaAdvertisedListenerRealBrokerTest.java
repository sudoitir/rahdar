package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.command.InspectContainerResponse;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ConnectionCheck;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.lifecycle.Startables;

/**
 * The commonest way a Kafka registration goes wrong: the bootstrap server answers, but a broker advertises an address
 * Rahdar cannot reach (ADR-0219). With one broker the check fails and says so; with two, it reaches the first and
 * names the second with the address it advertises and why it cannot be reached there.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaAdvertisedListenerRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.image", "apache/kafka:4.4.0");
    private static final String UNREACHABLE = "nowhere.invalid:9092";
    private static final String HEAP = "-Xms128m -Xmx384m";

    @Autowired
    ClusterService service;

    private ConnectionCheck check(String bootstrap) {
        return service.checkConnection(new RegisterClusterRequest(
                KafkaModule.SYSTEM,
                "advertised-listener",
                null,
                null,
                false,
                new KafkaConnection(KafkaModule.SYSTEM, List.of(bootstrap), "PLAINTEXT", null, null, null, null)));
    }

    private static Map<String, String> brokerEnvironment(int id, String voters) {
        return Map.of(
                "KAFKA_NODE_ID",
                String.valueOf(id),
                "KAFKA_CONTROLLER_QUORUM_VOTERS",
                voters,
                "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR",
                "1",
                "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR",
                "1",
                "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR",
                "1",
                "KAFKA_HEAP_OPTS",
                HEAP);
    }

    @Test
    void aLoneBrokerAdvertisingAnUnreachableAddressFailsTheCheckWithTheHint() {
        try (GenericContainer<?> lone = new GenericContainer<>(IMAGE)
                .withExposedPorts(9092)
                .withEnv(brokerEnvironment(1, "1@localhost:9093"))
                .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
                .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://" + UNREACHABLE)
                .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
                .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
                .waitingFor(Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)))) {
            lone.start();

            ConnectionCheck check = check(lone.getHost() + ":" + lone.getMappedPort(9092));

            assertThat(check.failure()).isNotNull();
            assertThat(check.failure().cause()).isEqualTo("ADVERTISED_UNREACHABLE");
            assertThat(check.failure().next())
                    .contains("bootstrap server answered")
                    .contains("advertised.listeners");
        }
    }

    @Test
    void theSecondBrokerIsNamedWithTheAddressItAdvertisesWhileTheFirstIsReached() {
        String voters = "1@kafka-1:9094,2@kafka-2:9094";
        try (Network network = Network.newNetwork();
                Broker first = new Broker(IMAGE, 1, voters, null).withNetwork(network);
                Broker second = new Broker(IMAGE, 2, voters, UNREACHABLE).withNetwork(network)) {
            Startables.deepStart(first, second).join();

            ConnectionCheck check = check(first.bootstrap());

            assertThat(check.nodes())
                    .extracting(n -> n.id() + ":" + n.outcome())
                    .containsExactlyInAnyOrder("1:OK", "2:UNREACHABLE");
            assertThat(check.nodes().stream()
                            .filter(n -> n.id().equals("2"))
                            .findFirst()
                            .orElseThrow())
                    .satisfies(node -> {
                        assertThat(node.address()).isEqualTo(UNREACHABLE);
                        assertThat(node.cause()).isEqualTo("DNS");
                    });
        }
    }

    /**
     * One KRaft broker of a two-broker cluster. Its client listener advertises {@code advertised}, or the address
     * Docker maps for it when that is null, which is known only once the container exists, so the advertised
     * listeners are written by a start script the container waits for.
     */
    private static final class Broker extends GenericContainer<Broker> {

        private static final String SCRIPT = "/tmp/rahdar-start.sh";

        private final int id;
        private final String advertised;

        Broker(String image, int id, String voters, String advertised) {
            super(image);
            this.id = id;
            this.advertised = advertised;
            withNetworkAliases("kafka-" + id);
            withCreateContainerCmdModifier(cmd -> cmd.withHostName("kafka-" + id));
            withExposedPorts(9092);
            withCommand("sh", "-c", "while [ ! -f " + SCRIPT + " ]; do sleep 0.1; done; sh " + SCRIPT);
            withEnv(brokerEnvironment(id, voters));
            withEnv("KAFKA_PROCESS_ROLES", "broker,controller");
            withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,BROKER://:9093,CONTROLLER://:9094");
            withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER");
            withEnv(
                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                    "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,BROKER:PLAINTEXT");
            withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "BROKER");
            waitingFor(Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(2)));
        }

        String bootstrap() {
            return getHost() + ":" + getMappedPort(9092);
        }

        @Override
        protected void containerIsStarting(InspectContainerResponse containerInfo) {
            String client = advertised != null ? advertised : bootstrap();
            copyFileToContainer(
                    Transferable.of(
                            "#!/bin/sh\nexport KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://" + client + ",BROKER://kafka-"
                                    + id + ":9093\nexec /etc/kafka/docker/run\n",
                            0777),
                    SCRIPT);
        }
    }
}
