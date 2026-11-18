package io.github.sudoitir.rahdar.system.kafka.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Partition placement and replica moves (ADR-0230). A missing figure is absent, never an invented zero. */
public final class KafkaReplicationViews {

    private KafkaReplicationViews() {}

    public record ReplicaOnBroker(
            @Schema(requiredMode = REQUIRED) int broker,

            @Schema(nullable = true, description = "Bytes of this replica on this broker. Absent when not known.")
            Long bytes,

            @Schema(nullable = true, description = "This replica's offset lag. Absent when not known.")
            Long offsetLag,

            @Schema(requiredMode = REQUIRED, description = "A future log that has not replaced the current one.")
            boolean future) {}

    public record PartitionRow(
            @Schema(requiredMode = REQUIRED) String topic,
            @Schema(requiredMode = REQUIRED) int partition,

            @Schema(nullable = true, description = "Absent when the partition has no leader.")
            Integer leader,

            @Schema(requiredMode = REQUIRED, description = "Replicas in the order the cluster keeps them.")
            List<Integer> replicas,

            @Schema(requiredMode = REQUIRED) List<Integer> inSyncReplicas,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Replicas that are not in sync, and every replica when there is no leader.")
            List<Integer> offlineReplicas,

            @Schema(nullable = true) Long logStartOffset,
            @Schema(nullable = true) Long logEndOffset,

            @Schema(nullable = true, description = "The leader replica's bytes on its broker, not remote storage.")
            Long sizeBytes,

            @Schema(requiredMode = REQUIRED) List<ReplicaOnBroker> replicasOnBroker,

            @Schema(requiredMode = REQUIRED, description = "True when the leader is the first replica.")
            boolean preferredLeader,

            @Schema(nullable = true, description = "Eligible leader replicas, only when the cluster keeps them.")
            List<Integer> eligibleLeaderReplicas,

            @Schema(nullable = true) Long localLogStartOffset,
            @Schema(nullable = true) Long tieredOffset,

            @Schema(nullable = true, description = "Why the tiered offset is absent, such as not tiered.")
            String tieredOffsetReason,

            @Schema(nullable = true, description = "Brokers that did not answer describeLogDirs. Absent when all did.")
            String logDirsUnavailable) {}

    public record PartitionPage(
            @Schema(requiredMode = REQUIRED) List<PartitionRow> items,

            @Schema(nullable = true, description = "The cursor of the next page; absent on the last.")
            String next) {}

    @Schema(name = "KafkaReplicaSuggestion")
    public record ReplicaSuggestion(
            @Schema(requiredMode = REQUIRED, description = "Always suggestion: this list is not a move.")
            String label,

            @Schema(requiredMode = REQUIRED) String topic,
            @Schema(requiredMode = REQUIRED) int partition,
            @Schema(requiredMode = REQUIRED) List<Integer> currentReplicas,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Current replicas, then one broker for each rack not yet represented.")
            List<Integer> replicas,

            @Schema(requiredMode = REQUIRED) String note) {}

    @Schema(name = "KafkaSelectedPartition")
    public record SelectedPartition(String topic, Integer partition) {}

    @Schema(name = "KafkaPartitionSelection")
    public record PartitionSelection(List<SelectedPartition> partitions, String etag) {}

    @Schema(name = "KafkaElectionPreview")
    public record ElectionPreview(
            @Schema(requiredMode = REQUIRED) int wouldChange,
            @Schema(requiredMode = REQUIRED) int alreadyPreferred,
            @Schema(requiredMode = REQUIRED) String etag,
            @Schema(requiredMode = REQUIRED) String note) {}

    @Schema(name = "KafkaElectionOutcome")
    public record ElectionOutcome(
            @Schema(requiredMode = REQUIRED) String scope,
            @Schema(requiredMode = REQUIRED) String status,
            @Schema(requiredMode = REQUIRED) int elected,
            @Schema(requiredMode = REQUIRED) int alreadyPreferred) {}

    @Schema(name = "KafkaReplicaMoveRequest")
    public record ReplicaMoveRequest(List<Integer> replicas, Long throttleBytesPerSecond, String etag) {}

    @Schema(name = "KafkaReassignPreview")
    public record ReassignPreview(
            @Schema(requiredMode = REQUIRED) List<Integer> currentReplicas,
            @Schema(requiredMode = REQUIRED) List<Integer> replicas,
            @Schema(requiredMode = REQUIRED) int currentReplicationFactor,
            @Schema(requiredMode = REQUIRED) int replicationFactor,
            @Schema(requiredMode = REQUIRED) int minInSyncReplicas,

            @Schema(nullable = true, description = "Bytes to copy. Absent when not known, never a stand-in zero.")
            Long bytes,

            @Schema(nullable = true) String bytesUnavailable,

            @Schema(nullable = true, description = "How long the copy should take. Absent when it cannot be estimated.")
            String duration,

            @Schema(nullable = true) String durationUnavailable,

            @Schema(requiredMode = REQUIRED) String throttleOutcome,
            @Schema(requiredMode = REQUIRED) long throttleBytesPerSecond,
            @Schema(requiredMode = REQUIRED) String etag) {}

    @Schema(name = "KafkaReassignOutcome")
    public record ReassignOutcome(
            @Schema(requiredMode = REQUIRED) String scope,
            @Schema(requiredMode = REQUIRED) String status,
            @Schema(requiredMode = REQUIRED) String throttleOutcome) {}

    @Schema(name = "KafkaCancelPartition")
    public record CancelPartition(
            @Schema(requiredMode = REQUIRED) String topic,
            @Schema(requiredMode = REQUIRED) int partition,
            @Schema(requiredMode = REQUIRED) List<Integer> currentReplicas,

            @Schema(requiredMode = REQUIRED, description = "The replicas cancel leaves: the set before the move.")
            List<Integer> replicas) {}

    @Schema(name = "KafkaCancelPreview")
    public record CancelPreview(
            @Schema(requiredMode = REQUIRED) List<CancelPartition> partitions,
            @Schema(requiredMode = REQUIRED) String etag,
            @Schema(requiredMode = REQUIRED) String note) {}

    @Schema(name = "KafkaCancelOutcome")
    public record CancelOutcome(
            @Schema(requiredMode = REQUIRED) String scope,
            @Schema(requiredMode = REQUIRED) String status,
            @Schema(requiredMode = REQUIRED) List<CancelPartition> partitions) {}

    public record ReassignmentRow(
            @Schema(requiredMode = REQUIRED) String topic,
            @Schema(requiredMode = REQUIRED) int partition,

            @Schema(nullable = true, description = "The in-progress reassignment, when the cluster still lists one.")
            String reassignment,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "This product started the move and stored its throttle baseline.")
            boolean startedHere) {}

    public record ReassignmentList(
            @Schema(requiredMode = REQUIRED) List<ReassignmentRow> items) {}
}
