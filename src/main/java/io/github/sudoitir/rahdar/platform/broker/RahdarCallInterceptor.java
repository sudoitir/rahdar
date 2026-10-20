package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.CallInfo;
import io.github.sudoitir.openbroker.CallInterceptor;
import io.github.sudoitir.openbroker.Dispatch;
import io.github.sudoitir.openbroker.ErrorInfo;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Outcome;
import io.github.sudoitir.openbroker.Permit;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.Status;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Rahdar's control of every call a broker driver makes (ADR-0203): the per-node rate ceiling
 * (ADR-0076), the guard against calls inside a database transaction, broker call health, the clock
 * offset read from the broker's own answers (ADR-0053), and the {@code rahdar.broker.management} and
 * {@code rahdar.broker.core} observations, with the names and tags they have always had.
 *
 * <p>Management calls and the Core calls that read or send messages ({@code sample}, {@code browse},
 * {@code send}) take permits from the node's ceiling, keyed by its management URL so both channels
 * share it; the relay's own calls never wait for one.
 */
@Component
public class RahdarCallInterceptor implements CallInterceptor {

    static final String MANAGEMENT = "rahdar.broker.management";
    static final String CORE = "rahdar.broker.core";

    /** The Core calls that take a permit from the node's ceiling. */
    private static final Set<String> LIMITED_CORE = Set.of("browse", "send");

    private static final Duration REFRESH = Duration.ofSeconds(30);

    private final NodeCallLimiter limiter;
    private final BrokerCallGuard guard;
    private final NodeCallHealth health;
    private final ClockOffsetRegistry clockOffsets;
    private final ObservationRegistry observations;
    private final ObjectProvider<NodeDirectory> directory;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(Map.of(), Long.MIN_VALUE));

    public RahdarCallInterceptor(
            NodeCallLimiter limiter,
            BrokerCallGuard guard,
            NodeCallHealth health,
            ClockOffsetRegistry clockOffsets,
            ObservationRegistry observations,
            ObjectProvider<NodeDirectory> directory) {
        this.limiter = limiter;
        this.guard = guard;
        this.health = health;
        this.clockOffsets = clockOffsets;
        this.observations = observations;
        this.directory = directory;
    }

    @Override
    public Permit admit(CallInfo call) {
        boolean management = call.plane() == Plane.MANAGEMENT;
        String managementUrl = management ? call.endpoint() : managementUrl(call);
        if (management || LIMITED_CORE.contains(call.operation())) {
            acquire(call, managementUrl, management ? call.operation() : "core");
        }
        checkGuard(call);
        Observation observation = observation(call, management, managementUrl);
        observation.start();
        long t0 = clockOffsets.now();
        return new Permit() {
            private boolean done;

            @Override
            public void complete(Outcome outcome, Optional<Instant> serverTime) {
                done = true;
                if (management) {
                    managementDone(call, outcome, serverTime, t0, observation);
                }
                outcome.failure().ifPresent(observation::error);
                observation.stop();
            }

            @Override
            public void close() {
                if (!done) {
                    observation.stop();
                }
            }
        };
    }

    private void checkGuard(CallInfo call) {
        try {
            guard.check();
        } catch (IllegalStateException e) {
            throw new BrokerException(
                    Status.FAILED_PRECONDITION,
                    ErrorInfo.of(ErrorReasons.CALL_REFUSED, Map.of(BrokerFailures.GUARD, "true")),
                    Dispatch.NOT_SENT,
                    call.node(),
                    e.getMessage());
        }
    }

    private Observation observation(CallInfo call, boolean management, String managementUrl) {
        return management
                ? Observation.createNotStarted(MANAGEMENT, observations)
                        .contextualName("jolokia")
                        .lowCardinalityKeyValue("node", NodeAddress.hostPort(call.endpoint()))
                : Observation.createNotStarted(CORE, observations)
                        .contextualName("core " + call.operation())
                        .lowCardinalityKeyValue("operation", call.operation())
                        .lowCardinalityKeyValue("node", NodeAddress.hostPort(managementUrl))
                        .highCardinalityKeyValue("core.address", NodeAddress.hostPort(call.endpoint()));
    }

    /** A management call's node health, clock reading and outcome tag. */
    private void managementDone(
            CallInfo call, Outcome outcome, Optional<Instant> serverTime, long t0, Observation observation) {
        long t1 = clockOffsets.now();
        if (outcome.success()) {
            serverTime.ifPresent(t -> clockOffsets.recordReading(call.endpoint(), t.getEpochSecond(), t0, t1));
            health.succeeded(call.endpoint());
        } else {
            health.failed(
                    call.endpoint(),
                    outcome.failure().map(Throwable::getMessage).orElse(""));
        }
        observation.lowCardinalityKeyValue(KeyValue.of("outcome", outcome.success() ? "SUCCESS" : "ERROR"));
    }

    private void acquire(CallInfo call, String key, String kind) {
        try {
            limiter.acquire(key, call.cost(), kind);
        } catch (BrokerConnectionException e) {
            String reason = e.kind() == BrokerConnectionException.Kind.THROTTLED
                    ? ErrorReasons.RATE_LIMITED
                    : ErrorReasons.UNREACHABLE;
            throw new BrokerException(
                    e.kind() == BrokerConnectionException.Kind.THROTTLED
                            ? Status.RESOURCE_EXHAUSTED
                            : Status.UNAVAILABLE,
                    ErrorInfo.of(reason),
                    Dispatch.NOT_SENT,
                    call.node(),
                    e.getMessage(),
                    e);
        }
    }

    /** The management URL of the node a data-plane call goes to, so both channels share its ceiling. */
    private String managementUrl(CallInfo call) {
        long now = System.nanoTime();
        Snapshot current = snapshot.get();
        if (current.readAt() == Long.MIN_VALUE || now - current.readAt() > REFRESH.toNanos()) {
            Map<String, String> byNode = new HashMap<>();
            try {
                NodeDirectory nodes = directory.getIfAvailable();
                if (nodes != null) {
                    nodes.nodes().stream()
                            .filter(n -> n.managementUrl() != null)
                            .forEach(n -> byNode.put(n.id().toString(), n.managementUrl()));
                }
                current = new Snapshot(byNode, now);
            } catch (RuntimeException _) {
                // Telemetry and limits never fail a call for the directory: keep the last map.
                current = new Snapshot(current.byNode(), now);
            }
            snapshot.set(current);
        }
        return current.byNode().getOrDefault(call.node().value(), call.endpoint());
    }

    private record Snapshot(Map<String, String> byNode, long readAt) {}
}
