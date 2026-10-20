package io.github.sudoitir.rahdar.feature.alerting;

import io.github.sudoitir.rahdar.platform.broker.ClockOffsetService;
import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import io.github.sudoitir.rahdar.platform.clusters.BrokerNodeMapper;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.HaStateEvaluator;
import io.github.sudoitir.rahdar.platform.clusters.LogicalNode;
import io.github.sudoitir.rahdar.platform.clusters.SplitBrainStatus;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Reads the already-computed HA read models — never a metric row (design.md
 * decision 1). {@code SPLIT_BRAIN} fires only on a corroborated
 * {@link SplitBrainStatus#CRITICAL}, never on the first-sighting
 * {@code SUSPECTED} verdict (ADR-0012).
 */
@Component
@Order(10)
@RequiredArgsConstructor
public class StateCondition implements AlertCondition {

    private static final String NODE_PREFIX = "node:";
    private static final String CLUSTER_SUBJECT = "cluster";

    /** The one subject that is not a broker: Rahdar's own host, when it is the suspect. */
    private static final String RAHDAR_SUBJECT = "rahdar";

    private final ClusterDirectory nodes;
    private final ClockOffsetService clocks;
    private final BrokerNodeMapper nodeMapper;
    private final HaStateEvaluator evaluator;
    private final List<AlertSignalSource> signals;

    @Override
    public boolean supports(AlertRuleSpec rule) {
        return !rule.thresholdRule();
    }

    @Override
    public Evaluation evaluate(UUID clusterId, AlertRuleSpec rule) {
        List<ClusterNode> rows = nodes.nodes(clusterId);
        return switch (rule.stateCondition()) {
            case "SPLIT_BRAIN" -> splitBrain(rows);
            case "NODE_DOWN" -> nodeDown(rows);
            case "REPLICATION_BEHIND" -> replicationBehind(rows);
            case "CLUSTER_DEGRADED" -> clusterDegraded(clusterId, rows);
            case "CLOCK_SKEW" -> clockSkew(clusterId);
            default -> signal(rule.stateCondition(), clusterId);
        };
    }

    private Evaluation splitBrain(List<ClusterNode> rows) {
        List<LogicalNode> logical =
                evaluator.toLogicalNodes(nodeMapper.toEndpoints(rows), SplitBrainStatus.byNodeId(rows));
        boolean critical = logical.stream().anyMatch(n -> n.splitBrain() == SplitBrainStatus.CRITICAL);
        Set<String> universe = Set.of(CLUSTER_SUBJECT);
        return new Evaluation(universe, critical ? Map.of(CLUSTER_SUBJECT, 1.0) : Map.of());
    }

    private Evaluation nodeDown(List<ClusterNode> rows) {
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (ClusterNode node : rows) {
            if (node.getManagementUrl() == null) {
                continue; // not manageable — nothing to be "down" from Rahdar's view
            }
            String key = NODE_PREFIX + node.getId();
            universe.add(key);
            boolean down = "STOPPED".equals(node.getState()) || node.getLastError() != null;
            if (down) {
                active.put(key, 1.0);
            }
        }
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }

    private Evaluation replicationBehind(List<ClusterNode> rows) {
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        for (ClusterNode node : rows) {
            if (!"BACKUP".equals(node.getHaDesignation())) {
                continue;
            }
            String key = NODE_PREFIX + node.getId();
            universe.add(key);
            if (Boolean.FALSE.equals(node.getReplicaSync())) {
                active.put(key, 1.0);
            }
        }
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }

    /**
     * A clock that disagrees with Rahdar's beyond tolerance (ADR-0053).
     *
     * <p>Subject-keyed by node so each tracks and silences independently, with one
     * extra subject for the case Rahdar cannot measure directly: when every node in
     * the estate disagrees the same way, the common factor is Rahdar's own host, and
     * the alert says so rather than blaming every broker at once.
     *
     * <p>The universe is only what has actually been measured. A node whose Jolokia
     * agent strips the response timestamp is unknown, not in agreement, and putting
     * it in the universe would resolve an alert on the strength of no evidence.
     */
    private Evaluation clockSkew(UUID clusterId) {
        ClockOffsetService.Assessment assessment = clocks.assessmentFor(clusterId);
        if (assessment.verdict() == ClockOffsetService.Verdict.UNKNOWN) {
            return Evaluation.EMPTY;
        }
        Set<String> universe = new HashSet<>();
        Map<String, Double> active = new HashMap<>();
        assessment.measured().forEach(measured -> universe.add(NODE_PREFIX + measured.nodeId()));
        if (assessment.verdict() == ClockOffsetService.Verdict.RAHDAR_SUSPECT) {
            universe.add(RAHDAR_SUBJECT);
            active.put(RAHDAR_SUBJECT, 1.0);
        } else {
            for (ClockOffsetService.NodeSkew skewed : assessment.skewed()) {
                active.put(NODE_PREFIX + skewed.nodeId(), (double)
                        Math.abs(skewed.offset().offsetMs()));
            }
        }
        return new Evaluation(Set.copyOf(universe), Map.copyOf(active));
    }

    private Evaluation clusterDegraded(UUID clusterId, List<ClusterNode> rows) {
        List<NodeEndpoint> endpoints = nodeMapper.toEndpoints(rows);
        List<LogicalNode> logical = evaluator.toLogicalNodes(endpoints, SplitBrainStatus.byNodeId(rows));
        ClusterHealth health = evaluator.toHealth(clusterId, logical);
        Set<String> universe = Set.of(CLUSTER_SUBJECT);
        boolean degraded =
                health.level() == ClusterHealth.Level.DEGRADED || health.level() == ClusterHealth.Level.CRITICAL;
        return new Evaluation(universe, degraded ? Map.of(CLUSTER_SUBJECT, 1.0) : Map.of());
    }

    /**
     * A condition another module owns, such as {@code CONFIG_DRIFT}, answered by its
     * {@link AlertSignalSource}. Nothing is active while that module is disabled.
     */
    private Evaluation signal(String condition, UUID clusterId) {
        return signals.stream()
                .filter(s -> s.condition().equals(condition))
                .findFirst()
                .map(s -> s.evaluate(clusterId))
                .orElse(Evaluation.EMPTY);
    }
}
