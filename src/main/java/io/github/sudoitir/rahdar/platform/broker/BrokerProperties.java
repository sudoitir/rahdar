package io.github.sudoitir.rahdar.platform.broker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Broker connection defaults.
 *
 * @param connectTimeout connect timeout of every broker call; Settings changes it live (ADR-0010)
 * @param readTimeout response timeout of every broker call; Settings changes it live (ADR-0010)
 * @param clockSkewToleranceMs how far a broker clock may disagree with Rahdar's before it
 *     is called skew. The floor is set by the measurement, not by taste: Jolokia reports
 *     its timestamp in whole seconds, so a single reading is ±500ms before the network is
 *     counted. Two seconds is the smallest value that is not mostly quantisation (ADR-0053).
 */
@ConfigurationProperties(prefix = "rahdar.broker")
public record BrokerProperties(
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("2000") long clockSkewToleranceMs) {}
