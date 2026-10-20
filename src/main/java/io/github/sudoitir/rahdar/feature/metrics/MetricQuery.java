package io.github.sudoitir.rahdar.feature.metrics;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One metric-series read.
 *
 * @param metrics the metric names, between 1 and 4
 * @param resource the subscription to read, or null for the cluster's totals
 * @param from start of the range
 * @param to end of the range
 * @param requestedStep the bucket width asked for, or null for the server's choice
 * @param splitBy {@code NODE} for a per-node breakdown, or null for totals only
 */
@PluginApi
public record MetricQuery(
        List<String> metrics, ResourceRef resource, Instant from, Instant to, Duration requestedStep, String splitBy) {}
