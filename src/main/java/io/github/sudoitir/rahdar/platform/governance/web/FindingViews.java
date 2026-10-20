package io.github.sudoitir.rahdar.platform.governance.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** The classification inbox on the wire (data-governance spec). A finding never carries the detected value. */
public final class FindingViews {

    private FindingViews() {}

    /**
     * One field in which the detectors found a class of personal data no rule covers.
     *
     * @param fieldPath property or header name, or JSON body path; empty for a plain-text body
     * @param status OPEN, CONFIRMED or DISMISSED
     */
    public record FindingView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String destination,
            @Schema(requiredMode = REQUIRED) String location,
            @Schema(requiredMode = REQUIRED) String fieldPath,
            @Schema(requiredMode = REQUIRED) String dataClass,
            @Schema(requiredMode = REQUIRED) String dataClassLabel,
            @Schema(requiredMode = REQUIRED) String status,
            @Schema(requiredMode = REQUIRED) long hitCount,
            @Schema(requiredMode = REQUIRED) Instant firstSeenAt,
            @Schema(requiredMode = REQUIRED) Instant lastSeenAt) {}
}
