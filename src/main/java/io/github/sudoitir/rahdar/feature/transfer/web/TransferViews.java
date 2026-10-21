package io.github.sudoitir.rahdar.feature.transfer.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.feature.transfer.TransferMode;
import io.github.sudoitir.rahdar.feature.transfer.TransferState;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The transfer API (ADR-0097). */
public final class TransferViews {

    private TransferViews() {}

    public enum SelectionKind {
        /** Exactly the listed message ids. */
        IDS,
        /** The messages matching a filter, up to the run's frozen moment. */
        FILTER,
        /** The whole queue, up to the run's frozen moment. */
        ALL
    }

    /** Which messages: {@code ids} for {@code IDS}, {@code filter} for {@code FILTER}, neither for {@code ALL}. */
    public record TransferSelection(
            @Schema(requiredMode = REQUIRED) @NotNull SelectionKind kind,
            @Schema(nullable = true) List<Long> ids,
            @Schema(nullable = true) String filter) {}

    /**
     * A transfer to preview. The path's cluster is the source. Node ids are Rahdar's node ids, as the
     * cluster's node list gives them. {@code source} is a subscription id. {@code target} is a subscription
     * id, taken at its own destination or, for one that does not exist yet, created at the destination of
     * its name, or a destination id, where the subscription of its name takes the messages.
     */
    public record TransferPreviewRequest(
            @Schema(requiredMode = REQUIRED) @NotNull TransferMode mode,
            @Schema(requiredMode = REQUIRED) @NotBlank String source,
            @Schema(requiredMode = REQUIRED) @NotNull UUID sourceNodeId,
            @Schema(requiredMode = REQUIRED) @NotNull @Valid TransferSelection selection,
            @Schema(requiredMode = REQUIRED) @NotNull UUID targetClusterId,
            @Schema(requiredMode = REQUIRED) @NotNull UUID targetNodeId,
            @Schema(requiredMode = REQUIRED) @NotBlank String target) {}

    /**
     * @param planHash the preview's {@code planHash}, echoed to prove this is the plan being confirmed
     * @param override run a selection over the bulk cap
     * @param acknowledged the codes of every warning the preview raised
     * @param confirmSubscription a move only: the source subscription's name, typed by the operator
     */
    public record TransferExecuteRequest(
            @Schema(requiredMode = REQUIRED) @NotNull String planHash,
            boolean override,
            @Schema(nullable = true) List<String> acknowledged,
            @Schema(nullable = true) String confirmSubscription) {}

    public enum FindingKind {
        /** The transfer cannot run: the target would drop, or could not take, the messages. */
        REFUSE,
        /** A risk the operator must acknowledge before running. */
        WARN,
        /** A check that could not be made. Never counts as passing. */
        UNKNOWN
    }

    /**
     * One acceptance finding, in words.
     *
     * @param code stable, for acknowledging a warning
     * @param snippet the {@code broker.xml} that changes the verdict, where there is one
     */
    public record Finding(
            @Schema(requiredMode = REQUIRED) FindingKind kind,
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String words,
            @Schema(nullable = true) String snippet) {}

    /** One side of a transfer: a subscription and its destination, by id. */
    public record TransferEnd(
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) String subscription,
            @Schema(requiredMode = REQUIRED) String destination) {}

    /**
     * A run. Counts are cumulative over every segment (execute, resumes).
     *
     * @param estimate selected messages at preview; null when the source did not say
     * @param estimateBytes their estimated size; null when unavailable
     * @param staged messages a move has taken off the source into its staging queue
     * @param held messages a move holds in staging now: taken, and not yet delivered or expired
     * @param notTransferred selected messages the run could not take: in delivery to a consumer,
     *     scheduled, or gone from the source
     * @param expired messages that expired while held
     * @param returned messages a return put back on the source queue
     * @param messagesPerSecond delivered over the time since the run started, while it runs
     * @param stagingQueue a cross-node move's staging queue on the source broker
     * @param cap the {@code safety.bulk-cap} in force now; {@code overCap} when the estimate exceeds it
     *     or is unknown, and the run then needs the override
     */
    public record TransferRunView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) TransferMode mode,
            @Schema(requiredMode = REQUIRED) TransferState state,
            @Schema(requiredMode = REQUIRED) TransferEnd source,
            @Schema(requiredMode = REQUIRED) TransferEnd target,
            @Schema(requiredMode = REQUIRED) boolean sameNode,
            @Schema(requiredMode = REQUIRED) TransferSelection selection,
            @Schema(requiredMode = REQUIRED) Instant t0,
            @Schema(requiredMode = REQUIRED) String planHash,
            @Schema(requiredMode = REQUIRED) String username,
            @Schema(requiredMode = REQUIRED) Instant createdAt,
            @Schema(requiredMode = REQUIRED) Instant expiresAt,
            @Schema(nullable = true) Instant startedAt,
            @Schema(nullable = true) Instant updatedAt,
            @Schema(nullable = true) Instant finishedAt,
            @Schema(nullable = true) Long estimate,
            @Schema(nullable = true) Long estimateBytes,
            @Schema(requiredMode = REQUIRED) long staged,
            @Schema(requiredMode = REQUIRED) long held,
            @Schema(requiredMode = REQUIRED) long delivered,
            @Schema(requiredMode = REQUIRED) long notTransferred,
            @Schema(requiredMode = REQUIRED) long expired,
            @Schema(requiredMode = REQUIRED) long returned,
            @Schema(requiredMode = REQUIRED) long bytes,
            @Schema(nullable = true) Double messagesPerSecond,
            @Schema(nullable = true) String stagingQueue,
            @Schema(requiredMode = REQUIRED) List<Finding> findings,
            @Schema(requiredMode = REQUIRED) List<String> notes,
            @Schema(requiredMode = REQUIRED) long cap,
            @Schema(requiredMode = REQUIRED) boolean overCap,
            @Schema(requiredMode = REQUIRED) boolean overrideCap,
            @Schema(requiredMode = REQUIRED) boolean resumable,
            @Schema(requiredMode = REQUIRED) boolean returnable,
            @Schema(nullable = true) Long auditEventId,
            @Schema(nullable = true) Long targetAuditEventId,
            @Schema(nullable = true) String lastError,
            @Schema(nullable = true) String errorSnippet) {}

    /** The {@code transfer} stream topic's payload: one per batch and one per state change, on both clusters. */
    public record TransferProgress(
            @Schema(requiredMode = REQUIRED) UUID runId,
            @Schema(requiredMode = REQUIRED) TransferState state,
            @Schema(requiredMode = REQUIRED) long staged,
            @Schema(requiredMode = REQUIRED) long held,
            @Schema(requiredMode = REQUIRED) long delivered,
            @Schema(requiredMode = REQUIRED) long notTransferred,
            @Schema(nullable = true) Long estimate) {}

    /**
     * A staging queue no run knows: left by a run whose record is gone. {@code depth} is null when the
     * node did not say.
     */
    public record OrphanView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) String stagingQueue,
            @Schema(nullable = true) Long depth) {}

    /** Put an orphaned staging queue's messages on the {@code target} subscription of the same node, then remove it. */
    public record OrphanReturnRequest(
            @Schema(requiredMode = REQUIRED) @NotNull UUID nodeId,
            @Schema(requiredMode = REQUIRED) @NotBlank String stagingQueue,
            @Schema(requiredMode = REQUIRED) @NotBlank String target) {}

    /** What an orphan return did: {@code remaining} messages are still in staging when it could not finish. */
    public record OrphanReturnView(
            @Schema(requiredMode = REQUIRED) String stagingQueue,
            @Schema(requiredMode = REQUIRED) long returned,
            @Schema(requiredMode = REQUIRED) long remaining,
            @Schema(requiredMode = REQUIRED) boolean removed) {}
}
