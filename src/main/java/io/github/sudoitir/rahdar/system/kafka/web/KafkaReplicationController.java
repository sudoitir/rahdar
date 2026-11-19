package io.github.sudoitir.rahdar.system.kafka.web;

import io.github.sudoitir.rahdar.kernel.gate.HeldResponse;
import io.github.sudoitir.rahdar.system.kafka.KafkaPartitionReads;
import io.github.sudoitir.rahdar.system.kafka.KafkaReplication;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.CancelOutcome;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.CancelPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ElectionOutcome;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ElectionPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.PartitionPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.PartitionSelection;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReassignOutcome;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReassignPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReassignmentList;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReplicaMoveRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReplicaSuggestion;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partition placement, preferred election and replica moves, under the same {@code /clusters/{id}/kafka/} path as the
 * other Kafka reads. Split from {@link KafkaController} so that class stays within its dependency budget. The document
 * keeps that class's tag, so these routes stay where clients already find them. Another system's cluster answers these
 * with {@code 404}.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/kafka")
@Tag(name = "kafka-controller")
@RequiredArgsConstructor
public class KafkaReplicationController {

    private final KafkaPartitionReads partitionReads;
    private final KafkaReplication replication;

    @GetMapping("/topics/{topic}/partitions")
    public PartitionPage partitions(
            @PathVariable UUID clusterId,
            @PathVariable String topic,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return partitionReads.partitions(clusterId, topic, cursor, limit);
    }

    /** Partitions that are under-replicated, offline, or not on their preferred leader. */
    @GetMapping("/replication")
    public PartitionPage replication(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return partitionReads.replication(clusterId, topic, cursor, limit);
    }

    /** A rack suggestion. It keeps the current replicas and changes nothing. */
    @GetMapping("/topics/{topic}/partitions/{partition}:suggestion")
    public ReplicaSuggestion suggest(
            @PathVariable UUID clusterId, @PathVariable String topic, @PathVariable int partition) {
        return partitionReads.suggest(clusterId, topic, partition);
    }

    @PostMapping("/replication:elect-preview")
    public ElectionPreview previewElection(@PathVariable UUID clusterId, @RequestBody PartitionSelection request) {
        return replication.previewElection(clusterId, request);
    }

    @HeldResponse
    @PostMapping("/replication:elect")
    public ElectionOutcome elect(@PathVariable UUID clusterId, @RequestBody PartitionSelection request) {
        return replication.elect(clusterId, request);
    }

    @PostMapping("/topics/{topic}/partitions/{partition}:reassign-preview")
    public ReassignPreview previewReassign(
            @PathVariable UUID clusterId,
            @PathVariable String topic,
            @PathVariable int partition,
            @RequestBody ReplicaMoveRequest request) {
        return replication.previewReassign(clusterId, topic, partition, request);
    }

    @HeldResponse
    @PostMapping("/topics/{topic}/partitions/{partition}:reassign")
    public ReassignOutcome reassign(
            @PathVariable UUID clusterId,
            @PathVariable String topic,
            @PathVariable int partition,
            @RequestBody ReplicaMoveRequest request) {
        return replication.reassign(clusterId, topic, partition, request);
    }

    @GetMapping("/reassignments")
    public ReassignmentList reassignments(@PathVariable UUID clusterId) {
        return replication.reassignments(clusterId);
    }

    @PostMapping("/reassignments:cancel-preview")
    public CancelPreview previewCancel(@PathVariable UUID clusterId, @RequestBody PartitionSelection request) {
        return replication.previewCancel(clusterId, request);
    }

    @HeldResponse
    @PostMapping("/reassignments:cancel")
    public CancelOutcome cancel(@PathVariable UUID clusterId, @RequestBody PartitionSelection request) {
        return replication.cancel(clusterId, request);
    }
}
