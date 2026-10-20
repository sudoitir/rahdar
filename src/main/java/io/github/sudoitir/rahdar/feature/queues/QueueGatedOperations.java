package io.github.sudoitir.rahdar.feature.queues;

import io.github.sudoitir.rahdar.feature.queues.QueueLifecycleService.AddressDeleteParams;
import io.github.sudoitir.rahdar.feature.queues.QueueLifecycleService.QueueDeleteParams;
import io.github.sudoitir.rahdar.feature.queues.QueueLifecycleService.Reach;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterEnvironmentIndex;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gated queue and address deletes: {@code queue.delete} and {@code address.delete} (ADR-0179). Both are
 * destructive and run as the requester once approved. The queue's estimate is the messages the scrape counts on it,
 * pinned to which nodes hold it and the incarnation each holds; an address has no identity beyond its name.
 */
@Configuration(proxyBeanMethods = false)
class QueueGatedOperations {

    @Bean
    GatedOperation<QueueDeleteParams> queueDeleteOperation(
            QueueLifecycleService lifecycle, ClusterEnvironmentIndex clusters) {
        return new GatedOperation<>() {
            @Override
            public String type() {
                return "queue.delete";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<QueueDeleteParams> paramsType() {
                return QueueDeleteParams.class;
            }

            @Override
            public Set<Trait> traits(QueueDeleteParams p) {
                return Set.of(Trait.DESTRUCTIVE);
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.ON_APPROVAL;
            }

            @Override
            public OperationScope scope(QueueDeleteParams p) {
                return OperationScope.cluster(p.clusterId(), clusters.environmentOf(p.clusterId()));
            }

            @Override
            public String summary(QueueDeleteParams p) {
                return "Delete queue " + p.queue() + " on " + clusters.labelOf(p.clusterId());
            }

            @Override
            public List<DisplayRow> display(QueueDeleteParams p) {
                return List.of(
                        DisplayRow.of("Cluster", clusters.labelOf(p.clusterId())),
                        DisplayRow.of("Queue", p.queue()),
                        DisplayRow.of("Consumers", p.disconnectConsumers() ? "disconnected" : "must be gone"));
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of();
            }

            @Override
            public Effect estimate(QueueDeleteParams p) {
                Reach reach = lifecycle.reachOfQueueDelete(p.clusterId(), p.queue());
                return new Effect(reach.count(), "messages", reach.identity(), null);
            }

            @Override
            public void replay(QueueDeleteParams p) {
                succeeded(
                        lifecycle.deleteQueue(p.clusterId(), p.queue(), false, p.override(), p.disconnectConsumers()));
            }
        };
    }

    @Bean
    GatedOperation<AddressDeleteParams> addressDeleteOperation(
            QueueLifecycleService lifecycle, ClusterEnvironmentIndex clusters) {
        return new GatedOperation<>() {
            @Override
            public String type() {
                return "address.delete";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<AddressDeleteParams> paramsType() {
                return AddressDeleteParams.class;
            }

            @Override
            public Set<Trait> traits(AddressDeleteParams p) {
                return Set.of(Trait.DESTRUCTIVE);
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.ON_APPROVAL;
            }

            @Override
            public OperationScope scope(AddressDeleteParams p) {
                return OperationScope.cluster(p.clusterId(), clusters.environmentOf(p.clusterId()));
            }

            @Override
            public String summary(AddressDeleteParams p) {
                return "Delete address " + p.address() + " on " + clusters.labelOf(p.clusterId());
            }

            @Override
            public List<DisplayRow> display(AddressDeleteParams p) {
                return List.of(
                        DisplayRow.of("Cluster", clusters.labelOf(p.clusterId())),
                        DisplayRow.of("Address", p.address()));
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of();
            }

            @Override
            public Effect estimate(AddressDeleteParams p) {
                return new Effect(1, "addresses", p.clusterId() + "|" + p.address(), null);
            }

            @Override
            public void replay(AddressDeleteParams p) {
                succeeded(lifecycle.deleteAddress(p.clusterId(), p.address(), false));
            }
        };
    }

    /** A replay that failed on a node, or that no broker answered, is a failed request, not a success. */
    private static void succeeded(Attempt<LifecycleOutcome> attempt) {
        switch (attempt) {
            case Attempt.Failed<LifecycleOutcome>(var kind, var detail) ->
                throw new BrokerConnectionException(kind, detail);
            case Attempt.Ok<LifecycleOutcome>(var outcome) -> {
                String errors = outcome.nodes().stream()
                        .filter(n -> n.status() == LifecycleOutcome.NodeStatus.FAILED)
                        .map(n -> n.nodeName() + ": " + n.error())
                        .collect(Collectors.joining("; "));
                if (!errors.isEmpty()) {
                    throw new IllegalStateException("Failed on " + errors);
                }
            }
        }
    }
}
