package io.github.sudoitir.rahdar.feature.metrics.web;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.metrics.MetricQuery;
import io.github.sudoitir.rahdar.feature.metrics.MetricQueryService;
import io.github.sudoitir.rahdar.feature.metrics.web.MetricViews.MetricSeriesResponse;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Historical metric reads (metrics spec, ADR-0033), optionally split by broker node (ADR-0110). */
@RestController
@RequestMapping("/clusters/{clusterId}/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricQueryService metricQuery;

    @GetMapping
    public MetricSeriesResponse metrics(
            @PathVariable UUID clusterId,
            @RequestParam List<String> metric,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String step,
            @RequestParam(required = false) String splitBy) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(Duration.ofHours(1));
        Duration requestedStep = step != null ? Duration.parse(step) : null;
        return metricQuery.query(
                clusterId,
                new MetricQuery(metric, subscriptionOf(resource), effectiveFrom, effectiveTo, requestedStep, splitBy));
    }

    /** The subscription a resource id names; null for none, which asks for the cluster's totals. */
    private static ResourceRef subscriptionOf(String resource) {
        return resource == null || resource.isBlank()
                ? null
                : ResourceRef.subscription(ResourceId.parse(resource).name());
    }

    /** A plugin metric's series for one subject (ADR-0113). */
    @GetMapping("/plugin")
    public MetricSeriesResponse plugin(
            @PathVariable UUID clusterId,
            // Optional here and checked by the service after cluster access, so a caller without a grant
            // on the cluster cannot tell it exists from a 400 (ClusterScopeAuthorizationTest).
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String step) {
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(Duration.ofHours(1));
        return metricQuery.pluginQuery(
                clusterId, metric, subject, effectiveFrom, effectiveTo, step != null ? Duration.parse(step) : null);
    }
}
