package io.github.sudoitir.rahdar.platform.scrape;

import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bucketed reads over {@code metric_sample} (metrics spec, ADR-0033). JDBC, no JPA
 * entity — an analytic read of a partitioned, disposable cache, the same shape as
 * {@code subscription_snapshot}'s bulk-upsert path (ADR-0016).
 *
 * <p>A gauge (point-in-time quantity, e.g. {@code messageCount}) is averaged per
 * bucket with its maximum reported as a peak. A counter (broker-lifetime monotonic,
 * e.g. {@code messagesAdded}) is converted to a per-second rate: each sample's increase
 * over the same subject's previous sample on the same node ({@code lag()}), summed into
 * the bucket the sample falls in. Taking the delta from the previous sample, not from
 * the first sample inside the bucket, counts what was added between two buckets too, so
 * a bucket holding a single sample still reads the traffic (issue #73). Computed per
 * subject and node first — one queue's independent counters on several nodes would
 * make a meaningless cluster-wide difference. {@code GREATEST(..., 0)} clamps a
 * broker-restart counter reset to zero rather than a negative spike.
 */
@Repository
@RequiredArgsConstructor
public class MetricSamples {

    private static final String SUBJECT_PATH = "subject_path";

    /** The subject kind of a plugin's own metric (ADR-0113). */
    public static final String PLUGIN_SUBJECT = "rahdar.plugin";

    private static final String NODE_ID = "node_id";
    private static final String BUCKET = "bucket";

    public record Bucket(Instant ts, double value, Double peak) {}

    /** One node's bucket of a per-node series (ADR-0110). */
    public record NodeBucket(UUID nodeId, Instant ts, double value, Double peak) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;

    /**
     * A subject's counter rate between its oldest and newest sample in a window.
     *
     * @param asOf the newest sample's time, so a reader can state the rate's age
     * @param span the time between the two samples the rate spans — a slow-tier queue's
     *     rate is an average over minutes, and a reader must be able to say so
     */
    public record SubjectRate(double rate, Instant asOf, Duration span) {

        /**
         * Several nodes' rates as one: summed, as of the newest, spanning the longest — the same
         * aggregate {@link #latestRateWithTimeBySubject} computes in SQL.
         */
        public static SubjectRate sum(Collection<SubjectRate> perNode) {
            double rate = 0;
            Instant asOf = Instant.EPOCH;
            Duration span = Duration.ZERO;
            for (SubjectRate r : perNode) {
                rate += r.rate();
                if (r.asOf().isAfter(asOf)) {
                    asOf = r.asOf();
                }
                if (r.span().compareTo(span) > 0) {
                    span = r.span();
                }
            }
            return new SubjectRate(rate, asOf, span);
        }
    }

    /**
     * Like {@link #latestRateBySubject}, but computed per node and summed, divided by the
     * time each node's samples actually span rather than by the window, and carrying the
     * longest span and the newest sample's time. A subject with no node sampled twice in
     * the window is omitted, never zero.
     */
    public Map<String, SubjectRate> latestRateWithTimeBySubject(
            UUID clusterId, String metric, Instant from, Instant to) {
        // Per node first: each node's counter is its own lifetime count, so max - min across
        // nodes would subtract one broker's counter from another's.
        String sql = """
                SELECT subject_path,
                       sum(delta / span_seconds) AS rate,
                       max(as_of) AS as_of,
                       max(span_seconds) AS span_seconds
                  FROM (SELECT subject_path,
                               GREATEST(max(value) - min(value), 0)::double precision AS delta,
                               max(ts) AS as_of,
                               EXTRACT(EPOCH FROM max(ts) - min(ts))::double precision AS span_seconds
                          FROM metric_sample
                         WHERE cluster_id = :clusterId AND subject_kind = 'QUEUE' AND metric = :metric
                           AND ts >= :from AND ts < :to
                         GROUP BY subject_path, node_id
                        HAVING count(*) >= 2 AND max(ts) > min(ts)) per_node
                 GROUP BY subject_path
                """;
        MapSqlParameterSource p = windowParams(clusterId, metric, from, to);
        Map<String, SubjectRate> out = new HashMap<>();
        jdbc.query(sql, p, rs -> {
            double spanSeconds = rs.getDouble("span_seconds");
            out.put(
                    rs.getString(SUBJECT_PATH),
                    new SubjectRate(
                            rs.getDouble("rate"),
                            rs.getTimestamp("as_of").toInstant(),
                            Duration.ofMillis(Math.round(spanSeconds * 1000))));
        });
        return out;
    }

    /**
     * {@link #latestRateWithTimeBySubject} without the sum across nodes: each subject's rate on each
     * node that sampled it twice in the window (ADR-0110). The per-node rates of a subject add up to
     * its {@link #latestRateWithTimeBySubject} rate exactly; see {@link SubjectRate#sum}.
     *
     * @return subject name → node id → rate on that node
     */
    public Map<String, Map<UUID, SubjectRate>> latestRateWithTimeBySubjectAndNode(
            UUID clusterId, String metric, Instant from, Instant to) {
        String sql = """
                SELECT subject_path, node_id,
                       GREATEST(max(value) - min(value), 0)::double precision AS delta,
                       max(ts) AS as_of,
                       EXTRACT(EPOCH FROM max(ts) - min(ts))::double precision AS span_seconds
                  FROM metric_sample
                 WHERE cluster_id = :clusterId AND subject_kind = 'QUEUE' AND metric = :metric
                   AND ts >= :from AND ts < :to
                 GROUP BY subject_path, node_id
                HAVING count(*) >= 2 AND max(ts) > min(ts)
                """;
        MapSqlParameterSource p = windowParams(clusterId, metric, from, to);
        Map<String, Map<UUID, SubjectRate>> out = new HashMap<>();
        jdbc.query(sql, p, rs -> {
            double spanSeconds = rs.getDouble("span_seconds");
            out.computeIfAbsent(rs.getString(SUBJECT_PATH), k -> new HashMap<>())
                    .put(
                            rs.getObject(NODE_ID, UUID.class),
                            new SubjectRate(
                                    rs.getDouble("delta") / spanSeconds,
                                    rs.getTimestamp("as_of").toInstant(),
                                    Duration.ofMillis(Math.round(spanSeconds * 1000))));
        });
        return out;
    }

    /**
     * The per-second slope of a gauge per subject over a window, by least-squares
     * regression (ADR-0089). Answers "is this climbing, and how fast" where
     * {@link #latestRateWithTimeBySubject} answers "how fast is it moving through".
     *
     * <p>Comparing the first and last sample cannot tell a queue oscillating around a
     * mean from one climbing steadily; a regression over every sample in the window can.
     * Postgres' {@code regr_slope} does it in the database, so no rows cross into the
     * JVM and there is no hand-rolled regression to test.
     *
     * <p>Computed per node and summed, like every other read here: the same queue on two
     * nodes is two independent series, and a cluster-wide regression over both would
     * interleave them into a meaningless line. A subject with fewer than two samples, or
     * with every sample at one instant (a zero-variance x, where {@code regr_slope} is
     * undefined and returns NULL), is omitted rather than reported as flat — "not
     * measurable" and "not moving" are different facts.
     *
     * @return subject name → change in the gauge per second; positive is growing
     */
    public Map<String, Double> depthSlopeBySubject(UUID clusterId, String metric, Instant from, Instant to) {
        String sql = """
                SELECT subject_path, sum(slope) AS slope
                  FROM (SELECT subject_path,
                               regr_slope(value, EXTRACT(EPOCH FROM ts)) AS slope
                          FROM metric_sample
                         WHERE cluster_id = :clusterId AND subject_kind = 'QUEUE' AND metric = :metric
                           AND ts >= :from AND ts < :to
                         GROUP BY subject_path, node_id
                        HAVING count(*) >= 2 AND max(ts) > min(ts)) per_node
                 WHERE slope IS NOT NULL
                 GROUP BY subject_path
                """;
        MapSqlParameterSource p = windowParams(clusterId, metric, from, to);
        Map<String, Double> out = new HashMap<>();
        jdbc.query(sql, p, rs -> {
            out.put(rs.getString(SUBJECT_PATH), rs.getDouble("slope"));
        });
        return out;
    }

    /**
     * A queue gauge's buckets: one queue's average and peak when {@code subjectName} is given, and
     * the cluster's total when it is not ({@link #clusterGaugeTotal}).
     */
    public List<Bucket> gaugeSeries(
            UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        return subjectName == null
                ? clusterGaugeTotal(clusterId, metric, from, to, step)
                : gaugeSeries("QUEUE", clusterId, metric, subjectName, from, to, step);
    }

    /**
     * A cluster's gauge total at each bucket's end: the sum, over every queue on every node, of
     * that queue's last sample at or before the end, while that sample is still within the queue's
     * own sampling interval (ADR-0127, issue #72). Averaging every sample in a bucket, as before,
     * read 10,000 messages over 50 queues as about 200, and a queue on the slow sweep, absent from
     * most fine buckets, dropped out of them entirely.
     *
     * <p>Each sample holds from its own time until the queue's next sample, and for at most one and
     * a half of the gap since the previous one, so a deleted queue stops counting. A sample with no
     * previous one uses two slow-tier intervals. There is no peak: the total's maximum inside a
     * bucket is not a sum of the queues' maxima. A bucket with no sample at all is left out.
     */
    public List<Bucket> clusterGaugeTotal(UUID clusterId, String metric, Instant from, Instant to, Duration step) {
        String sql = """
                WITH samples AS (
                  SELECT ts, value,
                         lead(ts) OVER w AS next_ts,
                         ts - lag(ts) OVER w AS gap
                    FROM metric_sample
                   WHERE cluster_id = :clusterId AND subject_kind = 'QUEUE' AND metric = :metric
                     AND ts >= :lookbackFrom AND ts < :to
                  WINDOW w AS (PARTITION BY subject_path, node_id ORDER BY ts)
                ), spans AS (
                  -- The bucket ends at which this sample is the queue's latest, still-fresh value.
                  SELECT value,
                         date_bin(make_interval(secs => :stepSeconds), ts, TIMESTAMPTZ '2000-01-01')
                             + make_interval(secs => :stepSeconds) AS first_end,
                         LEAST(COALESCE(next_ts, 'infinity'),
                               ts + COALESCE(gap, make_interval(secs => :fallbackSeconds)) * 1.5,
                               CAST(:to AS timestamptz) + make_interval(secs => :stepSeconds)) AS last_end
                    FROM samples
                )
                SELECT bucket_end - make_interval(secs => :stepSeconds) AS bucket, sum(value) AS v
                  FROM spans, generate_series(first_end, last_end, make_interval(secs => :stepSeconds)) AS bucket_end
                 WHERE bucket_end - make_interval(secs => :stepSeconds)
                           >= date_bin(make_interval(secs => :stepSeconds), CAST(:from AS timestamptz), TIMESTAMPTZ '2000-01-01')
                   AND bucket_end - make_interval(secs => :stepSeconds) < :to
                 GROUP BY bucket_end ORDER BY bucket_end
                """;
        MapSqlParameterSource p = rateParams(clusterId, metric, null, from, to, step)
                .addValue("fallbackSeconds", (double) lookback().toSeconds());
        return jdbc.query(sql, p, (rs, i) -> new Bucket(rs.getTimestamp(BUCKET).toInstant(), rs.getDouble("v"), null));
    }

    /** A plugin metric's gauge buckets for one subject (ADR-0113). */
    public List<Bucket> pluginSeries(
            UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        return gaugeSeries(PLUGIN_SUBJECT, clusterId, metric, subjectName, from, to, step);
    }

    private List<Bucket> gaugeSeries(
            String subjectType,
            UUID clusterId,
            String metric,
            String subjectName,
            Instant from,
            Instant to,
            Duration step) {
        String sql = """
                SELECT date_bin(make_interval(secs => :stepSeconds), ts, TIMESTAMPTZ '2000-01-01') AS bucket,
                       avg(value) AS v, max(value) AS peak
                  FROM metric_sample
                 WHERE cluster_id = :clusterId AND subject_kind = :subjectType AND metric = :metric
                   AND (:subjectName::text IS NULL OR subject_path = :subjectName)
                   AND ts >= :from AND ts < :to
                 GROUP BY bucket ORDER BY bucket
                """;
        return jdbc.query(
                sql,
                params(clusterId, metric, subjectName, from, to, step).addValue("subjectType", subjectType),
                (rs, i) -> new Bucket(
                        rs.getTimestamp(BUCKET).toInstant(), rs.getDouble("v"), (Double) rs.getObject("peak")));
    }

    public List<Bucket> rateSeries(
            UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        String sql = """
                SELECT bucket, sum(delta) / :stepSeconds AS v
                  FROM (%s) deltas
                 GROUP BY bucket ORDER BY bucket
                """.formatted(DELTAS);
        return jdbc.query(
                sql,
                rateParams(clusterId, metric, subjectName, from, to, step),
                (rs, i) -> new Bucket(rs.getTimestamp(BUCKET).toInstant(), rs.getDouble("v"), null));
    }

    /**
     * Each counter sample's increase over its subject's previous sample on the same node, with the
     * bucket it falls in. The previous sample may predate {@code :from}, so rows are read from
     * {@code :lookbackFrom}; a sample with no predecessor there has no delta and counts nothing.
     */
    private static final String DELTAS = """
            SELECT node_id, bucket, GREATEST(value - previous, 0) AS delta
              FROM (SELECT node_id, ts, value,
                           date_bin(make_interval(secs => :stepSeconds), ts, TIMESTAMPTZ '2000-01-01') AS bucket,
                           lag(value) OVER (PARTITION BY subject_path, node_id ORDER BY ts) AS previous
                      FROM metric_sample
                     WHERE cluster_id = :clusterId AND subject_kind = 'QUEUE' AND metric = :metric
                       AND (:subjectName::text IS NULL OR subject_path = :subjectName)
                       AND ts >= :lookbackFrom AND ts < :to) samples
             -- Tested before the clamp: GREATEST skips NULLs, so it would read "no predecessor" as 0.
             WHERE ts >= :from AND previous IS NOT NULL
            """;

    /**
     * {@link #params} plus how far before {@code from} a counter's previous sample is looked for:
     * two slow-tier intervals, so a queue on the slowest sweep still finds its last sample after
     * one missed scrape.
     */
    private MapSqlParameterSource rateParams(
            UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        return params(clusterId, metric, subjectName, from, to, step)
                .addValue("lookbackFrom", Timestamp.from(from.minus(lookback())));
    }

    private Duration lookback() {
        return settings.duration(ScrapeSettings.TIER_C).multipliedBy(2);
    }

    /**
     * {@link #gaugeSeries} for one subject, kept apart per node (ADR-0110): the average in each
     * bucket on each node, and its peak. The same rows and index as the total; only the grouping
     * differs.
     */
    public List<NodeBucket> gaugeSeriesByNode(
            UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        String sql = """
                SELECT node_id,
                       date_bin(make_interval(secs => :stepSeconds), ts, TIMESTAMPTZ '2000-01-01') AS bucket,
                       avg(value) AS v, max(value) AS peak
                  FROM metric_sample
                 WHERE cluster_id = :clusterId AND subject_kind = 'QUEUE' AND metric = :metric
                   AND subject_path = :subjectName
                   AND ts >= :from AND ts < :to
                 GROUP BY node_id, bucket ORDER BY node_id, bucket
                """;
        return jdbc.query(
                sql,
                params(clusterId, metric, subjectName, from, to, step),
                (rs, i) -> new NodeBucket(
                        rs.getObject(NODE_ID, UUID.class),
                        rs.getTimestamp(BUCKET).toInstant(),
                        rs.getDouble("v"),
                        (Double) rs.getObject("peak")));
    }

    /**
     * {@link #rateSeries} for one subject, kept apart per node (ADR-0110). Computed exactly as the
     * total is, so the nodes' buckets add up to the total's.
     */
    public List<NodeBucket> rateSeriesByNode(
            UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        String sql = """
                SELECT node_id, bucket, sum(delta) / :stepSeconds AS v
                  FROM (%s) deltas
                 GROUP BY node_id, bucket ORDER BY node_id, bucket
                """.formatted(DELTAS);
        return jdbc.query(
                sql,
                rateParams(clusterId, metric, subjectName, from, to, step),
                (rs, i) -> new NodeBucket(
                        rs.getObject(NODE_ID, UUID.class),
                        rs.getTimestamp(BUCKET).toInstant(),
                        rs.getDouble("v"),
                        null));
    }

    /**
     * The current rate per subject over one window (metrics spec) — used by
     * alert rate-threshold rules, one query per {@code (cluster, metric)} per
     * tick regardless of rule count, not one query per rule. Same restart-safe
     * {@code GREATEST(...,0)} clamp as {@link #rateSeries}, but grouped over one
     * window instead of {@code date_bin} buckets. A subject with fewer than two
     * samples in the window has no computable rate and is omitted, never
     * reported as zero — reporting zero would read as "throughput dropped" for
     * a queue simply not sampled twice yet. Computed per node and summed, as
     * {@link #latestRateWithTimeBySubject}: a queue on two nodes has two unrelated counters.
     */
    public Map<String, Double> latestRateBySubject(UUID clusterId, String metric, Instant from, Instant to) {
        Map<String, Double> out = new HashMap<>();
        latestRateWithTimeBySubject(clusterId, metric, from, to).forEach((subject, r) -> out.put(subject, r.rate()));
        return out;
    }

    private MapSqlParameterSource params(
            UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        return windowParams(clusterId, metric, from, to)
                .addValue("subjectName", subjectName)
                .addValue("stepSeconds", (double) step.toSeconds());
    }

    private static MapSqlParameterSource windowParams(UUID clusterId, String metric, Instant from, Instant to) {
        // pgjdbc cannot infer a SQL type for a bare java.time.Instant parameter
        // ("Can't infer the SQL type to use..."); java.sql.Timestamp maps to
        // timestamptz without ambiguity.
        return new MapSqlParameterSource(Map.of(
                "clusterId", clusterId, "metric", metric, "from", Timestamp.from(from), "to", Timestamp.from(to)));
    }
}
