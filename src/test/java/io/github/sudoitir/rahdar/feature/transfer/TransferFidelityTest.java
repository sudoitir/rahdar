package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferRunView;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.ObjectMessage;
import jakarta.jms.Session;
import jakarta.jms.StreamMessage;
import jakarta.jms.TextMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;

/**
 * 5.4: what a consumer on the target reads is the message that was sent to the source, for every
 * body type and a 5 MiB large message, with its headers and properties, plus where it came from and
 * none of the source broker's bookkeeping.
 */
class TransferFidelityTest extends TransferTestSupport {

    private static final long TTL = 3_600_000;

    /** What the producer saw once each message was sent. */
    private record Sent(String messageId, long timestamp, long expiration, int priority, int deliveryMode) {}

    @Test
    void aMovedMessageArrivesAsSentWithProvenanceAndNoBookkeepingButWithoutExpiration() throws Exception {
        String src = queue(p, "fidelity.move.src");
        String dst = queue(d, "fidelity.move.dst");
        byte[] large = large();
        List<Sent> sent = send(src, large);
        List<Long> brokerIds = p.messageIds(src);

        TransferRunView run = awaitEnded(execute(preview(TransferMode.MOVE, src, all(), dst)));

        assertThat(run.state()).as(run.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.notes()).anyMatch(n -> n.contains("clears each message's expiration"));
        assertFaithful(d.drain(dst), sent, brokerIds, large, run, src);
    }

    @Test
    void aCopiedMessageArrivesAsSentIncludingItsExpiration() throws Exception {
        String src = queue(p, "fidelity.copy.src");
        String dst = queue(d, "fidelity.copy.dst");
        byte[] large = large();
        List<Sent> sent = send(src, large);
        List<Long> brokerIds = p.messageIds(src);

        TransferRunView run = awaitEnded(execute(preview(TransferMode.COPY, src, all(), dst)));

        assertThat(run.state()).as(run.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.notes()).noneMatch(n -> n.contains("expiration"));
        assertThat(p.depth(src)).isEqualTo(6);
        assertFaithful(d.drain(dst), sent, brokerIds, large, run, src);
    }

    private static byte[] large() {
        byte[] large = new byte[5 * 1024 * 1024];
        new Random(42).nextBytes(large);
        return large;
    }

    private void assertFaithful(
            List<Message> received,
            List<Sent> sent,
            List<Long> brokerIds,
            byte[] large,
            TransferRunView run,
            String src)
            throws Exception {
        assertThat(brokerIds).hasSize(6);
        List<Message> arrived = new ArrayList<>(received);
        assertThat(arrived).hasSize(6);
        arrived.sort((a, b) -> Integer.compare(seq(a), seq(b)));
        List<Long> idsBySeq = new ArrayList<>(brokerIds);
        // Higher priority first on the source too: the listing is in delivery order, seq 5 first.
        Collections.reverse(idsBySeq);

        assertThat(((TextMessage) arrived.get(0)).getText()).isEqualTo("héllo ✓ wörld");
        BytesMessage bytes = (BytesMessage) arrived.get(1);
        assertThat(bytes.getBodyLength()).isEqualTo(large.length);
        byte[] body = new byte[large.length];
        bytes.readBytes(body);
        assertThat(body).as("the large body is byte-identical").isEqualTo(large);
        MapMessage map = (MapMessage) arrived.get(2);
        assertThat(map.getInt("count")).isEqualTo(7);
        assertThat(map.getString("name")).isEqualTo("widget");
        assertThat(map.getBytes("raw")).containsExactly(1, 2, 3);
        StreamMessage stream = (StreamMessage) arrived.get(3);
        assertThat(stream.readString()).isEqualTo("first");
        assertThat(stream.readLong()).isEqualTo(Long.MAX_VALUE);
        assertThat(stream.readBoolean()).isTrue();
        assertThat(((ObjectMessage) arrived.get(4)).getObject()).isEqualTo(new ArrayList<>(List.of("a", "b")));
        assertThat(arrived.get(5).getClass().getSimpleName()).isEqualTo("ActiveMQMessage");

        boolean moved = run.mode() == TransferMode.MOVE;
        for (int i = 0; i < 6; i++) {
            Message m = arrived.get(i);
            Sent s = sent.get(i);
            assertThat(m.getJMSMessageID()).isEqualTo(s.messageId());
            assertThat(m.getJMSTimestamp()).isEqualTo(s.timestamp());
            assertThat(m.getJMSExpiration()).isEqualTo(moved ? 0 : s.expiration());
            assertThat(m.getJMSPriority()).isEqualTo(s.priority());
            // A relay commits on the target, and a transacted message is durable: a non-durable one arrives durable.
            assertThat(m.getJMSDeliveryMode()).isEqualTo(DeliveryMode.PERSISTENT);
            assertThat(m.getJMSCorrelationID()).isEqualTo("corr-" + i);
            assertThat(m.getJMSType()).isEqualTo("type-" + i);
            assertThat(m.getJMSReplyTo()).hasToString("ActiveMQQueue[replies." + sfx + "]");
            assertThat(m.getStringProperty("JMSXGroupID")).isEqualTo("group-a");
            assertThat(m.getStringProperty("_AMQ_LVQ_NAME")).isEqualTo("last-" + i);
            assertThat(m.getStringProperty("appString")).isEqualTo("value-" + i);
            assertThat(m.getLongProperty("appLong")).isEqualTo(Long.MIN_VALUE + i);
            assertThat(m.getDoubleProperty("appDouble")).isEqualTo(i + 0.5);
            assertThat(m.getBooleanProperty("appFlag")).isEqualTo(i % 2 == 0);
            assertThat(m.getShortProperty("appShort")).isEqualTo((short) i);

            assertThat(m.getStringProperty("_rahdar_transfer_run"))
                    .isEqualTo(run.id().toString());
            assertThat(m.getStringProperty("_rahdar_orig_cluster"))
                    .isEqualTo(clusterP.id().toString());
            assertThat(m.getStringProperty("_rahdar_orig_node"))
                    .isEqualTo(run.source().nodeName());
            assertThat(m.getStringProperty("_rahdar_orig_queue")).isEqualTo(src);
            assertThat(m.getLongProperty("_rahdar_orig_message_id")).isEqualTo(idsBySeq.get(i));
            for (String bookkeeping :
                    List.of("_AMQ_ORIG_ADDRESS", "_AMQ_ORIG_QUEUE", "_AMQ_ORIG_MESSAGE_ID", "_AMQ_ORIG_ROUTING_TYPE")) {
                assertThat(m.propertyExists(bookkeeping)).as(bookkeeping).isFalse();
            }
        }
    }

    private static int seq(Message m) {
        try {
            return m.getIntProperty("seq");
        } catch (JMSException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Sent> send(String queue, byte[] large) throws Exception {
        List<Sent> sent = new ArrayList<>();
        try (ActiveMQConnectionFactory factory = p.jms();
                Connection connection = factory.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            List<Message> messages = new ArrayList<>();
            messages.add(session.createTextMessage("héllo ✓ wörld"));
            BytesMessage bytes = session.createBytesMessage();
            bytes.writeBytes(large);
            messages.add(bytes);
            MapMessage map = session.createMapMessage();
            map.setInt("count", 7);
            map.setString("name", "widget");
            map.setBytes("raw", new byte[] {1, 2, 3});
            messages.add(map);
            StreamMessage stream = session.createStreamMessage();
            stream.writeString("first");
            stream.writeLong(Long.MAX_VALUE);
            stream.writeBoolean(true);
            messages.add(stream);
            messages.add(session.createObjectMessage(new ArrayList<>(List.of("a", "b"))));
            messages.add(session.createMessage());
            for (int i = 0; i < messages.size(); i++) {
                Message m = messages.get(i);
                m.setIntProperty("seq", i);
                m.setJMSCorrelationID("corr-" + i);
                m.setJMSType("type-" + i);
                m.setJMSReplyTo(session.createQueue("replies." + sfx));
                m.setStringProperty("JMSXGroupID", "group-a");
                m.setStringProperty("_AMQ_LVQ_NAME", "last-" + i);
                m.setStringProperty("appString", "value-" + i);
                m.setLongProperty("appLong", Long.MIN_VALUE + i);
                m.setDoubleProperty("appDouble", i + 0.5);
                m.setBooleanProperty("appFlag", i % 2 == 0);
                m.setShortProperty("appShort", (short) i);
                int mode = i == 3 ? DeliveryMode.NON_PERSISTENT : DeliveryMode.PERSISTENT;
                producer.send(m, mode, i + 2, TTL);
                sent.add(new Sent(m.getJMSMessageID(), m.getJMSTimestamp(), m.getJMSExpiration(), i + 2, mode));
            }
        }
        return Collections.unmodifiableList(sent);
    }
}
