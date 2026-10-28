package io.github.sudoitir.rahdar.feature.messages.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.kernel.core.KindName;
import io.github.sudoitir.rahdar.platform.governance.GovernanceViews.RedactionView;
import io.github.sudoitir.rahdar.platform.governance.GovernanceViews.WithheldView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Phase 3 message API (ADR-0021). Message I/O is Jolokia-only: bodies are
 * carried as text and the broker truncates oversized values, disclosed per
 * message via {@code bodyTruncated} + {@code observedLimitBytes}. Every header,
 * property and body value is governed by the content policy (ADR-0075): a
 * sensitive value is its marker unless the caller holds clear access, and
 * {@code redactions} / {@code withheld} say which values those are.
 *
 * <p>{@code @Schema} on every component so the generated OpenAPI document (and
 * the frontend's {@code schema.d.ts}) declares requiredness and nullability
 * honestly (ADR-0019): required unless marked {@code nullable = true}.
 */
public final class MessageViews {

    private MessageViews() {}

    /** One row in the message grid. */
    public record MessageSummaryView(
            @Schema(requiredMode = REQUIRED, description = "The message's position on its node, opaque.")
            String position,

            @Schema(requiredMode = REQUIRED) int type,
            @Schema(requiredMode = REQUIRED) boolean durable,
            @Schema(requiredMode = REQUIRED) int priority,
            @Schema(requiredMode = REQUIRED) long timestamp,
            @Schema(requiredMode = REQUIRED) long expiration,
            @Schema(requiredMode = REQUIRED) long size,
            @Schema(nullable = true) String groupId,
            @Schema(nullable = true) String correlationId,
            @Schema(nullable = true) String bodyPreview,
            @Schema(requiredMode = REQUIRED) boolean bodyTruncated,
            @Schema(requiredMode = REQUIRED) int propertyCount,
            @Schema(requiredMode = REQUIRED) List<RedactionView> redactions,

            @Schema(
                    requiredMode = REQUIRED,
                    description =
                            "A log record with no value: it deletes its key on compaction. Never true on a queue.")
            boolean tombstone,

            @Schema(nullable = true, description = "A log record's key as text, or base64 when it is not text.")
            String key) {}

    /** One message, expanded: the full header set, the typed property maps, and the body. */
    public record MessageDetailView(
            @Schema(requiredMode = REQUIRED, description = "The message's position on its node, opaque.")
            String position,

            @Schema(requiredMode = REQUIRED) int type,
            @Schema(requiredMode = REQUIRED) boolean durable,
            @Schema(requiredMode = REQUIRED) int priority,
            @Schema(requiredMode = REQUIRED) long timestamp,
            @Schema(requiredMode = REQUIRED) long expiration,
            @Schema(requiredMode = REQUIRED) long size,
            @Schema(nullable = true) String groupId,
            @Schema(nullable = true) String correlationId,
            @Schema(nullable = true) String userId,
            @Schema(nullable = true) String body,
            @Schema(requiredMode = REQUIRED) String bodyEncoding,

            @Schema(
                    nullable = true,
                    description =
                            "How a text body was compressed on the broker (gzip or deflate); null when it was not.")
            String bodyCompression,

            @Schema(nullable = true) String contentType,
            @Schema(requiredMode = REQUIRED) boolean bodyTruncated,
            @Schema(nullable = true) Integer observedLimitBytes,

            @Schema(
                    nullable = true,
                    description = "The node the message was read from; null for a log, which has none.")
            UUID node,

            @Schema(requiredMode = REQUIRED) Map<String, String> stringProperties,
            @Schema(requiredMode = REQUIRED) Map<String, Long> intProperties,
            @Schema(requiredMode = REQUIRED) Map<String, Long> longProperties,
            @Schema(requiredMode = REQUIRED) Map<String, Double> doubleProperties,
            @Schema(requiredMode = REQUIRED) Map<String, Boolean> booleanProperties,
            @Schema(requiredMode = REQUIRED) List<RedactionView> redactions,
            @Schema(requiredMode = REQUIRED) List<WithheldView> withheld,
            @Schema(requiredMode = REQUIRED) boolean tombstone,
            @Schema(nullable = true) String key) {}

    /** A page of messages: the shared list envelope plus the node it was read from. */
    public record MessagePageView(
            @Schema(requiredMode = REQUIRED) List<MessageSummaryView> data,
            @Schema(requiredMode = REQUIRED) int page,
            @Schema(requiredMode = REQUIRED) int pageSize,

            @Schema(
                    nullable = true,
                    description = "The broker's own message count; null when it could not be obtained, never"
                            + " a guess — see countUnavailable.")
            Long count,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Whether another page follows; when count is null, a full page counts as one.")
            boolean hasNext,

            @Schema(nullable = true, description = "Why count is null. Null whenever count is present.")
            String countUnavailable,

            @Schema(nullable = true, description = "The node the page was read from; null for a log, which has none.")
            UUID node,

            @Schema(nullable = true, description = "Where in the log the page sits; null for a queue.")
            LogView log) {}

    /**
     * Where a page of a partitioned log sits. {@code start} and {@code end} are the partition's first offset and
     * the one after its last, so an offset asked for past the end is an empty page that says where the end is,
     * and one before the start is read from the start with {@code retentionMoved} set.
     */
    public record LogView(
            @Schema(requiredMode = REQUIRED) int partition,
            @Schema(requiredMode = REQUIRED) long start,
            @Schema(requiredMode = REQUIRED) long end,

            @Schema(nullable = true, description = "The offset the next page starts at; null at the end.")
            Long next,

            @Schema(requiredMode = REQUIRED, description = "The offset asked for was already removed by retention.")
            boolean retentionMoved,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Records of open or aborted transactions are not shown, as a read-committed"
                            + " consumer would not see them.")
            boolean uncommittedHidden,

            @Schema(
                    nullable = true,
                    description = "Where the group stands on this partition, when the log is read through a group's"
                            + " subscription: its committed offset (a share group's start offset). Absent when the"
                            + " log is read as a topic, or the group has no position on the partition.")
            Long subscriptionPosition) {}

    /**
     * A send whose outcome is unknown (HTTP 202): the message left, and the broker's confirmation never came
     * back, so it may or may not be on the broker. Look for it before sending it again (ADR-0211).
     *
     * @param inDoubt always true, so a client can tell this from an affected count
     * @param detail what happened and what to do next
     */
    public record InDoubtView(
            @Schema(requiredMode = REQUIRED) boolean inDoubt,
            @Schema(requiredMode = REQUIRED) String detail,
            @Schema(requiredMode = REQUIRED) UUID node) {}

    /** The result of a mutation: the broker's own affected count (not a dry-run estimate). */
    public record AffectedView(
            @Schema(requiredMode = REQUIRED, description = "Messages destroyed; 0 for a purge of a log.")
            long affectedCount,

            @Schema(requiredMode = REQUIRED) boolean dryRun,

            @Schema(nullable = true, description = "The node acted on; null where the system has none.")
            UUID node,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Records a purge of a log passed over without deleting them; 0 otherwise.")
            long skippedCount) {}

    /**
     * An operation by ids that stopped part-way. Some messages were acted on, so this is never
     * reported as a plain failure: the operator needs to know what already changed.
     *
     * @param affectedCount how many were acted on
     * @param notDone every id that was not acted on: refused by the broker, or never sent after the failure
     * @param error why it stopped
     */
    public record PartialView(
            @Schema(requiredMode = REQUIRED) long affectedCount,
            @Schema(requiredMode = REQUIRED) List<String> notDone,
            @Schema(requiredMode = REQUIRED) String error,
            @Schema(requiredMode = REQUIRED) boolean partial,
            @Schema(requiredMode = REQUIRED) UUID node) {}

    /**
     * A point-in-time estimate from the broker for a destructive action, before it runs.
     *
     * @param affectedCount broker-side estimate ({@code countMessages(filter)} / id count / {@code MessageCount})
     * @param cap the current {@code safety.bulk-cap}
     * @param overCap whether {@code affectedCount} exceeds the cap (UI must gate an override behind typed confirmation)
     */
    public record DryRunView(
            @Schema(requiredMode = REQUIRED) long affectedCount,
            @Schema(requiredMode = REQUIRED) long cap,
            @Schema(requiredMode = REQUIRED) boolean overCap,
            @Schema(nullable = true) UUID node,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Records a purge of a log would pass over without deleting them; 0 otherwise."
                            + " The cap counts destroyed messages only, so it never applies to these.")
            long skippedCount) {}

    // ---- dead letters (Slice 9) -----------------------------------------

    public record DeadLetterDepth(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) long depth) {}

    public record DeadLetterSubscription(
            @Schema(requiredMode = REQUIRED, description = "The subscription's canonical id.")
            String id,

            @Schema(requiredMode = REQUIRED, description = "The id of its kind, such as artemis.queue.")
            String kind,

            @Schema(requiredMode = REQUIRED, description = "The kind's name for people, singular and plural.")
            KindName kindLabel,

            @Schema(requiredMode = REQUIRED) String name,

            @Schema(requiredMode = REQUIRED, description = "The id of the destination it is bound to.")
            String destination,

            @Schema(requiredMode = REQUIRED) long totalDepth,
            @Schema(requiredMode = REQUIRED) List<DeadLetterDepth> perNode) {}

    public record DeadLetterDestination(
            @Schema(requiredMode = REQUIRED, description = "The destination's canonical id.")
            String id,

            @Schema(requiredMode = REQUIRED, description = "The id of its kind, such as artemis.address.")
            String kind,

            @Schema(requiredMode = REQUIRED, description = "The kind's name for people, singular and plural.")
            KindName kindLabel,

            @Schema(requiredMode = REQUIRED) String name,

            @Schema(requiredMode = REQUIRED, description = "dead-letter or expiry.")
            String purpose,

            @Schema(requiredMode = REQUIRED) List<DeadLetterSubscription> subscriptions) {}

    public record DeadLetterView(
            @Schema(requiredMode = REQUIRED) List<DeadLetterDestination> destinations,
            @Schema(requiredMode = REQUIRED) boolean settingsAvailable) {}
}
