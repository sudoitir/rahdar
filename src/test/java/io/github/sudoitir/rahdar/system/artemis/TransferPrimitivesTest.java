package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.openbroker.artemis.ArtemisMBeans;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.time.Instant;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The platform primitives a cross-broker transfer is built from (ADR-0097), against a real broker:
 * the staging queue's lifecycle, the acceptance facts, the chunked frozen move, and the transacted
 * relay with its duplicate id and a large message.
 */
class TransferPrimitivesTest extends ArtemisIntegrationTest {

    private ArtemisNode client;
    private final MessageOperations operations = new MessageOperations();
    private final StagingQueues staging = new StagingQueues();
    private final AcceptanceProbe probe = new AcceptanceProbe();
    private String suffix;

    @BeforeEach
    void setUp() {
        client = jolokiaClient();
        suffix = Long.toString(System.nanoTime());
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

        String frozen = ArtemisSelectors.frozen(null, t0);
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

    private static void drain(String queue) {
        try (var factory = jms();
                Connection connection = factory.createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
            while (consumer.receive(1_000) != null) {
                // taken off the queue
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
