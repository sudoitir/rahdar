package io.github.sudoitir.rahdar.platform.scrape;

import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.rahdar.kernel.core.PartitionProvisioner;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Appends {@code metric_sample} rows for swept queues (ADR-0006 — Rahdar owns the
 * timeseries in Postgres). Six metrics per queue, stored once per {@link SampleGate#HEARTBEAT}
 * and never faster (ADR-0220), whether or not the value changed. Append-only, JDBC batch —
 * the table is range-partitioned and insert-tuned. Its key is the lookup (cluster, subject,
 * metric, time), so a sample the key already holds is skipped.
 *
 * <p>{@code deliveringCount} and {@code messagesExpired} are sampled for ADR-0089:
 * in-flight depth is what separates a stalled consumer from a starved one, and both
 * arrive on the {@link QueueRow} the sweep has already fetched, so recording them
 * costs no additional broker request. {@code metric_sample.metric} is a {@code text}
 * column, so adding a name needs no migration.
 */
@Component
@RequiredArgsConstructor
public class MetricSampleWriter {

    private static final String INSERT = """
            INSERT INTO metric_sample (ts, value, subject_kind, subject_path, metric, cluster_id, node_id)
            VALUES (now(), :value, 'QUEUE', :subjectName, :metric, :clusterId, :nodeId)
            ON CONFLICT ON CONSTRAINT uq_metric_sample DO NOTHING
            """;

    private static final String PLUGIN_INSERT = """
            INSERT INTO metric_sample (ts, value, subject_kind, subject_path, metric, cluster_id, node_id)
            VALUES (now(), :value, 'rahdar.plugin', :subjectName, :metric, :clusterId, NULL)
            ON CONFLICT ON CONSTRAINT uq_metric_sample DO NOTHING
            """;

    private static final String QUEUE = "QUEUE";
    private static final String PLUGIN = "rahdar.plugin";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PartitionProvisioner partitions;
    private final Clock clock;
    private final SampleGate gate = new SampleGate();

    public void appendQueueSamples(List<QueueRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<SqlParameterSource> params = new ArrayList<>(rows.size() * 6);
        for (QueueRow r : rows) {
            if (r.backlogKnown()) {
                consider(
                        params,
                        QUEUE,
                        r.clusterId(),
                        r.nodeId(),
                        r.queueName(),
                        MetricKeys.BACKLOG.name(),
                        r.messageCount(),
                        now,
                        r);
            }
            consider(
                    params,
                    QUEUE,
                    r.clusterId(),
                    r.nodeId(),
                    r.queueName(),
                    MetricKeys.CONSUMER_COUNT.name(),
                    r.consumerCount(),
                    now,
                    r);
            consider(
                    params,
                    QUEUE,
                    r.clusterId(),
                    r.nodeId(),
                    r.queueName(),
                    MetricKeys.ENQUEUED.name(),
                    r.messagesAdded(),
                    now,
                    r);
            consider(
                    params,
                    QUEUE,
                    r.clusterId(),
                    r.nodeId(),
                    r.queueName(),
                    MetricKeys.ACKNOWLEDGED.name(),
                    r.messagesAcked(),
                    now,
                    r);
            consider(
                    params,
                    QUEUE,
                    r.clusterId(),
                    r.nodeId(),
                    r.queueName(),
                    MetricKeys.IN_FLIGHT.name(),
                    r.deliveringCount(),
                    now,
                    r);
            consider(
                    params,
                    QUEUE,
                    r.clusterId(),
                    r.nodeId(),
                    r.queueName(),
                    MetricKeys.EXPIRED.name(),
                    r.messagesExpired(),
                    now,
                    r);
        }
        if (!params.isEmpty()) {
            write(INSERT, params.toArray(SqlParameterSource[]::new));
        }
    }

    /** A plugin metric's values on one cluster (ADR-0113): subject kind {@code rahdar.plugin}, no node. */
    public void appendPluginSamples(UUID clusterId, String metric, Map<String, Double> values) {
        if (values.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        SqlParameterSource[] params = values.entrySet().stream()
                .filter(e -> gate.due(PLUGIN, clusterId, null, e.getKey(), metric, now))
                .map(e -> new MapSqlParameterSource()
                        .addValue("value", e.getValue())
                        .addValue("subjectName", e.getKey())
                        .addValue("metric", metric)
                        .addValue("clusterId", clusterId))
                .toArray(SqlParameterSource[]::new);
        if (params.length > 0) {
            write(PLUGIN_INSERT, params);
        }
    }

    private void consider(
            List<SqlParameterSource> params,
            String kind,
            UUID clusterId,
            UUID nodeId,
            String subject,
            String metric,
            long value,
            Instant now,
            QueueRow row) {
        if (gate.due(kind, clusterId, nodeId, subject, metric, now)) {
            params.add(sample(row, metric, value));
        }
    }

    /** A sample whose day has no partition creates it and is written once more, in a new transaction (ADR-0193). */
    private void write(String sql, SqlParameterSource[] params) {
        partitions.retryingNoPartition(() -> transactions.execute(status -> jdbc.batchUpdate(sql, params)));
    }

    private static SqlParameterSource sample(QueueRow r, String metric, long value) {
        return new MapSqlParameterSource()
                .addValue("value", (double) value)
                .addValue("subjectName", r.queueName())
                .addValue("metric", metric)
                .addValue("clusterId", r.clusterId())
                .addValue("nodeId", r.nodeId());
    }
}
