package io.github.sudoitir.rahdar.support;

import static io.github.sudoitir.rahdar.support.ArtemisIntegrationTest.BROKER_PASSWORD;
import static io.github.sudoitir.rahdar.support.ArtemisIntegrationTest.BROKER_USER;

import io.github.sudoitir.rahdar.platform.broker.BrokerMBeans;
import io.github.sudoitir.rahdar.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.rahdar.platform.broker.JolokiaRequest;
import io.github.sudoitir.rahdar.support.wire.WireRecorder;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;

/**
 * The brokers a cross-broker transfer is tested between (cross-broker-message-transfer 5.1), and
 * the Core client helpers that put messages on them and take them off.
 *
 * <ul>
 *   <li>{@link #primary()}: the suite's shared broker ({@link ArtemisIntegrationTest}).
 *   <li>{@link #second()}: a second, independent broker, the dev-compose {@code secondary} config
 *       with its disk check off; registered as a second cluster.
 *   <li>{@link #clusterPair()}: two live nodes of one symmetric cluster, from the test {@code
 *       artemis/cluster-node-*.xml} pair.
 * </ul>
 *
 * Each is started on first use and kept for the JVM, as {@link ArtemisIntegrationTest} is.
 */
public final class ArtemisBrokers {

    private static final String OVERRIDE = "/var/lib/artemis-instance/etc-override/broker.xml";

    private ArtemisBrokers() {}

    /** One running broker: its Core and Jolokia endpoints as mapped on this host. */
    public record Broker(GenericContainer<?> container) {

        public String coreUrl() {
            return "tcp://%s:%d".formatted(container.getHost(), container.getMappedPort(61616));
        }

        /** The URL to hand to Rahdar: through the {@link WireRecorder}'s proxy when recording. */
        public String jolokiaUrl() {
            return WireRecorder.proxied(directJolokiaUrl());
        }

        private String directJolokiaUrl() {
            return "http://%s:%d/console/jolokia".formatted(container.getHost(), container.getMappedPort(8161));
        }

        /** A Jolokia client of the test's own, outside Rahdar's rate limiter and the recording. */
        public JolokiaBrokerClient jolokia() {
            return ArtemisIntegrationTest.jolokiaClient(directJolokiaUrl());
        }

        /** An anycast queue on its own address, with an optional filter; kept if it exists. */
        public void createQueue(String queue, String filter) {
            String json = filter == null
                    ? "{\"name\":\"%s\",\"address\":\"%s\",\"routing-type\":\"ANYCAST\"}".formatted(queue, queue)
                    : "{\"name\":\"%s\",\"address\":\"%s\",\"routing-type\":\"ANYCAST\",\"filter-string\":\"%s\"}"
                            .formatted(queue, queue, filter);
            var response = jolokia().execOnBroker("createQueue(java.lang.String,boolean)", json, true);
            if (!response.ok()) {
                throw new IllegalStateException("createQueue refused: " + response.error());
            }
        }

        public void createQueue(String queue) {
            createQueue(queue, null);
        }

        /** Drop the queue and its messages, and its address. Quiet when either is gone. */
        public void destroyQueue(String queue) {
            JolokiaBrokerClient client = jolokia();
            try {
                client.execOnBroker("destroyQueue(java.lang.String,boolean)", queue, true);
            } catch (RuntimeException _) {
                // already gone
            }
            try {
                client.execOnBroker("deleteAddress(java.lang.String,boolean)", queue, true);
            } catch (RuntimeException _) {
                // already gone
            }
        }

        /** Address settings for exactly this address, as {@code addAddressSettings} JSON (camelCase keys). */
        public void addressSettings(String address, String json) {
            var response =
                    jolokia().execOnBroker("addAddressSettings(java.lang.String,java.lang.String)", address, json);
            if (!response.ok()) {
                throw new IllegalStateException("addAddressSettings refused: " + response.error());
            }
        }

        public void removeAddressSettings(String address) {
            try {
                jolokia().execOnBroker("removeAddressSettings(java.lang.String)", address);
            } catch (RuntimeException _) {
                // none
            }
        }

        private String queueMbean(JolokiaBrokerClient client, String queue) {
            return BrokerMBeans.queue(client.resolveBrokerObjectName(), queue, queue, "ANYCAST");
        }

        public boolean queueExists(String queue) {
            JolokiaBrokerClient client = jolokia();
            return !client.search(BrokerMBeans.queuePattern(client.resolveBrokerObjectName(), queue))
                    .isEmpty();
        }

        /** Messages on the queue, including any in delivery. */
        public long depth(String queue) {
            JolokiaBrokerClient client = jolokia();
            JsonNode value =
                    client.parsed(client.single(JolokiaRequest.read(queueMbean(client, queue), "MessageCount")));
            return value.isObject() ? value.path("MessageCount").asLong() : value.asLong();
        }

        /** The messages on the queue as the broker lists them, in queue order, without consuming any. */
        public List<JsonNode> list(String queue) {
            JolokiaBrokerClient client = jolokia();
            JsonNode listed = client.parsed(client.single(
                    JolokiaRequest.exec(queueMbean(client, queue), "listMessages(java.lang.String)", "")));
            List<JsonNode> messages = new ArrayList<>();
            listed.forEach(messages::add);
            return messages;
        }

        /** The broker's message ids on the queue, in queue order. */
        public List<Long> messageIds(String queue) {
            return list(queue).stream().map(m -> m.path("messageID").asLong()).toList();
        }

        public ActiveMQConnectionFactory jms() {
            ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(
                    coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
            factory.setDeserializationAllowList("java.lang,java.util");
            return factory;
        }

        /** Send {@code count} messages built by {@code build}, committing every thousand. */
        public List<Message> produce(String queue, int count, MessageBuilder build) {
            List<Message> sent = new ArrayList<>();
            try (ActiveMQConnectionFactory factory = jms();
                    Connection connection = factory.createConnection()) {
                Session session = connection.createSession(true, Session.SESSION_TRANSACTED);
                MessageProducer producer = session.createProducer(session.createQueue(queue));
                for (int i = 0; i < count; i++) {
                    Message m = build.build(session, i);
                    producer.send(m);
                    if (count <= 1_000) {
                        sent.add(m);
                    }
                    if ((i + 1) % 1_000 == 0) {
                        session.commit();
                    }
                }
                session.commit();
            } catch (JMSException e) {
                throw new IllegalStateException(e);
            }
            return sent;
        }

        /** {@code count} text messages {@code m0..}, each with an int property {@code seq}. */
        public List<Message> produce(String queue, int count) {
            return produce(queue, count, (session, i) -> {
                Message m = session.createTextMessage("m" + i);
                m.setIntProperty("seq", i);
                return m;
            });
        }

        /** Take every message off the queue, reading each large body while the session is open. */
        public List<Message> drain(String queue) {
            List<Message> received = new ArrayList<>();
            try (ActiveMQConnectionFactory factory = jms();
                    Connection connection = factory.createConnection()) {
                connection.start();
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
                for (Message m; (m = consumer.receive(1_000)) != null; ) {
                    if (m instanceof BytesMessage bytes) {
                        byte[] all = new byte[(int) bytes.getBodyLength()];
                        bytes.readBytes(all);
                        bytes.reset();
                    }
                    received.add(m);
                }
            } catch (JMSException e) {
                throw new IllegalStateException(e);
            }
            return received;
        }

        /** Stop the container's processes, as a node that stops answering. */
        public void pause() {
            container
                    .getDockerClient()
                    .pauseContainerCmd(container.getContainerId())
                    .exec();
        }

        public void unpause() {
            container
                    .getDockerClient()
                    .unpauseContainerCmd(container.getContainerId())
                    .exec();
        }
    }

    @FunctionalInterface
    public interface MessageBuilder {
        Message build(Session session, int i) throws JMSException;
    }

    public static Broker primary() {
        return new Broker(ArtemisIntegrationTest.ARTEMIS);
    }

    public static Broker second() {
        return Second.BROKER;
    }

    /** Node 1 and node 2 of one live-live cluster. */
    public static List<Broker> clusterPair() {
        return Pair.NODES;
    }

    private static final class Second {
        static final Broker BROKER = start(withBrokerXml(
                container(),
                read("deploy/compose/dev/artemis/secondary/broker.xml")
                        // The disk check is not under test, and a nearly full developer disk
                        // would make every run wait for capacity.
                        .replace("<max-disk-usage>98</max-disk-usage>", "<max-disk-usage>-1</max-disk-usage>")));
    }

    private static final class Pair {
        static final List<Broker> NODES;

        static {
            Network network = Network.newNetwork();
            NODES = List.of(node(network, 1), node(network, 2));
        }

        private static Broker node(Network network, int n) {
            GenericContainer<?> c = container()
                    .withNetwork(network)
                    .withNetworkAliases("node-" + n)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("artemis/cluster-node-" + n + ".xml", 0644), OVERRIDE);
            return start(c);
        }
    }

    private static GenericContainer<?> container() {
        return new GenericContainer<>(ArtemisIntegrationTest.IMAGE)
                .withEnv("ARTEMIS_USER", BROKER_USER)
                .withEnv("ARTEMIS_PASSWORD", BROKER_PASSWORD)
                .withEnv("ANONYMOUS_LOGIN", "false")
                .withExposedPorts(61616, 8161)
                .waitingFor(Wait.forLogMessage(".*Artemis Console available.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(120)));
    }

    private static GenericContainer<?> withBrokerXml(GenericContainer<?> c, String xml) {
        try {
            Path file = Files.createTempFile("broker", ".xml");
            Files.writeString(file, xml);
            file.toFile().deleteOnExit();
            return c.withCopyFileToContainer(MountableFile.forHostPath(file, 0644), OVERRIDE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Broker start(GenericContainer<?> c) {
        c.start();
        return new Broker(c);
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
