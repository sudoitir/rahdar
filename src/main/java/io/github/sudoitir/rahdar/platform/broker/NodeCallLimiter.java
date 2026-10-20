package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * A per-node rate ceiling on management requests (CLAUDE.md non-negotiable #1 —
 * "Rahdar must never be the reason a broker falls over").
 *
 * <p>One {@link Semaphore} per broker node, sized to the configured requests-per-second and
 * keyed by the node's Jolokia URL; its meters are tagged with the node's {@code host:port} only ({@link NodeAddress}). {@link #acquire} is called by the transports themselves
 * before <em>every</em> request they send (ADR-0076) — never by a feature — so a new caller
 * cannot forget it and a caller that grows a second request is charged for it.
 * {@link #refill()} tops every bucket back up to the ceiling once a second. This is a coarse
 * token bucket: it bounds sustained rate and absorbs a one-second burst.
 *
 * <p>The configured rate is the node's ceiling for all replicas together (ADR-0196): each replica
 * takes its share, the rate divided by the number of ready replicas, rounded up and at least one.
 * A database round trip per call would cost more than it saves, so the count is read at most every
 * {@link #REPLICAS_REFRESH} and the buckets follow it at the next refill.
 */
// ponytail: per-node Semaphore + 1s refill. Swap for Bucket4j only if precise
// sub-second burst shaping is ever needed — same call sites.
@Component
public class NodeCallLimiter {

    /** How long one permit may be waited for before the request is refused rather than queued without end. */
    private static final Duration MAX_WAIT = Duration.ofSeconds(5);

    private static final Duration REPLICAS_REFRESH = Duration.ofSeconds(5);

    private final Map<String, Semaphore> perNode = new ConcurrentHashMap<>();
    private final Map<String, Duration> lastWait = new ConcurrentHashMap<>();
    private final MeterRegistry meters;
    private final IntSupplier readyReplicas;
    private volatile int permitsPerSecond;
    private volatile int replicas = 1;
    private volatile long replicasReadAtNanos;
    private volatile boolean closed;

    @Autowired
    public NodeCallLimiter(RateLimitProperties properties, MeterRegistry meters, ReplicaRegistry registry) {
        this(properties, meters, registry::readyCount);
    }

    NodeCallLimiter(RateLimitProperties properties, MeterRegistry meters, IntSupplier readyReplicas) {
        this.permitsPerSecond = Math.max(1, properties.managementCallsPerSecond());
        this.meters = meters;
        this.readyReplicas = readyReplicas;
        // Already due, so the first refill reads the count and a new replica does not start at the full rate.
        this.replicasReadAtNanos = System.nanoTime() - REPLICAS_REFRESH.toNanos();
    }

    /** A limiter for one replica, which keeps the whole configured rate: a test's, or a tool's. */
    public NodeCallLimiter(RateLimitProperties properties, MeterRegistry meters) {
        this(properties, meters, () -> 1);
    }

    /** Runtime override hook — {@code SettingsService} calls this when the ceiling changes. */
    public void setPermitsPerSecond(int permitsPerSecond) {
        this.permitsPerSecond = Math.max(1, permitsPerSecond);
    }

    /** The configured rate for the node, all replicas together. */
    public int permitsPerSecond() {
        return permitsPerSecond;
    }

    /** This replica's share of {@link #permitsPerSecond()}. */
    int share() {
        return Math.max(1, Math.ceilDiv(permitsPerSecond, replicas));
    }

    /**
     * Wait for {@code permits} of this node's ceiling and take them, for one request. A permit
     * is returned by the next {@link #refill()}, not by the caller. Permits are taken one at a
     * time, so a request needing more than the ceiling spreads over seconds instead of waiting
     * forever for a burst that can never be available.
     *
     * @throws BrokerConnectionException when Rahdar is shutting down, when capacity does not
     *     come within the wait, or when the thread is interrupted (its interrupt status kept)
     */
    public void acquire(String node, int permits) {
        acquire(node, permits, "other");
    }

    /** As {@link #acquire(String, int)}, counting the call in {@code rahdar.broker.calls} under {@code kind}. */
    public void acquire(String node, int permits, String kind) {
        if (closed) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "Rahdar is shutting down; no new broker call is started.");
        }
        Semaphore sem = perNode.computeIfAbsent(node, k -> new Semaphore(share(), true));
        String tag = NodeAddress.hostPort(node);
        long started = System.nanoTime();
        try {
            for (int i = 0; i < permits; i++) {
                if (!sem.tryAcquire(MAX_WAIT.toNanos(), TimeUnit.NANOSECONDS)) {
                    meters.counter("rahdar.broker.permit.timeouts", "node", tag).increment();
                    throw new BrokerConnectionException(
                            BrokerConnectionException.Kind.THROTTLED,
                            "Rahdar held this call back: it is already calling this node as fast as the configured"
                                    + " management-call rate allows. The node itself was not asked.");
                }
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    "Interrupted while waiting for this node's management-call ceiling.");
        } finally {
            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            lastWait.put(node, waited);
            meters.timer("rahdar.broker.permit.wait", "node", tag).record(waited);
        }
        meters.counter("rahdar.broker.requests", "node", tag).increment();
        meters.counter("rahdar.broker.calls", "kind", kind).increment();
    }

    /** Top every node's bucket back up to the current ceiling. Driven every second by {@code BrokerJobs}. */
    public void refill() {
        refreshReplicas();
        int ceiling = share();
        perNode.forEach((node, sem) -> {
            int deficit = ceiling - sem.availablePermits();
            if (deficit > 0) {
                sem.release(deficit);
            } else if (deficit < 0) {
                // Ceiling was lowered at runtime; drain the surplus without blocking.
                sem.tryAcquire(-deficit);
            }
        });
    }

    private void refreshReplicas() {
        long now = System.nanoTime();
        if (now - replicasReadAtNanos < REPLICAS_REFRESH.toNanos()) {
            return;
        }
        replicasReadAtNanos = now;
        try {
            replicas = Math.max(1, readyReplicas.getAsInt());
        } catch (DataAccessException _) {
            // The count stands until the database answers; a node is never called faster for not knowing.
        }
    }

    /** Refuse every later call: Rahdar is shutting down (operational-health spec). */
    public void close() {
        closed = true;
    }

    /** Accept calls again after a stopped context has been started. */
    public void open() {
        closed = false;
    }

    /** How long the latest request to this node waited for a permit; zero before any. */
    public Duration lastWait(String node) {
        return lastWait.getOrDefault(node, Duration.ZERO);
    }
}
