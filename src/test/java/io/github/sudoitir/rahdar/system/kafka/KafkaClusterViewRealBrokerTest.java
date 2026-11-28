package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterView;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.CoReplication;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The cluster read against a real broker: three KRaft brokers sharing one topic, one of them stopped, and a ZooKeeper
 * cluster whose quorum cannot be read.
 */
@ExtendWith(AdminAuthenticationExtension.class)
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class KafkaClusterViewRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.image", "apache/kafka:4.4.0");

    private static final String ZOOKEEPER_IMAGE =
            System.getProperty("kafka.zookeeper.image", "confluentinc/cp-kafka:7.5.0");

    private static final int BROKERS = 3;

    private static final int PARTITIONS = 6;

    private static final Network network = Network.newNetwork();

    private static final List<KafkaContainer> brokers = new ArrayList<>();

    @Autowired
    KafkaClusterView clusterView;

    @Autowired
    ClusterService clusters;

    @Autowired
    ClusterRepository repository;

    @BeforeAll
    static void startBrokers() throws Exception {
        String voters = "1@kafka-1:9094,2@kafka-2:9094,3@kafka-3:9094";
        for (int id = 1; id <= BROKERS; id++) {
            brokers.add(new KafkaContainer(IMAGE)
                    .withNetwork(network)
                    .withNetworkAliases("kafka-" + id)
                    .withEnv("KAFKA_NODE_ID", Integer.toString(id))
                    .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", voters)
                    .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                    .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                    .withEnv("KAFKA_REPLICA_LAG_TIME_MAX_MS", "5000")
                    .withEnv("KAFKA_BROKER_SESSION_TIMEOUT_MS", "6000")
                    .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx384m")
                    .waitingFor(Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(3))));
        }
        ExecutorService pool = Executors.newFixedThreadPool(BROKERS);
        try {
            List<Future<?>> started = new ArrayList<>();
            for (KafkaContainer broker : brokers) {
                started.add(pool.submit(broker::start));
            }
            for (Future<?> one : started) {
                one.get(4, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @AfterAll
    static void stopBrokers() {
        brokers.forEach(KafkaContainer::stop);
        network.close();
    }

    @Test
    void sharedPairsSumToEveryPartitionAndAStoppedBrokerIsDashed() throws Exception {
        // The client stays on a broker this test does not stop, so a dead broker is a cluster fact, not a timeout.
        UUID cluster = register(
                List.of(brokers.get(0).getBootstrapServers(), brokers.get(1).getBootstrapServers()));
        try (Admin admin =
                Admin.create(Map.of("bootstrap.servers", brokers.get(0).getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("shared", PARTITIONS, (short) BROKERS)))
                    .all()
                    .get();
            await().pollInSameThread().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                ClusterView view = clusterView.read(cluster);
                long shared = view.edges().stream()
                        .mapToLong(CoReplication::sharedPartitions)
                        .sum();
                assertThat(shared).isEqualTo(PARTITIONS * combinations(BROKERS));
            });
            brokers.get(2).stop();
            await().pollInSameThread()
                    .atMost(Duration.ofMinutes(2))
                    .pollInterval(Duration.ofSeconds(2))
                    .untilAsserted(() -> {
                        ClusterView view = readRetrying(cluster);
                        assertThat(view.totals().underReplicated())
                                .as(view.notes() + " " + view.brokers())
                                .isPositive();
                        // Kafka before 4.0 does not list fenced brokers, so a stopped one is absent from
                        // the nodes; its replicas still count and its edges still dash.
                        view.brokers().stream()
                                .filter(broker -> broker.id().equals("3"))
                                .findFirst()
                                .ifPresentOrElse(
                                        down -> assertThat(down.reachable()).isFalse(),
                                        () -> assertThat(view.notes()).contains(KafkaClusterView.FENCE_GAP));
                        assertThat(view.edges().stream()
                                        .filter(edge ->
                                                edge.a().equals("3") || edge.b().equals("3")))
                                .isNotEmpty()
                                .allMatch(edge -> edge.outOfSyncReplicas() > 0);
                    });
        } finally {
            repository.deleteById(cluster);
        }
    }

    @Test
    void aZooKeeperClusterNamesTheQuorumItCannotRead() {
        try (ZooKeeperCluster kafka = new ZooKeeperCluster(ZOOKEEPER_IMAGE).start()) {
            UUID cluster = register(List.of(kafka.bootstrapServers()));
            try {
                ClusterView view = clusterView.read(cluster);
                assertThat(view.mode()).isEqualTo("ZooKeeper");
                assertThat(view.quorum().available()).isFalse();
                assertThat(view.quorum().reason()).isEqualTo(KafkaClusterView.ZOOKEEPER_QUORUM);
            } finally {
                repository.deleteById(cluster);
            }
        }
    }

    private ClusterView readRetrying(UUID cluster) {
        try {
            return clusterView.read(cluster);
        } catch (RuntimeException e) {
            fail(e.getMessage());
            throw e;
        }
    }

    private UUID register(List<String> bootstrap) {
        return ((Attempt.Ok<ClusterDetail>) clusters.register(new RegisterClusterRequest(
                        KafkaModule.SYSTEM,
                        "kafka-view-" + UUID.randomUUID().toString().substring(0, 8),
                        null,
                        null,
                        false,
                        new KafkaConnection(KafkaModule.SYSTEM, bootstrap, "PLAINTEXT", null, null, null, null))))
                .value()
                .id();
    }

    private static int combinations(int brokers) {
        return brokers * (brokers - 1) / 2;
    }
}
