package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.openbroker.artemis.JolokiaRequest;
import io.github.sudoitir.openbroker.artemis.JolokiaResponse;
import io.github.sudoitir.rahdar.platform.broker.BodyDecoder;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrowsePage;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads messages off a queue over Jolokia (ADR-0021 — Phase 3 message I/O is
 * Jolokia-only). One batched POST per browse: {@code browse(int,int,String)} for
 * the page plus a {@code MessageCount} read for the total, so the broker pages
 * and Rahdar never pulls a whole deep queue through its heap (non-negotiable #1).
 *
 * <p>Unlike the {@code listX} envelopes, {@code browse()}'s {@code value} is a
 * plain JSON array — no double-decode. Every scalar is already typed. The broker
 * truncates oversized body / property strings at
 * {@code management-message-attribute-size-limit} and appends a literal
 * {@code , + N more} marker; that marker is the only truncation signal there is
 * (slice 0), so {@link #TRUNCATION_MARKER} detection drives the per-message
 * {@code bodyTruncated} flag.
 */
@Component
public class MessageBrowser {

    /** {@code managementBrowsePageSize} default — the broker will not return more per page. */
    public static final int BROKER_PAGE_CAP = 200;

    /** Artemis' verbatim truncation suffix, e.g. {@code ", + 3744 more"}. */
    static final Pattern TRUNCATION_MARKER = Pattern.compile(", \\+ \\d+ more$");

    private static final String OP_BROWSE = "browse(int,int,java.lang.String)";
    private static final String ATTR_MESSAGE_COUNT = "MessageCount";
    private static final String OP_ADDRESS_SETTINGS = "getAddressSettingsAsJSON(java.lang.String)";

    /**
     * Artemis' default {@code management-message-attribute-size-limit}, assumed when the address settings
     * could not be read. A bytes body arrives as a {@code BodyPreview} cut at that limit with no marker, so
     * the limit is the only way to know a preview is the whole body.
     */
    static final int DEFAULT_ATTRIBUTE_SIZE_LIMIT = 256;

    /**
     * One page of one queue. {@code page} is 1-based; the broker caps
     * {@code size} at {@code managementBrowsePageSize} (200 by default).
     *
     * @throws IllegalArgumentException the filter is not valid selector syntax
     *     ({@code AMQ229020}) — maps to a 400
     */
    public BrowsePage browse(ArtemisNode client, String queueMbean, int page, int size, String filter) {
        String selector = filter == null ? "" : filter;
        List<JolokiaResponse> responses = client.batch(List.of(
                JolokiaRequest.exec(queueMbean, OP_BROWSE, page, size, selector),
                JolokiaRequest.read(queueMbean, ATTR_MESSAGE_COUNT),
                addressSettings(queueMbean)));

        JolokiaResponse browse = responses.get(0);
        requireBrowsed(browse);
        int sizeLimit = attributeSizeLimit(client, responses.size() > 2 ? responses.get(2) : null);

        ArrayList<BrowsedMessage> messages = new ArrayList<>();
        JsonNode array = browse.value();
        if (array != null && array.isArray()) {
            array.forEach(row -> messages.add(decodeRow(row, sizeLimit)));
        }

        JolokiaResponse count = responses.size() > 1 ? responses.get(1) : null;
        JsonNode totalNode = count != null && count.ok() ? count.attribute(ATTR_MESSAGE_COUNT) : null;
        if (totalNode == null || !totalNode.isNumber()) {
            // Not the page size: an absent count reported as a number reads as the real one.
            return new BrowsePage(List.copyOf(messages), null, "The broker did not return this queue's message count.");
        }
        return new BrowsePage(List.copyOf(messages), totalNode.asLong());
    }

    private static void requireBrowsed(JolokiaResponse browse) {
        if (browse.ok()) {
            return;
        }
        String error = browse.error() == null ? "" : browse.error();
        if (error.contains("AMQ229020") || error.toLowerCase().contains("invalid filter")) {
            // Never echo the filter: it can carry sensitive literals (data-governance spec).
            throw new IllegalArgumentException("Invalid message filter.");
        }
        throw new BrokerConnectionException(
                BrokerConnectionException.Kind.BAD_RESPONSE,
                "browse() failed: " + (error.isEmpty() ? "status " + browse.status() : error));
    }

    /** The address's effective settings, read on the broker MBean named in the queue's object name. */
    private static JolokiaRequest addressSettings(String queueMbean) {
        try {
            ObjectName queue = ObjectName.getInstance(queueMbean);
            String broker = queue.getDomain() + ":broker=" + queue.getKeyProperty("broker");
            return JolokiaRequest.exec(
                    broker, OP_ADDRESS_SETTINGS, ObjectName.unquote(queue.getKeyProperty("address")));
        } catch (MalformedObjectNameException | RuntimeException e) {
            throw new IllegalArgumentException("Not a queue MBean: " + queueMbean, e);
        }
    }

    /** The limit a bytes preview was cut at; -1 means uncut. The default when the settings did not answer. */
    private static int attributeSizeLimit(ArtemisNode client, JolokiaResponse settings) {
        if (settings == null || !settings.ok()) {
            return DEFAULT_ATTRIBUTE_SIZE_LIMIT;
        }
        try {
            JsonNode limit = client.parsed(settings).get("managementMessageAttributeSizeLimit");
            return limit != null && limit.isNumber() ? limit.asInt() : DEFAULT_ATTRIBUTE_SIZE_LIMIT;
        } catch (RuntimeException _) {
            return DEFAULT_ATTRIBUTE_SIZE_LIMIT;
        }
    }

    /** A row's body as read, and the size the broker cut it at; {@code cutAt} is null when it was not cut. */
    private record Body(String text, BodyEncoding encoding, BodyDecoder.Compression compression, Integer cutAt) {}

    private static Body body(JsonNode row, int sizeLimit) {
        String text = text(row, "text");
        JsonNode preview = row.get("BodyPreview");
        if (text != null || preview == null || !preview.isArray()) {
            return new Body(text, BodyEncoding.TEXT, BodyDecoder.Compression.NONE, observedLimit(text));
        }
        // A bytes message has no text, only its leading bytes: read them as text when they are (ADR-0148).
        byte[] raw = new byte[preview.size()];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) preview.get(i).asInt();
        }
        Integer cutAt = sizeLimit >= 0 && raw.length >= sizeLimit ? sizeLimit : null;
        BodyDecoder.Decoded decoded = cutAt != null ? BodyDecoder.decodePrefix(raw) : BodyDecoder.decode(raw);
        return decoded.isBinary()
                ? new Body(Base64.getEncoder().encodeToString(raw), BodyEncoding.BASE64, decoded.compression(), cutAt)
                : new Body(decoded.text(), BodyEncoding.TEXT, decoded.compression(), cutAt);
    }

    /** Where the broker cut the first truncated property value, or null when none was cut. */
    private static Integer propertyCut(Map<String, String> strings) {
        return strings.values().stream()
                .map(MessageBrowser::observedLimit)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static BrowsedMessage decodeRow(JsonNode row, int sizeLimit) {
        Map<String, String> strings = stringMap(row, "StringProperties");
        Body body = body(row, sizeLimit);
        Integer observedLimit = body.cutAt() != null ? body.cutAt() : propertyCut(strings);
        boolean truncated = observedLimit != null;

        return new BrowsedMessage(
                asLong(row, "messageID"),
                (int) asLong(row, "type"),
                asBool(row, "durable"),
                (int) asLong(row, "priority"),
                asLong(row, "timestamp"),
                asLong(row, "expiration"),
                asLong(row, "persistentSize"),
                text(row, "groupID"),
                text(row, "correlationID"),
                null, // Jolokia's browse() row carries no reply-to field (§11.2) — Core-only (ADR-0029)
                blankToNull(text(row, "userID")),
                body.text(),
                body.encoding(),
                body.compression(),
                null,
                truncated,
                observedLimit,
                strings,
                longMap(row, "IntProperties", "ShortProperties", "ByteProperties"),
                longMap(row, "LongProperties"),
                doubleMap(row, "DoubleProperties", "FloatProperties"),
                boolMap(row, "BooleanProperties"));
    }

    // ---- truncation ------------------------------------------------------

    /** Chars that survived truncation = full length minus the {@code , + N more} marker; null when not truncated. */
    private static Integer observedLimit(String value) {
        if (value == null) {
            return null;
        }
        var matcher = TRUNCATION_MARKER.matcher(value);
        return matcher.find() ? matcher.start() : null;
    }

    // ---- scalar coercion (browse values are already typed, but be defensive) ----

    private static long asLong(JsonNode row, String field) {
        JsonNode v = row.get(field);
        if (v == null || v.isNull()) {
            return 0L;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        try {
            return Long.parseLong(v.asText().trim());
        } catch (NumberFormatException _) {
            return 0L;
        }
    }

    private static boolean asBool(JsonNode row, String field) {
        JsonNode v = row.get(field);
        return v != null && !v.isNull() && (v.isBoolean() ? v.asBoolean() : Boolean.parseBoolean(v.asText()));
    }

    private static String text(JsonNode row, String field) {
        JsonNode v = row.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    // ---- typed property maps -------------------------------------------

    private static Map<String, String> stringMap(JsonNode row, String field) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode node = row.get(field);
        if (node != null && node.isObject()) {
            node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }

    private static Map<String, Long> longMap(JsonNode row, String... fields) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String field : fields) {
            JsonNode node = row.get(field);
            if (node != null && node.isObject()) {
                node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asLong()));
            }
        }
        return out;
    }

    private static Map<String, Double> doubleMap(JsonNode row, String... fields) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String field : fields) {
            JsonNode node = row.get(field);
            if (node != null && node.isObject()) {
                node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asDouble()));
            }
        }
        return out;
    }

    private static Map<String, Boolean> boolMap(JsonNode row, String field) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        JsonNode node = row.get(field);
        if (node != null && node.isObject()) {
            node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asBoolean()));
        }
        return out;
    }
}
