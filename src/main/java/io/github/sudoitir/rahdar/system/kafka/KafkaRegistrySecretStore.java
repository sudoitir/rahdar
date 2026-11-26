package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.kernel.security.TableSealedStore;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The sealed schema-registry credential on {@code kafka_connection}, for key rotation (ADR-0132). */
@Component
class KafkaRegistrySecretStore extends TableSealedStore {

    KafkaRegistrySecretStore(JdbcTemplate jdbc) {
        super(jdbc, "kafka_connection", "registry_secret", List.of("cluster_id"));
    }
}
