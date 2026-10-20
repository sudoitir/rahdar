package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.messaging.MessageAction.Delete;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
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
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rahdar's messages through the broker SDK, against a real broker: a browse returns whole bodies and
 * typed properties, text sent as bytes reads as text, a send lands with the headers the operator gave
 * under the broker's own names, and a filter the broker refuses is the caller's mistake.
 */
class BrokerMessagesTest extends ArtemisIntegrationTest {

    private static final String JSON = "{\"orderId\":\"A-1\",\"status\":\"FAILED\"}";

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private ClusterConnection connection;
    private BrokerMessages messages;
    private String queue;

    @BeforeEach
    void setUp() throws Exception {
        queue = "sdk.messages.it." + System.nanoTime();
        connection = cluster(nodeId);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(clusterId)).thenReturn(connection);
        messages = new BrokerMessages(gateway);
        seed();
    }

    @AfterEach
    void tearDown() {
        connection.close();
    }

    private void seed() throws Exception {
        var factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            var producer = session.createProducer(session.createQueue(queue));

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
        } finally {
            factory.close();
        }
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private List<BrowsedMessage> firstPage() {
        return messages.browse(clusterId, nodeId, queue, 1, 50, null).messages();
    }

    @Test
    void aBrowseReturnsWholeBodiesTypedPropertiesAndTheQueuesCount() {
        BrowsePage page = messages.browse(clusterId, nodeId, queue, 1, 50, null);

        assertThat(page.messages())
                .hasSize(4)
                .allSatisfy(m -> assertThat(m.bodyTruncated()).isFalse());
        assertThat(page.total()).isEqualTo(4L);
        BrowsedMessage text =
                page.messages().stream().filter(m -> m.type() == 3).findFirst().orElseThrow();
        assertThat(text.body()).isEqualTo("hello core");
        assertThat(text.stringProperties()).containsEntry("orderId", "A-1");
        assertThat(text.longProperties()).containsEntry("attempts", 4L);
        BrowsedMessage bytes = page.messages().stream()
                .filter(m -> m.bodyEncoding() == BodyEncoding.BASE64)
                .findFirst()
                .orElseThrow();
        assertThat(Base64.getDecoder().decode(bytes.body())).containsExactly(1, 2, 3, 4, 5);
        assertThat(bytes.stringProperties().get("big")).hasSize(4096);
    }

    @Test
    void textSentAsBytesIsReadAsTextAndKeepsItsType() throws IOException {
        List<BrowsedMessage> decoded =
                firstPage().stream().filter(m -> JSON.equals(m.body())).toList();

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
    void aLaterPageReadsOnlyThatPage() {
        assertThat(messages.browse(clusterId, nodeId, queue, 2, 3, null).messages())
                .hasSize(1);
    }

    @Test
    void aSendLandsWithTheOperatorsHeadersUnderTheBrokersOwnNames() throws Exception {
        messages.send(
                clusterId,
                nodeId,
                queue,
                SentMessages.of(
                        MessageKind.TEXT,
                        true,
                        "hi",
                        Map.of(
                                "correlationId", "corr-1",
                                "type", "order",
                                "replyTo", "replies",
                                "groupId", "g-1",
                                "groupSeq", 2),
                        Map.of("orderId", "A-2", "anInt", 7, "aLong", 8_000_000_000L, "aBool", true)));

        Message received = browseOne("A-2");
        assertThat(((TextMessage) received).getText()).isEqualTo("hi");
        assertThat(received.getJMSCorrelationID()).isEqualTo("corr-1");
        assertThat(received.getJMSType()).isEqualTo("order");
        assertThat(received.getJMSReplyTo().toString()).contains("replies");
        assertThat(received.getStringProperty("JMSXGroupID")).isEqualTo("g-1");
        assertThat(received.getIntProperty("JMSXGroupSeq")).isEqualTo(2);
        assertThat(received.getStringProperty("correlationId")).isNull();
        assertThat(received.getIntProperty("anInt")).isEqualTo(7);
        assertThat(received.getLongProperty("aLong")).isEqualTo(8_000_000_000L);
        assertThat(received.getBooleanProperty("aBool")).isTrue();
    }

    @Test
    void aBytesSendLandsAsABytesMessage() throws Exception {
        messages.send(
                clusterId,
                nodeId,
                queue,
                SentMessages.of(
                        MessageKind.BYTES,
                        true,
                        Base64.getEncoder().encodeToString(new byte[] {9, 8, 7}),
                        Map.of(),
                        Map.of("orderId", "B-1")));

        Message received = browseOne("B-1");
        assertThat(received).isInstanceOf(BytesMessage.class);
        BytesMessage bytes = (BytesMessage) received;
        byte[] body = new byte[(int) bytes.getBodyLength()];
        bytes.readBytes(body);
        assertThat(body).containsExactly(9, 8, 7);
    }

    @Test
    void countsAndActsByFilterAndById() {
        assertThat(messages.count(clusterId, nodeId, queue, "orderId = 'A-1'")).isEqualTo(1);

        long id = firstPage().getFirst().messageId();
        messages.byIds(clusterId, nodeId, queue, List.of(id), new Delete());
        assertThat(messages.byFilter(clusterId, nodeId, queue, "big IS NOT NULL", Optional.empty(), new Delete())
                        .done())
                .isEqualTo(1);

        assertThat(messages.count(clusterId, nodeId, queue, null)).isEqualTo(2);
    }

    @Test
    void aFilterTheBrokerRefusesIsTheCallersMistakeAndIsNotEchoed() {
        assertThatThrownBy(() -> messages.browse(clusterId, nodeId, queue, 1, 50, "secret = 'x' AND ((("))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid message filter.");
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
            var browser = session.createBrowser(session.createQueue(queue), "orderId = '" + orderId + "'");
            var found = browser.getEnumeration();
            return found.hasMoreElements() ? (Message) found.nextElement() : null;
        } finally {
            factory.close();
        }
    }
}
