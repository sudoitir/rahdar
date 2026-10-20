package io.github.sudoitir.rahdar.feature.rr.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The request-reply tracing API (request-reply-tracing spec). */
public final class RrViews {

    private RrViews() {}

    public record ExpectationView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String requestDestination,
            @Schema(requiredMode = REQUIRED) List<String> replyDestinations,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "What replyDestinations currently expands to, from the last scrape's addresses")
            List<String> resolvedReplyDestinations,

            @Schema(requiredMode = REQUIRED, description = "The resolution cap cut the expansion short")
            boolean replyDestinationsCapped,

            @Schema(nullable = true) String correlationProperty,
            @Schema(nullable = true) Integer deadlineMs,
            @Schema(requiredMode = REQUIRED) int samplePerMin,
            @Schema(requiredMode = REQUIRED) boolean capturePayload,
            @Schema(requiredMode = REQUIRED) boolean enabled) {}

    public record CreateExpectationRequest(
            @NotBlank String requestDestination,
            List<String> replyDestinations,
            String correlationProperty,
            @Min(1) Integer deadlineMs,
            @Min(1) int samplePerMin,
            boolean capturePayload) {}

    public record UpdateExpectationRequest(
            List<String> replyDestinations,
            String correlationProperty,
            @Min(1) Integer deadlineMs,
            @Min(1) int samplePerMin,
            boolean capturePayload,
            boolean enabled) {}

    public record RrEventView(
            @Schema(requiredMode = REQUIRED) long seq,
            @Schema(requiredMode = REQUIRED) Instant ts,
            @Schema(requiredMode = REQUIRED) String kind,
            @Schema(nullable = true) UUID nodeId,
            @Schema(nullable = true) Map<String, Object> detail) {}

    public record FlowView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(nullable = true) UUID nodeId,
            @Schema(nullable = true) String requestDestination,
            @Schema(nullable = true) String replyDestination,
            @Schema(requiredMode = REQUIRED) String replyKind,
            @Schema(requiredMode = REQUIRED) String state,
            @Schema(nullable = true) String correlationId,
            @Schema(requiredMode = REQUIRED) Instant requestedAt,
            @Schema(nullable = true) Instant deadlineAt,
            @Schema(nullable = true) Instant repliedAt,
            @Schema(nullable = true) Long latencyMs,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "How latencyMs was measured: OBSERVED (quantised to the sample interval, "
                            + "see latencyBoundMs) or MESSAGE_TIMESTAMPS (the messages' own clocks, normalised)")
            String latencySource,

            @Schema(nullable = true, description = "The error bar on an OBSERVED latency, in milliseconds")
            Integer latencyBoundMs,

            @Schema(nullable = true, description = "When the request says it was produced, on Rahdar's clock")
            Instant requestEnqueuedAt,

            @Schema(nullable = true) Instant replyEnqueuedAt,

            @Schema(
                    nullable = true,
                    description = "How far into the future the request claimed to be produced. Forward skew only: "
                            + "an earlier timestamp is ordinary queue residency, not evidence of a wrong clock")
            Long requestSkewMs,

            @Schema(nullable = true) Long replySkewMs,
            @Schema(nullable = true) List<RrEventView> events) {}

    public record DestinationStatsView(
            @Schema(requiredMode = REQUIRED) String destination,
            @Schema(requiredMode = REQUIRED) long inFlight,
            @Schema(nullable = true) Long oldestInFlightMs,
            @Schema(requiredMode = REQUIRED) long completed,
            @Schema(requiredMode = REQUIRED) long timedOut,
            @Schema(requiredMode = REQUIRED) long orphaned,
            @Schema(requiredMode = REQUIRED) long responderDropped,
            @Schema(nullable = true) Double p50Ms,
            @Schema(nullable = true) Double p95Ms,
            @Schema(nullable = true) Double p99Ms,
            @Schema(requiredMode = REQUIRED) boolean sampled,
            @Schema(nullable = true) Double coverageRatio,
            @Schema(requiredMode = REQUIRED) long windowMs) {}

    public record StatsResponse(
            @Schema(requiredMode = REQUIRED) List<DestinationStatsView> destinations) {}

    /**
     * One reason an operator might be seeing no flows, and what to do about it.
     *
     * <p>Ranked most-likely-first and each carrying its own remedy: an empty Flows
     * tab used to render as a bare {@code 0 flows}, which is indistinguishable from
     * "nothing was sent" and from "Rahdar never looked".
     */
    public record TracingReasonView(
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String summary,
            @Schema(requiredMode = REQUIRED) String remedy) {}

    /** What the sampler did for one traced address on its most recent tick. */
    public record ExpectationDiagnosticsView(
            @Schema(requiredMode = REQUIRED) UUID expectationId,
            @Schema(requiredMode = REQUIRED) String requestDestination,
            @Schema(requiredMode = REQUIRED) boolean enabled,
            @Schema(nullable = true) Instant lastAttemptAt,
            @Schema(nullable = true) Instant lastSuccessAt,
            @Schema(requiredMode = REQUIRED) int nodesSampled,
            @Schema(requiredMode = REQUIRED) int nodesTotal,

            @Schema(requiredMode = REQUIRED, description = "Nodes not sampled this tick, each with the reason")
            List<String> skipped,

            @Schema(requiredMode = REQUIRED) int messagesBrowsed,
            @Schema(requiredMode = REQUIRED) int observations,
            @Schema(nullable = true) String lastError,
            @Schema(nullable = true) Instant lastErrorAt,
            @Schema(requiredMode = REQUIRED) int samplePerMin,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The requested rate is faster than rr.sample-interval can deliver; "
                            + "the global interval is the floor")
            boolean rateExceedsInterval) {}

    /** Rahdar's current verdict on the clocks it can see (ADR-0053). */
    public record ClockDiagnosticsView(
            @Schema(
                    requiredMode = REQUIRED,
                    description = "UNKNOWN, IN_AGREEMENT, BROKER_SKEWED, or RAHDAR_SUSPECT — the last meaning "
                            + "every node disagrees the same way, so the common factor is Rahdar's own host")
            String verdict,

            @Schema(nullable = true) Long worstOffsetMs,
            @Schema(nullable = true) Long uncertaintyMs,
            @Schema(requiredMode = REQUIRED) List<String> skewedNodes,
            @Schema(nullable = true) Instant measuredAt,
            @Schema(requiredMode = REQUIRED) long toleranceMs) {}

    /** Why tracing is or is not producing flows on this cluster. */
    public record RrDiagnosticsView(
            @Schema(requiredMode = REQUIRED) Instant asOf,
            @Schema(requiredMode = REQUIRED) long sampleIntervalMs,
            @Schema(requiredMode = REQUIRED) int nodesWithCoreEndpoint,
            @Schema(requiredMode = REQUIRED) int nodesTotal,
            @Schema(requiredMode = REQUIRED) String notificationsCapability,
            @Schema(requiredMode = REQUIRED) ClockDiagnosticsView clock,
            @Schema(requiredMode = REQUIRED) List<ExpectationDiagnosticsView> expectations,
            @Schema(requiredMode = REQUIRED) List<TracingReasonView> reasons) {}
}
