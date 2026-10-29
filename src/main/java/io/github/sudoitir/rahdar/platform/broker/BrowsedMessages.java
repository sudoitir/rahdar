package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.messaging.Blob;
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
        Body body0 = body(m);
        byte[] raw = body0.bytes();
        boolean cut = m.truncated().contains(MessagePart.BODY) || body0.cut();
        BodyDecoder.Decoded decoded = cut ? BodyDecoder.decodePrefix(raw) : BodyDecoder.decode(raw);
        String body = decoded.isBinary() ? Base64.getEncoder().encodeToString(raw) : decoded.text();
        Map<String, String> strings = new LinkedHashMap<>();
        Map<String, Long> ints = new LinkedHashMap<>();
        Map<String, Long> longs = new LinkedHashMap<>();
        Map<String, Double> doubles = new LinkedHashMap<>();
        Map<String, Boolean> booleans = new LinkedHashMap<>();
        m.properties().forEach((name, value) -> {
            switch (value) {
                case PropertyValue.OfBoolean(var b) -> booleans.put(name, b);
                case PropertyValue.OfByte(var b) -> ints.put(name, (long) b);
                case PropertyValue.OfShort(var s) -> ints.put(name, (long) s);
                case PropertyValue.OfInt(var i) -> ints.put(name, (long) i);
                case PropertyValue.OfLong(var l) -> longs.put(name, l);
                case PropertyValue.OfFloat(var f) -> doubles.put(name, (double) f);
                case PropertyValue.OfDouble(var d) -> doubles.put(name, d);
                case PropertyValue.OfString(var s) -> strings.put(name, s);
                case PropertyValue.OfBytes(var b) ->
                    strings.put(name, Base64.getEncoder().encodeToString(b.toByteArray()));
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

    /** A record's key and how it is written: text when it reads as text, base64 when it does not. */
    public record Key(String text, BodyEncoding encoding) {}

    /** The record's key; null for a record with none. */
    public static Key keyOf(Message m) {
        return m.headers()
                .key()
                .map(Blob::toByteArray)
                .map(BrowsedMessages::readable)
                .orElse(null);
    }

    private static Key readable(byte[] key) {
        BodyDecoder.Decoded decoded = BodyDecoder.decode(key);
        return decoded.isBinary()
                ? new Key(Base64.getEncoder().encodeToString(key), BodyEncoding.BASE64)
                : new Key(decoded.text(), BodyEncoding.TEXT);
    }

    /** A body's bytes, and whether more followed than were read. */
    private record Body(byte[] bytes, boolean cut) {}

    private static Body body(Message m) {
        if (m.body().isTombstone()) {
            return new Body(new byte[0], false);
        }
        if (m.body().bytes().isPresent()) {
            return new Body(m.body().bytes().get().toByteArray(), false);
        }
        // A streamed body is read up to the browse limit and no further, so one huge value cannot fill the heap.
        try (InputStream in = m.body().openStream()) {
            byte[] read = in.readNBytes((int) BrokerMessages.MAX_BODY_BYTES);
            return new Body(read, in.read() != -1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
