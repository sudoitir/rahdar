package io.github.sudoitir.rahdar.feature.rr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Destination;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TemporaryQueue;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 5 slice 0 spike (design.md D1): settles broker behaviour the
 * request-reply correlator design depends on before any product code is
 * written. Findings recorded in {@code docs/broker-management-notes.md} §13.
 *
 * <p>Not assertion-heavy by design — this test's job is to dump observed shapes
 * to the log for a human to read, the way the notification catalogue spike (Phase 4's
 * equivalent spike) does. A handful of assertions guard the properties the
 * design already committed to (§13 answers 4 and 5); the rest is captured for
 * inspection.
 */
class RequestReplySpikeIntegrationTest extends ArtemisIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RequestReplySpikeIntegrationTest.class);
    private static final String NOTIF_ADDRESS = "activemq.notifications";

    @Test
    void temporaryQueuePattern() throws Exception {
        String requestAddress = "rr.spike.temp." + System.nanoTime();
        ActiveMQConnectionFactory factory = factory();

        List<Message> notifications = new ArrayList<>();
        Thread drain = drainNotifications(factory, notifications, 6_000);

        try (Connection responderConn = factory.createConnection(BROKER_USER, BROKER_PASSWORD);
                Connection requesterConn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            responderConn.start();
            requesterConn.start();

            Session responderSession = responderConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue requestQueue = responderSession.createQueue(requestAddress);
            MessageConsumer responder = responderSession.createConsumer(requestQueue);

            Session requesterSession = requesterConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            TemporaryQueue replyQueue = requesterSession.createTemporaryQueue();
            MessageConsumer replyConsumer = requesterSession.createConsumer(replyQueue);
            MessageProducer requestProducer = requesterSession.createProducer(requestQueue);

            TextMessage request = requesterSession.createTextMessage("ping");
            request.setJMSReplyTo(replyQueue);
            request.setJMSCorrelationID("corr-temp-1");
            requestProducer.send(request);

            log.info("[spike] JMSReplyTo.toString() on the requester = {}", replyQueue);

            Message received = responder.receive(5_000);
            assertThat(received).isNotNull();
            Destination replyTo = received.getJMSReplyTo();
            log.info(
                    "[spike] browser-side getJMSReplyTo() = {} (class {})",
                    replyTo,
                    replyTo == null ? "null" : replyTo.getClass());
            log.info("[spike] browser-side getJMSCorrelationID() = {}", received.getJMSCorrelationID());
            log.info("[spike] browser-side getJMSMessageID() = {}", received.getJMSMessageID());

            Session responderReplySession = responderConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer replyProducer = responderReplySession.createProducer(replyTo);
            TextMessage reply = responderReplySession.createTextMessage("pong");
            reply.setJMSCorrelationID(received.getJMSCorrelationID());
            replyProducer.send(reply);

            Message gotReply = replyConsumer.receive(5_000);
            assertThat(gotReply).isNotNull();

            responder.close();
            replyConsumer.close();
            replyQueue.delete();
            responderReplySession.close();
            responderSession.close();
            requesterSession.close();
        }

        drain.join(TimeUnit.SECONDS.toMillis(8));
        log.info("[spike] temporaryQueuePattern: {} notifications captured", notifications.size());
        notifications.forEach(this::logNotification);

        factory.close();
    }

    @Test
    void sharedReplyQueuePattern() throws Exception {
        String requestAddress = "rr.spike.shared.req." + System.nanoTime();
        String replyAddress = "rr.spike.shared.rep." + System.nanoTime();
        ActiveMQConnectionFactory factory = factory();

        try (Connection responderConn = factory.createConnection(BROKER_USER, BROKER_PASSWORD);
                Connection requesterConn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            responderConn.start();
            requesterConn.start();

            Session responderSession = responderConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue requestQueue = responderSession.createQueue(requestAddress);
            Queue replyQueue = responderSession.createQueue(replyAddress);
            MessageConsumer responder = responderSession.createConsumer(requestQueue);
            MessageProducer replyProducer = responderSession.createProducer(replyQueue);

            Session requesterSession = requesterConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer requestProducer = requesterSession.createProducer(requestQueue);
            MessageConsumer replyConsumer = requesterSession.createConsumer(
                    requesterSession.createQueue(replyAddress), "JMSCorrelationID = 'corr-shared-1'");

            TextMessage request = requesterSession.createTextMessage("ping");
            request.setJMSCorrelationID("corr-shared-1");
            requestProducer.send(request);

            // Mid-flight browse of the request queue before the responder drains it —
            // exercise the same non-destructive browser the sampler will use.
            AtomicReference<QueueBrowser> browserRef = new AtomicReference<>();
            Session browseSession = requesterConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            QueueBrowser browser = browseSession.createBrowser(browseSession.createQueue(requestAddress));
            browserRef.set(browser);
            Enumeration<?> enumeration = browser.getEnumeration();
            while (enumeration.hasMoreElements()) {
                Message m = (Message) enumeration.nextElement();
                log.info(
                        "[spike] browsed request: correlationId={} replyTo={} messageId={} expiration={}",
                        m.getJMSCorrelationID(),
                        m.getJMSReplyTo(),
                        m.getJMSMessageID(),
                        m.getJMSExpiration());
            }
            browser.close();
            browseSession.close();

            Message received = responder.receive(5_000);
            assertThat(received).isNotNull();
            log.info(
                    "[spike] shared-pattern request: correlationId={} replyTo={} messageId={}",
                    received.getJMSCorrelationID(),
                    received.getJMSReplyTo(),
                    received.getJMSMessageID());

            // The JMS convention: echo the request's own message id into the reply's
            // correlation id, rather than the request's correlation id. Both are tried
            // by RrCorrelator's join query (design.md D3) — this spike exercises the
            // "request's own correlation id" convention since that is what the
            // requester's selector above depends on.
            TextMessage reply = responderSession.createTextMessage("pong");
            reply.setJMSCorrelationID(received.getJMSCorrelationID());
            replyProducer.send(reply);

            Message gotReply = replyConsumer.receive(5_000);
            assertThat(gotReply).isNotNull();
            assertThat(gotReply.getJMSCorrelationID()).isEqualTo("corr-shared-1");

            responder.close();
            replyConsumer.close();
            responderSession.close();
            requesterSession.close();
        }

        factory.close();
    }

    @Test
    void stuckRequestExpires() throws Exception {
        String requestAddress = "rr.spike.stuck." + System.nanoTime();
        ActiveMQConnectionFactory factory = factory();

        List<Message> notifications = new ArrayList<>();
        Thread drain = drainNotifications(factory, notifications, 6_000);

        try (Connection requesterConn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            requesterConn.start();
            Session session = requesterConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue requestQueue = session.createQueue(requestAddress);
            MessageProducer producer = session.createProducer(requestQueue);
            producer.setTimeToLive(1_000);
            producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);

            TextMessage request = session.createTextMessage("nobody home");
            request.setJMSCorrelationID("corr-stuck-1");
            producer.send(request);
            log.info("[spike] sent expiring request with no consumer, ttl=1000ms");

            session.close();
        }

        drain.join(TimeUnit.SECONDS.toMillis(8));
        log.info("[spike] stuckRequestExpires: {} notifications captured", notifications.size());
        notifications.forEach(this::logNotification);
        boolean sawExpired = notifications.stream()
                .anyMatch(m -> quietlyGet(() -> String.valueOf(m.getObjectProperty("_AMQ_NotifType")))
                        .contains("EXPIRED"));
        log.info("[spike] MESSAGE_EXPIRED notification observed = {}", sawExpired);

        try (Connection consumerConn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            consumerConn.start();
            Session session = consumerConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createQueue(requestAddress));
            assertThat(consumer.receive(1_000))
                    .as("the request expired instead of waiting")
                    .isNull();
        }

        factory.close();
    }

    // ---- helpers ------------------------------------------------------

    private ActiveMQConnectionFactory factory() {
        var factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        factory.setInitialConnectAttempts(1);
        factory.setReconnectAttempts(0);
        return factory;
    }

    private Thread drainNotifications(ActiveMQConnectionFactory factory, List<Message> sink, long millis) {
        Thread t = new Thread(() -> {
            try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
                conn.start();
                Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Topic notifications = session.createTopic(NOTIF_ADDRESS);
                MessageConsumer consumer = session.createConsumer(notifications);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
                while (System.nanoTime() < deadline) {
                    Message m = consumer.receive(250);
                    if (m != null) {
                        sink.add(m);
                    }
                }
                consumer.close();
                session.close();
            } catch (Exception e) {
                log.warn("[spike] notification drain failed", e);
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void logNotification(Message m) {
        try {
            StringBuilder sb = new StringBuilder();
            Enumeration<?> names = m.getPropertyNames();
            while (names.hasMoreElements()) {
                String name = (String) names.nextElement();
                sb.append(name).append('=').append(m.getObjectProperty(name)).append(' ');
            }
            log.info("[spike] notification: {}", sb);
        } catch (Exception e) {
            log.warn("[spike] failed to read notification properties", e);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static <T> T quietlyGet(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception _) {
            return null;
        }
    }
}
