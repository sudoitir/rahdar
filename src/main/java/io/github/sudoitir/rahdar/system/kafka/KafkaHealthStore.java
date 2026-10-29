package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth;
import io.github.sudoitir.rahdar.platform.clusters.ClusterHealth.Level;
import io.github.sudoitir.rahdar.platform.clusters.SplitBrainStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code kafka_health}: the verdict the scrape formed for a Kafka cluster, kept where every replica reads it, so a
 * health request never asks a broker and every replica gives the same answer.
 */
@Component
@RequiredArgsConstructor
class KafkaHealthStore {

    /** A verdict and when the scrape formed it. */
    record Stored(ClusterHealth health, Instant at) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    void write(ClusterHealth health, Instant at) {
        jdbc.sql("""
                        INSERT INTO kafka_health (cluster_id, evaluated_at, level, serving, notes, behind)
                        VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)
                        ON CONFLICT (cluster_id) DO UPDATE SET evaluated_at = EXCLUDED.evaluated_at,
                            level = EXCLUDED.level, serving = EXCLUDED.serving, notes = EXCLUDED.notes,
                            behind = EXCLUDED.behind
                        """)
                .params(
                        health.clusterId(),
                        Timestamp.from(at),
                        health.level().name(),
                        mapper.writeValueAsString(health.liveEndpointNames()),
                        mapper.writeValueAsString(health.notes()),
                        health.replicationBehind())
                .update();
    }

    Optional<Stored> read(UUID clusterId) {
        return jdbc.sql("SELECT evaluated_at, level, serving, notes, behind FROM kafka_health WHERE cluster_id = ?")
                .param(clusterId)
                .query((rs, row) -> new Stored(
                        new ClusterHealth(
                                clusterId,
                                Level.valueOf(rs.getString("level")),
                                List.of(mapper.readValue(rs.getString("serving"), String[].class)),
                                SplitBrainStatus.NONE,
                                rs.getBoolean("behind"),
                                List.of(mapper.readValue(rs.getString("notes"), String[].class)),
                                List.of()),
                        rs.getTimestamp("evaluated_at").toInstant()))
                .optional();
    }
}
