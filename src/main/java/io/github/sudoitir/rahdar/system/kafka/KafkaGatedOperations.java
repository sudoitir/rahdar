package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.system.kafka.KafkaAclChanges.AclChangeParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaBrokerConfigChanges.BrokerConfigParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaHangingAborts.HangingAbortParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaOffsetResets.OffsetResetParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicConfigChanges.ConfigChangeParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.CreateParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.DeleteParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.GroupDeleteParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.PartitionsParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTransactionAborts.AbortParams;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Acl;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gated Kafka operations (ADR-0179, ADR-0216): {@code subscription.seek}, {@code access-rule.change} and
 * {@code transaction.abort}. Each is destructive, runs as the requester once approved, and pins the state the operator
 * previewed, so one approved after the cluster changed is refused as stale.
 */
@Configuration(proxyBeanMethods = false)
class KafkaGatedOperations {

    private static final String CLUSTER = "Cluster";

    private static final String TOPIC = "Topic";

    @Bean
    GatedOperation<OffsetResetParams> offsetReset(KafkaOffsetResets resets, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "subscription.seek",
                OffsetResetParams.class,
                OffsetResetParams::clusterId,
                p -> "Reset offsets of group " + p.group() + " to " + p.to() + " on " + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of("Group", p.group()),
                        DisplayRow.of(
                                "Topics",
                                p.topics() == null || p.topics().isEmpty() ? "all" : String.join(", ", p.topics())),
                        DisplayRow.of("Reset to", p.value() == null ? p.to() : p.to() + " " + p.value())),
                resets::estimate,
                resets::replay,
                clusters,
                Set.of());
    }

    @Bean
    GatedOperation<ConfigChangeParams> topicConfigChange(
            KafkaTopicConfigChanges changes, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "destination.update-config",
                ConfigChangeParams.class,
                ConfigChangeParams::clusterId,
                p -> "Change settings of topic " + p.topic() + " on " + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of(TOPIC, p.topic()),
                        DisplayRow.of("Set", changes.shown(p)),
                        DisplayRow.of(
                                "Remove overrides",
                                p.remove() == null || p.remove().isEmpty()
                                        ? "none"
                                        : p.remove().toString())),
                changes::estimate,
                changes::replay,
                clusters,
                Set.of("/set"));
    }

    @Bean
    GatedOperation<BrokerConfigParams> brokerConfigChange(
            KafkaBrokerConfigChanges changes, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "broker.update-config",
                BrokerConfigParams.class,
                BrokerConfigParams::clusterId,
                p -> "Change settings of " + broker(p) + " on " + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of("Broker", broker(p)),
                        DisplayRow.of("Set", changes.shown(p)),
                        DisplayRow.of(
                                "Remove overrides",
                                p.remove() == null || p.remove().isEmpty()
                                        ? "none"
                                        : p.remove().toString())),
                changes::estimate,
                changes::replay,
                clusters,
                Set.of("/set"));
    }

    private static String broker(BrokerConfigParams p) {
        return KafkaBrokerConfigs.DEFAULT.equals(p.broker()) ? "the cluster default" : "broker " + p.broker();
    }

    @Bean
    GatedOperation<AclChangeParams> aclChange(KafkaAclChanges changes, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "access-rule.change",
                AclChangeParams.class,
                AclChangeParams::clusterId,
                p -> "Add " + p.add().size() + " and remove " + p.remove().size() + " ACLs on "
                        + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of(
                                "Add",
                                p.add().isEmpty()
                                        ? "none"
                                        : p.add().stream()
                                                .map(KafkaGatedOperations::text)
                                                .toList()
                                                .toString()),
                        DisplayRow.of(
                                "Remove",
                                p.remove().isEmpty()
                                        ? "none"
                                        : p.remove().stream()
                                                .map(KafkaGatedOperations::text)
                                                .toList()
                                                .toString())),
                changes::estimate,
                changes::replay,
                clusters,
                Set.of());
    }

    @Bean
    GatedOperation<AbortParams> transactionAbort(KafkaTransactionAborts aborts, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "transaction.abort",
                AbortParams.class,
                AbortParams::clusterId,
                p -> "Abort transaction " + p.transactionalId() + " on " + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of("Transactional id", p.transactionalId())),
                aborts::estimate,
                aborts::replay,
                clusters,
                Set.of());
    }

    @Bean
    GatedOperation<HangingAbortParams> hangingTransactionAbort(
            KafkaHangingAborts aborts, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "transaction.abort-hanging",
                HangingAbortParams.class,
                HangingAbortParams::clusterId,
                p -> "Abort the hanging transaction of producer " + p.producerId() + " on " + p.topic() + "-"
                        + p.partition() + " on " + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of("Partition", p.topic() + "-" + p.partition()),
                        DisplayRow.of("Producer id", String.valueOf(p.producerId()))),
                aborts::estimate,
                aborts::replay,
                clusters,
                Set.of());
    }

    @Bean
    GatedOperation<CreateParams> topicCreate(KafkaTopicLifecycle lifecycle, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "destination.create",
                CreateParams.class,
                CreateParams::clusterId,
                p -> "Create topic " + p.name() + " on " + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of(TOPIC, p.name()),
                        DisplayRow.of("Partitions", String.valueOf(p.partitions())),
                        DisplayRow.of("Replication factor", String.valueOf(p.replicationFactor()))),
                lifecycle::estimateCreate,
                lifecycle::replayCreate,
                clusters,
                Set.of());
    }

    @Bean
    GatedOperation<DeleteParams> topicDelete(KafkaTopicLifecycle lifecycle, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "kafka.topic.delete",
                DeleteParams.class,
                DeleteParams::clusterId,
                p -> "Delete topic " + p.topic() + " on " + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of(TOPIC, p.topic()),
                        DisplayRow.of("Override the safety cap", p.override() ? "yes" : "no")),
                lifecycle::estimateDelete,
                lifecycle::replayDelete,
                clusters,
                Set.of());
    }

    @Bean
    GatedOperation<PartitionsParams> increasePartitions(
            KafkaTopicLifecycle lifecycle, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "destination.increase-partitions",
                PartitionsParams.class,
                PartitionsParams::clusterId,
                p -> "Raise partitions of " + p.topic() + " to " + p.partitions() + " on "
                        + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of(TOPIC, p.topic()),
                        DisplayRow.of("Partitions", String.valueOf(p.partitions()))),
                lifecycle::estimatePartitions,
                lifecycle::replayPartitions,
                clusters,
                Set.of());
    }

    @Bean
    GatedOperation<GroupDeleteParams> deleteGroup(KafkaTopicLifecycle lifecycle, ClusterEnvironmentIndex clusters) {
        return new KafkaGatedOperation<>(
                "kafka.group.delete",
                GroupDeleteParams.class,
                GroupDeleteParams::clusterId,
                p -> "Delete group " + p.group() + " on " + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())), DisplayRow.of("Group", p.group())),
                lifecycle::estimateGroup,
                lifecycle::replayGroup,
                clusters,
                Set.of());
    }

    private static String text(Acl a) {
        return a.permission() + " " + a.principal() + " " + a.operation() + " on " + a.resourceType() + " " + a.name();
    }
}
