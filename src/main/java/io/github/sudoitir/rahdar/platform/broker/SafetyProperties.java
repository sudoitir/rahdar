package io.github.sudoitir.rahdar.platform.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Server-enforced ceiling on a single destructive message operation (ADR-0022). A dry-run
 * count above this is a {@code 422} unless the caller passes {@code ?override=true} behind
 * the UI's typed confirmation.
 *
 * <p>{@code bulkSubscriptionCap} bounds how many queues one bulk run may act on (ADR-0093), with no override.
 */
@ConfigurationProperties(prefix = "rahdar.safety")
public record SafetyProperties(
        @DefaultValue("1000") int bulkCap,
        @DefaultValue("200") int bulkSubscriptionCap) {}
