package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.ClusterScrape;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A Kafka cluster's brokers on the scrape schedule: one batched read of every node, then each stored broker takes
 * its state from what the cluster says of it (serving, or fenced), and one the cluster did not answer for keeps its
 * last state with the error. The partitions without a leader are counted on the same cycle.
 */
@Component
@RequiredArgsConstructor
class KafkaScrape implements ClusterScrape {

    private final BrokerGateway gateway;
    private final ClusterDirectory clusters;
    private final NodeStateRecorder recorder;
    private static final int PAGE = 200;

    private final KafkaPartitions partitions;
    private final MeterRegistry meters;

    @Override
    public String system() {
        return KafkaModule.SYSTEM;
    }

    @Override
    public void nodes(UUID clusterId, long cycle) {
        List<ClusterNode> stored = clusters.nodes(clusterId);
        Partial<List<NodeDescription>> described;
        try {
            round(clusterId, "nodes");
            described = gateway.cluster(clusterId).nodes().describe(Scope.whole());
        } catch (BrokerException failure) {
            RuntimeException e = BrokerFailures.translate(failure);
            BrokerConnectionException.Kind kind = e instanceof BrokerConnectionException bce ? bce.kind() : null;
            if (kind != BrokerConnectionException.Kind.THROTTLED) {
                String message =
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                stored.forEach(node -> recorder.recordNodeError(node.getId(), message, kind));
            }
            throw e;
        }
        Map<String, ClusterNode> byBroker = new HashMap<>();
        stored.forEach(node -> byBroker.put(node.getNativeNodeId(), node));
        Set<String> answered = new HashSet<>();
        for (NodeDescription node : described.value()) {
            ClusterNode known = byBroker.get(node.nativeId());
            if (known != null && !described.failures().containsKey(node.node())) {
                recorder.applyTierA(known.getId(), node, cycle);
                answered.add(node.nativeId());
            }
        }
        for (NodeDescription node : described.value()) {
            if (!byBroker.containsKey(node.nativeId())) {
                recorder.adopt(
                        clusterId, KafkaRegistrar.nameOf(node), node.nativeId(), KafkaRegistrar.dataEndpoints(node));
            }
        }
        described.failures().forEach((node, failure) -> record(byBroker.get(node.value()), failure));
        stored.stream()
                .filter(node -> node.getNativeNodeId() != null && !answered.contains(node.getNativeNodeId()))
                .filter(node -> !described.failures().containsKey(NodeId.of(node.getNativeNodeId())))
                .forEach(node -> recorder.recordNodeError(
                        node.getId(),
                        "The cluster no longer lists this broker.",
                        BrokerConnectionException.Kind.UNREACHABLE));
        round(clusterId, "partitions");
        partitions.refresh(clusterId);
    }

    /**
     * One call to the cluster's driver, each of which is one batched read of the whole cluster, counted as
     * {@code rahdar.kafka.scrape.rounds} by cluster and call. Set against the cycles it takes, it is the scrape's cost
     * to the brokers: a cycle that grew one round per group or topic would show here.
     */
    private void round(UUID clusterId, String call) {
        Counter.builder("rahdar.kafka.scrape.rounds")
                .description("Driver reads the Kafka scrape issued, by cluster and call")
                .tag("cluster", clusterId.toString())
                .tag("call", call)
                .register(meters)
                .increment();
    }

    /** Every subscription of the cluster, a group on a topic, carried by its first stored broker. */
    @Override
    public List<QueueRow> subscriptions(UUID clusterId) {
        ClusterNode carrier = clusters.nodes(clusterId).stream()
                .min(Comparator.comparing(ClusterNode::getName))
                .orElseThrow(() -> new NotFoundException("node", clusterId));
        List<QueueRow> rows = new ArrayList<>();
        Optional<String> next = Optional.empty();
        try {
            do {
                round(clusterId, "subscriptions");
                ResourceQuery query =
                        ResourceQuery.of(ResourceClass.SUBSCRIPTION).limit(PAGE);
                ResourcePage page = gateway.cluster(clusterId)
                        .resources()
                        .read(next.map(query::continueFrom).orElse(query))
                        .orThrow();
                page.items().forEach(r -> rows.add(QueueRow.of(clusterId, carrier.getId(), r)));
                next = page.continueToken();
            } while (next.isPresent());
        } catch (BrokerException failure) {
            throw BrokerFailures.translate(failure);
        }
        return rows;
    }

    private void record(ClusterNode node, BrokerException failure) {
        if (node != null) {
            RuntimeException e = BrokerFailures.translate(failure);
            recorder.recordNodeError(
                    node.getId(),
                    failure.getMessage(),
                    e instanceof BrokerConnectionException bce
                            ? bce.kind()
                            : BrokerConnectionException.Kind.UNREACHABLE);
        }
    }
}
