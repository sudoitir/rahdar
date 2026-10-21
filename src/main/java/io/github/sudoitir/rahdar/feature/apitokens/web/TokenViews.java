package io.github.sudoitir.rahdar.feature.apitokens.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Personal API tokens (api-tokens spec, ADR-0039, ADR-0136). */
public final class TokenViews {

    private TokenViews() {}

    public record TokenGrantRequest(
            @NotBlank @Schema(requiredMode = REQUIRED) String action,
            @NotBlank @Schema(requiredMode = REQUIRED) String scopeType,
            @Schema(nullable = true) UUID scopeId,

            @Schema(
                    description = "SUBSCRIPTION or DESTINATION: limits the grant to names matching resourcePattern",
                    nullable = true)
            String resourceType,

            @Schema(
                    description = "An Artemis wildcard pattern, with resourceType; absent for the whole scope",
                    nullable = true)
            String resourcePattern) {}

    public record CreateTokenRequest(
            @NotBlank String name,
            @NotNull @Schema(requiredMode = REQUIRED) Instant expiresAt,
            @NotEmpty @Schema(requiredMode = REQUIRED) List<@Valid TokenGrantRequest> grants,

            @Schema(description = "MCP tools the token may call; empty or absent means every tool", nullable = true)
            List<@NotBlank String> mcpTools) {}

    public record TokenGrantView(
            @Schema(requiredMode = REQUIRED) String action,
            @Schema(requiredMode = REQUIRED) String scopeType,
            @Schema(requiredMode = REQUIRED) UUID scopeId,
            @Schema(nullable = true) String resourceType,
            @Schema(nullable = true) String resourcePattern) {}

    /**
     * {@code expiresAt} is the effective expiry: the earlier of the token's own and its creation plus
     * the current maximum lifetime. {@code previousValidUntil} is when a rotated-out secret stops working.
     */
    public record TokenView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String owner,
            @Schema(requiredMode = REQUIRED) String prefix,
            @Schema(requiredMode = REQUIRED) Instant expiresAt,
            @Schema(nullable = true) Instant lastUsedAt,
            @Schema(nullable = true) Instant revokedAt,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(nullable = true) Instant previousValidUntil,
            @Schema(requiredMode = REQUIRED) List<TokenGrantView> grants,
            @Schema(requiredMode = REQUIRED) List<String> mcpTools,
            @Schema(requiredMode = REQUIRED) boolean stale) {}

    public record CreatedTokenView(
            @Schema(requiredMode = REQUIRED) TokenView token,
            @Schema(requiredMode = REQUIRED) String value) {}

    /** What minting may ask for right now. */
    public record TokenPolicyView(
            @Schema(requiredMode = REQUIRED, description = "ISO-8601 duration")
            String maxLifetime,

            @Schema(requiredMode = REQUIRED) Instant latestExpiry,

            @Schema(requiredMode = REQUIRED, description = "ISO-8601 duration")
            String rotationOverlap) {}

    public record UsageDayView(
            @Schema(requiredMode = REQUIRED) LocalDate day,
            @Schema(requiredMode = REQUIRED) long requests,
            @Schema(requiredMode = REQUIRED) long denied,
            @Schema(requiredMode = REQUIRED) long limited,
            @Schema(requiredMode = REQUIRED) long errors) {}

    public record UsageView(
            @Schema(requiredMode = REQUIRED) int days,
            @Schema(requiredMode = REQUIRED) long requests,
            @Schema(requiredMode = REQUIRED) long denied,
            @Schema(requiredMode = REQUIRED) long limited,
            @Schema(requiredMode = REQUIRED) long errors,
            @Schema(requiredMode = REQUIRED) List<UsageDayView> perDay) {}
}
