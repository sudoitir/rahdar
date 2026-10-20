package io.github.sudoitir.rahdar.feature.plugins.messaging.internal;

import io.github.sudoitir.openbroker.messaging.JmsExtension;
import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.MessageHeaders;
import io.github.sudoitir.openbroker.messaging.PropertyValue;
import io.github.sudoitir.rahdar.feature.plugins.messaging.PluginMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Turns a received message into what a plugin is handed. */
final class MessageConversion {

    /** The default priority, for a message whose system has none. */
    private static final int DEFAULT_PRIORITY = 4;

    private MessageConversion() {}

    static PluginMessage toPlugin(String key, UUID clusterId, UUID nodeId, String queue, Message m) {
        MessageHeaders h = m.headers();
        Map<String, Object> headers = new LinkedHashMap<>();
        h.id().ifPresent(id -> headers.put("messageId", id));
        h.correlationId().ifPresent(id -> headers.put("correlationId", id));
        h.type().ifPresent(type -> headers.put("type", type));
        headers.put("priority", h.priority().orElse(DEFAULT_PRIORITY));
        headers.put("timestamp", h.producedAt().map(Instant::toEpochMilli).orElse(0L));
        h.expiresAt().ifPresent(at -> headers.put("expiration", at.toEpochMilli()));
        headers.put("durable", h.durable().orElse(false));
        h.replyTo().ifPresent(to -> headers.put("replyTo", to));
        Map<String, Object> properties = new LinkedHashMap<>();
        m.properties().forEach((name, value) -> {
            if (!name.startsWith("_AMQ") && !name.startsWith("__AMQ") && !name.startsWith("JMSX")) {
                properties.put(name, plain(value));
            }
        });
        return new PluginMessage(
                key,
                clusterId,
                nodeId,
                queue,
                body(m),
                JmsExtension.messageType(m).orElse(JmsExtension.GENERIC) == JmsExtension.TEXT,
                Collections.unmodifiableMap(headers),
                Collections.unmodifiableMap(properties),
                h.deliveryCount().orElse(1));
    }

    private static byte[] body(Message m) {
        if (m.body().bytes().isPresent()) {
            return m.body().bytes().get().toByteArray();
        }
        try (InputStream in = m.body().openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Object plain(PropertyValue value) {
        return switch (value) {
            case PropertyValue.OfBoolean(var b) -> b;
            case PropertyValue.OfByte(var b) -> b;
            case PropertyValue.OfShort(var s) -> s;
            case PropertyValue.OfInt(var i) -> i;
            case PropertyValue.OfLong(var l) -> l;
            case PropertyValue.OfFloat(var f) -> f;
            case PropertyValue.OfDouble(var d) -> d;
            case PropertyValue.OfString(var s) -> s;
            case PropertyValue.OfBytes(var b) -> b.toByteArray();
        };
    }
}
