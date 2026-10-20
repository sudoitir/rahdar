package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A message a plugin sends.
 *
 * @param target where it goes: a destination, which routes it to its subscriptions, or a subscription,
 *     which receives it directly where the system supports sending to one
 * @param body the body; sent as a text message when {@code text}, else as bytes
 * @param headers string properties set on the message, as Rahdar's own send sets them
 * @param properties typed properties (string, boolean, int, long, double)
 * @param actingUserId the Rahdar user sending it, who needs {@code message:send} on the cluster
 */
@PluginApi
public record OutboundMessage(
        UUID clusterId,
        ResourceRef target,
        byte[] body,
        boolean text,
        Map<String, String> headers,
        Map<String, Object> properties,
        boolean durable,
        UUID actingUserId) {

    /** Equal when every component is, the body by content. */
    @Override
    public boolean equals(Object other) {
        return other
                        instanceof
                        OutboundMessage(
                                var otherClusterId,
                                var otherTarget,
                                var otherBody,
                                var otherText,
                                var otherHeaders,
                                var otherProperties,
                                var otherDurable,
                                var otherActingUserId)
                && Objects.equals(clusterId, otherClusterId)
                && Objects.equals(target, otherTarget)
                && Arrays.equals(body, otherBody)
                && text == otherText
                && Objects.equals(headers, otherHeaders)
                && Objects.equals(properties, otherProperties)
                && durable == otherDurable
                && Objects.equals(actingUserId, otherActingUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clusterId, target, Arrays.hashCode(body), text, headers, properties, durable, actingUserId);
    }

    /** The body is reported by length only, so a message's content never lands in a log line. */
    @Override
    public String toString() {
        return "OutboundMessage[clusterId=" + clusterId + ", target=" + target + ", body="
                + (body == null ? "null" : body.length + " bytes") + ", text=" + text + ", headers=" + headers
                + ", properties=" + properties + ", durable=" + durable + ", actingUserId=" + actingUserId + "]";
    }
}
