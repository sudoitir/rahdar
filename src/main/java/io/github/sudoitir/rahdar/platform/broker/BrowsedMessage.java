package io.github.sudoitir.rahdar.platform.broker;

import java.util.HashMap;
import java.util.Map;

/** One decoded message: the header set plus five typed property maps and the body. */
public record BrowsedMessage(
        long messageId,
        int type,
        boolean durable,
        int priority,
        long timestamp,
        long expiration,
        long size,
        String groupId,
        String correlationId,
        String replyTo,
        String userId,
        String body,
        BodyEncoding bodyEncoding,
        BodyDecoder.Compression bodyCompression,
        String contentType,
        boolean bodyTruncated,
        Integer observedLimitBytes,
        Map<String, String> stringProperties,
        Map<String, Long> intProperties,
        Map<String, Long> longProperties,
        Map<String, Double> doubleProperties,
        Map<String, Boolean> booleanProperties) {

    public int propertyCount() {
        return stringProperties.size()
                + intProperties.size()
                + longProperties.size()
                + doubleProperties.size()
                + booleanProperties.size();
    }

    /** The same message with one more integer property, such as the partition a record was read from. */
    public BrowsedMessage withLongProperty(String name, long value) {
        Map<String, Long> extended = new HashMap<>(longProperties);
        extended.put(name, value);
        return new BrowsedMessage(
                messageId,
                type,
                durable,
                priority,
                timestamp,
                expiration,
                size,
                groupId,
                correlationId,
                replyTo,
                userId,
                body,
                bodyEncoding,
                bodyCompression,
                contentType,
                bodyTruncated,
                observedLimitBytes,
                stringProperties,
                intProperties,
                Map.copyOf(extended),
                doubleProperties,
                booleanProperties);
    }

    /** First ~200 chars of the body, for the grid cell. */
    public String bodyPreview() {
        if (body == null) {
            return null;
        }
        return body.length() <= 200 ? body : body.substring(0, 200);
    }
}
