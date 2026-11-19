package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.system.kafka.KafkaReplication.CancelParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaReplication.ElectParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaReplication.ReassignParams;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Preferred election, a throttled replica move, and cancelling one (ADR-0230). Each is destructive, runs as the
 * requester once approved, and pins the state the operator previewed.
 */
@Configuration(proxyBeanMethods = false)
class KafkaReplicationGatedOperations {

    private static final String CLUSTER = "Cluster";

    private static final String TOPIC = "Topic";

    @Bean
    GatedOperation<ElectParams> electPreferred(KafkaReplication replication, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "kafka.partition.elect",
                ElectParams.class,
                ElectParams::clusterId,
                p -> "Elect the preferred leader of " + p.partitions().size() + " partitions on "
                        + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of(
                                "Partitions",
                                p.partitions().stream()
                                        .map(part -> part.topic() + "-" + part.partition())
                                        .toList()
                                        .toString())),
                replication::estimateElect,
                replication::replayElect,
                clusters,
                Set.of());
    }

    @Bean
    GatedOperation<ReassignParams> reassignReplicas(KafkaReplication replication, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "kafka.partition.reassign",
                ReassignParams.class,
                ReassignParams::clusterId,
                p -> "Move replicas of " + p.topic() + "-" + p.partition() + " on " + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of(TOPIC, p.topic() + "-" + p.partition()),
                        DisplayRow.of("Replicas", p.replicas().toString()),
                        DisplayRow.of("Throttle bytes per second", String.valueOf(p.throttleBytesPerSecond()))),
                replication::estimateReassign,
                replication::replayReassign,
                clusters,
                Set.of());
    }

    @Bean
    GatedOperation<CancelParams> cancelReassignment(KafkaReplication replication, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "kafka.partition.cancel-reassignment",
                CancelParams.class,
                CancelParams::clusterId,
                p -> "Cancel " + p.partitions().size() + " replica moves on " + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of(
                                "Partitions",
                                p.partitions().stream()
                                        .map(part -> part.topic() + "-" + part.partition())
                                        .toList()
                                        .toString())),
                replication::estimateCancel,
                replication::replayCancel,
                clusters,
                Set.of());
    }
}
