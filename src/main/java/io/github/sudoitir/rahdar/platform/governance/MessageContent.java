package io.github.sudoitir.rahdar.platform.governance;

import io.github.sudoitir.rahdar.kernel.core.DecodedRecordView;
import java.util.Map;

/**
 * The content of one message, in the neutral shape the policy reads. Callers adapt their own
 * message types into it, so the policy depends on no feature (ADR-0075 D1).
 *
 * @param headers header name to value, e.g. {@code correlationId}; null values are ignored
 * @param properties application properties, typed as the broker returned them
 * @param base64 the body is binary, carried as base64
 */
public record MessageContent(
        Map<String, String> headers,
        Map<String, Object> properties,
        String body,
        boolean base64,
        String contentType,
        DecodedRecordView decoded,
        DecodedRecordView decodedKey) {

    public MessageContent(
            Map<String, String> headers,
            Map<String, Object> properties,
            String body,
            boolean base64,
            String contentType) {
        this(headers, properties, body, base64, contentType, null, null);
    }

    public MessageContent(
            Map<String, String> headers,
            Map<String, Object> properties,
            String body,
            boolean base64,
            String contentType,
            DecodedRecordView decoded) {
        this(headers, properties, body, base64, contentType, decoded, null);
    }

    public MessageContent {
        headers = headers == null ? Map.of() : headers;
        properties = properties == null ? Map.of() : properties;
    }
}
