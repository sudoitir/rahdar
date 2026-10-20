package io.github.sudoitir.rahdar.platform.scrape.internal.persistence;

import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read access to {@code subscription_snapshot} for the cross-node aggregator. Writes are
 * {@link QueueSnapshotUpsert}'s job, not this repository's — never call
 * {@code save}/{@code delete} here (ADR-0016).
 */
public interface QueueSnapshotRepository extends JpaRepository<QueueSnapshotEntity, QueueSnapshotEntity.Key> {

    List<QueueSnapshotEntity> findByClusterId(UUID clusterId);

    List<QueueSnapshotEntity> findByNodeId(UUID nodeId);

    /**
     * Distinct addresses seen on a cluster, for reply-address pattern resolution.
     * A projection rather than {@link #findByClusterId}: the resolver wants names
     * only, and a cluster's snapshot can be thousands of rows wide.
     */
    @Query("select distinct s.address from QueueSnapshotEntity s where s.clusterId = :clusterId")
    List<String> findDistinctAddressesByClusterId(@Param("clusterId") UUID clusterId);
}
