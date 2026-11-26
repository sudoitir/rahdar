package io.github.sudoitir.rahdar.system.kafka.internal.persistence;

import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaSchemaBindingEntity.Key;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code kafka_schema_binding} access. One row per topic side of a cluster. */
public interface KafkaSchemaBindingRepository extends JpaRepository<KafkaSchemaBindingEntity, Key> {

    List<KafkaSchemaBindingEntity> findByClusterIdOrderByTopicAscSideAsc(UUID clusterId);

    Optional<KafkaSchemaBindingEntity> findByClusterIdAndTopicAndSide(UUID clusterId, String topic, String side);
}
