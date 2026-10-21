package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A message delivered to a plugin.
 *
 * @param registrationKey the registration it was delivered for
 * @param source the subscription it was received from
 * @param message where the message is: the serving node that delivered it and its position there
 * @param body the body's bytes; a text message's are its UTF-8 encoding, and a message with no
 *     readable body (map, stream, object) has none
 * @param text whether it was sent as a text message
 * @param headers the standard headers that are set: {@code messageId}, {@code correlationId},
 *     {@code type}, {@code priority}, {@code timestamp}, {@code expiration}, {@code durable},
 *     {@code replyTo}
 * @param properties the application properties, with their types; the broker's own
 *     ({@code _AMQ_*}) are left out
 * @param deliveryCount 1 on first delivery, more on a redelivery
 */
@PluginApi
public record PluginMessage(
        String registrationKey,
        UUID clusterId,
        ResourceRef source,
        MessageRef message,
        byte[] body,
        boolean text,
        Map<String, Object> headers,
        Map<String, Object> properties,
        int deliveryCount) {

    /** The body decoded as UTF-8. */
    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** Equal when every component is, the body by content. */
    @Override
    public boolean equals(Object other) {
        return other
                        instanceof
                        PluginMessage(
                                var otherRegistrationKey,
                                var otherClusterId,
                                var otherSource,
                                var otherMessage,
                                var otherBody,
                                var otherText,
                                var otherHeaders,
                                var otherProperties,
                                var otherDeliveryCount)
                && Objects.equals(registrationKey, otherRegistrationKey)
                && Objects.equals(clusterId, otherClusterId)
                && Objects.equals(source, otherSource)
                && Objects.equals(message, otherMessage)
                && Arrays.equals(body, otherBody)
                && text == otherText
                && Objects.equals(headers, otherHeaders)
                && Objects.equals(properties, otherProperties)
                && deliveryCount == otherDeliveryCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                registrationKey,
                clusterId,
                source,
                message,
                Arrays.hashCode(body),
                text,
                headers,
                properties,
                deliveryCount);
    }

    /** The body is reported by length only, so a message's content never lands in a log line. */
    @Override
    public String toString() {
        return "PluginMessage[registrationKey=" + registrationKey + ", clusterId=" + clusterId + ", source=" + source
                + ", message=" + message + ", body=" + (body == null ? "null" : body.length + " bytes") + ", text="
                + text
                + ", headers=" + headers + ", properties=" + properties + ", deliveryCount=" + deliveryCount + "]";
    }
}
