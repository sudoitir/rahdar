package io.github.sudoitir.rahdar.feature.setupreview;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ManagementRequest;
import io.github.sudoitir.rahdar.system.artemis.ManagementResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads one node for the review in a single batched Jolokia POST (non-negotiable #1, ADR-0106),
 * taken through the node's rate limiter like every other call:
 *
 * <ol>
 *   <li>every attribute of the broker MBean — HA policy, clustering, persistence, security, disk
 *       limits, connectors and acceptors;
 *   <li>the effective address settings for {@code #};
 *   <li>every attribute of every cluster connection, by pattern.
 * </ol>
 *
 * <p>Parts fail independently. A broker with no cluster connection answers the pattern read with
 * 404, which is "none", not an error; anything else leaves that part null with its reason, and the
 * rules that need it report themselves as not assessed. Never a write.
 */
@Component
@RequiredArgsConstructor
public class SetupReader {

    private final ObjectMapper mapper;

    public NodeRead read(ArtemisNode client, ClusterNode node) {
        boolean live = Boolean.TRUE.equals(node.getActive());
        try {
            String broker = client.resolveBrokerObjectName();
            List<ManagementResponse> responses = client.batch(List.of(
                    ManagementRequest.readAll(broker),
                    ManagementRequest.exec(broker, "getAddressSettingsAsJSON(java.lang.String)", "#"),
                    ManagementRequest.readAll(broker + ",component=cluster-connections,name=*")));
            if (responses.size() != 3) {
                return unreadable(node, live, "The node answered " + responses.size() + " of 3 batched requests.");
            }
            ManagementResponse attributes = responses.get(0);
            if (!attributes.ok()) {
                return unreadable(node, live, "The broker MBean could not be read: " + reason(attributes));
            }
            JsonNode brokerAttributes = attributes.value();
            Part<JsonNode> settings = defaultSettings(client, responses.get(1));
            Part<Map<String, JsonNode>> connections = clusterConnections(responses.get(2));

            JsonNode active = brokerAttributes.get("Active");
            if (active != null && active.isBoolean()) {
                live = active.asBoolean();
            }
            return new NodeRead(
                    node.getId(),
                    node.getName(),
                    reportedNodeId(brokerAttributes, node.getNativeNodeId()),
                    live,
                    true,
                    null,
                    brokerAttributes,
                    settings.value(),
                    settings.error(),
                    connections.value(),
                    connections.error());
        } catch (BrokerConnectionException e) {
            return unreadable(
                    node,
                    live,
                    e.getMessage() != null ? e.getMessage() : e.kind().defaultMessage());
        } catch (RuntimeException e) {
            return unreadable(node, live, "The node's answer could not be read: " + e.getMessage());
        }
    }

    /** One part of the read: its value, or the reason it is missing. */
    private record Part<T>(T value, String error) {}

    private static NodeRead unreadable(ClusterNode node, boolean live, String reason) {
        return NodeRead.unreadable(node.getId(), node.getName(), node.getNativeNodeId(), live, true, reason);
    }

    private static Part<JsonNode> defaultSettings(ArtemisNode client, ManagementResponse response) {
        JsonNode parsed = response.ok() ? client.parsed(response) : null;
        if (parsed != null && parsed.isObject()) {
            return new Part<>(parsed, null);
        }
        return new Part<>(null, "The default address settings could not be read: " + reason(response));
    }

    private static Part<Map<String, JsonNode>> clusterConnections(ManagementResponse cc) {
        if (cc.ok() && cc.value() != null && cc.value().isObject()) {
            Map<String, JsonNode> connections = new LinkedHashMap<>();
            for (var entry : cc.value().properties()) {
                connections.put(name(entry.getKey()), entry.getValue());
            }
            return new Part<>(connections, null);
        }
        if (cc.status() == 404) {
            return new Part<>(Map.of(), null);
        }
        return new Part<>(null, "The cluster connections could not be read: " + reason(cc));
    }

    /** The NodeID the broker reports for itself, else the one Rahdar already knew. */
    private static String reportedNodeId(JsonNode brokerAttributes, String known) {
        JsonNode reported = brokerAttributes.get("NodeID");
        if (reported != null && !reported.isNull() && !reported.asString().isBlank()) {
            return reported.asString();
        }
        return known;
    }

    /** {@code ...,component=cluster-connections,name="rahdar-dev"} → {@code rahdar-dev}. */
    static String name(String objectName) {
        int at = objectName.lastIndexOf("name=");
        if (at < 0) {
            return objectName;
        }
        String name = objectName.substring(at + "name=".length());
        int comma = name.indexOf(',');
        if (comma >= 0) {
            name = name.substring(0, comma);
        }
        return name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")
                ? name.substring(1, name.length() - 1)
                : name;
    }

    private static String reason(ManagementResponse response) {
        return response.error() != null ? response.error() : "status " + response.status();
    }
}
