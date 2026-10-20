package io.github.sudoitir.rahdar.system.artemis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One bridge as a running broker reports it, tagged with the node it was read from.
 *
 * <p>{@code started} and {@code connected} are separate facts and both are carried:
 * a bridge can be started and unable to reach its target, which is the state an
 * operator asking "is this bridge actually running" needs to be able to see.
 *
 * <p>These are the thirteen configuration attributes {@code BridgeControl} reports
 * (ADR-0091's measurement) and no more. The ten a bridge can be created with and the
 * broker never hands back — the window sizes, the large-message size, the check
 * period, the connection TTL, the routing type, the concurrency, the client id, the
 * initial connect attempts, the user and the password — are absent here on purpose,
 * so nothing downstream can claim to have verified them.
 */
public record BridgeRow(
        UUID nodeId,
        String nodeName,
        String name,
        String queueName,
        String forwardingAddress,
        String filterString,
        String discoveryGroupName,
        String transformerClassName,
        Map<String, String> transformerProperties,
        List<String> staticConnectors,
        long retryInterval,
        double retryIntervalMultiplier,
        long maxRetryInterval,
        int reconnectAttempts,
        long messagesAcknowledged,
        long messagesPendingAcknowledgement,
        boolean started,
        boolean connected,
        boolean useDuplicateDetection,
        boolean highlyAvailable) {

    /** Parse one bridge MBean's attribute map. */
    public static BridgeRow parse(JsonNode attributes, UUID nodeId, String nodeName) {
        return new BridgeRow(
                nodeId,
                nodeName,
                text(attributes, "Name"),
                text(attributes, "QueueName"),
                text(attributes, "ForwardingAddress"),
                text(attributes, "FilterString"),
                text(attributes, "DiscoveryGroupName"),
                text(attributes, "TransformerClassName"),
                properties(attributes),
                strings(attributes, "StaticConnectors"),
                num(attributes, "RetryInterval"),
                decimal(attributes, "RetryIntervalMultiplier"),
                num(attributes, "MaxRetryInterval"),
                (int) num(attributes, "ReconnectAttempts"),
                num(attributes, "MessagesAcknowledged"),
                num(attributes, "MessagesPendingAcknowledgement"),
                flag(attributes, "Started"),
                flag(attributes, "Connected"),
                flag(attributes, "UseDuplicateDetection"),
                flag(attributes, "HA"));
    }

    /**
     * The transformer's properties, which the broker reports in full. Jolokia renders
     * the attribute as an object; {@code TransformerPropertiesAsJSON} carries the same
     * map as an encoded string and is the fallback when it does not.
     */
    private static Map<String, String> properties(JsonNode node) {
        JsonNode v = node == null ? null : node.get("TransformerProperties");
        if (v == null || !v.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        v.properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
        return Map.copyOf(out);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || !v.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        v.forEach(e -> out.add(e.asString()));
        return List.copyOf(out);
    }

    private static long num(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? 0L : v.asLong();
    }

    private static double decimal(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? 0d : v.asDouble();
    }

    private static boolean flag(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && !v.isNull() && v.asBoolean();
    }
}
