package io.github.sudoitir.rahdar.system.kafka;

import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/** One Confluent Platform broker and its ZooKeeper, of the Confluent release {@code image} names. */
final class ZooKeeperCluster implements AutoCloseable {

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper;
    private final ZooKeeperKafkaBroker broker;

    /** @param image a {@code confluentinc/cp-kafka} image; its ZooKeeper is the {@code cp-zookeeper} of the same tag */
    ZooKeeperCluster(String image) {
        zookeeper = new GenericContainer<>(image.replace("cp-kafka", "cp-zookeeper"))
                .withNetwork(network)
                .withNetworkAliases("zookeeper")
                .withEnv("ZOOKEEPER_CLIENT_PORT", "2181")
                .withEnv("ZOOKEEPER_TICK_TIME", "2000")
                .waitingFor(Wait.forLogMessage(".*binding to port.*", 1));
        broker = new ZooKeeperKafkaBroker(image, "kafka-1")
                .withSettings(Map.of(
                        "KAFKA_BROKER_ID", "1",
                        "KAFKA_ZOOKEEPER_CONNECT", "zookeeper:2181",
                        "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1",
                        "KAFKA_HEAP_OPTS", "-Xms128m -Xmx384m"))
                .withNetwork(network)
                .withNetworkAliases("kafka-1");
    }

    ZooKeeperCluster start() {
        zookeeper.start();
        broker.start();
        return this;
    }

    String bootstrapServers() {
        return broker.getBootstrapServers();
    }

    @Override
    public void close() {
        broker.stop();
        zookeeper.stop();
        network.close();
    }
}
