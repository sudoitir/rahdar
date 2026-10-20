package io.github.sudoitir.rahdar.feature.rr.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RrExpectationRepository extends JpaRepository<RrExpectationEntity, UUID> {

    List<RrExpectationEntity> findByClusterIdOrderByRequestDestination(UUID clusterId);

    List<RrExpectationEntity> findByEnabledTrue();

    boolean existsByClusterIdAndRequestDestination(UUID clusterId, String requestDestination);
}
