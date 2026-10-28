package io.github.sudoitir.rahdar.system.kafka.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.Backlog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** What the Kafka namespace answers (ADR-0216). Every figure that is not known is absent with its reason, never zero. */
public final class KafkaViews {

    private KafkaViews() {}

    // ---- groups -------------------------------------------------------------------------------------------------

    public record GroupRow(
            @Schema(requiredMode = REQUIRED) String id,

            @Schema(requiredMode = REQUIRED, description = "classic, consumer, share, streams or unknown.")
            String type,

            @Schema(requiredMode = REQUIRED) String state,
            @Schema(requiredMode = REQUIRED) long members,

            @Schema(requiredMode = REQUIRED, description = "Total lag over the group's topics, an estimate.")
            Backlog lag,

            @Schema(nullable = true, description = "Why the group's offsets cannot be changed; absent when they can.")
            String readOnly) {}

    public record GroupPage(
            @Schema(requiredMode = REQUIRED) List<GroupRow> items,

            @Schema(nullable = true, description = "The cursor of the next page; absent on the last.")
            String next) {}

    public record PartitionLag(
            @Schema(requiredMode = REQUIRED) int partition,

            @Schema(nullable = true, description = "The group's committed (or start) offset; absent when it has none.")
            Long committed,

            @Schema(nullable = true, description = "The partition's end offset; absent when it could not be read.")
            Long end,

            @Schema(nullable = true, description = "Absent, with the reason, where the lag is not known.")
            Long lag,

            @Schema(nullable = true) String reason) {}

    public record GroupTopic(
            @Schema(requiredMode = REQUIRED) String topic,

            @Schema(requiredMode = REQUIRED, description = "The group has offsets on a topic that no longer exists.")
            boolean deleted,

            @Schema(requiredMode = REQUIRED, description = "Slowest first.")
            List<PartitionLag> partitions) {}

    public record GroupMember(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String clientId,
            @Schema(requiredMode = REQUIRED) String host,

            @Schema(requiredMode = REQUIRED, description = "Partitions as topic-partition.")
            List<String> assignments) {}

    public record GroupDetail(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String type,
            @Schema(requiredMode = REQUIRED) String state,
            @Schema(nullable = true) String readOnly,
            @Schema(requiredMode = REQUIRED) List<GroupTopic> topics,
            @Schema(requiredMode = REQUIRED) List<GroupMember> members,
            @Schema(requiredMode = REQUIRED) Instant sampledAt) {}

    // ---- offsets ------------------------------------------------------------------------------------------------

    /** Where a reset moves a group: {@code to} is earliest, latest, time (an ISO-8601 instant) or offset. */
    public record OffsetRequest(
            @Schema(description = "The topics to move; every topic of the group when absent.")
            List<String> topics,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"earliest", "latest", "time", "offset"})
            String to,

            @Schema(description = "An ISO-8601 instant for time, an offset for offset.")
            String value,

            @Schema(description = "The preview's etag; required to reset.")
            String etag) {}

    public record OffsetRow(
            @Schema(requiredMode = REQUIRED) String topic,
            @Schema(requiredMode = REQUIRED) int partition,
            @Schema(nullable = true) Long current,

            @Schema(nullable = true, description = "Absent where the partition is refused.")
            Long next,

            @Schema(requiredMode = REQUIRED, description = "Records the group will no longer read.")
            long skipped,

            @Schema(requiredMode = REQUIRED, description = "Records the group will read again.")
            long reread,

            @Schema(nullable = true) String note,

            @Schema(
                    nullable = true,
                    description = "Why this partition cannot be moved, such as an offset out of range.")
            String refused) {}

    public record OffsetPreview(
            @Schema(requiredMode = REQUIRED) List<OffsetRow> partitions,

            @Schema(requiredMode = REQUIRED, description = "Active members, as client@host (id).")
            List<String> members,

            @Schema(nullable = true, description = "Why the reset is refused now: members, rebalancing, read-only.")
            String blocked,

            @Schema(requiredMode = REQUIRED) String etag) {}

    public record OffsetResult(
            @Schema(requiredMode = REQUIRED) List<OffsetRow> partitions,
            @Schema(requiredMode = REQUIRED) long moved) {}

    // ---- topic configuration ------------------------------------------------------------------------------------

    public enum ConfigSource {
        TOPIC,
        BROKER_DEFAULT,
        STATIC,
        DEFAULT
    }

    public record ConfigEntry(
            @Schema(requiredMode = REQUIRED) String name,

            @Schema(nullable = true, description = "Never sent for a sensitive setting.")
            String value,

            @Schema(requiredMode = REQUIRED) ConfigSource source,

            @Schema(nullable = true, description = "The value the setting falls back to.")
            String defaultValue,

            @Schema(requiredMode = REQUIRED) boolean readOnly,
            @Schema(requiredMode = REQUIRED) boolean sensitive,

            @Schema(nullable = true, description = "ms, bytes or the like, when the value is a quantity.")
            String unit) {}

    public record TopicConfig(
            @Schema(requiredMode = REQUIRED) boolean internal,

            @Schema(nullable = true, description = "Why every setting is read-only.")
            String readOnlyReason,

            @Schema(requiredMode = REQUIRED) List<ConfigEntry> entries) {}

    public record ConfigChangeRequest(
            @Schema(description = "Settings to override, by name.")
            java.util.Map<String, String> set,

            @Schema(description = "Overrides to remove, by name.")
            List<String> remove,

            @Schema(description = "The preview's etag; required to apply.")
            String etag) {}

    public record ConfigChangeRow(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(nullable = true) String before,

            @Schema(nullable = true, description = "Absent when the override is removed.")
            String after,

            @Schema(nullable = true, description = "The value the setting falls back to, for a removal.")
            String fallback,

            @Schema(nullable = true, description = "The broker's validation message; nothing is applied then.")
            String error) {}

    public record ConfigPreview(
            @Schema(requiredMode = REQUIRED) List<ConfigChangeRow> changes,
            @Schema(requiredMode = REQUIRED) boolean valid,
            @Schema(requiredMode = REQUIRED) String etag) {}

    // ---- ACLs ---------------------------------------------------------------------------------------------------

    public record Acl(
            @Schema(requiredMode = REQUIRED, example = "User:app")
            String principal,

            @Schema(requiredMode = REQUIRED, example = "*") String host,

            @Schema(requiredMode = REQUIRED, example = "read")
            String operation,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"allow", "deny"})
            String permission,

            @Schema(requiredMode = REQUIRED, example = "topic")
            String resourceType,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"literal", "prefixed"})
            String pattern,

            @Schema(requiredMode = REQUIRED) String name) {}

    public record AclPage(
            @Schema(requiredMode = REQUIRED, description = "False where the cluster has no authorizer.")
            boolean available,

            @Schema(nullable = true) String reason,
            @Schema(requiredMode = REQUIRED) List<Acl> items,
            @Schema(nullable = true) String next) {}

    public record AclChangeRequest(
            @Schema(description = "ACLs to add.") List<Acl> add,
            @Schema(description = "ACLs to remove.") List<Acl> remove,

            @Schema(description = "The preview's etag; required to apply.")
            String etag) {}

    /** What a principal may do to one resource, before and after a change. */
    public record EffectiveAccess(
            @Schema(requiredMode = REQUIRED) String principal,
            @Schema(requiredMode = REQUIRED) String resourceType,
            @Schema(requiredMode = REQUIRED) String resource,
            @Schema(requiredMode = REQUIRED) List<String> before,
            @Schema(requiredMode = REQUIRED) List<String> after) {}

    public record AclPreview(
            @Schema(requiredMode = REQUIRED) List<EffectiveAccess> access,

            @Schema(requiredMode = REQUIRED, description = "In words, such as: User:app loses READ on topic orders.")
            List<String> losses,

            @Schema(requiredMode = REQUIRED, description = "Where a deny rule wins, and what is not evaluated.")
            List<String> notes,

            @Schema(requiredMode = REQUIRED) int added,
            @Schema(requiredMode = REQUIRED) int removed,
            @Schema(requiredMode = REQUIRED) String etag) {}

    public record AclResult(
            @Schema(requiredMode = REQUIRED) int added,
            @Schema(requiredMode = REQUIRED) int removed) {}

    // ---- transactions -------------------------------------------------------------------------------------------

    public record BlockedPartition(
            @Schema(requiredMode = REQUIRED) String topic,
            @Schema(requiredMode = REQUIRED) int partition) {}

    public record TransactionRow(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String state,
            @Schema(nullable = true) Instant startedAt,
            @Schema(requiredMode = REQUIRED) long timeoutMs,

            @Schema(requiredMode = REQUIRED, description = "Open longer than its timeout.")
            boolean hanging,

            @Schema(requiredMode = REQUIRED) List<BlockedPartition> blocked) {}

    public record TransactionPage(
            @Schema(requiredMode = REQUIRED) List<TransactionRow> items,
            @Schema(nullable = true) String next) {}

    public record AbortPreview(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(requiredMode = REQUIRED) String state,
            @Schema(requiredMode = REQUIRED) List<BlockedPartition> released,

            @Schema(requiredMode = REQUIRED, description = "What the abort does, in words, naming the fenced producer.")
            String effect,

            @Schema(requiredMode = REQUIRED) String etag) {}

    public record AbortRequest(
            @Schema(description = "The preview's etag; required to abort.")
            String etag) {}

    public record AbortResult(
            @Schema(requiredMode = REQUIRED) String id,

            @Schema(requiredMode = REQUIRED, description = "False where it had ended already.")
            boolean aborted) {}

    // ---- brokers ------------------------------------------------------------------------------------------------

    public record BrokerRow(
            @Schema(requiredMode = REQUIRED) String id,
            @Schema(nullable = true) String rack,
            @Schema(nullable = true) String address,

            @Schema(requiredMode = REQUIRED, description = "Whether Rahdar reaches it on the address it advertises.")
            boolean reachable,

            @Schema(nullable = true, description = "Why it is not reachable or not serving.")
            String reason,

            @Schema(requiredMode = REQUIRED) long leaders,
            @Schema(requiredMode = REQUIRED) long underReplicated,

            @Schema(requiredMode = REQUIRED, description = "Partitions it replicates that have no leader.")
            long offline) {}

    public record Voter(
            @Schema(requiredMode = REQUIRED) String id,

            @Schema(nullable = true, description = "Records behind the quorum leader.")
            Long lag,

            @Schema(requiredMode = REQUIRED) boolean caughtUp) {}

    public record Quorum(
            @Schema(requiredMode = REQUIRED, description = "False on a ZooKeeper-mode cluster.")
            boolean available,

            @Schema(nullable = true) String reason,
            @Schema(nullable = true) String leader,
            @Schema(requiredMode = REQUIRED) List<Voter> voters,

            @Schema(requiredMode = REQUIRED, description = "Whether the voters that are caught up are a majority.")
            boolean majority) {}

    public record Brokers(
            @Schema(requiredMode = REQUIRED, description = "KRaft or ZooKeeper.")
            String mode,

            @Schema(requiredMode = REQUIRED) List<BrokerRow> brokers,
            @Schema(requiredMode = REQUIRED) Quorum quorum) {}
}
