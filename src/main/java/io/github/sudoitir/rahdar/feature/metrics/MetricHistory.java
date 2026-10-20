package io.github.sudoitir.rahdar.feature.metrics;

import io.github.sudoitir.rahdar.feature.metrics.web.MetricViews.MetricSeriesResponse;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import io.github.sudoitir.rahdar.kernel.security.OperatorHandoff;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Metric history for a plugin, read as a named user (ADR-0154). A plugin has no request to take a
 * user from, and plugin work in the background has none either, so each read names the acting user
 * and runs as their account as it stands now: the cluster check, a plugin metric's declared
 * permission, the retention clamp, the point cap and {@code truncated} are exactly those of the REST
 * API. A user who is unknown, disabled or without access to the cluster gets the answer for a
 * cluster that does not exist, so a plugin cannot use this to learn which clusters exist.
 */
@Component
@PluginApi
@RequiredArgsConstructor
public class MetricHistory {

    private final MetricQueryService queries;
    private final OperatorHandoff handoff;

    /** Queue and cluster metrics ({@code openbroker.backlog}, {@code openbroker.enqueued}, ...), as the REST API returns them. */
    public MetricSeriesResponse read(UUID actingUserId, UUID clusterId, MetricQuery query) {
        return asUser(actingUserId, clusterId, () -> queries.query(clusterId, query));
    }

    /** One subject's series of a metric some running plugin declared, needing the permission it declared. */
    public MetricSeriesResponse readPluginMetric(
            UUID actingUserId, UUID clusterId, String metric, String subject, Instant from, Instant to, Duration step) {
        return asUser(actingUserId, clusterId, () -> queries.pluginQuery(clusterId, metric, subject, from, to, step));
    }

    private MetricSeriesResponse asUser(UUID actingUserId, UUID clusterId, Supplier<MetricSeriesResponse> read) {
        var operator = handoff.forUser(actingUserId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
        return handoff.callAs(operator, read);
    }
}
