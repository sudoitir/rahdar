package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** Request bodies for the subscription and destination lifecycle API (ADR-0049, ADR-0065). */
public final class LifecycleRequests {

    private LifecycleRequests() {}

    /** No whitespace and none of the characters a broker management object name cannot safely carry. */
    public static final String MANAGEMENT_NAME = "[^\\s,=:*?\"\\\\]*";

    /**
     * A subscription to create. {@code kind}, {@code name} and {@code destination} identify it; the rest of the
     * system's own properties go in {@code attributes} ({@code routingType} and {@code durable} are fixed once
     * the subscription exists; {@code filter}, {@code maxConsumers}, {@code purgeOnNoConsumers},
     * {@code exclusive}, {@code nonDestructive} and {@code ringSize} can be changed later).
     *
     * <p>{@code autoCreateAddress} is explicit rather than left to the broker's default: with it on, a create
     * that fails validation still leaves the auto-created destination behind, so the operator is told which is
     * happening.
     */
    public record CreateSubscriptionRequest(
            @NotBlank @Schema(description = "The subscription's kind, such as artemis.queue.")
            String kind,

            @NotBlank @Schema(description = "The subscription's name.")
            String name,

            @NotBlank @Schema(description = "The id of the destination this subscription binds to.")
            String destination,

            @Schema(
                    description = "The system's properties of the subscription: routingType (ANYCAST or MULTICAST),"
                            + " durable (default true), autoCreateAddress (default true), filter, maxConsumers,"
                            + " purgeOnNoConsumers, exclusive, nonDestructive, ringSize.")
            Map<String, Object> attributes) {

        public CreateSubscriptionRequest {
            attributes = attributes == null ? Map.of() : attributes;
        }

        /** The destination's name, which the permission checks and the broker calls are keyed by. */
        public String destinationName() {
            return ResourceIds.nameOf(destination);
        }

        public String routingType() {
            String value = RequestValues.text(attributes, "routingType");
            if (value == null || !value.matches("(?i)ANYCAST|MULTICAST")) {
                throw new IllegalArgumentException("routingType must be ANYCAST or MULTICAST.");
            }
            return value;
        }

        public boolean durable() {
            return RequestValues.flag(attributes, "durable", true);
        }

        public boolean autoCreateAddress() {
            return RequestValues.flag(attributes, "autoCreateAddress", true);
        }

        public String filter() {
            return RequestValues.text(attributes, "filter");
        }

        public Integer maxConsumers() {
            return RequestValues.integer(attributes, "maxConsumers");
        }

        public Boolean purgeOnNoConsumers() {
            return RequestValues.flag(attributes, "purgeOnNoConsumers");
        }

        public Boolean exclusive() {
            return RequestValues.flag(attributes, "exclusive");
        }

        public Boolean nonDestructive() {
            return RequestValues.flag(attributes, "nonDestructive");
        }

        public Long ringSize() {
            return RequestValues.longValue(attributes, "ringSize");
        }
    }

    /**
     * A sparse patch to a live subscription's configuration. Only the attributes present are changed; the
     * service reads the subscription's current configuration and sends the merged whole, because the broker's
     * update operation replaces rather than merges and would otherwise clear every field this patch omits (D7).
     *
     * <p>Routing type, destination, name and durability are not accepted: the broker refuses to change them on
     * a live subscription.
     */
    public record UpdateResourceRequest(
            @Schema(
                    description = "The attributes to change: filter, maxConsumers, purgeOnNoConsumers, exclusive,"
                            + " nonDestructive, ringSize.")
            Map<String, Object> attributes) {

        public UpdateResourceRequest {
            attributes = attributes == null ? Map.of() : attributes;
        }

        public String filter() {
            return RequestValues.text(attributes, "filter");
        }

        public Integer maxConsumers() {
            return RequestValues.integer(attributes, "maxConsumers");
        }

        public Boolean purgeOnNoConsumers() {
            return RequestValues.flag(attributes, "purgeOnNoConsumers");
        }

        public Boolean exclusive() {
            return RequestValues.flag(attributes, "exclusive");
        }

        public Boolean nonDestructive() {
            return RequestValues.flag(attributes, "nonDestructive");
        }

        public Long ringSize() {
            return RequestValues.longValue(attributes, "ringSize");
        }
    }

    /** A destination to create. */
    public record CreateDestinationRequest(
            @NotBlank @Schema(description = "The destination's kind, such as artemis.address.")
            String kind,

            @NotBlank @Schema(description = "The destination's name.")
            String name,

            @Schema(
                    description = "The system's properties of the destination: routingTypes, a comma-separated"
                            + " list of ANYCAST and MULTICAST.")
            Map<String, Object> attributes) {

        public CreateDestinationRequest {
            attributes = attributes == null ? Map.of() : attributes;
        }

        public String routingTypes() {
            String value = RequestValues.text(attributes, "routingTypes");
            if (value == null || !value.matches("(?i)ANYCAST|MULTICAST|ANYCAST,MULTICAST|MULTICAST,ANYCAST")) {
                throw new IllegalArgumentException("routingTypes must be ANYCAST, MULTICAST or both, comma-separated.");
            }
            return value;
        }
    }

    /** Reads a request's free-form attributes as the types the broker calls take. */
    private static final class RequestValues {

        private RequestValues() {}

        static String text(Map<String, Object> attributes, String key) {
            Object value = attributes.get(key);
            return value == null ? null : value.toString();
        }

        static Boolean flag(Map<String, Object> attributes, String key) {
            Object value = attributes.get(key);
            return value == null ? null : Boolean.valueOf(value.toString());
        }

        static boolean flag(Map<String, Object> attributes, String key, boolean fallback) {
            Boolean value = flag(attributes, key);
            return value == null ? fallback : value;
        }

        static Long longValue(Map<String, Object> attributes, String key) {
            Object value = attributes.get(key);
            if (value == null) {
                return null;
            }
            try {
                return Long.valueOf(value.toString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be a number.");
            }
        }

        static Integer integer(Map<String, Object> attributes, String key) {
            Long value = longValue(attributes, key);
            if (value != null && (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)) {
                throw new IllegalArgumentException(key + " is out of range.");
            }
            return value == null ? null : value.intValue();
        }
    }
}
