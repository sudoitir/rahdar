package io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code rabbitmq_connection} access. */
public interface RabbitMqConnectionRepository extends JpaRepository<RabbitMqConnectionEntity, UUID> {}
