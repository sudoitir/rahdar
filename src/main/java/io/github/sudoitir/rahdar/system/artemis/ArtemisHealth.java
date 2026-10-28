package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealthEvaluator;
import io.github.sudoitir.rahdar.platform.clusters.HaStateEvaluator;
import io.github.sudoitir.rahdar.platform.clusters.LogicalNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** An Artemis cluster's health is its HA pairs: who is live, split brain, replication behind. */
@Component
@RequiredArgsConstructor
class ArtemisHealth implements ClusterHealthEvaluator {

    private final HaStateEvaluator evaluator;

    @Override
    public String system() {
        return ArtemisModule.SYSTEM;
    }

    @Override
    public ClusterHealth evaluate(UUID clusterId, List<LogicalNode> nodes, Set<UUID> rejectedNodeIds) {
        return evaluator.toHealth(clusterId, nodes, rejectedNodeIds);
    }
}
