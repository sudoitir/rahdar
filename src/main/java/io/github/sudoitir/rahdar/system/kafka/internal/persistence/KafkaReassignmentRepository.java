package io.github.sudoitir.rahdar.system.kafka.internal.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code kafka_reassignment} access. One row per partition of a cluster. */
public interface KafkaReassignmentRepository extends JpaRepository<KafkaReassignmentEntity, UUID> {

    List<KafkaReassignmentEntity> findByClusterId(UUID clusterId);

    Optional<KafkaReassignmentEntity> findByClusterIdAndTopicAndPartition(UUID clusterId, String topic, int partition);
}
