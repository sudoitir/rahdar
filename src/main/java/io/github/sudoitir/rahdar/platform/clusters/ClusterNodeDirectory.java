package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetRegistry.ClockOffset;
import io.github.sudoitir.rahdar.platform.broker.NodeDirectory;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class ClusterNodeDirectory implements NodeDirectory {

    private final BrokerNodeRepository nodes;
    private final ClusterRepository clusters;

    @Override
    @Transactional(readOnly = true)
    public String system(UUID clusterId) {
        return clusters.findById(clusterId)
                .map(ClusterEntity::getSystem)
                .orElseThrow(() -> new NotFoundException("cluster", clusterId.toString()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnownNode> nodes() {
        return nodes.findAll().stream()
                .map(n -> new KnownNode(n.getId(), n.getClusterId(), n.getName(), n.getEndpoints()))
                .toList();
    }

    @Override
    @Transactional
    public void recordClockOffsets(Map<UUID, ClockOffset> byNode) {
        for (BrokerNodeEntity node : nodes.findAllById(byNode.keySet())) {
            ClockOffset offset = byNode.get(node.getId());
            node.recordClockOffset(offset.offsetMs(), offset.uncertaintyMs(), offset.measuredAt());
        }
    }
}
