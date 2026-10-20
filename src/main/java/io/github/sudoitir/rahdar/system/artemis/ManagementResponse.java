package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.openbroker.artemis.JolokiaResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One answer of an Artemis node's management agent. In a batch each answer stands alone: check
 * {@link #ok()} per answer. The value of the broker's {@code listX} operations is a JSON-encoded
 * string: read it with {@link #valueParsed(ObjectMapper)}.
 *
 * @param timestamp the broker's wall clock when it answered, in epoch seconds, when the agent gave it
 */
public record ManagementResponse(int status, JsonNode value, String error, String errorType, Long timestamp) {

    static ManagementResponse of(JolokiaResponse response) {
        return new ManagementResponse(
                response.status(), response.value(), response.error(), response.errorType(), response.timestamp());
    }

    JolokiaResponse toDriver() {
        return new JolokiaResponse(status, value, error, errorType, timestamp);
    }

    public boolean ok() {
        return toDriver().ok();
    }

    /** Why this answer failed, for a message. */
    public String failure() {
        return toDriver().failure();
    }

    /** One attribute's value: a list read answers with a map keyed by name, a scalar read with the value. */
    public JsonNode attribute(String name) {
        return toDriver().attribute(name);
    }

    /** The value, parsed again when it is a JSON string. */
    public JsonNode valueParsed(ObjectMapper mapper) {
        return toDriver().valueParsed(mapper);
    }
}
