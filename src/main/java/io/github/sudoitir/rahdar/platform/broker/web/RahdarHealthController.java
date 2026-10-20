package io.github.sudoitir.rahdar.platform.broker.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.kernel.core.SecretRedactor;
import io.github.sudoitir.rahdar.kernel.jobs.JobStatus;
import io.github.sudoitir.rahdar.kernel.jobs.JobStatuses;
import io.github.sudoitir.rahdar.platform.broker.NodeAddress;
import io.github.sudoitir.rahdar.platform.broker.NodeCallHealth;
import io.github.sudoitir.rahdar.platform.broker.NodeCallHealth.Calls;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.platform.broker.NodeDirectory;
import io.github.sudoitir.rahdar.platform.broker.ReplicaDirectory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/system/health}: Rahdar's own jobs, broker calls, connection pool, event streams
 * and replicas in one read (operational-health spec). Built from what those already recorded; it calls no
 * broker and keeps no state. A figure that cannot be read is {@code null}, never zero. The jobs, node
 * figures, pool and stream clients are this replica's own view, and the replicas list says which one that is.
 */
@RestController
@RequiredArgsConstructor
public class RahdarHealthController {

    private static final double MANAGEMENT_PERCENTILE = 0.95;

    private final JobStatuses jobs;
    private final NodeDirectory nodes;
    private final ReplicaDirectory replicas;
    private final NodeCallHealth calls;
    private final NodeCallLimiter limiter;
    private final MeterRegistry meters;

    public enum JobState {
        NEVER_RUN,
        FAILING,
        OK
    }

    public record JobHealth(
            @Schema(requiredMode = REQUIRED) String name,

            @Schema(requiredMode = REQUIRED, description = "The module that owns the job.")
            String feature,

            @Schema(requiredMode = REQUIRED) JobState status,
            @Schema(nullable = true) Instant lastEnd,

            @Schema(
                    nullable = true,
                    description =
                            "Seconds past the job's interval since it last completed; null until its interval is known.")
            Double lagSeconds,

            @Schema(requiredMode = REQUIRED, description = "No run has finished within three of the job's intervals.")
            boolean degraded) {

        static JobHealth of(JobStatus s, Instant now) {
            Duration lag = s.lag(now);
            JobState state = JobState.OK;
            if (s.lastError() != null) {
                state = JobState.FAILING;
            } else if (s.lastEnd() == null) {
                state = JobState.NEVER_RUN;
            }
            return new JobHealth(
                    s.id(),
                    s.featureId(),
                    state,
                    s.lastEnd(),
                    lag == null ? null : lag.toMillis() / 1000.0,
                    s.degraded(now));
        }
    }

    public record NodeHealth(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) UUID clusterId,

            @Schema(
                    nullable = true,
                    description = "The node's host:port; null while Rahdar has no management address for it.")
            String node,

            @Schema(nullable = true) Instant lastSuccess,
            @Schema(nullable = true) Instant lastFailure,

            @Schema(nullable = true, description = "The most recent failure's message, redacted.")
            String lastError,

            @Schema(
                    nullable = true,
                    description = "95th percentile of recent management call latency; null when none was measured.")
            Double managementP95Millis,

            @Schema(nullable = true, description = "How long the latest request waited for this node's rate ceiling.")
            Long rateLimitWaitMillis,

            @Schema(requiredMode = REQUIRED, description = "The latest management call to the node failed.")
            boolean degraded) {}

    public record PoolHealth(
            @Schema(nullable = true) Integer active,
            @Schema(nullable = true) Integer idle,
            @Schema(nullable = true) Integer max,
            @Schema(nullable = true) Integer pending) {}

    /** GONE is derived: the replica never recorded a stop and has not sent a heartbeat within the ttl. */
    public enum ReplicaState {
        STARTING,
        READY,
        DRAINING,
        STOPPED,
        GONE
    }

    public record OwnedCluster(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name) {}

    public record ReplicaHealth(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String host,
            @Schema(requiredMode = REQUIRED) String version,
            @Schema(requiredMode = REQUIRED) ReplicaState state,
            @Schema(requiredMode = REQUIRED) Instant startedAt,

            @Schema(requiredMode = REQUIRED, description = "How long ago it last checked in, by database time.")
            long heartbeatAgeMillis,

            @Schema(requiredMode = REQUIRED, description = "The clusters it runs broker duties for.")
            List<OwnedCluster> ownedClusters,

            @Schema(requiredMode = REQUIRED, description = "The replica answering this request.")
            boolean self,

            @Schema(requiredMode = REQUIRED, description = "The replica is gone or draining.")
            boolean degraded) {

        static ReplicaHealth of(ReplicaDirectory.KnownReplica r, UUID self) {
            ReplicaState state = r.gone()
                    ? ReplicaState.GONE
                    : ReplicaState.valueOf(r.state().toUpperCase(Locale.ROOT));
            return new ReplicaHealth(
                    r.id(),
                    r.host(),
                    r.version(),
                    state,
                    r.startedAt(),
                    r.heartbeatAgeMillis(),
                    r.clusters().stream()
                            .map(c -> new OwnedCluster(c.id(), c.name()))
                            .toList(),
                    r.id().equals(self),
                    state == ReplicaState.GONE || state == ReplicaState.DRAINING);
        }
    }

    public record RahdarHealth(
            @Schema(requiredMode = REQUIRED) List<JobHealth> jobs,
            @Schema(requiredMode = REQUIRED) List<NodeHealth> nodes,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Every replica seen in the last ten minutes, including stopped and gone ones.")
            List<ReplicaHealth> replicas,

            @Schema(requiredMode = REQUIRED, description = "The replica that answered; node figures are its view.")
            UUID answeringReplica,

            @Schema(requiredMode = REQUIRED) PoolHealth dbPool,

            @Schema(nullable = true, description = "Event stream clients connected to this instance.")
            Integer streamClients,

            @Schema(requiredMode = REQUIRED, description = "Any job, node or replica is degraded.")
            boolean degraded) {}

    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.kernel.security.SettingsPermissions).SETTINGS_READ)")
    @GetMapping("/system/health")
    public RahdarHealth rahdarHealth() {
        Instant now = Instant.now();
        List<JobHealth> jobViews =
                jobs.all().stream().map(s -> JobHealth.of(s, now)).toList();
        List<NodeHealth> nodeViews =
                nodes.nodes().stream().map(this::nodeHealth).toList();
        PoolHealth pool = new PoolHealth(
                gauge("hikaricp.connections.active"),
                gauge("hikaricp.connections.idle"),
                gauge("hikaricp.connections.max"),
                gauge("hikaricp.connections.pending"));
        UUID self = replicas.self();
        List<ReplicaHealth> replicaViews =
                replicas.replicas().stream().map(r -> ReplicaHealth.of(r, self)).toList();
        boolean degraded = jobViews.stream().anyMatch(JobHealth::degraded)
                || nodeViews.stream().anyMatch(NodeHealth::degraded)
                || replicaViews.stream().anyMatch(ReplicaHealth::degraded);
        return new RahdarHealth(
                jobViews, nodeViews, replicaViews, self, pool, gauge("rahdar.stream.clients"), degraded);
    }

    private NodeHealth nodeHealth(NodeDirectory.KnownNode node) {
        String url = node.managementUrl();
        Optional<Calls> latest = url == null ? Optional.empty() : calls.calls(url);
        String address = url == null ? null : NodeAddress.hostPort(url);
        return new NodeHealth(
                node.name(),
                node.clusterId(),
                address,
                latest.map(Calls::lastSuccess).orElse(null),
                latest.map(Calls::lastFailure).orElse(null),
                latest.map(Calls::lastError).map(SecretRedactor::redact).orElse(null),
                address == null ? null : managementP95Millis(address),
                latest.isPresent() ? limiter.lastWait(url).toMillis() : null,
                latest.map(Calls::failing).orElse(false));
    }

    /** The slowest recent 95th percentile among the node's management timers, or null when none has samples. */
    private Double managementP95Millis(String address) {
        return meters.find("rahdar.broker.management").tag("node", address).timers().stream()
                .filter(t -> t.count() > 0)
                .flatMap(this::p95)
                .max(Double::compare)
                .orElse(null);
    }

    private Stream<Double> p95(Timer timer) {
        return Arrays.stream(timer.takeSnapshot().percentileValues())
                .filter(p -> p.percentile() == MANAGEMENT_PERCENTILE)
                .map(p -> p.value(TimeUnit.MILLISECONDS))
                .filter(v -> !v.isNaN());
    }

    private Integer gauge(String name) {
        Gauge g = meters.find(name).gauge();
        return g == null || Double.isNaN(g.value()) ? null : (int) g.value();
    }
}
