package io.github.sudoitir.rahdar.system.kafka;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * A Confluent Platform broker that keeps its metadata in ZooKeeper, which Testcontainers' Kafka containers do not
 * run (they need KRaft). Clients outside Docker reach it on a mapped port known only once the container exists, so
 * its advertised listeners are written by a start script the container waits for.
 */
final class ZooKeeperKafkaBroker extends GenericContainer<ZooKeeperKafkaBroker> {

    private static final int EXTERNAL_PORT = 9093;
    private static final int INTERNAL_PORT = 9092;
    private static final String START_SCRIPT = "/tmp/rahdar-start.sh";

    private final String host;

    ZooKeeperKafkaBroker(String image, String host) {
        super(image);
        this.host = host;
        withExposedPorts(EXTERNAL_PORT);
        withCommand("sh", "-c", "while [ ! -f " + START_SCRIPT + " ]; do sleep 0.1; done; sh " + START_SCRIPT);
        withEnv("KAFKA_LISTENERS", "EXTERNAL://0.0.0.0:" + EXTERNAL_PORT + ",INTERNAL://0.0.0.0:" + INTERNAL_PORT);
        withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "EXTERNAL:PLAINTEXT,INTERNAL:PLAINTEXT");
        withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL");
        waitingFor(Wait.forLogMessage(".*\\[KafkaServer id=\\d+\\] started.*", 1));
    }

    ZooKeeperKafkaBroker withSettings(Map<String, String> settings) {
        settings.forEach(this::withEnv);
        return this;
    }

    /** The address a client outside Docker bootstraps from. */
    String getBootstrapServers() {
        return getHost() + ":" + getMappedPort(EXTERNAL_PORT);
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo) {
        String advertised = "EXTERNAL://" + getBootstrapServers() + ",INTERNAL://" + host + ":" + INTERNAL_PORT;
        copyFileToContainer(
                Transferable.of(
                        "#!/bin/sh\nexport KAFKA_ADVERTISED_LISTENERS=" + advertised
                                + "\nexec /etc/confluent/docker/run\n",
                        0777),
                START_SCRIPT);
    }
}
