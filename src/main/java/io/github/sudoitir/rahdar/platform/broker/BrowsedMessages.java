package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.messaging.JmsExtension;
import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.MessageHeaders;
import io.github.sudoitir.openbroker.messaging.MessagePart;
import io.github.sudoitir.openbroker.messaging.PropertyValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The SDK's message as Rahdar shows it. A body that decodes as text, plainly or compressed, reads as
 * text everywhere (ADR-0148); anything else stays base64.
 */
public final class BrowsedMessages {

    /** The default JMS priority, for a message whose system has none. */
    private static final int DEFAULT_PRIORITY = 4;

    private BrowsedMessages() {}

    public static BrowsedMessage of(Message m) {
        MessageHeaders h = m.headers();
        byte[] raw = bytes(m);
        boolean cut = m.truncated().contains(MessagePart.BODY);
        BodyDecoder.Decoded decoded = cut ? BodyDecoder.decodePrefix(raw) : BodyDecoder.decode(raw);
        String body = decoded.isBinary() ? Base64.getEncoder().encodeToString(raw) : decoded.text();
        Map<String, String> strings = new LinkedHashMap<>();
        Map<String, Long> ints = new LinkedHashMap<>();
        Map<String, Long> longs = new LinkedHashMap<>();
        Map<String, Double> doubles = new LinkedHashMap<>();
        Map<String, Boolean> booleans = new LinkedHashMap<>();
        m.properties().forEach((name, value) -> {
            switch (value) {
                case PropertyValue.OfBoolean b -> booleans.put(name, b.value());
                case PropertyValue.OfByte b -> ints.put(name, (long) b.value());
                case PropertyValue.OfShort s -> ints.put(name, (long) s.value());
                case PropertyValue.OfInt i -> ints.put(name, (long) i.value());
                case PropertyValue.OfLong l -> longs.put(name, l.value());
                case PropertyValue.OfFloat f -> doubles.put(name, (double) f.value());
                case PropertyValue.OfDouble d -> doubles.put(name, d.value());
                case PropertyValue.OfString s -> strings.put(name, s.value());
                case PropertyValue.OfBytes b ->
                    strings.put(
                            name, Base64.getEncoder().encodeToString(b.value().toByteArray()));
            }
        });
        return new BrowsedMessage(
                Long.parseLong(m.ref().position()),
                JmsExtension.messageType(m).orElse(JmsExtension.GENERIC),
                h.durable().orElse(false),
                h.priority().orElse(DEFAULT_PRIORITY),
                h.producedAt().map(Instant::toEpochMilli).orElse(0L),
                h.expiresAt().map(Instant::toEpochMilli).orElse(0L),
                m.body().size(),
                h.groupId().orElse(null),
                h.correlationId().orElse(null),
                h.replyTo().orElse(null),
                h.id().orElse(null),
                body,
                decoded.isBinary() ? BodyEncoding.BASE64 : BodyEncoding.TEXT,
                decoded.compression(),
                h.contentType().orElse(null),
                cut,
                cut ? raw.length : null,
                strings,
                ints,
                longs,
                doubles,
                booleans);
    }

    private static byte[] bytes(Message m) {
        if (m.body().bytes().isPresent()) {
            return m.body().bytes().get().toByteArray();
        }
        try (InputStream in = m.body().openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
