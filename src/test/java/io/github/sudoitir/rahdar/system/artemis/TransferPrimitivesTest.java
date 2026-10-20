package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.openbroker.artemis.ArtemisMBeans;
import io.github.sudoitir.rahdar.platform.broker.BrokerCallGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerProperties;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionFactory;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.CoreObservations;
import io.github.sudoitir.rahdar.platform.broker.CoreRelay;
import io.github.sudoitir.rahdar.platform.broker.FrozenFilter;
import io.github.sudoitir.rahdar.platform.broker.OutboundMessages;
import io.github.sudoitir.rahdar.platform.broker.OutboundMessages.Outbound;
import io.github.sudoitir.rahdar.platform.broker.OutboundMessages.Provenance;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.activemq.artemis.api.core.ActiveMQDuplicateIdException;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.ssl.SslBundles;

/**
 * The platform primitives a cross-broker transfer is built from (ADR-0097), against a real broker:
 * the staging queue's lifecycle, the acceptance facts, the chunked frozen move, and the transacted
 * relay with its duplicate id and a large message.
 */
class TransferPrimitivesTest extends ArtemisIntegrationTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final CoreConnectionSettings SETTINGS =
            new CoreConnectionSettings(CLUSTER, BROKER_USER, BROKER_PASSWORD, null, true);

    private ArtemisNode client;
    private final MessageOperations operations = new MessageOperations();
    private final StagingQueues staging = new StagingQueues();
    private final AcceptanceProbe probe = new AcceptanceProbe();
    private CoreRelay relay;
    private String suffix;

    @BeforeEach
    void setUp() {
        client = jolokiaClient();
        relay = new CoreRelay(
                new CoreConnectionFactory(
                        new BrokerProperties(Duration.ofSeconds(5), Duration.ofSeconds(10), 2_000),
                        Mockito.mock(SslBundles.class),
                        BrokerCallGuard.OFF),
                CoreObservations.none());
        suffix = Long.toString(System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        relay.closeAll();
    }

    @Test
    void stagingIsCreatedRepeatablyListedAndDestroyedOnlyWhenEmpty() {
        UUID run = UUID.randomUUID();
        String queue = StagingQueues.queueName(run);

        staging.create(client, run);
        staging.create(client, run);
        assertThat(staging.list(client)).contains(queue);

        var settings = client.execOnBrokerParsed("getAddressSettingsAsJSON(java.lang.String)", queue);
        assertThat(settings.path("maxDeliveryAttempts").asInt()).isEqualTo(-1);
        assertThat(settings.path("redistributionDelay").asLong()).isEqualTo(-1);
        AcceptanceProbe.Facts facts = probe.read(client, queue, queue, "ANYCAST");
        assertThat(facts.queueExists()).isTrue();
        assertThat(facts.filter()).isEmpty();

        produce(queue, 1);
        assertThat(staging.destroyIfEmpty(client, queue)).isFalse();
        assertThat(staging.list(client)).contains(queue);

        drain(queue);
        assertThat(staging.destroyIfEmpty(client, queue)).isTrue();
        assertThat(staging.destroyIfEmpty(client, queue)).isTrue();
        assertThat(staging.list(client)).doesNotContain(queue);
        assertThatThrownBy(() -> staging.destroyIfEmpty(client, "orders")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptanceFactsAnswerEachOnTheirOwnAndAMissingQueueIsSaidToBeMissing() {
        String present = "facts.present." + suffix;
        createQueue(present);
        produce(present, 2);

        AcceptanceProbe.Facts facts = probe.read(client, present, present, "ANYCAST");
        assertThat(facts.queueExists()).isTrue();
        assertThat(facts.filter()).isEmpty();
        assertThat(facts.messageCount()).isEqualTo(2);
        assertThat(facts.persistentSize()).isPositive();
        assertThat(facts.consumerCount()).isZero();
        assertThat(facts.ringSize()).isEqualTo(-1);
        assertThat(facts.lastValue()).isFalse();
        assertThat(facts.idCacheSize()).isPositive();
        assertThat(facts.persistIdCache()).isNotNull();
        assertThat(facts.diskStoreUsage()).isNotNull();
        assertThat(facts.maxDiskUsage()).isNotNull();
        assertThat(facts.addressSize()).isNotNull();
        assertThat(facts.addressSettings().path("addressFullMessagePolicy").asText())
                .isNotEmpty();

        AcceptanceProbe.Facts missing =
                probe.read(client, "facts.absent." + suffix, "facts.absent." + suffix, "ANYCAST");
        assertThat(missing.queueExists()).isFalse();
        assertThat(missing.messageCount()).isNull();
        assertThat(missing.idCacheSize()).as("broker facts still answer").isPositive();
    }

    @Test
    void aFrozenSelectionMovesInChunksAndLeavesLaterMessages() {
        String source = "move.src." + suffix;
        String target = "move.dst." + suffix;
        createQueue(source);
        createQueue(target);
        produce(source, 5);
        awaitClockAdvance();
        Instant t0 = Instant.now();
        awaitClockAdvance();
        produce(source, 2);

        String frozen = FrozenFilter.compose(null, t0);
        String mbean = ArtemisMBeans.queue(client.resolveBrokerObjectName(), source, source, "ANYCAST");
        assertThat(operations.countMessages(client, mbean, frozen)).isEqualTo(5);

        assertThat(operations.moveMessages(client, mbean, 100, frozen, target, false, 3))
                .isEqualTo(3);
        assertThat(operations.moveMessages(client, mbean, 100, frozen, target, false, 3))
                .isEqualTo(2);
        assertThat(operations.moveMessages(client, mbean, 100, frozen, target, false, 3))
                .isZero();
        assertThat(operations.messageCount(client, mbean)).isEqualTo(2);
    }

    /**
     * Discovery stores a broker-advertised connector as a bare {@code host:port}. The relay must dial
     * it anyway: without a scheme the Core client fails with "Schema &lt;host&gt; not found", which is
     * what a live cross-broker move ran into.
     */
    @Test
    void theRelayDialsANodeWhoseCoreUrlHasNoScheme() throws Exception {
        String bare = coreUrl().substring("tcp://".length());
        String queue = "relay.bare." + suffix;
        createQueue(queue);

        try (CoreRelay.Session session = relay.open(CLUSTER, bare, SETTINGS)) {
            assertThat(session.browser(queue, null)).isNotNull();
        }
    }

    /** A node with no Core URL says so, rather than failing somewhere inside the client. */
    @Test
    void aNodeWithoutACoreUrlIsRefusedWithAReason() {
        assertThatThrownBy(() -> relay.open(CLUSTER, "  ", SETTINGS))
                .isInstanceOf(BrokerConnectionException.class)
                .hasMessageContaining("no Core URL");
    }

    @Test
    void theRelayCommitsTargetThenSourceAndARepeatedBatchIsRefusedAsADuplicate() throws Exception {
        String source = "relay.src." + suffix;
        String target = "relay.dst." + suffix;
        createQueue(source);
        createQueue(target);
        byte[] large = new byte[5 * 1024 * 1024];
        Arrays.fill(large, (byte) 7);
        produceTextAndLarge(source, large);

        UUID run = UUID.randomUUID();
        Provenance provenance = new Provenance(run, CLUSTER, "node-1", source, false);
        try (CoreRelay.Session from = relay.open(CLUSTER, coreUrl(), SETTINGS);
                CoreRelay.Session to = relay.open(CLUSTER, coreUrl(), SETTINGS)) {
            ClientConsumer receiver = from.receiver(source);

            // First pass: the target commits, then Rahdar "dies" before acknowledging the source.
            relayBatch(receiver, from, to, target, provenance, 2);
            to.commit();
            from.rollback();

            // Second pass: the same batch again. The target has every message already.
            assertThatThrownBy(() -> {
                        relayBatch(receiver, from, to, target, provenance, 2);
                        to.commit();
                    })
                    .as("a transaction repeating a committed duplicate id is refused as a whole")
                    .isInstanceOf(ActiveMQDuplicateIdException.class);
            to.rollback();
            from.commit();
        }

        List<Message> arrived = drain(target);
        assertThat(arrived).hasSize(2);
        assertThat(((TextMessage) arrived.get(0)).getText()).isEqualTo("hello");
        assertThat(arrived.get(0).getStringProperty("app")).isEqualTo("kept");
        assertThat(arrived.get(0).getStringProperty("_rahdar_orig_queue")).isEqualTo(source);
        assertThat(arrived.get(0).propertyExists("_AMQ_ORIG_QUEUE")).isFalse();
        BytesMessage body = (BytesMessage) arrived.get(1);
        byte[] copied = new byte[(int) body.getBodyLength()];
        body.readBytes(copied);
        assertThat(copied).isEqualTo(large);
        assertThat(drain(source)).isEmpty();
        assertThat(OutboundMessages.spoolRoot().resolve(run.toString())).isEmptyDirectory();
    }

    private static void relayBatch(
            ClientConsumer receiver,
            CoreRelay.Session from,
            CoreRelay.Session to,
            String target,
            Provenance provenance,
            int count)
            throws Exception {
        List<Outbound> sent = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                ClientMessage message = receiver.receive(5_000);
                assertThat(message).isNotNull();
                Outbound out = OutboundMessages.from(message, provenance);
                sent.add(out);
                from.acknowledge(message);
                to.send(target, target, out.message());
            }
        } finally {
            sent.forEach(Outbound::close);
        }
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

    private static void produce(String queue, int count) {
        try (var factory = jms();
                Connection connection = factory.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            for (int i = 0; i < count; i++) {
                producer.send(session.createTextMessage("m" + i));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Block until the clock has moved on, so messages produced after this call carry a later timestamp. */
    private static void awaitClockAdvance() {
        Instant until = Instant.now().plusMillis(20);
        while (Instant.now().isBefore(until)) {
            Thread.onSpinWait();
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

    private static List<Message> drain(String queue) {
        List<Message> received = new ArrayList<>();
        try (var factory = jms();
                Connection connection = factory.createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
            for (Message m; (m = consumer.receive(1_000)) != null; ) {
                if (m instanceof BytesMessage bytes) {
                    bytes.getBodyLength(); // read the large body while the session is open
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
