package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth.Level;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The verdict the scrape writes is what any replica reads, and it goes with its cluster. */
class KafkaHealthStoreTest extends PostgresIntegrationTest {

    @Autowired
    KafkaHealthStore store;

    @Autowired
    ClusterRepository clusters;

    @Test
    void aVerdictRoundTripsIsReplacedByTheNextAndGoesWhenTheClusterDoes() {
        UUID id = clusters.save(new ClusterEntity(KafkaModule.SYSTEM, "k-" + UUID.randomUUID(), null, null))
                .getId();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ClusterHealth degraded = KafkaHealth.verdict(
                id, Level.DEGRADED, List.of("broker-1", "broker-2"), List.of("a \"note\"", "b"), true);

        store.write(degraded, at);

        assertThat(store.read(id)).hasValueSatisfying(stored -> {
            assertThat(stored.health()).isEqualTo(degraded);
            assertThat(stored.at()).isEqualTo(at);
        });

        ClusterHealth ok = KafkaHealth.verdict(id, Level.OK, List.of("broker-1"), List.of(), false);
        store.write(ok, at.plusSeconds(30));
        assertThat(store.read(id))
                .hasValueSatisfying(stored -> assertThat(stored.health()).isEqualTo(ok));

        clusters.deleteById(id);
        clusters.flush();
        assertThat(store.read(id)).isEmpty();
    }
}
