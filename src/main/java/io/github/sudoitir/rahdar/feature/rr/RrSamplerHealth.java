package io.github.sudoitir.rahdar.feature.rr;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * What the sampler actually did on its last tick, per traced address.
 *
 * <p>Tracing could fail completely and silently: no serving node meant an empty
 * loop with nothing logged, an address with no matching queue threw into a
 * throttled warning nobody reads, and a `samplePerMin` an operator had set was
 * never consulted at all. An operator staring at an empty Flows tab had no way to
 * tell "nothing was sent" from "Rahdar never looked".
 *
 * <p>Deliberately in memory and deliberately not a table. This is broker-derived,
 * disposable state about the last few seconds (ADR-0002's disposable-cache rule);
 * a restart losing it costs one sample interval.
 */
@Component
public class RrSamplerHealth {

    /** One node's outcome for one expectation on one tick. */
    public record NodeOutcome(String nodeName, boolean sampled, String reason) {}

    /**
     * @param lastSuccessAt the last tick that browsed at least one node without error
     * @param skipped the nodes not sampled, each with the reason — the ranked
     *     diagnosis an operator needs is assembled from these, not guessed
     */
    public record ExpectationHealth(
            UUID expectationId,
            UUID clusterId,
            String requestDestination,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            int nodesSampled,
            List<NodeOutcome> skipped,
            int messagesBrowsed,
            int observations,
            String lastError,
            Instant lastErrorAt) {}

    private final Clock clock;
    private final Map<UUID, ExpectationHealth> byExpectation = new ConcurrentHashMap<>();

    public RrSamplerHealth(Clock clock) {
        this.clock = clock;
    }

    /** Accumulates one expectation's tick, then publishes it in one go. */
    public Tick begin(UUID expectationId, UUID clusterId, String requestDestination) {
        return new Tick(expectationId, clusterId, requestDestination, clock.instant());
    }

    public Optional<ExpectationHealth> forExpectation(UUID expectationId) {
        return Optional.ofNullable(byExpectation.get(expectationId));
    }

    public List<ExpectationHealth> forCluster(UUID clusterId) {
        return byExpectation.values().stream()
                .filter(h -> h.clusterId().equals(clusterId))
                .sorted((a, b) -> a.requestDestination().compareTo(b.requestDestination()))
                .toList();
    }

    /** An expectation that no longer exists should not keep answering for itself. */
    public void forget(UUID expectationId) {
        byExpectation.remove(expectationId);
    }

    /** One expectation's tick in progress. Not thread-safe; one tick owns one instance. */
    public final class Tick {

        private final UUID expectationId;
        private final UUID clusterId;
        private final String requestDestination;
        private final Instant startedAt;
        private final List<NodeOutcome> skipped = new ArrayList<>();

        private int nodesSampled;
        private int messagesBrowsed;
        private int observations;
        private String lastError;

        private Tick(UUID expectationId, UUID clusterId, String requestDestination, Instant startedAt) {
            this.expectationId = expectationId;
            this.clusterId = clusterId;
            this.requestDestination = requestDestination;
            this.startedAt = startedAt;
        }

        public void sampled(int browsed, int emitted) {
            nodesSampled += 1;
            messagesBrowsed += browsed;
            observations += emitted;
        }

        /** A node deliberately not sampled, and why — the reason is the whole point. */
        public void skipped(String nodeName, String reason) {
            skipped.add(new NodeOutcome(nodeName, false, reason));
        }

        public void failed(String nodeName, String error) {
            skipped.add(new NodeOutcome(nodeName, false, error));
            lastError = error;
        }

        public void publish() {
            ExpectationHealth previous = byExpectation.get(expectationId);
            Instant previousSuccessAt = previous == null ? null : previous.lastSuccessAt();
            Instant previousErrorAt = previous == null ? null : previous.lastErrorAt();
            String previousError = previous == null ? null : previous.lastError();
            Instant lastSuccessAt = nodesSampled > 0 ? startedAt : previousSuccessAt;
            Instant lastErrorAt = lastError != null ? startedAt : previousErrorAt;
            String error = lastError != null ? lastError : previousError;
            byExpectation.put(
                    expectationId,
                    new ExpectationHealth(
                            expectationId,
                            clusterId,
                            requestDestination,
                            startedAt,
                            lastSuccessAt,
                            nodesSampled,
                            List.copyOf(skipped),
                            messagesBrowsed,
                            observations,
                            error,
                            lastErrorAt));
        }
    }
}
