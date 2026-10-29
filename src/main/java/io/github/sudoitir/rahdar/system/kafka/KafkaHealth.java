package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth.Level;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealthEvaluator;
import io.github.sudoitir.rahdar.platform.clusters.LogicalNode;
import io.github.sudoitir.rahdar.platform.clusters.SplitBrainStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
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

    /** A verdict older than this is the scrape's silence, not a reading. */
    static final Duration STALE = Duration.ofMinutes(2);

    private static final String QUORUM_VOTER = "kafka.quorum_voter";
    private static final String QUORUM_LEADER = "kafka.quorum_leader";

    private final KafkaHealthStore store;
    private final Clock clock;

    KafkaHealth(KafkaHealthStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public String system() {
        return KafkaModule.SYSTEM;
    }

    /** What the scrape last wrote, never a call to a broker: a slow or silent cluster cannot hold a request. */
    @Override
    public ClusterHealth evaluate(UUID clusterId, List<LogicalNode> nodes, Set<UUID> rejectedNodeIds) {
        Optional<KafkaHealthStore.Stored> stored = store.read(clusterId);
        if (stored.isEmpty()) {
            return unknown(clusterId, "The scrape has not read this cluster yet.");
        }
        Instant at = stored.get().at();
        if (Duration.between(at, clock.instant()).compareTo(STALE) > 0) {
            return unknown(
                    clusterId, "The scrape last read this cluster at " + at + ", so its health is not known now.");
        }
        return stored.get().health();
    }

    private static ClusterHealth unknown(UUID clusterId, String note) {
        return new ClusterHealth(
                clusterId, Level.UNKNOWN, List.of(), SplitBrainStatus.NONE, false, List.of(note), List.of());
    }

    /** The verdict for a cluster no broker answered. */
    static ClusterHealth unreachable(UUID clusterId, String why) {
        return verdict(clusterId, Level.CRITICAL, List.of(), List.of("No broker is reachable: " + why), false);
    }

    /** The verdict for a cluster that now reports another cluster id than the one registered. */
    static ClusterHealth replaced(UUID clusterId, String words) {
        return verdict(clusterId, Level.CRITICAL, List.of(), List.of(words), false);
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

    static ClusterHealth verdict(
            UUID clusterId, Level level, List<String> serving, List<String> notes, boolean behind) {
        return new ClusterHealth(clusterId, level, serving, SplitBrainStatus.NONE, behind, notes, List.of());
    }

    private static Level worst(Level a, Level b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
