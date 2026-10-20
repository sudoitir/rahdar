package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.artemis.ArtemisErrors;
import io.github.sudoitir.openbroker.artemis.ArtemisJolokia;
import io.github.sudoitir.openbroker.artemis.JolokiaRequest;
import io.github.sudoitir.openbroker.artemis.JolokiaResponse;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import java.util.List;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One node's Artemis management agent, for what has no neutral meaning. Every request goes through
 * the cluster's connection and so through Rahdar's call interceptor; a call that gets no answer fails
 * as one of Rahdar's broker exceptions, and a refusal is the {@link ManagementResponse} the caller reads.
 */
public final class ArtemisNode {

    private final ArtemisJolokia jolokia;

    ArtemisNode(ArtemisJolokia jolokia) {
        this.jolokia = jolokia;
    }

    public NodeId node() {
        return jolokia.node();
    }

    public String resolveBrokerObjectName() {
        return call(jolokia::brokerName);
    }

    public ManagementResponse single(ManagementRequest request) {
        return ManagementResponse.of(call(() -> jolokia.single(request.toDriver())));
    }

    public List<ManagementResponse> batch(List<ManagementRequest> requests) {
        return call(() -> jolokia.batch(
                        requests.stream().map(ManagementRequest::toDriver).toList()))
                .stream()
                .map(ManagementResponse::of)
                .toList();
    }

    public List<String> search(String pattern) {
        return call(() -> jolokia.search(pattern));
    }

    public ManagementResponse readBrokerAttributes(String... attributes) {
        return ManagementResponse.of(call(() -> jolokia.readBroker(attributes)));
    }

    public ManagementResponse execOnBroker(String operation, Object... arguments) {
        return ManagementResponse.of(call(() -> jolokia.execOnBroker(operation, arguments)));
    }

    public JsonNode execOnBrokerParsed(String operation, Object... arguments) {
        return call(() -> jolokia.execOnBrokerParsed(operation, arguments));
    }

    public JsonNode parsed(ManagementResponse entry) {
        return call(() -> jolokia.parsed(entry.toDriver()));
    }

    /**
     * Throws the refusal of a failed response as Rahdar has always named it: a {@code
     * ManagementRefusal} for a broker code it knows, a bad response otherwise.
     */
    public static void require(ManagementResponse response, String operation) {
        ArtemisErrors.refusal(response.toDriver(), operation).ifPresent(e -> {
            throw BrokerFailures.translate(e);
        });
    }

    public ObjectMapper mapper() {
        return jolokia.mapper();
    }

    /**
     * This node's agent for the SDK's own Artemis helpers ({@link ListRows}), failing as Rahdar's
     * broker exceptions like every other call here.
     */
    ArtemisJolokia jolokia() {
        return translating;
    }

    private final ArtemisJolokia translating = new ArtemisJolokia() {
        @Override
        public NodeId node() {
            return jolokia.node();
        }

        @Override
        public String brokerName() {
            return resolveBrokerObjectName();
        }

        @Override
        public JolokiaResponse single(JolokiaRequest request) {
            return call(() -> jolokia.single(request));
        }

        @Override
        public List<JolokiaResponse> batch(List<JolokiaRequest> requests) {
            return call(() -> jolokia.batch(requests));
        }

        @Override
        public List<String> search(String pattern) {
            return ArtemisNode.this.search(pattern);
        }

        @Override
        public JolokiaResponse readBroker(String... attributes) {
            return call(() -> jolokia.readBroker(attributes));
        }

        @Override
        public JolokiaResponse execOnBroker(String operation, Object... arguments) {
            return call(() -> jolokia.execOnBroker(operation, arguments));
        }

        @Override
        public JsonNode execOnBrokerParsed(String operation, Object... arguments) {
            return ArtemisNode.this.execOnBrokerParsed(operation, arguments);
        }

        @Override
        public JsonNode parsed(JolokiaResponse entry) {
            return call(() -> jolokia.parsed(entry));
        }

        @Override
        public ObjectMapper mapper() {
            return jolokia.mapper();
        }
    };

    private static <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (BrokerException e) {
            throw BrokerFailures.translate(e);
        }
    }
}
