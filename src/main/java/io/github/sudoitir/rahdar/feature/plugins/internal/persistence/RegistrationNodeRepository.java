package io.github.sudoitir.rahdar.feature.plugins.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface RegistrationNodeRepository extends JpaRepository<RegistrationNodeEntity, UUID> {

    List<RegistrationNodeEntity> findByRegistrationId(UUID registrationId);

    /** The registration's row for a node, or for the whole cluster when {@code nodeId} is null. */
    Optional<RegistrationNodeEntity> findByRegistrationIdAndNodeId(UUID registrationId, UUID nodeId);

    @Transactional
    void deleteByRegistrationIdAndNodeId(UUID registrationId, UUID nodeId);
}
