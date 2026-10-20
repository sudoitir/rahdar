package io.github.sudoitir.rahdar.platform.clusters.internal.persistence;

import io.github.sudoitir.openbroker.Plane;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * {@code broker_node} access (ADR-0011). Updates are dirty-checking mutations on
 * a managed {@link BrokerNodeEntity} — no delete+reinsert, so row identity and
 * every {@code audit_event.node_id} reference are stable across refreshes.
 */
public interface BrokerNodeRepository extends JpaRepository<BrokerNodeEntity, UUID> {

    List<BrokerNodeEntity> findByClusterIdOrderByNameAsc(UUID clusterId);

    Optional<BrokerNodeEntity> findByClusterIdAndName(UUID clusterId, String name);

    /** Whether another node of the cluster is managed at this URL. */
    @Query("""
            SELECT COUNT(n) > 0 FROM BrokerNodeEntity n JOIN n.endpoints e
            WHERE n.clusterId = :clusterId AND n.id <> :id AND e.plane = :plane AND e.url = :url
            """)
    boolean existsEndpointElsewhere(UUID clusterId, Plane plane, String url, UUID id);

    default boolean existsManagementUrlElsewhere(UUID clusterId, String managementUrl, UUID id) {
        return existsEndpointElsewhere(clusterId, Plane.MANAGEMENT, managementUrl, id);
    }

    /** Every registered node of any cluster that carries one of these broker node ids (ADR-0167). */
    List<BrokerNodeEntity> findByNativeNodeIdIn(Collection<String> nativeNodeIds);

    /** Every registered node with an endpoint on this plane. */
    @Query("SELECT DISTINCT n FROM BrokerNodeEntity n JOIN n.endpoints e WHERE e.plane = :plane")
    List<BrokerNodeEntity> findWithEndpoint(Plane plane);

    /** Every registered node with a management URL, for matching URLs by their normal form (ADR-0167). */
    default List<BrokerNodeEntity> findManaged() {
        return findWithEndpoint(Plane.MANAGEMENT);
    }
}
