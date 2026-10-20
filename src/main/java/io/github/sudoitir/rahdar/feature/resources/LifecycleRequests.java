package io.github.sudoitir.rahdar.feature.resources;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request bodies for the queue, address and divert lifecycle API (ADR-0049, ADR-0065). */
public final class LifecycleRequests {

    private LifecycleRequests() {}

    /** No whitespace and none of the characters a broker management object name cannot safely carry. */
    public static final String MANAGEMENT_NAME = "[^\\s,=:*?\"\\\\]*";

    /**
     * A queue to create. {@code address}, {@code routingType}, {@code name} and
     * {@code durable} identify the queue and cannot be changed afterwards; the rest
     * is configuration the broker accepts at creation.
     *
     * <p>{@code autoCreateAddress} is explicit rather than left to the broker's
     * default: with it on, a create that fails validation still leaves the
     * auto-created address behind, so the operator is told which is happening.
     */
    public record CreateQueueRequest(
            @NotBlank @Schema(description = "The address this queue binds to.")
            String address,

            @NotBlank @Schema(description = "The queue's name.")
            String name,

            @NotBlank @Pattern(regexp = "(?i)ANYCAST|MULTICAST")
            @Schema(
                    description = "ANYCAST or MULTICAST. Cannot be changed once the queue exists.",
                    allowableValues = {"ANYCAST", "MULTICAST"})
            String routingType,

            @Schema(description = "Whether the queue survives a broker restart.", defaultValue = "true")
            Boolean durable,

            @Schema(nullable = true, description = "A JMS selector limiting what the queue accepts.")
            String filter,

            @Schema(nullable = true, description = "Maximum concurrent consumers; -1 for unlimited.")
            Integer maxConsumers,

            @Schema(nullable = true, description = "Delete the queue when its last consumer disconnects.")
            Boolean purgeOnNoConsumers,

            @Schema(nullable = true, description = "Route to exactly one consumer at a time.")
            Boolean exclusive,

            @Schema(nullable = true, description = "Messages stay on the queue after delivery.")
            Boolean nonDestructive,

            @Schema(nullable = true, description = "Retain at most this many messages; -1 for unlimited.")
            Long ringSize,

            @Schema(description = "Create the address if it does not exist.", defaultValue = "true")
            Boolean autoCreateAddress) {

        public CreateQueueRequest {
            durable = durable == null || durable;
            autoCreateAddress = autoCreateAddress == null || autoCreateAddress;
        }
    }

    /**
     * A sparse patch to a live queue's configuration. Only the fields present are
     * changed; the service reads the queue's current configuration and sends the
     * merged whole, because the broker's update operation replaces rather than
     * merges and would otherwise clear every field this patch omits (D7).
     *
     * <p>Routing type, address, name and durability are absent on purpose — the
     * broker refuses to change them on a live queue.
     */
    public record UpdateQueueRequest(
            @Schema(nullable = true, description = "A JMS selector limiting what the queue accepts.")
            String filter,

            @Schema(nullable = true) Integer maxConsumers,
            @Schema(nullable = true) Boolean purgeOnNoConsumers,
            @Schema(nullable = true) Boolean exclusive,
            @Schema(nullable = true) Boolean nonDestructive,
            @Schema(nullable = true) Long ringSize) {}

    /** An address to create. */
    public record CreateAddressRequest(
            @NotBlank @Schema(description = "The address name.")
            String name,

            @NotBlank @Pattern(regexp = "(?i)ANYCAST|MULTICAST|ANYCAST,MULTICAST|MULTICAST,ANYCAST")
            @Schema(
                    description = "Comma-separated routing types the address supports.",
                    allowableValues = {"ANYCAST", "MULTICAST", "ANYCAST,MULTICAST"})
            String routingTypes) {}
}
