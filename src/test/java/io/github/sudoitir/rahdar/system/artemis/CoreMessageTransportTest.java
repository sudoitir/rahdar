package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.platform.broker.BodyDecoder;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrokerCallGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerProperties;
import io.github.sudoitir.rahdar.platform.broker.BrowsePage;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionFactory;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.CoreObservations;
import io.github.sudoitir.rahdar.platform.broker.CorePool;
import io.github.sudoitir.rahdar.platform.broker.MessageKind;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport.BrowseResult;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport.Channel;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport.SendSpec;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport.TransportTarget;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.platform.broker.RateLimitProperties;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundles;

/**
 * {@link CoreMessageTransport} against a real broker (ADR-0029): a
 * {@link jakarta.jms.QueueBrowser} returns real byte bodies and typed
 * properties, does not truncate, and a page past the bounded depth falls back to
 * Jolokia.
 */
class CoreMessageTransportTest extends ArtemisIntegrationTest {

    private CoreMessageTransport transport;
    private JolokiaMessageTransport jolokiaFallback;
    private String queueName;
    private final ArtemisNodes artemisNodes = mock(ArtemisNodes.class);

    @BeforeEach
    void setUp() throws Exception {
        UUID clusterId = UUID.randomUUID();
        queueName = "core.tx.it." + System.nanoTime();

        BrokerProperties props = new BrokerProperties(Duration.ofSeconds(3), Duration.ofSeconds(10), 2_000);
        CoreConnectionFactory connectionFactory =
                new CoreConnectionFactory(props, mock(SslBundles.class), BrokerCallGuard.OFF);
        CorePool corePool = new CorePool(connectionFactory, CoreObservations.none());

        BrokerConnections connections = mock(BrokerConnections.class);
        when(connections.coreSettingsFor(any()))
                .thenReturn(new CoreConnectionSettings(clusterId, BROKER_USER, BROKER_PASSWORD, null, true));

        jolokiaFallback = mock(JolokiaMessageTransport.class);
        when(jolokiaFallback.browse(any(), any(Integer.class), any(Integer.class), any()))
                .thenReturn(new BrowseResult(new BrowsePage(List.of(), 0), Channel.JOLOKIA));

        transport = new CoreMessageTransport(
                connections,
                corePool,
                jolokiaFallback,
                new NodeCallLimiter(new RateLimitProperties(1_000), new SimpleMeterRegistry()),
                new MessageOperations(),
                CoreObservations.none(),
                artemisNodes);

        seed();
    }

    private TransportTarget target() {
        return new TransportTarget(
                UUID.randomUUID(), UUID.randomUUID(), queueName, queueName, "ANYCAST", null, coreUrl());
    }

    private void seed() throws Exception {
        var factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            conn.start();
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            var queue = session.createQueue(queueName);
            var producer = session.createProducer(queue);

            TextMessage text = session.createTextMessage("hello core");
            text.setStringProperty("orderId", "A-1");
            text.setLongProperty("attempts", 4L);
            producer.send(text);

            BytesMessage bytes = session.createBytesMessage();
            bytes.writeBytes(new byte[] {1, 2, 3, 4, 5});
            bytes.setStringProperty("big", "x".repeat(4096)); // far over the 256-byte management limit
            producer.send(bytes);

            BytesMessage json = session.createBytesMessage();
            json.writeBytes(JSON.getBytes(StandardCharsets.UTF_8));
            producer.send(json);

            BytesMessage gzipped = session.createBytesMessage();
            gzipped.writeBytes(gzip(JSON));
            producer.send(gzipped);

            session.close();
        } finally {
            factory.close();
        }
    }

    private static final String JSON = "{\"orderId\":\"A-1\",\"status\":\"FAILED\"}";

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Test
    void browseReturnsFaithfulBodiesAndTypedPropertiesWithNoTruncation() {
        BrowseResult result = transport.browse(target(), 1, 50, null);

        assertThat(result.servedBy()).isEqualTo(Channel.CORE);
        assertThat(result.page().messages()).hasSize(4);
        assertThat(result.page().messages())
                .allSatisfy(m -> assertThat(m.bodyTruncated()).isFalse());

        var textMsg = result.page().messages().stream()
                .filter(m -> m.type() == 3)
                .findFirst()
                .orElseThrow();
        assertThat(textMsg.body()).isEqualTo("hello core");
        assertThat(textMsg.stringProperties()).containsEntry("orderId", "A-1");
        assertThat(textMsg.longProperties()).containsEntry("attempts", 4L);

        var bytesMsg = result.page().messages().stream()
                .filter(m -> m.bodyEncoding() == BodyEncoding.BASE64)
                .findFirst()
                .orElseThrow();
        assertThat(Base64.getDecoder().decode(bytesMsg.body())).containsExactly(1, 2, 3, 4, 5);
        assertThat(bytesMsg.stringProperties().get("big")).hasSize(4096); // not clipped
    }

    @Test
    void textSentAsBytesIsReadAsTextAndKeepsItsType() throws IOException {
        List<BrowsedMessage> decoded = transport.browse(target(), 1, 50, null).page().messages().stream()
                .filter(m -> JSON.equals(m.body()))
                .toList();

        assertThat(decoded).hasSize(2).allSatisfy(m -> {
            assertThat(m.type()).isEqualTo(4);
            assertThat(m.bodyEncoding()).isEqualTo(BodyEncoding.TEXT);
        });
        assertThat(decoded)
                .extracting(BrowsedMessage::bodyCompression, BrowsedMessage::size)
                .containsExactlyInAnyOrder(
                        tuple(BodyDecoder.Compression.NONE, (long) JSON.length()),
                        tuple(BodyDecoder.Compression.GZIP, (long) gzip(JSON).length));
    }

    @Test
    void aPageReadsOnlyThatPageAndStatesAnUnavailableTotalRatherThanCountingTheQueue() {
        BrowseResult result = transport.browse(target(), 1, 1, null);

        assertThat(result.servedBy()).isEqualTo(Channel.CORE);
        assertThat(result.page().messages()).hasSize(1);
        // This target has no management URL, so the broker's count cannot be read: it is
        // stated as unavailable, never replaced by the number of messages Rahdar walked.
        assertThat(result.page().total()).isNull();
        assertThat(result.page().totalUnavailable()).contains("no management URL");
    }

    @Test
    void aSampleReadsAtMostItsLimit() {
        assertThat(transport.sample(target(), 1)).hasSize(1);
    }

    @Test
    void aDeepPageFallsBackToJolokia() {
        BrowseResult result = transport.browse(target(), 10, 50, null);

        assertThat(result.servedBy()).isEqualTo(Channel.JOLOKIA);
    }

    @Test
    void sendOverCorePlacesABytesMessage() {
        transport.send(
                target(),
                new SendSpec(
                        MessageKind.BYTES,
                        true,
                        Base64.getEncoder().encodeToString(new byte[] {9, 9}),
                        Map.of(),
                        Map.of()));

        BrowseResult result = transport.browse(target(), 1, 50, null);
        assertThat(result.page().messages()).hasSize(5);
    }

    @Test
    void sendOverCoreSetsTheHeadersAsRealJmsHeaders() throws Exception {
        transport.send(
                target(),
                new SendSpec(
                        MessageKind.TEXT,
                        true,
                        "hi",
                        Map.of(
                                "correlationId", "corr-1",
                                "type", "order",
                                "replyTo", "replies",
                                "groupId", "g-1",
                                "groupSeq", 2),
                        Map.of("orderId", "A-2")));

        var factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            conn.start();
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            var browser = session.createBrowser(session.createQueue(queueName), "orderId = 'A-2'");
            var received = (Message) browser.getEnumeration().nextElement();
            assertThat(received.getJMSCorrelationID()).isEqualTo("corr-1");
            assertThat(received.getJMSType()).isEqualTo("order");
            assertThat(received.getJMSReplyTo().toString()).contains("replies");
            assertThat(received.getStringProperty("JMSXGroupID")).isEqualTo("g-1");
            assertThat(received.getIntProperty("JMSXGroupSeq")).isEqualTo(2);
            assertThat(received.getStringProperty("correlationId")).isNull();
        } finally {
            factory.close();
        }
    }

    private JolokiaMessageTransport jolokiaTransport() {
        BrokerConnections connections = mock(BrokerConnections.class);
        when(artemisNodes.on(any(UUID.class), any(UUID.class))).thenReturn(jolokiaClient());
        when(connections.settingsFor(any()))
                .thenAnswer(i -> BrokerConnectionSettings.basicAuth(i.getArgument(0), BROKER_USER, BROKER_PASSWORD));
        return new JolokiaMessageTransport(connections, new MessageBrowser(), new MessageOperations(), artemisNodes);
    }

    private TransportTarget jolokiaTarget() {
        return new TransportTarget(
                UUID.randomUUID(), UUID.randomUUID(), queueName, queueName, "ANYCAST", jolokiaUrl(), coreUrl());
    }

    /** The sent message, once a browser sees it: a browser's first page can arrive after the send returns. */
    private Message browseOne(String orderId) {
        return Awaitility.await("message " + orderId + " to be browsable")
                .atMost(Duration.ofSeconds(10))
                .until(() -> tryBrowse(orderId), Objects::nonNull);
    }

    private Message tryBrowse(String orderId) throws Exception {
        var factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            conn.start();
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            var browser = session.createBrowser(session.createQueue(queueName), "orderId = '" + orderId + "'");
            var messages = browser.getEnumeration();
            return messages.hasMoreElements() ? (Message) messages.nextElement() : null;
        } finally {
            factory.close();
        }
    }

    /** The test broker has {@code ANONYMOUS_LOGIN=false}: the send goes as the cluster's broker account. */
    @Test
    void aPlainSendOverJolokiaSucceedsOnASecuredBroker() throws Exception {
        jolokiaTransport()
                .send(jolokiaTarget(), new SendSpec(MessageKind.TEXT, true, "hi", Map.of(), Map.of("orderId", "P-1")));

        var received = browseOne("P-1");
        assertThat(((TextMessage) received).getText()).isEqualTo("hi");
    }

    @Test
    void aBytesSendOverJolokiaLandsAsABytesMessage() throws Exception {
        jolokiaTransport()
                .send(
                        jolokiaTarget(),
                        new SendSpec(
                                MessageKind.BYTES,
                                true,
                                Base64.getEncoder().encodeToString(new byte[] {9, 8, 7}),
                                Map.of(),
                                Map.of("orderId", "B-1")));

        var received = browseOne("B-1");
        assertThat(received).isInstanceOf(BytesMessage.class);
        BytesMessage bytes = (BytesMessage) received;
        byte[] body = new byte[(int) bytes.getBodyLength()];
        bytes.readBytes(body);
        assertThat(body).containsExactly(9, 8, 7);
    }

    @Test
    void jolokiaHeadersLandAsRealJmsHeaders() throws Exception {
        jolokiaTransport()
                .send(
                        jolokiaTarget(),
                        new SendSpec(
                                MessageKind.TEXT,
                                true,
                                "hi",
                                Map.of(
                                        "correlationId", "corr-2",
                                        "type", "order",
                                        "replyTo", "replies",
                                        "groupId", "g-2",
                                        "groupSeq", 4),
                                Map.of("orderId", "A-3")));

        var received = browseOne("A-3");
        assertThat(received.getJMSCorrelationID()).isEqualTo("corr-2");
        assertThat(received.getJMSType()).isEqualTo("order");
        assertThat(received.getJMSReplyTo().toString()).contains("replies");
        assertThat(received.getStringProperty("JMSXGroupID")).isEqualTo("g-2");
        assertThat(received.getIntProperty("JMSXGroupSeq")).isEqualTo(4);
    }

    /** The management operation takes strings only, so over Jolokia a typed property arrives as its text. */
    @Test
    void typedPropertiesOverJolokiaArriveAsText() throws Exception {
        jolokiaTransport()
                .send(
                        jolokiaTarget(),
                        new SendSpec(
                                MessageKind.TEXT,
                                true,
                                "hi",
                                Map.of(),
                                Map.of(
                                        "orderId",
                                        "T-1",
                                        "anInt",
                                        7,
                                        "aLong",
                                        8_000_000_000L,
                                        "aDouble",
                                        1.5,
                                        "aBool",
                                        true)));

        var received = browseOne("T-1");
        assertThat(received.getStringProperty("anInt")).isEqualTo("7");
        assertThat(received.getStringProperty("aLong")).isEqualTo("8000000000");
        assertThat(received.getStringProperty("aDouble")).isEqualTo("1.5");
        assertThat(received.getStringProperty("aBool")).isEqualTo("true");
    }

    /** Over Core the same properties keep their types. */
    @Test
    void typedPropertiesOverCoreKeepTheirTypes() throws Exception {
        transport.send(
                target(),
                new SendSpec(
                        MessageKind.TEXT,
                        true,
                        "hi",
                        Map.of(),
                        Map.of("orderId", "T-2", "anInt", 7, "aLong", 8_000_000_000L, "aDouble", 1.5, "aBool", true)));

        var received = browseOne("T-2");
        assertThat(received.getIntProperty("anInt")).isEqualTo(7);
        assertThat(received.getLongProperty("aLong")).isEqualTo(8_000_000_000L);
        assertThat(received.getDoubleProperty("aDouble")).isEqualTo(1.5);
        assertThat(received.getBooleanProperty("aBool")).isTrue();
    }
}
