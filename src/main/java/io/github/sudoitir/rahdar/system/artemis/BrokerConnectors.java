package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A node's declared connector names, read from the {@code ConnectorsAsJSON}
 * <em>attribute</em> — there is no operation that lists them, and the attribute
 * batches with everything else, so this costs one request and no MBean search.
 *
 * <p>A bridge names the connectors it uses, and a name the broker does not know is
 * accepted and silently ignored, so offering the real names is what stops an apply
 * from succeeding and deploying nothing. Where the attribute cannot be read the
 * answer is <em>unknown</em>, never an empty list: an absent name list would read as
 * "this broker has no connectors", which would send an operator looking for a
 * problem that is not there (ADR-0049 D5).
 */
@Component
@RequiredArgsConstructor
public class BrokerConnectors {

    private final ObjectMapper mapper;

    /**
     * @param names the connector names, empty when {@code known} is false
     * @param known whether the read succeeded; false means Rahdar does not know, not
     *     that there are none
     * @param unknownReason why, in the words a screen shows, or null when known
     */
    public record Connectors(List<String> names, boolean known, String unknownReason) {
        public Connectors {
            names = List.copyOf(names == null ? List.of() : names);
        }

        public static Connectors unknown(String reason) {
            return new Connectors(List.of(), false, reason);
        }
    }

    /** One node's connector names. Never throws: an unreadable node is an answer. */
    public Connectors read(ArtemisNode client) {
        try {
            String broker = client.resolveBrokerObjectName();
            ManagementResponse response = client.single(ManagementRequest.read(broker, "ConnectorsAsJSON"));
            if (!response.ok()) {
                return Connectors.unknown("The node did not answer the connector read: " + response.error());
            }
            JsonNode connectors = response.attribute("ConnectorsAsJSON");
            if (connectors != null && connectors.isString()) {
                connectors = mapper.readTree(connectors.asString());
            }
            if (connectors == null || !connectors.isArray()) {
                return Connectors.unknown("This broker did not report its connectors in a shape Rahdar can read.");
            }
            List<String> names = new ArrayList<>();
            connectors.forEach(c -> {
                String name = c.path("name").asString(null);
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            });
            return new Connectors(names, true, null);
        } catch (BrokerConnectionException e) {
            return Connectors.unknown(e.getMessage());
        } catch (RuntimeException e) {
            return Connectors.unknown("The connector read failed: " + e.getMessage());
        }
    }
}
