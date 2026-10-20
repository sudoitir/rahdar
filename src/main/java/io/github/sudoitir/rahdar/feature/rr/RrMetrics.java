package io.github.sudoitir.rahdar.feature.rr;

import io.github.sudoitir.rahdar.feature.rr.internal.persistence.RrFlowRepository;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.AddressStatsView;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.StatsResponse;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceKind;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Latency percentiles (Micrometer, ADR-0032) and their sampling coverage
 * ratio — never published without each other (request-reply-tracing spec,
 * non-negotiable #5). Coverage is a live best-effort estimate against the
 * current {@code subscription_snapshot} counter, not a persisted metric (Phase 6's
 * {@code metric_sample} owns real history — see design.md Non-Goals).
 */
@Service
public class RrMetrics {

    private static final String METRIC = "rahdar.rr.latency";

    private final MeterRegistry registry;
    private final RrFlowRepository flows;
    private final QueueSnapshots queueSnapshots;
    private final Duration percentileWindow;
    private final ClusterAccessGuard clusterAccess;
    private final PermissionResolver permissions;

    public RrMetrics(
            MeterRegistry registry,
            RrFlowRepository flows,
            QueueSnapshots queueSnapshots,
            RrProperties properties,
            ClusterAccessGuard clusterAccess,
            PermissionResolver permissions) {
        this.registry = registry;
        this.flows = flows;
        this.queueSnapshots = queueSnapshots;
        this.percentileWindow = properties.percentileWindow();
        this.clusterAccess = clusterAccess;
        this.permissions = permissions;
    }

    // ponytail: a single in-process baseline reading per address, not a persisted
    // time series. Good enough for a live estimate; upgrade to real history if a
    // deployment needs coverage numbers that survive a restart.
    private record Baseline(Instant at, long messagesAdded) {}

    private final Map<String, Baseline> coverageBaseline = new ConcurrentHashMap<>();

    public void recordCompletion(UUID clusterId, String address, long latencyMs) {
        timer(clusterId, address).record(latencyMs, TimeUnit.MILLISECONDS);
    }

    private Timer timer(UUID clusterId, String address) {
        return Timer.builder(METRIC)
                .tag("cluster", clusterId.toString())
                .tag("address", address)
                .publishPercentiles(0.5, 0.95, 0.99)
                .distributionStatisticExpiry(percentileWindow)
                .register(registry);
    }

    @Transactional(readOnly = true)
    public StatsResponse stats(UUID clusterId, Duration window) {
        clusterAccess.requireVisible(clusterId);
        ResourceFilter readable = permissions.filter(clusterId, ResourceKind.ADDRESS);
        List<String> addresses = flows.findDistinctRequestAddressByClusterId(clusterId).stream()
                .filter(readable::readable)
                .toList();
        Instant since = Instant.now().minus(window);
        List<AddressStatsView> views = addresses.stream()
                .map(a -> addressStats(clusterId, a, since, window))
                .toList();
        return new StatsResponse(views);
    }

    private AddressStatsView addressStats(UUID clusterId, String address, Instant since, Duration window) {
        long inFlight =
                flows.countByClusterIdAndRequestAddressAndState(clusterId, address, RrState.AWAITING_REPLY.name());
        Long oldestMs = flows.findFirstByClusterIdAndRequestAddressAndStateOrderByRequestedAtAsc(
                        clusterId, address, RrState.AWAITING_REPLY.name())
                .map(f -> Duration.between(f.getRequestedAt(), Instant.now()).toMillis())
                .orElse(null);

        long completed = flows.countByClusterIdAndRequestAddressAndStateAndRequestedAtAfter(
                clusterId, address, RrState.COMPLETED.name(), since);
        long timedOut = flows.countByClusterIdAndRequestAddressAndStateAndRequestedAtAfter(
                clusterId, address, RrState.TIMED_OUT.name(), since);
        long orphaned = flows.countByClusterIdAndRequestAddressAndStateAndRequestedAtAfter(
                clusterId, address, RrState.ORPHANED.name(), since);
        long responderDropped = flows.countByClusterIdAndRequestAddressAndStateAndRequestedAtAfter(
                clusterId, address, RrState.RESPONDER_DROPPED.name(), since);

        Timer t = registry.find(METRIC)
                .tag("cluster", clusterId.toString())
                .tag("address", address)
                .timer();
        Double p50 = null;
        Double p95 = null;
        Double p99 = null;
        if (t != null && t.count() > 0) {
            for (ValueAtPercentile v : t.takeSnapshot().percentileValues()) {
                if (v.percentile() == 0.5) p50 = v.value(TimeUnit.MILLISECONDS);
                if (v.percentile() == 0.95) p95 = v.value(TimeUnit.MILLISECONDS);
                if (v.percentile() == 0.99) p99 = v.value(TimeUnit.MILLISECONDS);
            }
        }

        Double coverage = estimateCoverage(clusterId, address, completed + timedOut + orphaned + responderDropped);

        return new AddressStatsView(
                address,
                inFlight,
                oldestMs,
                completed,
                timedOut,
                orphaned,
                responderDropped,
                p50,
                p95,
                p99,
                true,
                coverage,
                window.toMillis());
    }

    private Double estimateCoverage(UUID clusterId, String address, long observedInWindow) {
        long currentAdded = queueSnapshots.forCluster(clusterId).stream()
                .filter(s -> address.equals(s.address()))
                .mapToLong(QueueSnapshot::messagesAdded)
                .sum();
        Instant now = Instant.now();
        Baseline previous = coverageBaseline.put(clusterId + "|" + address, new Baseline(now, currentAdded));
        if (previous == null) {
            return null; // not enough history yet
        }
        long delta = currentAdded - previous.messagesAdded();
        if (delta <= 0) {
            return null;
        }
        return Math.min(1.0, observedInWindow / (double) delta);
    }
}
