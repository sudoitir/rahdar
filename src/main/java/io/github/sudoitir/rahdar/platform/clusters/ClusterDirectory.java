package io.github.sudoitir.rahdar.platform.clusters;

import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerIdentityClaims;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registered clusters and their nodes, as other modules read them (ADR-0069). Read-only:
 * registration changes nodes, and {@link NodeStateRecorder} records what a scrape observed.
 */
@Component
@RequiredArgsConstructor
public class ClusterDirectory {

    private final ClusterRepository clusters;
    private final BrokerNodeRepository nodes;
    private final ClusterOwnership ownership;
    private final BrokerIdentityClaims identityClaims;

    /** A cluster's nodes, ordered by name. */
    @Transactional(readOnly = true)
    public List<ClusterNode> nodes(UUID clusterId) {
        return List.copyOf(nodes.findByClusterIdOrderByNameAsc(clusterId));
    }

    @Transactional(readOnly = true)
    public List<ClusterNode> allNodes() {
        return List.copyOf(nodes.findAll());
    }

    @Transactional(readOnly = true)
    public Optional<ClusterNode> node(UUID nodeId) {
        return nodes.findById(nodeId).map(ClusterNode.class::cast);
    }

    /** The cluster identity the system reported when the cluster was registered, such as a Kafka cluster id. */
    @Transactional(readOnly = true)
    public Optional<String> registeredIdentity(UUID clusterId) {
        return identityClaims.identityOf(clusterId, SystemRegistration.CLUSTER_ID);
    }

    /** Every registered cluster, ordered by name. */
    @Transactional(readOnly = true)
    public List<RegisteredCluster> clusters() {
        return registered();
    }

    /** The registered clusters this replica owns (ADR-0152): the ones its timed broker duties visit. */
    public List<RegisteredCluster> owned() {
        return registered().stream().filter(c -> ownership.owns(c.getId())).toList();
    }

    private List<RegisteredCluster> registered() {
        return List.copyOf(clusters.findAllByOrderByNameAsc());
    }

    @Transactional(readOnly = true)
    public Optional<RegisteredCluster> cluster(UUID clusterId) {
        return clusters.findById(clusterId).map(RegisteredCluster.class::cast);
    }
}
