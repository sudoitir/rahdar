package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.NodeDescription;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A RabbitMQ cluster's health is its nodes running and in one piece, its resource alarms clear and its replicated
 * queues holding a majority (ADR-0215), read from the driver's node conditions and the queues' placements. It is
 * CRITICAL when no node answers, a node is partitioned from the others, a memory or disk alarm blocks every
 * publisher, or a quorum queue or stream has lost its majority; DEGRADED when a node is not running, a replicated
 * queue has a member behind or no leader, classic mirrors are unsynchronised, or the data plane cannot be reached
 * though the management API can; and otherwise OK. What was not read yet is a note and never counts as healthy.
 */
@Component
class RabbitMqHealth implements ClusterHealthEvaluator {

    private static final String NODE = "Node ";

    /** A verdict older than this is the scrape's silence, not a reading. */
    static final Duration STALE = Duration.ofMinutes(2);

    /** How many queues a note names before it only counts the rest. */
    static final int NAMED = 5;

    /** What the last pass over the queues found: the queues behind, those without a majority and unsynchronised mirrors. */
    record Queues(
            List<String> underReplicated, List<String> withoutMajority, List<String> leaderless, int mirrorsBehind) {}

    private final RabbitMqHealthStore store;
    private final Clock clock;

    RabbitMqHealth(RabbitMqHealthStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public String system() {
        return RabbitMqModule.SYSTEM;
    }

    /** What the scrape last wrote, never a call to a node: a slow or silent cluster cannot hold a request. */
    @Override
    public ClusterHealth evaluate(UUID clusterId, List<LogicalNode> nodes, Set<UUID> rejectedNodeIds) {
        Optional<RabbitMqHealthStore.Stored> stored = store.read(clusterId);
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
        return verdict(clusterId, Level.UNKNOWN, List.of(), List.of(note), false);
    }

    /** The verdict for a cluster no management URL answered. */
    static ClusterHealth unreachable(UUID clusterId, String why) {
        return verdict(clusterId, Level.CRITICAL, List.of(), List.of("No node is reachable: " + why), false);
    }

    /** The verdict for a cluster that now reports another cluster name than the one registered. */
    static ClusterHealth replaced(UUID clusterId, String words) {
        return verdict(clusterId, Level.CRITICAL, List.of(), List.of(words), false);
    }

    /**
     * The verdict for what the driver described; separate from the read so it is tested on its own. {@code queues} is
     * null until the first pass over the queues, and {@code dataPlane} is why the AMQP endpoints cannot be reached
     * when they cannot.
     */
    static ClusterHealth judge(
            UUID clusterId, Partial<List<NodeDescription>> described, Queues queues, Optional<String> dataPlane) {
        List<String> notes = new ArrayList<>();
        List<String> serving = new ArrayList<>();
        Level level = Level.OK;
        for (NodeDescription node : described.value()) {
            level = worst(level, node(node, notes, serving));
        }
        described.failures().forEach((node, failure) -> {
            notes.add(NODE + node.value() + " is unreachable: " + failure.getMessage());
        });
        if (!described.failures().isEmpty()) {
            level = worst(level, Level.DEGRADED);
        }
        if (described.value().isEmpty()) {
            level = Level.CRITICAL;
            notes.add("No node answered.");
        }
        if (dataPlane.isPresent()) {
            level = worst(level, Level.DEGRADED);
            notes.add("The management API answers but no AMQP endpoint accepts a connection (" + dataPlane.get()
                    + "), so clients cannot publish or consume.");
        }
        boolean behind = false;
        if (queues == null) {
            notes.add("The queues have not been read yet, so lost replicas are not ruled out.");
        } else {
            behind = !queues.underReplicated().isEmpty() || queues.mirrorsBehind() > 0;
            level = worst(level, queueLevel(queues, notes));
        }
        return verdict(clusterId, level, serving, notes, behind);
    }

    private static Level node(NodeDescription node, List<String> notes, List<String> serving) {
        Level level = Level.OK;
        String name = node.nativeId();
        boolean running = true;
        for (Condition condition : node.conditions()) {
            boolean holds = condition.status() == ConditionStatus.TRUE;
            if (ConditionTypes.SERVING.equals(condition.type()) && condition.status() == ConditionStatus.FALSE) {
                running = false;
                level = worst(level, Level.DEGRADED);
                notes.add(NODE + name + " is not running.");
            } else if (holds && ConditionTypes.OUT_OF_SYNC.equals(condition.type())) {
                level = Level.CRITICAL;
                notes.add(NODE + name + " has a network partition: " + condition.message());
            } else if (holds && ConditionTypes.FULL.equals(condition.type())) {
                level = Level.CRITICAL;
                notes.add(NODE + name + " raised a "
                        + ("mem_alarm".equals(condition.reason()) ? "memory" : "disk space")
                        + " alarm, so every publisher in the cluster is blocked.");
            }
        }
        if (running) {
            serving.add(name);
        }
        return level;
    }

    private static Level queueLevel(Queues queues, List<String> notes) {
        Level level = Level.OK;
        if (!queues.withoutMajority().isEmpty()) {
            level = Level.CRITICAL;
            notes.add(describe(
                    queues.withoutMajority(),
                    "has lost the majority of its members, so it can neither be read nor written.",
                    "have lost the majority of their members, so they can neither be read nor written."));
        }
        if (!queues.leaderless().isEmpty()) {
            level = worst(level, Level.DEGRADED);
            notes.add(describe(
                    queues.leaderless(),
                    "has no leader online while a majority of its members is up, so a leader is being elected.",
                    "have no leader online while a majority of their members is up, so leaders are being elected."));
        }
        if (!queues.underReplicated().isEmpty()) {
            level = worst(level, Level.DEGRADED);
            notes.add(describe(
                    queues.underReplicated(),
                    "is under-replicated: a member is offline.",
                    "are under-replicated: a member of each is offline."));
        }
        if (queues.mirrorsBehind() > 0) {
            level = worst(level, Level.DEGRADED);
            notes.add(queues.mirrorsBehind() + " classic queues have mirrors that are not synchronised.");
        }
        return level;
    }

    /** "Queue a is…" or "3 queues (a, b, c and 0 more) are…", naming at most {@link #NAMED}. */
    private static String describe(List<String> names, String one, String many) {
        String shown = String.join(", ", names.stream().limit(NAMED).toList());
        if (names.size() == 1) {
            return "Queue " + shown + " " + one;
        }
        String more = names.size() > NAMED ? " and " + (names.size() - NAMED) + " more" : "";
        return names.size() + " queues (" + shown + more + ") " + many;
    }

    static ClusterHealth verdict(
            UUID clusterId, Level level, List<String> serving, List<String> notes, boolean behind) {
        return new ClusterHealth(clusterId, level, serving, SplitBrainStatus.NONE, behind, notes, List.of());
    }

    private static Level worst(Level a, Level b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
