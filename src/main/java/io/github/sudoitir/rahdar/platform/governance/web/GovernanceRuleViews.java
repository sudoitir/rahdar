package io.github.sudoitir.rahdar.platform.governance.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Masking rules on the wire (data-governance spec). Required unless marked nullable (ADR-0019). */
public final class GovernanceRuleViews {

    private GovernanceRuleViews() {}

    /**
     * One rule. {@code exception} marks a dismissed finding: it leaves one class clear in one field.
     *
     * @param action null means the class default, stated in {@code defaultAction}
     */
    public record RuleView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(nullable = true) String destinationPattern,
            @Schema(requiredMode = REQUIRED) String target,
            @Schema(requiredMode = REQUIRED) String selector,
            @Schema(requiredMode = REQUIRED) String dataClass,
            @Schema(requiredMode = REQUIRED) String dataClassLabel,
            @Schema(nullable = true) String action,
            @Schema(requiredMode = REQUIRED) String defaultAction,
            @Schema(requiredMode = REQUIRED) boolean builtin,
            @Schema(requiredMode = REQUIRED) boolean enabled,
            @Schema(requiredMode = REQUIRED) boolean exception,
            @Schema(requiredMode = REQUIRED) Instant updatedAt) {}

    /** Create or replace a rule. A built-in rule accepts only {@code enabled}; its other fields are facts. */
    public record RuleRequest(
            @Schema(nullable = true) @Size(max = 255) @Pattern(regexp = "[^\\s]*", message = "must not contain spaces") String destinationPattern,

            @Schema(requiredMode = REQUIRED) @NotNull @Pattern(regexp = "HEADER|PROPERTY|BODY_PATH") String target,

            @Schema(requiredMode = REQUIRED) @NotBlank @Size(max = 255) String selector,

            @Schema(requiredMode = REQUIRED)
            @NotNull @Pattern(regexp = "CREDENTIAL|PAN|IBAN|EMAIL|PHONE|NATIONAL_ID|PERSONAL") String dataClass,

            @Schema(nullable = true) @Pattern(regexp = "DROP|PARTIAL|REDACT") String action,

            @Schema(requiredMode = REQUIRED) boolean enabled) {}

    /** How many stored rows are still masked under an earlier policy version. */
    public record PolicyView(
            @Schema(requiredMode = REQUIRED) int version,
            @Schema(requiredMode = REQUIRED) long rowsUnderEarlierVersion,

            @Schema(requiredMode = REQUIRED, description = "The count reached its cap; there are at least this many.")
            boolean capped) {}
}
