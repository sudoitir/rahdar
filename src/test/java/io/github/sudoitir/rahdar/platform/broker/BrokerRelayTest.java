package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.rahdar.platform.broker.RelayLink.Batch;
import io.github.sudoitir.rahdar.platform.broker.RelayLink.Hooks;
import io.github.sudoitir.rahdar.platform.broker.RelayLink.Outcome;
import io.github.sudoitir.rahdar.platform.broker.RelayedMessages.Provenance;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ManagementResponse;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The relay through the broker SDK against a real broker (ADR-0097, transfer design D1 to D3): the
 * target commits before the source acknowledges, a batch repeated after a crash between the two
 * commits ends on the target once, a large body travels whole, and a frozen browse leaves later
 * messages behind.
 */
class BrokerRelayTest extends ArtemisIntegrationTest {

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private final BrokerRelay.Endpoint node = new BrokerRelay.Endpoint(clusterId, nodeId);
    private ClusterConnection connection;
    private BrokerRelay relay;
    private ArtemisNode client;
    private String suffix;

    @BeforeEach
    void setUp() {
        connection = cluster(nodeId);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(clusterId)).thenReturn(connection);
        relay = new BrokerRelay(gateway);
        client = jolokiaClient();
        suffix = Long.toString(System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    private RelayLink.Route staged(String source, String target, UUID run) {
        return new RelayLink.Route(
                source, true, null, Optional.empty(), target, new Provenance(run, clusterId, "node-1", source, false));
    }

    @Test
    void theTargetCommitsThenTheSourceAndALargeBodyTravelsWhole() throws Exception {
        String source = "relay.src." + suffix;
        String target = "relay.dst." + suffix;
        createQueue(source);
        createQueue(target);
        byte[] large = new byte[5 * 1024 * 1024];
        Arrays.fill(large, (byte) 7);
        produceTextAndLarge(source, large);

        try (RelayLink link = relay.link(node, node, staged(source, target, UUID.randomUUID()))) {
            Batch batch = link.relay(10, new Hooks() {});
            assertThat(batch.outcome()).isEqualTo(Outcome.RELAYED);
            assertThat(batch.delivered()).isEqualTo(2);
        }

        List<Message> arrived = drain(target);
        assertThat(arrived).hasSize(2);
        assertThat(((TextMessage) arrived.get(0)).getText()).isEqualTo("hello");
        assertThat(arrived.get(0).getStringProperty("app")).isEqualTo("kept");
        assertThat(arrived.get(0).getStringProperty(RelayedMessages.ORIG_QUEUE)).isEqualTo(source);
        assertThat(arrived.get(0).propertyExists("_AMQ_ORIG_QUEUE")).isFalse();
        BytesMessage body = (BytesMessage) arrived.get(1);
        byte[] copied = new byte[(int) body.getBodyLength()];
        body.readBytes(copied);
        assertThat(copied).isEqualTo(large);
        assertThat(drain(source)).isEmpty();
    }

    @Test
    void aBatchRepeatedAfterACrashBetweenTheCommitsEndsOnTheTargetOnce() throws Exception {
        String source = "relay.dup.src." + suffix;
        String target = "relay.dup.dst." + suffix;
        createQueue(source);
        createQueue(target);
        produce(source, 3);
        UUID run = UUID.randomUUID();

        // First pass: the target commits, then Rahdar "dies" before the source acknowledges.
        Hooks dieBetweenCommits = new Hooks() {
            @Override
            public void afterTargetCommit(List<Long> sourceIds) {
                throw new IllegalStateException("stopped between the commits");
            }
        };
        try (RelayLink link = relay.link(node, node, staged(source, target, run))) {
            assertThatThrownBy(() -> link.relay(10, dieBetweenCommits)).isInstanceOf(IllegalStateException.class);
        }

        // Second pass: the same messages again. The target refuses each as a duplicate.
        int delivered = 0;
        int duplicates = 0;
        try (RelayLink link = relay.link(node, node, staged(source, target, run))) {
            for (int pass = 0; pass < 4; pass++) {
                Batch b = link.relay(10, new Hooks() {});
                if (b.outcome() == Outcome.EMPTY) {
                    break;
                }
                delivered += b.delivered();
                duplicates += b.duplicates();
            }
        }

        assertThat(delivered).isZero();
        assertThat(duplicates).isEqualTo(3);
        assertThat(drain(target)).hasSize(3);
        assertThat(drain(source)).isEmpty();
    }

    @Test
    void aRunKilledBeforeTheTargetCommitsLeavesNothingOnTheTargetAndLosesNothing() throws Exception {
        String source = "relay.kill.src." + suffix;
        String target = "relay.kill.dst." + suffix;
        createQueue(source);
        createQueue(target);
        produce(source, 3);
        UUID run = UUID.randomUUID();

        // First pass: the batch is sent, then Rahdar "dies" before the target commits it.
        Hooks dieBeforeTargetCommit = new Hooks() {
            @Override
            public void beforeTargetCommit(List<Long> sourceIds) {
                throw new IllegalStateException("stopped in the middle of the batch");
            }
        };
        try (RelayLink link = relay.link(node, node, staged(source, target, run))) {
            assertThatThrownBy(() -> link.relay(10, dieBeforeTargetCommit)).isInstanceOf(IllegalStateException.class);
        }

        // Second pass: nothing reached the target, so every message is delivered once, none a duplicate.
        int delivered = 0;
        int duplicates = 0;
        try (RelayLink link = relay.link(node, node, staged(source, target, run))) {
            for (int pass = 0; pass < 4; pass++) {
                Batch b = link.relay(10, new Hooks() {});
                if (b.outcome() == Outcome.EMPTY) {
                    break;
                }
                delivered += b.delivered();
                duplicates += b.duplicates();
            }
        }

        assertThat(delivered).isEqualTo(3);
        assertThat(duplicates).isZero();
        assertThat(drain(target)).hasSize(3);
        assertThat(drain(source)).isEmpty();
    }

    @Test
    void aFullTargetRefusesTheBatchAndTheSourceKeepsIt() throws Exception {
        String source = "relay.full.src." + suffix;
        String target = "relay.full.dst." + suffix;
        createQueue(source);
        createQueue(target);
        ManagementResponse limited = client.execOnBroker(
                "addAddressSettings(java.lang.String,java.lang.String)",
                target,
                "{\"maxSizeBytes\":1024,\"pageSizeBytes\":512,\"addressFullMessagePolicy\":\"FAIL\"}");
        assertThat(limited.ok()).as("%s", limited).isTrue();
        try {
            // The target takes one large message while it is empty, and is full from then on.
            produceTextAndLarge(target, new byte[8192]);
            produce(source, 3);

            Batch batch;
            try (RelayLink link = relay.link(node, node, staged(source, target, UUID.randomUUID()))) {
                batch = link.relay(10, new Hooks() {});
            }

            assertThat(batch.outcome()).isEqualTo(Outcome.ADDRESS_FULL);
            assertThat(batch.delivered()).isZero();
            assertThat(drain(source)).hasSize(3);
        } finally {
            client.execOnBroker("removeAddressSettings(java.lang.String)", target);
        }
    }

    @Test
    void aFrozenBrowseCopiesWhatExistedAtTheStartAndLeavesTheSource() throws Exception {
        String source = "relay.copy.src." + suffix;
        String target = "relay.copy.dst." + suffix;
        createQueue(source);
        createQueue(target);
        produce(source, 2);
        awaitClockAdvance();
        Instant t0 = Instant.now();
        awaitClockAdvance();
        produce(source, 1);

        RelayLink.Route route = new RelayLink.Route(
                source,
                false,
                null,
                Optional.of(t0),
                target,
                new Provenance(UUID.randomUUID(), clusterId, "node-1", source, false));
        try (RelayLink link = relay.link(node, node, route)) {
            assertThat(link.relay(10, new Hooks() {}).delivered()).isEqualTo(2);
            assertThat(link.relay(10, new Hooks() {}).outcome()).isEqualTo(Outcome.EMPTY);
        }

        assertThat(drain(target)).hasSize(2);
        assertThat(drain(source)).as("a copy leaves the source as it was").hasSize(3);
    }

    // ---- helpers ------------------------------------------------------------

    private void createQueue(String queue) {
        client.execOnBroker(
                "createQueue(java.lang.String,boolean)",
                "{\"name\":\"%s\",\"address\":\"%s\",\"routing-type\":\"ANYCAST\"}".formatted(queue, queue),
                true);
    }

    private static ActiveMQConnectionFactory jms() {
        return new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
    }

    private static void produce(String queue, int count) throws Exception {
        try (var factory = jms();
                Connection connection = factory.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            for (int i = 0; i < count; i++) {
                producer.send(session.createTextMessage("m" + i));
            }
        }
    }

    private static void produceTextAndLarge(String queue, byte[] large) throws Exception {
        try (var factory = jms();
                Connection connection = factory.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            TextMessage text = session.createTextMessage("hello");
            text.setStringProperty("app", "kept");
            producer.send(text);
            BytesMessage bytes = session.createBytesMessage();
            bytes.writeBytes(large);
            producer.send(bytes);
        }
    }

    /** Block until the clock has moved on, so messages produced after this call carry a later timestamp. */
    private static void awaitClockAdvance() {
        Instant until = Instant.now().plusMillis(20);
        while (Instant.now().isBefore(until)) {
            Thread.onSpinWait();
        }
    }

    private static List<Message> drain(String queue) {
        List<Message> received = new ArrayList<>();
        try (var factory = jms();
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
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return received;
    }
}
