package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.messaging.Blob;
import io.github.sudoitir.openbroker.messaging.JmsExtension;
import io.github.sudoitir.openbroker.messaging.MessageBody;
import io.github.sudoitir.openbroker.messaging.MessageHeaders;
import io.github.sudoitir.openbroker.messaging.OutboundMessage;
import io.github.sudoitir.openbroker.messaging.PropertyValue;
import io.github.sudoitir.openbroker.model.Attributes;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A message an operator or a plugin sends, as the SDK takes it: the {@link SendNames#HEADERS} as
 * headers (a group sequence as the JMS property {@code JMSXGroupSeq}), typed properties as their
 * types, and a text or a base64-given bytes body under its JMS type.
 */
public final class SentMessages {

    private SentMessages() {}

    public static OutboundMessage of(
            MessageKind kind,
            boolean durable,
            String body,
            Map<String, Object> headers,
            Map<String, Object> properties) {
        String text = body == null ? "" : body;
        byte[] bytes =
                kind == MessageKind.BYTES ? Base64.getDecoder().decode(text) : text.getBytes(StandardCharsets.UTF_8);
        return of(kind, durable, bytes, headers, properties);
    }

    /** A message whose body is given as its bytes, a text one's in UTF-8. */
    public static OutboundMessage of(
            MessageKind kind,
            boolean durable,
            byte[] bytes,
            Map<String, Object> headers,
            Map<String, Object> properties) {
        MessageHeaders.Builder h = MessageHeaders.builder().durable(durable);
        Map<String, PropertyValue> props = new LinkedHashMap<>();
        properties.forEach((name, value) -> {
            if (value != null) {
                props.put(name, typed(value));
            }
        });
        headers.forEach((name, value) -> {
            if (value == null) {
                return;
            }
            String text = value.toString();
            switch (name) {
                case "correlationId" -> h.correlationId(text);
                case "type" -> h.type(text);
                case "replyTo" -> h.replyTo(text);
                case "groupId" -> h.groupId(text);
                case "groupSeq" -> props.put("JMSXGroupSeq", PropertyValue.of(Integer.parseInt(text)));
                default -> throw new IllegalArgumentException("Unsupported header '" + name + "'.");
            }
        });
        int jmsType = kind == MessageKind.BYTES ? JmsExtension.BYTES : JmsExtension.TEXT;
        return new OutboundMessage(
                h.build(),
                props,
                MessageBody.of(Blob.of(bytes)),
                Map.of(
                        JmsExtension.NAMESPACE,
                        Attributes.builder()
                                .putRaw(JmsExtension.MESSAGE_TYPE, jmsType)
                                .build()));
    }

    private static PropertyValue typed(Object value) {
        return switch (value) {
            case Boolean b -> PropertyValue.of(b);
            case Integer i -> PropertyValue.of(i);
            case Long l -> PropertyValue.of(l);
            case Double d -> PropertyValue.of(d);
            default -> PropertyValue.of(value.toString());
        };
    }
}
