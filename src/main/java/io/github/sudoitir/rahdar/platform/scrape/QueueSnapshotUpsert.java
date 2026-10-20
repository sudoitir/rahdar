package io.github.sudoitir.rahdar.platform.scrape;

import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Bulk writer for {@code subscription_snapshot} (ADR-0016). A sweep rewrites thousands
 * of rows that have no identity worth dirty-checking, so this is a JDBC batched
 * {@code INSERT … ON CONFLICT (node_id, queue_name) DO UPDATE} rather than JPA.
 * {@code NamedParameterJdbcTemplate} + {@code ON CONFLICT} — both stock, no new
 * dependency. The estate tables stay pure JPA.
 */
@Component
@RequiredArgsConstructor
public class QueueSnapshotUpsert {

    private static final String UPSERT = """
            INSERT INTO subscription_snapshot
              (ts, backlog, consumer_count, in_flight, scheduled, enqueued, acknowledged, expired,
               destination, subscription, kind, native_attributes, cluster_id, node_id, durable, paused)
            VALUES
              (now(), :backlog, :consumerCount, :inFlight, :scheduled, :enqueued, :acknowledged, :expired,
               :destination, :subscription, :kind, :nativeAttributes::jsonb, :clusterId, :nodeId, :durable, :paused)
            ON CONFLICT (node_id, subscription) DO UPDATE SET
               ts                = now(),
               backlog           = EXCLUDED.backlog,
               consumer_count    = EXCLUDED.consumer_count,
               in_flight         = EXCLUDED.in_flight,
               scheduled         = EXCLUDED.scheduled,
               enqueued          = EXCLUDED.enqueued,
               acknowledged      = EXCLUDED.acknowledged,
               expired           = EXCLUDED.expired,
               destination       = EXCLUDED.destination,
               kind              = EXCLUDED.kind,
               native_attributes = EXCLUDED.native_attributes,
               cluster_id        = EXCLUDED.cluster_id,
               durable           = EXCLUDED.durable,
               paused            = EXCLUDED.paused
            """;

    private static final String REAP_STALE =
            "DELETE FROM subscription_snapshot WHERE node_id = :nodeId AND ts < :sweepStart";

    private static final String FORGET_QUEUE =
            "DELETE FROM subscription_snapshot WHERE cluster_id = :clusterId AND subscription = :queueName";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Upsert a batch of rows in one round trip. */
    @Transactional
    public void upsertBatch(List<QueueRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        SqlParameterSource[] params = rows.stream().map(this::params).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT, params);
    }

    /**
     * Drop rows for a node that this sweep did not touch — a queue removed on the
     * broker since the sweep began. Only called when a full tier-C sweep of the
     * node completes, so a partial page never reaps live rows.
     */
    @Transactional
    public int reapStale(UUID nodeId, Instant sweepStart) {
        return jdbc.update(REAP_STALE, Map.of("nodeId", nodeId, "sweepStart", Timestamp.from(sweepStart)));
    }

    /**
     * Drop a queue's rows across the cluster, for a queue Rahdar has just destroyed.
     *
     * <p>The snapshot is a cache of what the brokers run, and a sweep reaps what is
     * gone — but a destroyed queue stays listed until that sweep comes round, so the
     * delete reads as though it did nothing, and acting on the row that is left fails
     * against an MBean that no longer exists. A command that destroyed the queue knows
     * it is gone; this is that knowledge, applied to the cache.
     */
    @Transactional
    public int forget(UUID clusterId, String queueName) {
        return jdbc.update(FORGET_QUEUE, Map.of("clusterId", clusterId, "queueName", queueName));
    }

    private SqlParameterSource params(QueueRow r) {
        return new MapSqlParameterSource()
                .addValue("backlog", r.messageCount())
                .addValue("consumerCount", r.consumerCount())
                .addValue("inFlight", r.deliveringCount())
                .addValue("scheduled", r.scheduledCount())
                .addValue("enqueued", r.messagesAdded())
                .addValue("acknowledged", r.messagesAcked())
                .addValue("expired", r.messagesExpired())
                .addValue("destination", r.address())
                .addValue("subscription", r.queueName())
                .addValue("kind", r.kind())
                .addValue("nativeAttributes", nativeAttributes(r))
                .addValue("clusterId", r.clusterId())
                .addValue("nodeId", r.nodeId())
                .addValue("durable", r.durable())
                .addValue("paused", r.paused());
    }

    private String nativeAttributes(QueueRow r) {
        return mapper.writeValueAsString(r.nativeAttributes());
    }
}
