package io.github.sudoitir.rahdar.feature.messages.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
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

            @Schema(
                    nullable = true,
                    description = "When the producer stamped it, epoch milliseconds; null when it carries no time.")
            Long timestamp,

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
            String key,

            @Schema(
                    nullable = true,
                    allowableValues = {"TEXT", "BASE64"},
                    description = "How key is written; null with no key.")
            String keyEncoding,

            @Schema(
                    nullable = true,
                    description = "The partition this record was read from. Null on a queue, which has none.")
            Integer partition) {}

    /** One message, expanded: the full header set, the typed property maps, and the body. */
    public record MessageDetailView(
            @Schema(requiredMode = REQUIRED, description = "The message's position on its node, opaque.")
            String position,

            @Schema(requiredMode = REQUIRED) int type,
            @Schema(requiredMode = REQUIRED) boolean durable,
            @Schema(requiredMode = REQUIRED) int priority,

            @Schema(
                    nullable = true,
                    description = "When the producer stamped it, epoch milliseconds; null when it carries no time.")
            Long timestamp,

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
            @Schema(nullable = true) String key,

            @Schema(
                    nullable = true,
                    allowableValues = {"TEXT", "BASE64"},
                    description = "How key is written; null with no key.")
            String keyEncoding) {}

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
            LogView log,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The page was cut short at its byte budget: messages the broker returned are not in"
                            + " it. Ask for a smaller page, or, on a log, continue from log.next.")
            boolean truncated,

            @Schema(nullable = true, description = "Something worth saying about this page, such as why it is empty.")
            String notice) {}

    /**
     * Where the next page of a merged read resumes on one partition. The offset belongs to that partition.
     * {@code pending} names earlier offsets that sorted after a record this page showed, so the next read delivers
     * them without reading that record again.
     */
    public record PartitionResume(
            @Schema(requiredMode = REQUIRED) int partition,
            @Schema(requiredMode = REQUIRED) long offset,

            @Schema(
                    nullable = true,
                    description = "The offset the next read of this partition stops before, so a later page does not"
                            + " repeat a record already shown. Absent when the next read starts at offset and runs"
                            + " for a page.")
            Long end,

            @JsonInclude(JsonInclude.Include.NON_EMPTY)
            @Schema(
                    nullable = true,
                    description = "Inclusive offsets before offset that are still to deliver, as ranges such as 10 or"
                            + " 10-12.14. Absent when the next read just continues at offset.")
            String pending) {

        /** A continuation that starts at {@code offset} and does not still owe an earlier one. */
        public PartitionResume(int partition, long offset, Long end) {
            this(partition, offset, end, null);
        }

        public PartitionResume {
            if (pending != null && pending.isBlank()) {
                pending = null;
            }
        }
    }

    /**
     * Where a page of a partitioned log sits. When {@code partition} is set, {@code start} and {@code end} are that
     * partition's first offset and the one after its last, so an offset asked for past the end is an empty page that
     * says where the end is, and one before the start is read from the start with {@code retentionMoved} set.
     * When {@code partition} is null the page merges every partition: {@code next} is null, and {@code resume} holds
     * one offset per partition that still has records.
     */
    public record LogView(
            @Schema(
                    nullable = true,
                    description = "The partition this page was read from. Null when the page merges every partition.")
            Integer partition,

            @Schema(requiredMode = REQUIRED) long start,
            @Schema(requiredMode = REQUIRED) long end,

            @Schema(
                    nullable = true,
                    description = "The offset the next page of this one partition starts at; null at the end, and"
                            + " null when the page merges every partition (see resume).")
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
                            + " log is read as a topic, or the group has no position on the partition. Null when the page"
                            + " merges every partition: the group stands on each partition separately.")
            Long subscriptionPosition,

            @Schema(
                    nullable = true,
                    description = "Where a merged page continues: one offset per partition that still has records,"
                            + " and any earlier offsets that page still owes. Empty when a merged page is finished."
                            + " Null when the page is one partition, whose next offset is next. There is no offset that"
                            + " applies to every partition.")
            List<PartitionResume> resume) {}

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
            long skippedCount,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "What the preview's etag holds: INCARNATION, so a resource deleted and created"
                            + " again since is refused; CONFIGURATION, so one created again with the same"
                            + " configuration is not told apart.",
                    allowableValues = {"INCARNATION", "CONFIGURATION"})
            String identityBasis) {}

    // ---- dead letters (Slice 9) -----------------------------------------

    public record DeadLetterDepth(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,

            @Schema(nullable = true, description = "Null when the node reported no depth: unknown, not empty.")
            Long depth) {}

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

            @Schema(nullable = true, description = "The depth over the nodes that reported one; null when none did.")
            Long totalDepth,

            @Schema(
                    requiredMode = REQUIRED,
                    description =
                            "How many nodes reported no depth, which the total leaves out rather than counts as 0.")
            int unknownDepthNodes,

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
