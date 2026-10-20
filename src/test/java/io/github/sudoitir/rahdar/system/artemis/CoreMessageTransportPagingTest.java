package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.artemis.ArtemisMBeans;
import io.github.sudoitir.openbroker.artemis.JolokiaRequest;
import io.github.sudoitir.rahdar.platform.broker.BrokerCallGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerProperties;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionFactory;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.CoreObservations;
import io.github.sudoitir.rahdar.platform.broker.CorePool;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport.BrowseResult;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport.TransportTarget;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.platform.broker.RateLimitProperties;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import java.time.Duration;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundles;

/**
 * A queue whose address is paging, against a real broker. The JMS browser ends its
 * enumeration when the next message is not already on the client ({@code receiveImmediate}),
 * which on a paging queue happens part-way through. Measured on the dev stack, a queue of 120
 * messages read over Core came back as pages of 50, 50 and 0, or 50, 46 and 20, or a first
 * page of 100 holding 32 — each time with the broker counting 120.
 */
class CoreMessageTransportPagingTest extends ArtemisIntegrationTest {

    private static final int MESSAGES = 120;

    private CoreMessageTransport transport;
    private String queueName;
    private UUID clusterId;
    private ArtemisNode client;

    @BeforeEach
    void setUp() throws Exception {
        clusterId = UUID.randomUUID();
        queueName = "core.paging.it." + System.nanoTime();

        client = jolokiaClient();

        BrokerConnections connections = mock(BrokerConnections.class);
        ArtemisNodes artemisNodes = mock(ArtemisNodes.class);
        when(connections.coreSettingsFor(any()))
                .thenReturn(new CoreConnectionSettings(clusterId, BROKER_USER, BROKER_PASSWORD, null, true));
        when(artemisNodes.on(any(UUID.class), any(UUID.class))).thenReturn(client);

        BrokerProperties props = new BrokerProperties(Duration.ofSeconds(3), Duration.ofSeconds(10), 2_000);
        MessageOperations messageOps = new MessageOperations();
        transport = new CoreMessageTransport(
                connections,
                new CorePool(
                        new CoreConnectionFactory(props, mock(SslBundles.class), BrokerCallGuard.OFF),
                        CoreObservations.none()),
                new JolokiaMessageTransport(connections, new MessageBrowser(), messageOps, artemisNodes),
                new NodeCallLimiter(new RateLimitProperties(1_000), new SimpleMeterRegistry()),
                messageOps,
                CoreObservations.none(),
                artemisNodes);

        // 20 KiB before the address pages; 120 messages of 1 KiB page almost all of them.
        client.execOnBroker(
                "addAddressSettings(java.lang.String,java.lang.String)",
                queueName,
                "{\"maxSizeBytes\":20480,\"pageSizeBytes\":10240,\"addressFullMessagePolicy\":\"PAGE\"}");
        seed();
    }

    private void seed() throws Exception {
        var factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            var producer = session.createProducer(session.createQueue(queueName));
            String body = "x".repeat(1024);
            for (int i = 0; i < MESSAGES; i++) {
                producer.send(session.createTextMessage(i + " " + body));
            }
            session.close();
        } finally {
            factory.close();
        }
    }

    private TransportTarget target() {
        return new TransportTarget(
                clusterId, UUID.randomUUID(), queueName, queueName, "ANYCAST", jolokiaUrl(), coreUrl());
    }

    /**
     * The broker's MessageCount includes messages delivered to a consumer and not yet acked, and
     * scheduled ones; a browser sees neither. Measured against MessageCount alone, a consumed
     * queue's page read as short every time and was read a second time over Jolokia.
     */
    @Test
    void aPageMissingOnlyInFlightAndScheduledMessagesIsServedOverCore() throws Exception {
        String consumed = queueName + ".consumed";
        var factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false&consumerWindowSize=0", BROKER_USER, BROKER_PASSWORD);
        try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            Session session = conn.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            Queue queue = session.createQueue(consumed);
            var producer = session.createProducer(queue);
            for (int i = 0; i < 10; i++) {
                producer.send(session.createTextMessage("m" + i));
            }
            Message later = session.createTextMessage("scheduled");
            later.setLongProperty("_AMQ_SCHED_DELIVERY", System.currentTimeMillis() + 3_600_000);
            producer.send(later);
            conn.start();
            var consumer = session.createConsumer(queue);
            for (int i = 0; i < 3; i++) {
                assertThat(consumer.receive(5_000)).isNotNull();
            }

            BrowseResult result = transport.browse(
                    new TransportTarget(
                            clusterId, UUID.randomUUID(), consumed, consumed, "ANYCAST", jolokiaUrl(), coreUrl()),
                    1,
                    50,
                    null);

            assertThat(result.servedBy()).isEqualTo(MessageTransport.Channel.CORE);
            assertThat(result.page().messages()).hasSize(7);
            assertThat(result.page().total()).isEqualTo(11);

            BrowseResult filtered = transport.browse(
                    new TransportTarget(
                            clusterId, UUID.randomUUID(), consumed, consumed, "ANYCAST", jolokiaUrl(), coreUrl()),
                    1,
                    50,
                    "JMSPriority >= 0");
            assertThat(filtered.servedBy()).isEqualTo(MessageTransport.Channel.CORE);
            assertThat(filtered.page().messages()).hasSize(7);
        } finally {
            factory.close();
        }
    }

    @Test
    void everyPageOfAPagingQueueHoldsWhatTheBrokerCounts() {
        String address = ArtemisMBeans.address(client.resolveBrokerObjectName(), queueName);
        assertThat(client.single(JolokiaRequest.read(address, "Paging"))
                        .attribute("Paging")
                        .asBoolean())
                .as("precondition: the address is paging")
                .isTrue();

        // The shortfall depends on how far the broker has depaged when the browser asks, so
        // one read proves little; ten reads of each page, every one complete, does.
        for (int round = 0; round < 10; round++) {
            for (int page = 1; page <= 2; page++) {
                BrowseResult result = transport.browse(target(), page, 100, null);
                assertThat(result.page().total()).isEqualTo(MESSAGES);
                assertThat(result.page().messages())
                        .as("round %d, page %d", round, page)
                        .hasSize(page == 1 ? 100 : MESSAGES - 100);
            }
        }
    }
}
