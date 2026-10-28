package io.github.sudoitir.rahdar.feature.bulk.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.feature.bulk.BulkItemStatus;
import io.github.sudoitir.rahdar.feature.bulk.BulkOperation;
import io.github.sudoitir.rahdar.feature.bulk.BulkRunStatus;
import io.github.sudoitir.rahdar.kernel.core.KindName;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The bulk-run API (ADR-0093). */
public final class BulkViews {

    private BulkViews() {}

    /**
     * What to preview: explicit subscription {@code ids} ({@code artemis.queue/orders}), in the order given, or,
     * when {@code ids} is null, every subscription whose name or destination contains {@code q} (every
     * subscription when {@code q} is blank).
     */
    public record BulkPreviewRequest(
            @Schema(requiredMode = REQUIRED) @NotNull BulkOperation operation,
            @Schema(nullable = true) List<String> ids,
            @Schema(nullable = true) String q,

            @Schema(description = "Delete only: close attached consumers instead of refusing the queue.")
            boolean disconnectConsumers) {}

    /** @param planHash the preview's {@code planHash}, echoed to prove this is the plan being confirmed */
    public record BulkExecuteRequest(
            @Schema(requiredMode = REQUIRED) @NotNull String planHash, boolean override, boolean continueOnFailure) {}

    /** How the operator chose the subscriptions: their ids, or the filter. */
    public record BulkSelection(
            @Schema(nullable = true) List<String> ids,
            @Schema(nullable = true) String q) {}

    /** One node's figures for a queue at preview; null where the node has not answered recently. */
    public record NodeFigure(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(nullable = true) Long messageCount,
            @Schema(nullable = true) Long consumerCount,
            @Schema(requiredMode = REQUIRED) boolean paused) {}

    /**
     * @param estimate messages destroyed across the queues not refused, summed over the figures that are
     *     known; zero for pause and resume
     * @param estimateComplete false when any figure behind {@code estimate} is unknown, so it is a floor
     * @param cap the {@code safety.bulk-cap} in force now
     * @param overCap a destructive run whose estimate exceeds {@code cap}: executing it needs the override
     */
    public record BulkRunView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) BulkOperation operation,
            @Schema(requiredMode = REQUIRED) BulkRunStatus status,
            @Schema(requiredMode = REQUIRED) String username,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) Instant expiresAt,
            @Schema(nullable = true) Instant startedAt,
            @Schema(nullable = true) Instant finishedAt,
            @Schema(requiredMode = REQUIRED) int total,
            @Schema(requiredMode = REQUIRED) int succeeded,
            @Schema(requiredMode = REQUIRED) int failed,
            @Schema(requiredMode = REQUIRED) int skipped,
            @Schema(nullable = true) Long estimate,
            @Schema(requiredMode = REQUIRED) boolean estimateComplete,
            @Schema(requiredMode = REQUIRED) long cap,
            @Schema(requiredMode = REQUIRED) boolean overCap,
            @Schema(requiredMode = REQUIRED) boolean overrideCap,
            @Schema(requiredMode = REQUIRED) boolean continueOnFailure,
            @Schema(requiredMode = REQUIRED) boolean disconnectConsumers,
            @Schema(requiredMode = REQUIRED) BulkSelection selection,
            @Schema(requiredMode = REQUIRED) String planHash,
            @Schema(nullable = true) Long auditEventId,
            @Schema(nullable = true) String error) {}

    /**
     * @param error why the subscription was refused or did not succeed
     * @param warning what the preview noted without refusing, such as an unknown figure
     * @param outcome each node's result once acted on, in the single-queue command's shape
     */
    public record BulkItemView(
            @Schema(requiredMode = REQUIRED) int ordinal,

            @Schema(requiredMode = REQUIRED, description = "The subscription's id.")
            String subscription,

            @Schema(requiredMode = REQUIRED, description = "The id of the subscription's kind, such as artemis.queue.")
            String kind,

            @Schema(requiredMode = REQUIRED, description = "The kind's name for people, singular and plural.")
            KindName kindLabel,

            @Schema(requiredMode = REQUIRED) BulkItemStatus status,
            @Schema(nullable = true) String error,
            @Schema(nullable = true) String warning,
            @Schema(nullable = true) Long affected,
            @Schema(requiredMode = REQUIRED) List<NodeFigure> nodes,
            @Schema(nullable = true) List<NodeOutcome> outcome,
            @Schema(nullable = true) Instant startedAt,
            @Schema(nullable = true) Instant finishedAt) {}

    public record BulkRunDetailView(
            @Schema(requiredMode = REQUIRED) BulkRunView run,
            @Schema(requiredMode = REQUIRED) List<BulkItemView> items) {}

    /** The {@code bulk} stream topic's payload, one per queue acted on and one at the end. */
    public record BulkProgress(
            @Schema(requiredMode = REQUIRED) UUID runId,
            @Schema(requiredMode = REQUIRED) BulkRunStatus status,
            @Schema(requiredMode = REQUIRED) int succeeded,
            @Schema(requiredMode = REQUIRED) int failed,
            @Schema(requiredMode = REQUIRED) int skipped,
            @Schema(requiredMode = REQUIRED) int total) {}
}
