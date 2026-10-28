package io.github.sudoitir.rahdar.system.kafka.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code kafka_connection} access. */
public interface KafkaConnectionRepository extends JpaRepository<KafkaConnectionEntity, UUID> {}
