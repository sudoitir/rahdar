package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.ClusterScrape;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.NodeStateRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A Kafka cluster's brokers on the scrape schedule: one batched read of every node, then each stored broker takes
 * its state from what the cluster says of it (serving, or fenced), and one the cluster did not answer for keeps its
 * last state with the error. The partitions without a leader are counted on the same cycle. A broker the cluster stops listing is retired after {@link #RETIRE_AFTER}. A cluster that reports
 * another cluster id than the one registered is not read further, so the two clusters' data is never mixed.
 */
@Component
@RequiredArgsConstructor
class KafkaScrape implements ClusterScrape {

    private final BrokerGateway gateway;
    private final ClusterDirectory clusters;
    private final NodeStateRecorder recorder;
    private static final int PAGE = 200;

    /** A broker the cluster has not listed for this long, while the cluster answered, has left it for good. */
    static final Duration RETIRE_AFTER = Duration.ofMinutes(10);

    /** An unchanged health verdict is written again after this long, so readers can tell the scrape is alive. */
    static final Duration HEALTH_HEARTBEAT = Duration.ofSeconds(30);

    private final KafkaPartitions partitions;
    private final MeterRegistry meters;
    private final AuditService audit;
    private final ActorResolver actors;
    private final Clock clock;
    private final KafkaHealthStore healthStore;

    /** What the scrape last wrote of each cluster's health and when, so an unchanged verdict is rewritten only now and then. */
    private final Map<UUID, Written> written = new ConcurrentHashMap<>();

    private record Written(ClusterHealth health, Instant at) {}

    /** Since when each stored broker the cluster does not list has been missing, by cluster. */
    private final Map<UUID, Map<UUID, Instant>> unlistedSince = new ConcurrentHashMap<>();

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
                publish(KafkaHealth.unreachable(clusterId, message));
            }
            throw e;
        }
        String live = described.value().stream()
                .flatMap(node -> node.attributes().raw(KafkaRegistrar.CLUSTER_ID).stream())
                .map(String::valueOf)
                .findFirst()
                .orElse(null);
        Optional<String> changed = KafkaClusterIdentity.change(
                live, clusters.registeredIdentity(clusterId).orElse(null));
        if (changed.isPresent()) {
            stored.forEach(node ->
                    recorder.recordNodeError(node.getId(), changed.get(), BrokerConnectionException.Kind.UNREACHABLE));
            publish(KafkaHealth.replaced(clusterId, changed.get()));
            return;
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
        List<ClusterNode> unlisted = stored.stream()
                .filter(node -> node.getNativeNodeId() != null && !answered.contains(node.getNativeNodeId()))
                .filter(node -> !described.failures().containsKey(NodeId.of(node.getNativeNodeId())))
                .toList();
        Map<UUID, Instant> missing = unlistedSince.computeIfAbsent(clusterId, id -> new ConcurrentHashMap<>());
        missing.keySet().retainAll(unlisted.stream().map(ClusterNode::getId).toList());
        Instant now = clock.instant();
        for (ClusterNode node : unlisted) {
            Instant since = missing.computeIfAbsent(node.getId(), id -> now);
            if (!described.value().isEmpty() && Duration.between(since, now).compareTo(RETIRE_AFTER) >= 0) {
                retire(clusterId, node);
            } else {
                recorder.recordNodeError(
                        node.getId(),
                        "The cluster no longer lists this broker.",
                        BrokerConnectionException.Kind.UNREACHABLE);
            }
        }
        if (partitions.due(clusterId)) {
            round(clusterId, "partitions");
            partitions.refresh(clusterId);
        }
        publish(KafkaHealth.judge(clusterId, described, partitions.offline(clusterId), partitions.tried(clusterId)));
    }

    /** Writes the verdict where every replica reads it: when it changed, and otherwise every {@link #HEALTH_HEARTBEAT}. */
    private void publish(ClusterHealth health) {
        Instant now = clock.instant();
        Written last = written.get(health.clusterId());
        if (last != null
                && last.health().equals(health)
                && Duration.between(last.at(), now).compareTo(HEALTH_HEARTBEAT) < 0) {
            return;
        }
        healthStore.write(health, now);
        written.put(health.clusterId(), new Written(health, now));
    }

    /** Drops the cluster's per-cluster meters and the brokers it was waiting on. */
    void forget(UUID clusterId) {
        meters.find("rahdar.kafka.scrape.rounds")
                .tag("cluster", clusterId.toString())
                .meters()
                .forEach(meters::remove);
        unlistedSince.remove(clusterId);
        written.remove(clusterId);
    }

    /** Removes a broker the cluster dropped, and records that it left. */
    private void retire(UUID clusterId, ClusterNode node) {
        AuditEvent event = audit.begin(
                actors.system(),
                "NODE_LEFT_CLUSTER",
                "NODE",
                node.getName(),
                clusterId,
                null,
                Map.of(
                        "nativeNodeId",
                        node.getNativeNodeId(),
                        "detail",
                        "broker " + node.getNativeNodeId() + " left the cluster: the cluster has not listed it for "
                                + RETIRE_AFTER.toMinutes() + " minutes"),
                false);
        try {
            recorder.retire(node.getId());
            unlistedSince.getOrDefault(clusterId, Map.of()).remove(node.getId());
            audit.succeed(event, 1);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
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
        KafkaClusterIdentity.recorded(clusters.nodes(clusterId)).ifPresent(change -> {
            throw new IllegalStateException(change);
        });
        ClusterNode carrier = clusters.nodes(clusterId).stream()
                .min(Comparator.comparing(ClusterNode::getName))
                .orElseThrow(() -> new NotFoundException("node", clusterId));
        List<QueueRow> rows = new ArrayList<>();
        Optional<String> next = Optional.empty();
        try {
            round(clusterId, "subscriptions");
            do {
                round(clusterId, "subscription-pages");
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
