package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.system.kafka.KafkaAclChanges.AclChangeParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaHangingAborts.HangingAbortParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaOffsetResets.OffsetResetParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicConfigChanges.ConfigChangeParams;
import io.github.sudoitir.rahdar.system.kafka.KafkaTransactionAborts.AbortParams;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Acl;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
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

    @Bean
    GatedOperation<OffsetResetParams> offsetReset(KafkaOffsetResets resets, ClusterEnvironmentIndex clusters) {
        return new Operation<>(
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
        return new Operation<>(
                "destination.update-config",
                ConfigChangeParams.class,
                ConfigChangeParams::clusterId,
                p -> "Change settings of topic " + p.topic() + " on " + clusters.labelOf(p.clusterId()),
                p -> List.of(
                        DisplayRow.of(CLUSTER, clusters.labelOf(p.clusterId())),
                        DisplayRow.of("Topic", p.topic()),
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
    GatedOperation<AclChangeParams> aclChange(KafkaAclChanges changes, ClusterEnvironmentIndex clusters) {
        return new Operation<>(
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
        return new Operation<>(
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
        return new Operation<>(
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

    private static String text(Acl a) {
        return a.permission() + " " + a.principal() + " " + a.operation() + " on " + a.resourceType() + " " + a.name();
    }

    /** What the operations share: destructive, run again as the requester after approval, scoped to the cluster. */
    private record Operation<P extends Record>(
            String type,
            Class<P> paramsType,
            Function<P, UUID> cluster,
            Function<P, String> summary,
            Function<P, List<DisplayRow>> display,
            Function<P, Effect> estimate,
            Consumer<P> replay,
            ClusterEnvironmentIndex clusters,
            Set<String> redacted)
            implements GatedOperation<P> {

        @Override
        public int version() {
            return 1;
        }

        @Override
        public Set<Trait> traits(P params) {
            return Set.of(Trait.DESTRUCTIVE);
        }

        @Override
        public ExecutionMode mode() {
            return ExecutionMode.ON_APPROVAL;
        }

        @Override
        public OperationScope scope(P params) {
            UUID id = cluster.apply(params);
            return OperationScope.cluster(id, clusters.environmentOf(id));
        }

        @Override
        public String summary(P params) {
            return summary.apply(params);
        }

        @Override
        public List<DisplayRow> display(P params) {
            return display.apply(params);
        }

        @Override
        public Set<String> redactedPaths() {
            return redacted;
        }

        @Override
        public Effect estimate(P params) {
            return estimate.apply(params);
        }

        @Override
        public void replay(P params) {
            replay.accept(params);
        }
    }
}
