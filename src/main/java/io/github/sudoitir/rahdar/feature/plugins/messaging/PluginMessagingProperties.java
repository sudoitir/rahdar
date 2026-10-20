package io.github.sudoitir.rahdar.feature.plugins.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Plugin messaging (ADR-0111, ADR-0112).
 *
 * @param reconcileInterval how often registrations are converged onto the broker; also how soon a
 *     revoked permission, a failover or a stopped plugin is acted on
 * @param tapRingSize how many copies a tap queue holds before dropping the oldest; its byte bound,
 *     expiry and broker role are capture's ({@code rahdar.capture.*})
 */
@Validated
@ConfigurationProperties(prefix = "rahdar.plugins.messaging")
public record PluginMessagingProperties(
        @DefaultValue("10s") Duration reconcileInterval,
        @DefaultValue("10000") long tapRingSize) {}
