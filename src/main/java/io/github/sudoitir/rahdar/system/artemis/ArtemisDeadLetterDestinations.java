package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.DeadLetterDestinations;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Artemis keeps the default dead-letter and expiry addresses in the address settings that match every address. */
@Component
@RequiredArgsConstructor
class ArtemisDeadLetterDestinations implements DeadLetterDestinations {

    private final ArtemisNodes nodes;

    @Override
    public Optional<Map<String, String>> defaults(UUID clusterId, UUID nodeId) {
        try {
            JsonNode settings =
                    nodes.on(clusterId, nodeId).execOnBrokerParsed("getAddressSettingsAsJSON(java.lang.String)", "#");
            String dla = text(settings, "deadLetterAddress");
            String ea = text(settings, "expiryAddress");
            Map<String, String> kinds = new LinkedHashMap<>();
            if (dla != null && !dla.isBlank()) {
                kinds.put(dla, "dead-letter");
            }
            if (ea != null && !ea.isBlank()) {
                kinds.putIfAbsent(ea, "expiry");
            }
            return Optional.of(kinds);
        } catch (BrokerConnectionException _) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
