package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.openbroker.artemis.ArtemisMBeans;
import io.github.sudoitir.rahdar.platform.broker.BodyDecoder;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrowsePage;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.CoreObservations;
import io.github.sudoitir.rahdar.platform.broker.CorePool;
import io.github.sudoitir.rahdar.platform.broker.CorePool.PooledSession;
import io.github.sudoitir.rahdar.platform.broker.CoreUrl;
import io.github.sudoitir.rahdar.platform.broker.MessageKind;
import io.github.sudoitir.rahdar.platform.broker.MessageTransport;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.platform.broker.QueueDepth;
import jakarta.jms.BytesMessage;
import jakarta.jms.DeliveryMode;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.QueueBrowser;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQMessage;
import org.springframework.stereotype.Component;

/**
 * {@link MessageTransport} over the Core protocol client (ADR-0029). A
 * {@link QueueBrowser} is a non-destructive read of the real messages: real
 * property types, real byte bodies, no management-layer stringification, no
 * truncation.
 *
 * <ul>
 *   <li>A {@code QueueBrowser} has no server-side offset, so a page past a bounded
 *       depth ({@code MessageBrowser.BROKER_PAGE_CAP}) is served over Jolokia
 *       instead, and {@link BrowseResult#servedBy()} says so (non-negotiable #1).
 *   <li>A page shorter than the broker says a browser can see is read again over
 *       Jolokia: on a paging queue the browser's enumeration ends part-way through.
 *       Delivered-unacked and scheduled messages are counted by the broker but never
 *       browsed, so they do not make a page short.
 *   <li>By-id / by-filter mutations carry no payload and stay on Jolokia
 *       ({@code MessageOperations}) — no Core method here (ADR-0029, D9).
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoreMessageTransport implements MessageTransport {

    private static final int MAX_CORE_DEPTH = MessageBrowser.BROKER_PAGE_CAP;

    private final BrokerConnections connections;
    private final CorePool corePool;
    private final JolokiaMessageTransport jolokiaFallback;
    private final NodeCallLimiter limiter;
    private final MessageOperations messageOps;
    private final CoreObservations observations;
    private final ArtemisNodes artemisNodes;

    /**
     * Up to {@code limit} messages from the head of a queue, and no more — never counted, and
     * not charged to the Jolokia ceiling. The request-reply sampler's read: bounded by its size,
     * so a backed-up request queue costs the same to sample as an empty one.
     */
    public List<BrowsedMessage> sample(TransportTarget target, int limit) {
        try {
            return observations.observe("sample", target.coreUrl(), () -> {
                try (PooledSession jms = open(target.clusterId(), target.coreUrl())) {
                    Session session = jms.session();
                    QueueBrowser browser = session.createBrowser(session.createQueue(target.queueName()));
                    List<BrowsedMessage> rows = new ArrayList<>(limit);
                    Enumeration<?> e = browser.getEnumeration();
                    while (rows.size() < limit && e.hasMoreElements()) {
                        rows.add(toBrowsed((Message) e.nextElement()));
                    }
                    browser.close();
                    return List.copyOf(rows);
                }
            });
        } catch (JMSException ex) {
            log.debug("Core sample of {} failed, falling back to Jolokia: {}", target.queueName(), ex.getMessage());
            return jolokiaFallback.browse(target, 1, limit, null).page().messages();
        }
    }

    /** The node's limiter key: its Jolokia URL, so Core and Jolokia calls to a node share one ceiling. */
    private static String permitKey(TransportTarget target) {
        return target.jolokiaUrl() != null ? target.jolokiaUrl() : target.coreUrl();
    }

    @Override
    public Channel channel() {
        return Channel.CORE;
    }

    @Override
    public BrowseResult browse(TransportTarget target, int page, int size, String filter) {
        long skip = (long) (page - 1) * size;
        if (skip + size > MAX_CORE_DEPTH) {
            // No server-side offset on a JMS browser; a deep page would walk the queue.
            return new BrowseResult(
                    jolokiaFallback.browse(target, page, size, filter).page(), Channel.JOLOKIA);
        }
        limiter.acquire(permitKey(target), 1, "core");
        List<BrowsedMessage> rows = new ArrayList<>(size);
        try {
            observations.run("browse", target.coreUrl(), () -> {
                try (PooledSession jms = open(target.clusterId(), target.coreUrl())) {
                    Session session = jms.session();
                    Queue queue = session.createQueue(target.queueName());
                    QueueBrowser browser = (filter == null || filter.isBlank())
                            ? session.createBrowser(queue)
                            : session.createBrowser(queue, filter);
                    long index = 0;
                    Enumeration<?> e = browser.getEnumeration();
                    // Read up to the requested page and stop. Counting by walking the rest of the queue
                    // streamed every message on a deep queue to Rahdar to show one page of them.
                    while (rows.size() < size && e.hasMoreElements()) {
                        Message m = (Message) e.nextElement();
                        if (index++ >= skip) {
                            rows.add(toBrowsed(m));
                        }
                    }
                    browser.close();
                }
            });
        } catch (JMSException ex) {
            log.debug("Core browse of {} failed, falling back to Jolokia: {}", target.queueName(), ex.getMessage());
            return new BrowseResult(
                    jolokiaFallback.browse(target, page, size, filter).page(), Channel.JOLOKIA);
        }
        Count count = count(target, filter);
        if (count.browsable() != null && rows.size() < Math.min(size, count.browsable() - skip)) {
            // The JMS browser stops at the first message not already on the client
            // (receiveImmediate), which on a paging queue is part-way through. A page shorter
            // than the broker says a browser can see is read again over management.
            log.debug(
                    "Core browse of {} came up short ({} of {}), reading over Jolokia",
                    target.queueName(),
                    rows.size(),
                    count.browsable() - skip);
            return new BrowseResult(
                    jolokiaFallback.browse(target, page, size, filter).page(), Channel.JOLOKIA);
        }
        return new BrowseResult(new BrowsePage(List.copyOf(rows), count.total(), count.unavailable()), Channel.CORE);
    }

    /**
     * {@code total} is the broker's count; {@code browsable} is the part of it a browser can
     * see — the count less messages delivered and not yet acked, and scheduled ones, neither of
     * which a {@link QueueBrowser} returns. A page is short only against {@code browsable}.
     */
    private record Count(Long total, Long browsable, String unavailable) {}

    /**
     * The broker's own count, over management: the queue's message count, or its count of
     * messages matching the filter. One request, charged to the node's ceiling by the client.
     * When it cannot be had, the total is stated as unavailable — never estimated, never zero.
     */
    private Count count(TransportTarget target, String filter) {
        if (target.jolokiaUrl() == null) {
            return new Count(null, null, "This node has no management URL, so its message count cannot be read.");
        }
        try {
            ArtemisNode client = artemisNodes.on(target.clusterId(), target.nodeId());
            String mbean = ArtemisMBeans.queue(
                    client.resolveBrokerObjectName(), target.address(), target.queueName(), target.routingType());
            if (filter == null || filter.isBlank()) {
                QueueDepth depth = messageOps.depth(client, mbean);
                return new Count(depth.messageCount(), depth.browsable(), null);
            }
            long matching = messageOps.countMessages(client, mbean, filter);
            return new Count(matching, matching, null);
        } catch (RuntimeException e) {
            return new Count(null, null, "The broker did not return this queue's message count: " + e.getMessage());
        }
    }

    @Override
    public void send(TransportTarget target, SendSpec spec) {
        limiter.acquire(permitKey(target), 1, "core");
        try {
            observations.run("send", target.coreUrl(), () -> {
                try (PooledSession jms = open(target.clusterId(), target.coreUrl())) {
                    Session session = jms.session();
                    Queue queue = session.createQueue(target.address());
                    Message message = spec.kind() == MessageKind.BYTES
                            ? bytesMessage(session, spec.body())
                            : session.createTextMessage(spec.body());
                    applyHeaders(session, message, spec.headers());
                    applyProperties(message, spec.properties());
                    int deliveryMode = spec.durable() ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT;
                    try (MessageProducer producer = session.createProducer(queue)) {
                        producer.send(message, deliveryMode, Message.DEFAULT_PRIORITY, 0L);
                    }
                }
            });
        } catch (JMSException ex) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE, "Core send failed: " + ex.getMessage());
        }
    }

    // ---- helpers -------------------------------------------------------

    private BytesMessage bytesMessage(Session session, String base64) throws JMSException {
        BytesMessage message = session.createBytesMessage();
        message.writeBytes(Base64.getDecoder().decode(base64 == null ? "" : base64));
        return message;
    }

    /** The {@link SendNames#HEADERS} as the JMS headers (and JMSX properties) they name. */
    private static void applyHeaders(Session session, Message message, Map<String, Object> headers)
            throws JMSException {
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String value = entry.getValue().toString();
            switch (entry.getKey()) {
                case "correlationId" -> message.setJMSCorrelationID(value);
                case "type" -> message.setJMSType(value);
                case "replyTo" -> message.setJMSReplyTo(session.createQueue(value));
                case "groupId" -> message.setStringProperty("JMSXGroupID", value);
                case "groupSeq" -> message.setIntProperty("JMSXGroupSeq", Integer.parseInt(value));
                default -> throw new IllegalArgumentException("Unsupported header '" + entry.getKey() + "'.");
            }
        }
    }

    private static void applyProperties(Message message, Map<String, Object> props) throws JMSException {
        if (props == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            Object v = entry.getValue();
            String key = entry.getKey();
            switch (v) {
                case null -> {
                    /* skip */
                }
                case String s -> message.setStringProperty(key, s);
                case Boolean b -> message.setBooleanProperty(key, b);
                case Integer i -> message.setIntProperty(key, i);
                case Long l -> message.setLongProperty(key, l);
                case Double d -> message.setDoubleProperty(key, d);
                case Number n -> message.setStringProperty(key, n.toString());
                default -> message.setStringProperty(key, v.toString());
            }
        }
    }

    /**
     * The one JMS-message-to-{@link BrowsedMessage} mapping in the product. Public
     * because message capture drains the same messages through a consumer rather than
     * a browser, and a second mapper would be a second set of rules about what a
     * property's type is and when a body is text.
     */
    public static BrowsedMessage toBrowsed(Message m) throws JMSException {
        String body;
        BodyEncoding encoding;
        BodyDecoder.Compression compression = BodyDecoder.Compression.NONE;
        long size;
        switch (m) {
            case TextMessage text -> {
                body = text.getText();
                encoding = BodyEncoding.TEXT;
                size = body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length;
            }
            case BytesMessage bytes -> {
                bytes.reset();
                long len = bytes.getBodyLength();
                byte[] raw = new byte[(int) Math.min(len, Integer.MAX_VALUE)];
                bytes.readBytes(raw);
                size = raw.length;
                // Text carried as bytes reads as text everywhere (ADR-0148); only binary stays base64.
                BodyDecoder.Decoded decoded = BodyDecoder.decode(raw);
                if (decoded.isBinary()) {
                    body = Base64.getEncoder().encodeToString(raw);
                    encoding = BodyEncoding.BASE64;
                } else {
                    body = decoded.text();
                    encoding = BodyEncoding.TEXT;
                    compression = decoded.compression();
                }
            }
            default -> {
                body = null;
                encoding = BodyEncoding.TEXT;
                size = 0;
            }
        }

        Map<String, String> strings = new LinkedHashMap<>();
        Map<String, Long> ints = new LinkedHashMap<>();
        Map<String, Long> longs = new LinkedHashMap<>();
        Map<String, Double> doubles = new LinkedHashMap<>();
        Map<String, Boolean> bools = new LinkedHashMap<>();
        Enumeration<?> names = m.getPropertyNames();
        while (names.hasMoreElements()) {
            String name = (String) names.nextElement();
            Object v = m.getObjectProperty(name);
            switch (v) {
                case Boolean b -> bools.put(name, b);
                case Integer i -> ints.put(name, i.longValue());
                case Long l -> longs.put(name, l);
                case Double d -> doubles.put(name, d);
                case Float f -> doubles.put(name, f.doubleValue());
                case null -> {
                    /* skip */
                }
                default -> strings.put(name, v.toString());
            }
        }

        return new BrowsedMessage(
                coreMessageId(m),
                jmsTypeInt(m),
                m.getJMSDeliveryMode() == DeliveryMode.PERSISTENT,
                m.getJMSPriority(),
                m.getJMSTimestamp(),
                m.getJMSExpiration(),
                size,
                m.getStringProperty("_AMQ_GROUP_ID"),
                m.getJMSCorrelationID(),
                m.getJMSReplyTo() != null ? m.getJMSReplyTo().toString() : null,
                blankToNull(stringProp(m, "_AMQ_VALIDATED_USER")),
                body,
                encoding,
                compression,
                m.getJMSType(),
                false, // Core does not truncate
                null,
                strings,
                ints,
                longs,
                doubles,
                bools);
    }

    private static long coreMessageId(Message m) {
        try {
            if (m instanceof ActiveMQMessage amq) {
                return amq.getCoreMessage().getMessageID();
            }
        } catch (RuntimeException _) {
            // fall through
        }
        return 0L;
    }

    private static int jmsTypeInt(Message m) {
        if (m instanceof TextMessage) {
            return 3;
        }
        if (m instanceof BytesMessage) {
            return 4;
        }
        return 0;
    }

    private static String stringProp(Message m, String name) {
        try {
            return m.getStringProperty(name);
        } catch (JMSException _) {
            return null;
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private PooledSession open(UUID clusterId, String coreUrl) {
        String dialable = CoreUrl.dialable(coreUrl);
        if (dialable == null) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "No Core URL for this node.");
        }
        CoreConnectionSettings settings = connections.coreSettingsFor(clusterId);
        try {
            return corePool.borrow(clusterId, dialable, settings);
        } catch (JMSException e) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE, "Could not open a Core session: " + e.getMessage());
        }
    }
}
