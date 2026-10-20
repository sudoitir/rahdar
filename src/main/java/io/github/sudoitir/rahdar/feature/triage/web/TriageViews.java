package io.github.sudoitir.rahdar.feature.triage.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.feature.triage.ConsumerHealth;
import io.github.sudoitir.rahdar.kernel.security.ResourceIdTexts.Of;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The consumer-health API (ADR-0089). {@code @Schema} on every component so the generated
 * OpenAPI document — and the frontend's {@code schema.d.ts} — declares requiredness and
 * nullability honestly (ADR-0019).
 *
 * <p>Every rate is nullable on purpose. A null is "not computable from the samples
 * available", which the UI must render as such; serialising it as {@code 0} would turn
 * "we have not measured this subscription" into "this subscription has no throughput", which are
 * opposite operational facts.
 */
public final class TriageViews {

    private TriageViews() {}

    public record ConsumerHealthView(
            @Schema(requiredMode = REQUIRED, description = "The destination's id.")
            String destination,

            @Schema(requiredMode = REQUIRED, description = "The subscription's id.")
            String subscription,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "One of INSUFFICIENT_DATA, PAUSED, NO_CONSUMERS, BROKER_SLOW,"
                            + " STALLED, STARVED, FALLING_BEHIND, DRAINING, HEALTHY.")
            String verdict,

            @Schema(requiredMode = REQUIRED, description = "0-4; higher needs attention sooner.")
            int severity,

            @Schema(requiredMode = REQUIRED, description = "The likely cause and the next action, in words.")
            String cause,

            @Schema(requiredMode = REQUIRED, description = "BROKER when the broker judged it, else DERIVED.")
            String source,

            @Schema(nullable = true) String brokerConsumerName,
            @Schema(requiredMode = REQUIRED) long depth,
            @Schema(requiredMode = REQUIRED) long consumers,
            @Schema(requiredMode = REQUIRED) long delivering,
            @Schema(requiredMode = REQUIRED) long scheduled,
            @Schema(requiredMode = REQUIRED) boolean paused,

            @Schema(nullable = true, description = "Change in depth per second; positive is growing.")
            Double depthSlopePerSecond,

            @Schema(nullable = true) Double addRate,
            @Schema(nullable = true) Double ackRate,
            @Schema(nullable = true) Double netRate,
            @Schema(nullable = true) Double ackRatePerConsumer,

            @Schema(nullable = true, description = "Seconds until the backlog clears; only set while DRAINING.")
            Long drainEtaSeconds,

            @Schema(nullable = true, description = "The newest sample the rates rest on.")
            Instant asOf,

            @Schema(nullable = true, description = "Seconds the rates were measured over.")
            Long sampleSpanSeconds,

            @Schema(requiredMode = REQUIRED) boolean stale,
            @Schema(requiredMode = REQUIRED) int nodesPresent,
            @Schema(requiredMode = REQUIRED) int nodesTotal) {

        public static ConsumerHealthView of(Of ids, ConsumerHealth h) {
            return new ConsumerHealthView(
                    ids.destination(h.destination()),
                    ids.subscription(h.subscription()),
                    h.verdict().name(),
                    h.verdict().severity(),
                    h.cause(),
                    h.source().name(),
                    h.brokerConsumerName(),
                    h.depth(),
                    h.consumers(),
                    h.delivering(),
                    h.scheduled(),
                    h.paused(),
                    h.depthSlopePerSecond(),
                    h.addRate(),
                    h.ackRate(),
                    h.netRate(),
                    h.ackRatePerConsumer(),
                    h.drainEta() == null ? null : h.drainEta().toSeconds(),
                    h.asOf(),
                    h.sampleSpan() == null ? null : h.sampleSpan().toSeconds(),
                    h.stale(),
                    h.nodesPresent(),
                    h.nodesTotal());
        }
    }
}
