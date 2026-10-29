package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth.Level;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealthEvaluator;
import io.github.sudoitir.rahdar.platform.clusters.LogicalNode;
import io.github.sudoitir.rahdar.platform.clusters.SplitBrainStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A Kafka cluster's health is its brokers serving, its partitions in sync and its controller quorum (ADR-0215),
 * read from the driver's node conditions. It is CRITICAL when no broker is reachable or the quorum has no leader,
 * DEGRADED when a broker is fenced or unreachable, replicas are out of sync or a voter lags, and otherwise OK. A
 * quorum that cannot be read, as on a ZooKeeper-mode cluster, is stated in the notes and never counted as
 * healthy.
 */
@Component
class KafkaHealth implements ClusterHealthEvaluator {

    /** A reading is shared by the estate, the cluster screen and the health route for this long. */
    static final Duration FRESH = Duration.ofSeconds(5);

    private static final String QUORUM_VOTER = "kafka.quorum_voter";
    private static final String QUORUM_LEADER = "kafka.quorum_leader";

    private record Reading(Instant at, ClusterHealth health) {}

    private final BrokerGateway gateway;
    private final KafkaPartitions partitions;
    private final ClusterDirectory clusters;
    private final Map<UUID, Reading> readings = new ConcurrentHashMap<>();

    KafkaHealth(BrokerGateway gateway, KafkaPartitions partitions, ClusterDirectory clusters) {
        this.gateway = gateway;
        this.partitions = partitions;
        this.clusters = clusters;
    }

    @Override
    public String system() {
        return KafkaModule.SYSTEM;
    }

    @Override
    public ClusterHealth evaluate(UUID clusterId, List<LogicalNode> nodes, Set<UUID> rejectedNodeIds) {
        Instant now = Instant.now();
        Reading cached = readings.get(clusterId);
        if (cached != null && Duration.between(cached.at(), now).compareTo(FRESH) < 0) {
            return cached.health();
        }
        ClusterHealth health = read(clusterId);
        readings.put(clusterId, new Reading(now, health));
        return health;
    }

    void forget(UUID clusterId) {
        readings.remove(clusterId);
    }

    private ClusterHealth read(UUID clusterId) {
        Partial<List<NodeDescription>> described;
        try {
            described = gateway.cluster(clusterId).nodes().describe(Scope.whole());
        } catch (BrokerException e) {
            return verdict(
                    clusterId, Level.CRITICAL, List.of(), List.of("No broker is reachable: " + e.getMessage()), false);
        }
        Optional<String> changed = KafkaClusterIdentity.recorded(clusters.nodes(clusterId));
        if (changed.isPresent()) {
            return verdict(clusterId, Level.CRITICAL, List.of(), List.of(changed.get()), false);
        }
        return judge(clusterId, described, partitions.offline(clusterId), partitions.tried(clusterId));
    }

    /** The verdict for what the driver described; separate from the read so it is tested on its own. */
    static ClusterHealth judge(
            UUID clusterId, Partial<List<NodeDescription>> described, OptionalLong offline, boolean tried) {
        List<String> notes = new ArrayList<>();
        List<String> serving = new ArrayList<>();
        Level level = Level.OK;
        boolean behind = false;
        int brokers = 0;
        int voters = 0;
        boolean leader = false;

        for (NodeDescription node : described.value()) {
            String name =
                    (node.functions().contains(NodeFunction.BROKER) ? "broker-" : "controller-") + node.nativeId();
            if (node.functions().contains(NodeFunction.BROKER)) {
                brokers++;
            }
            if (node.attributes()
                    .raw(QUORUM_VOTER)
                    .map(v -> Boolean.parseBoolean(String.valueOf(v)))
                    .orElse(false)) {
                voters++;
                leader |= node.attributes()
                        .raw(QUORUM_LEADER)
                        .map(v -> Boolean.parseBoolean(String.valueOf(v)))
                        .orElse(false);
            }
            boolean fenced = false;
            for (Condition condition : node.conditions()) {
                boolean holds = condition.status() == ConditionStatus.TRUE;
                switch (condition.type()) {
                    case ConditionTypes.SERVING -> {
                        if (condition.status() == ConditionStatus.FALSE) {
                            fenced = true;
                            level = worst(level, Level.DEGRADED);
                            notes.add(name + " is not serving: " + condition.message());
                        }
                    }
                    case ConditionTypes.UNDER_REPLICATED -> {
                        if (holds) {
                            behind = true;
                            level = worst(level, Level.DEGRADED);
                            notes.add(name + " has under-replicated partitions: " + condition.message());
                        }
                    }
                    case ConditionTypes.OUT_OF_SYNC -> {
                        if (holds) {
                            behind = true;
                            level = worst(level, Level.DEGRADED);
                            notes.add(name + " is a controller voter that lags the quorum leader.");
                        }
                    }
                    default -> {}
                }
            }
            if (!fenced && node.functions().contains(NodeFunction.BROKER)) {
                serving.add(name);
            }
        }
        described.failures().forEach((node, failure) -> {
            notes.add("Node " + node.value() + " is unreachable: " + failure.getMessage());
        });
        if (!described.failures().isEmpty()) {
            level = worst(level, Level.DEGRADED);
        }
        if (brokers == 0) {
            level = Level.CRITICAL;
            notes.add("No broker answered.");
        }
        if (voters == 0) {
            notes.add(
                    "The controller quorum cannot be read (a ZooKeeper-mode cluster has none to read), so it is not counted as healthy.");
        } else if (!leader) {
            level = Level.CRITICAL;
            notes.add("The controller quorum has no leader.");
        }
        if (offline.isPresent() && offline.getAsLong() > 0) {
            level = Level.CRITICAL;
            notes.add(offline.getAsLong()
                    + " partitions have no leader, so their records can be neither read nor written.");
        } else if (offline.isEmpty() && !tried) {
            notes.add("The partitions have not been sampled yet, so offline partitions are not ruled out.");
        } else if (offline.isEmpty()) {
            notes.add("The partitions' leaders could not be read, so offline partitions are not ruled out.");
        }
        return verdict(clusterId, level, serving, notes, behind);
    }

    private static ClusterHealth verdict(
            UUID clusterId, Level level, List<String> serving, List<String> notes, boolean behind) {
        return new ClusterHealth(clusterId, level, serving, SplitBrainStatus.NONE, behind, notes, List.of());
    }

    private static Level worst(Level a, Level b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
