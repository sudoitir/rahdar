package io.github.sudoitir.rahdar.feature.metrics;

import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.rahdar.feature.metrics.web.MetricViews.MetricNodeSeries;
import io.github.sudoitir.rahdar.feature.metrics.web.MetricViews.MetricPoint;
import io.github.sudoitir.rahdar.feature.metrics.web.MetricViews.MetricSeries;
import io.github.sudoitir.rahdar.feature.metrics.web.MetricViews.MetricSeriesResponse;
import io.github.sudoitir.rahdar.kernel.lifecycle.LifecycleRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceKind;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.MetricSampleStore;
import io.github.sudoitir.rahdar.platform.scrape.MetricSamples;
import io.github.sudoitir.rahdar.platform.scrape.MetricSamples.Bucket;
import io.github.sudoitir.rahdar.platform.scrape.MetricSamples.NodeBucket;
import io.github.sudoitir.rahdar.platform.scrape.PluginMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Turns a raw metric query into bucketed, server-clamped series (metrics spec,
 * ADR-0033). Every metric name Rahdar samples is intrinsically either a gauge or a
 * monotonic counter — a fixed lookup here, not a per-row flag, since
 * {@code MetricSampleWriter} never mixes the two under one name.
 */
@Service
public class MetricQueryService {

    private static final Set<String> GAUGE_METRICS =
            Set.of(MetricKeys.BACKLOG.name(), MetricKeys.CONSUMER_COUNT.name(), MetricKeys.IN_FLIGHT.name());
    private static final Set<String> RATE_METRICS =
            Set.of(MetricKeys.ENQUEUED.name(), MetricKeys.ACKNOWLEDGED.name(), MetricKeys.EXPIRED.name());

    /** No bucket finer than the fastest tier that samples metrics (tier B, 15s). */
    private static final Duration MIN_STEP = Duration.ofSeconds(15);

    private static final int MAX_POINTS = 500;

    private static final String QUEUE_SUBJECT = ResourceKind.QUEUE.name();
    private static final String GAUGE = "GAUGE";

    /** A split by node draws at most this many nodes (ADR-0110). */
    static final int MAX_SPLIT_NODES = 16;

    /** ...and at most this many points per metric across all of them. */
    static final int MAX_SPLIT_POINTS = 2_000;

    /** The one split there is. */
    public static final String SPLIT_BY_NODE = "NODE";

    private final MetricSamples repository;
    private final LifecycleRegistry lifecycle;
    private final ClusterAccessGuard clusterAccess;
    private final ClusterDirectory directory;
    private final PluginMetrics pluginMetrics;

    public MetricQueryService(
            MetricSamples repository,
            LifecycleRegistry lifecycle,
            ClusterAccessGuard clusterAccess,
            ClusterDirectory directory,
            PluginMetrics pluginMetrics) {
        this.pluginMetrics = pluginMetrics;
        this.repository = repository;
        this.lifecycle = lifecycle;
        this.clusterAccess = clusterAccess;
        this.directory = directory;
    }

    public MetricSeriesResponse query(UUID clusterId, MetricQuery query) {
        List<String> metrics = query.metrics();
        Instant to = query.to();
        String splitBy = query.splitBy();
        // Before input validation, so a caller with no grant cannot use the
        // difference between a 400 and a 404 to probe which clusters exist.
        requireReadable(clusterId, query);
        requireValid(metrics, query.subjectType(), query.subject(), splitBy);
        String subjectName = QUEUE_SUBJECT.equals(query.subjectType()) ? query.subject() : null;

        Window requested = window(query.from(), to, query.requestedStep());
        List<ClusterNode> nodes = splitBy == null ? List.of() : directory.nodes(clusterId);
        Window window = splitBy == null ? requested : splitWindow(requested, to, nodes.size());

        List<MetricSeries> series = metrics.stream()
                .map(metric -> buildSeries(clusterId, metric, subjectName, window.from(), to, window.step()))
                .toList();
        if (splitBy == null) {
            return new MetricSeriesResponse(window.from(), to, window.step().toString(), window.truncated(), series);
        }

        Split split = split(clusterId, metrics, subjectName, window.from(), to, window.step(), nodes);
        return new MetricSeriesResponse(
                window.from(),
                to,
                window.step().toString(),
                window.truncated() || split.clamped(),
                series,
                SPLIT_BY_NODE,
                split.nodes());
    }

    /**
     * A series of one queue needs read access to it. A cluster's totals add up every queue, so they are
     * read only by a caller who may read every queue of the cluster.
     */
    private void requireReadable(UUID clusterId, MetricQuery query) {
        if (QUEUE_SUBJECT.equals(query.subjectType())
                && query.subject() != null
                && !query.subject().isBlank()) {
            clusterAccess.requireResource(clusterId, ResourceRef.queue(query.subject()), Permissions.QUEUE_READ);
        } else {
            clusterAccess.requireOnAll(clusterId, ResourceKind.QUEUE, "#", Permissions.QUEUE_READ);
        }
    }

    private static void requireValid(List<String> metrics, String subjectType, String subject, String splitBy) {
        if (metrics.isEmpty() || metrics.size() > 4) {
            throw new IllegalArgumentException("metric must list between 1 and 4 metric names");
        }
        for (String m : metrics) {
            if (!GAUGE_METRICS.contains(m) && !RATE_METRICS.contains(m)) {
                throw new IllegalArgumentException("unknown metric: " + m);
            }
        }
        boolean queue = QUEUE_SUBJECT.equals(subjectType);
        boolean subjectMissing = subject == null || subject.isBlank();
        if (queue && subjectMissing) {
            throw new IllegalArgumentException("subject is required when subjectType=QUEUE");
        }
        if (splitBy != null && !SPLIT_BY_NODE.equals(splitBy)) {
            throw new IllegalArgumentException("splitBy must be NODE");
        }
        // The per-node read is per queue (ADR-0110); a cluster-wide split per node is not built.
        if (splitBy != null && !queue) {
            throw new IllegalArgumentException("a split by node needs one queue: subjectType=QUEUE and a subject");
        }
    }

    /** The step widens until every node's series together stays within the point bound. */
    private static Window splitWindow(Window window, Instant to, int nodeCount) {
        int drawn = Math.clamp(nodeCount, 1, MAX_SPLIT_NODES);
        long splitStep = Duration.between(window.from(), to)
                .multipliedBy(drawn)
                .dividedBy(MAX_SPLIT_POINTS)
                .plusSeconds(1)
                .getSeconds();
        if (window.step().toSeconds() < splitStep) {
            return new Window(window.from(), Duration.ofSeconds(splitStep), true);
        }
        return window;
    }

    /**
     * A plugin metric's series for one subject (ADR-0113), as gauge buckets. Needs cluster read and
     * the permission the metric declares; a metric no running plugin declares is unknown.
     */
    public MetricSeriesResponse pluginQuery(
            UUID clusterId, String metric, String subject, Instant from, Instant to, Duration requestedStep) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("metric is required");
        }
        PluginMetrics.Declared declared = pluginMetrics
                .declared(metric)
                .orElseThrow(() -> new IllegalArgumentException("unknown metric: " + metric));
        // Refused like any cluster-scoped read the caller may not make (ClusterAccessGuard).
        clusterAccess.requireCluster(clusterId, declared.metric().permission());
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        Window window = window(from, to, requestedStep);
        List<MetricPoint> points =
                repository.pluginSeries(clusterId, metric, subject, window.from(), to, window.step()).stream()
                        .map(b -> new MetricPoint(b.ts(), b.value(), b.peak()))
                        .toList();
        return new MetricSeriesResponse(
                window.from(),
                to,
                window.step().toString(),
                window.truncated(),
                List.of(new MetricSeries(metric, GAUGE, declared.metric().unit(), points)));
    }

    private record Window(Instant from, Duration step, boolean truncated) {}

    /** The window clamped to retention, and the step clamped to the fastest tier and the point cap. */
    private Window window(Instant from, Instant to, Duration requestedStep) {
        boolean truncated = false;
        Instant retentionFloor =
                Instant.now().minus(lifecycle.retention(MetricSampleStore.ID).orElseThrow());
        Instant effectiveFrom = from;
        if (effectiveFrom.isBefore(retentionFloor)) {
            effectiveFrom = retentionFloor;
            truncated = true;
        }
        Duration range = Duration.between(effectiveFrom, to);
        Duration step = requestedStep != null ? requestedStep : Duration.ofMinutes(1);
        if (step.compareTo(MIN_STEP) < 0) {
            step = MIN_STEP;
            truncated = requestedStep != null || truncated;
        }
        long maxPointStep = range.dividedBy(MAX_POINTS).plusSeconds(1).getSeconds();
        if (step.toSeconds() < maxPointStep) {
            step = Duration.ofSeconds(maxPointStep);
            truncated = true;
        }
        return new Window(effectiveFrom, step, truncated);
    }

    private record Split(List<MetricNodeSeries> nodes, boolean clamped) {}

    /**
     * Each node's series of one queue: every node that sampled it in the window, and every serving
     * node that did not — listed as not sampled rather than left out, since an absent node reads as
     * a node with nothing on it.
     */
    private Split split(
            UUID clusterId,
            List<String> metrics,
            String subjectName,
            Instant from,
            Instant to,
            Duration step,
            List<ClusterNode> nodes) {
        Map<UUID, String> names = new HashMap<>();
        Set<UUID> ids = new LinkedHashSet<>();
        for (ClusterNode n : nodes) {
            names.put(n.getId(), n.getName());
            if (Boolean.TRUE.equals(n.getActive())) {
                ids.add(n.getId());
            }
        }
        Map<String, Map<UUID, List<MetricPoint>>> perMetric = new HashMap<>();
        for (String metric : metrics) {
            Map<UUID, List<MetricPoint>> byNode = pointsByNode(clusterId, metric, subjectName, from, to, step);
            ids.addAll(byNode.keySet());
            perMetric.put(metric, byNode);
        }

        List<MetricNodeSeries> out = new ArrayList<>();
        for (UUID id : ids) {
            boolean sampled = perMetric.values().stream().anyMatch(m -> m.containsKey(id));
            List<MetricSeries> series = metrics.stream()
                    .map(metric -> nodeSeries(metric, perMetric.get(metric).getOrDefault(id, List.of())))
                    .toList();
            // A node since removed from the cluster still owns its samples; it is named as such.
            String name = names.getOrDefault(id, "removed node " + id.toString().substring(0, 8));
            out.add(new MetricNodeSeries(id.toString(), name, sampled, series));
        }
        out.sort(Comparator.comparing(MetricNodeSeries::nodeName));
        boolean clamped = out.size() > MAX_SPLIT_NODES;
        return new Split(clamped ? List.copyOf(out.subList(0, MAX_SPLIT_NODES)) : out, clamped);
    }

    /** In the order the nodes first appear, so equal names keep a stable order after the sort. */
    private Map<UUID, List<MetricPoint>> pointsByNode(
            UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        List<NodeBucket> buckets = GAUGE_METRICS.contains(metric)
                ? repository.gaugeSeriesByNode(clusterId, metric, subjectName, from, to, step)
                : repository.rateSeriesByNode(clusterId, metric, subjectName, from, to, step);
        Map<UUID, List<MetricPoint>> byNode = new LinkedHashMap<>();
        for (NodeBucket b : buckets) {
            byNode.computeIfAbsent(b.nodeId(), k -> new ArrayList<>())
                    .add(new MetricPoint(b.ts(), b.value(), b.peak()));
        }
        return byNode;
    }

    private static MetricSeries nodeSeries(String metric, List<MetricPoint> points) {
        boolean gauge = GAUGE_METRICS.contains(metric);
        return new MetricSeries(metric, gauge ? GAUGE : "RATE", gauge ? "count" : "msg/s", points);
    }

    private MetricSeries buildSeries(
            UUID clusterId, String metric, String subjectName, Instant from, Instant to, Duration step) {
        boolean isGauge = GAUGE_METRICS.contains(metric);
        List<Bucket> buckets = isGauge
                ? repository.gaugeSeries(clusterId, metric, subjectName, from, to, step)
                : repository.rateSeries(clusterId, metric, subjectName, from, to, step);
        List<MetricPoint> points = buckets.stream()
                .map(b -> new MetricPoint(b.ts(), b.value(), b.peak()))
                .toList();
        return new MetricSeries(metric, isGauge ? GAUGE : "RATE", isGauge ? "count" : "msg/s", points);
    }
}
