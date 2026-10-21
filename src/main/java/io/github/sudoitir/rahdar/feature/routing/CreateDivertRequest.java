package io.github.sudoitir.rahdar.feature.routing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A divert to create. Rahdar does not offer an in-place update: Artemis'
 * {@code updateDivert} replaces the configuration, and a partially applied
 * change presented as atomic is exactly what the routing spec forbids. Changing
 * a divert is a delete and a create, each confirmed and audited on its own.
 *
 * <p>Nothing here is temporary. A divert created over management survives a
 * broker restart (ADR-0065), so what the operator is told is that it will not
 * appear in the configuration their broker will next deploy — and the
 * {@code broker.xml} that would close that gap is generated from these values.
 */
public record CreateDivertRequest(
        @NotBlank @Size(max = 200) @Pattern(
                regexp = LifecycleRequests.MANAGEMENT_NAME,
                message = "A divert name cannot contain whitespace or any of , = : * ? \" \\")
        @Schema(description = "The divert's unique name on each node.")
        String name,

        @Size(max = 200) @Pattern(
                regexp = LifecycleRequests.MANAGEMENT_NAME,
                message = "A routing name cannot contain whitespace or any of , = : * ? \" \\")
        @Schema(nullable = true, description = "The routing name; defaults to the divert's name.")
        String routingName,

        @NotBlank @Size(max = 200) @Schema(description = "The address whose messages are diverted.")
        String address,

        @NotBlank @Size(max = 200) @Schema(description = "The address messages are diverted to.")
        String forwardingAddress,

        @Schema(
                description = "Take the message rather than copy it. Exclusive diverts are"
                        + " evaluated before non-exclusive ones.",
                defaultValue = "false")
        Boolean exclusive,

        @Size(max = 4000) @Schema(nullable = true, description = "A filter limiting which messages are diverted.")
        String filter,

        @Pattern(
                regexp = "(?i)ANYCAST|MULTICAST|PASS|STRIP",
                message = "A divert's routing type is ANYCAST, MULTICAST, PASS or STRIP.")
        @Schema(
                nullable = true,
                description = "Routing type applied to the diverted copy.",
                allowableValues = {"ANYCAST", "MULTICAST", "PASS", "STRIP"})
        String routingType,

        @Schema(
                description = "Create an exclusive divert even though its source address is being captured."
                        + " Capture of that address then observes nothing, because Artemis applies exclusive"
                        + " diverts before every other one.",
                defaultValue = "false")
        Boolean acknowledgeCaptureShadowing) {

    public CreateDivertRequest {
        exclusive = exclusive != null && exclusive;
        acknowledgeCaptureShadowing = acknowledgeCaptureShadowing != null && acknowledgeCaptureShadowing;
    }

    /** A divert that forwards to the address it reads from would feed itself. */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "A divert cannot forward to the address it reads from.") public boolean isForwardingAddressDistinct() {
        return address == null || !address.equals(forwardingAddress);
    }
}
