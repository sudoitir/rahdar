package io.github.sudoitir.rahdar.platform.broker;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * How far each broker's clock is from Rahdar's, measured for free from the
 * timestamp Jolokia already puts on every response (ADR-0053).
 *
 * <p>Keyed by management URL and fed by {@link RahdarCallInterceptor} from the server time each
 * completed call reports, so every connection to a node teaches one estimate. No call site changes
 * and no extra broker request is made — non-negotiable #1 is untouched.
 *
 * <p>The estimator is the standard NTP one, cut down to what one field per
 * response supports. A reading is
 *
 * <pre>{@code offset = brokerMillis - (t0 + rtt / 2)}</pre>
 *
 * where {@code t0} is Rahdar's clock before the request and {@code rtt} the round
 * trip. The lowest-RTT reading is the least distorted by queueing in either
 * direction, so a reading is only allowed to teach the estimate when its round
 * trip is at or near the best seen; the rest are dropped. What survives is
 * smoothed by an EWMA so one outlier cannot move the verdict.
 *
 * <p>The best round trip decays slightly on every reading. Without that, a single
 * unusually fast response early on would lock the filter shut and no later reading
 * would ever be accepted.
 */
@Component
public class ClockOffsetRegistry {

    /**
     * Jolokia reports its timestamp in whole seconds, so any single reading is
     * already ±500ms before the network is considered. Carried in every
     * uncertainty, and the reason skew under a couple of seconds is never reported
     * as meaningful.
     */
    public static final long QUANTISATION_MS = 500;

    private static final double EWMA_ALPHA = 0.25;
    private static final double RTT_DECAY = 1.05;
    /** A reading this much slower than the best seen carries too much queueing to learn from. */
    private static final double RTT_REJECT_FACTOR = 2.0;

    private final Clock clock;
    private final Map<String, NodeOffset> byUrl = new ConcurrentHashMap<>();

    public ClockOffsetRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * One measurement of a broker's clock against Rahdar's.
     *
     * @param offsetMs how far ahead of Rahdar the broker is; negative means behind
     * @param uncertaintyMs the half round trip plus Jolokia's second-granularity, so
     *     an offset within this band is indistinguishable from zero
     * @param rttMs the best round trip seen, which is what the estimate is based on
     * @param samples how many readings have been accepted, for judging confidence
     */
    public record ClockOffset(long offsetMs, long uncertaintyMs, long rttMs, int samples, Instant measuredAt) {

        /** Whether the measurement is large enough to mean anything at all. */
        public boolean isMeaningful() {
            return Math.abs(offsetMs) > uncertaintyMs;
        }
    }

    /** Rahdar's own clock, so a caller measuring a round trip uses the same one as the estimator. */
    public long now() {
        return clock.millis();
    }

    /**
     * Record one reading.
     *
     * @param brokerEpochSeconds the {@code timestamp} field of a successful Jolokia
     *     response — in seconds, as Jolokia sends it
     * @param t0 Rahdar's clock immediately before the request, from {@link #now()}
     * @param t1 Rahdar's clock immediately after the response
     */
    public void recordReading(String jolokiaUrl, long brokerEpochSeconds, long t0, long t1) {
        if (jolokiaUrl == null || brokerEpochSeconds <= 0 || t1 < t0) {
            return;
        }
        long rtt = t1 - t0;
        long offset = brokerEpochSeconds * 1_000L - (t0 + rtt / 2);
        byUrl.computeIfAbsent(jolokiaUrl, k -> new NodeOffset()).accept(offset, rtt, clock.instant());
    }

    public Optional<ClockOffset> offsetFor(String jolokiaUrl) {
        NodeOffset node = jolokiaUrl == null ? null : byUrl.get(jolokiaUrl);
        return node == null ? Optional.empty() : node.snapshot();
    }

    /** Every node measured so far, for the estate-wide corroboration that implicates Rahdar itself. */
    public Map<String, ClockOffset> all() {
        Map<String, ClockOffset> out = new ConcurrentHashMap<>();
        byUrl.forEach((url, node) -> node.snapshot().ifPresent(o -> out.put(url, o)));
        return Map.copyOf(out);
    }

    /**
     * Discard every estimate.
     *
     * <p>Called when Rahdar's own wall clock is seen to step (an NTP correction, a
     * VM resume, a manual set). Every reading in the filter was taken against the
     * old clock, so keeping them would drag the new estimate towards a value that
     * is no longer true.
     */
    public void invalidate() {
        byUrl.clear();
    }

    private static final class NodeOffset {

        private long bestRttMs = Long.MAX_VALUE;
        private double smoothedOffsetMs;
        private int samples;
        private Instant measuredAt;

        synchronized void accept(long offsetMs, long rttMs, Instant at) {
            if (samples == 0) {
                bestRttMs = rttMs;
                smoothedOffsetMs = (double) offsetMs;
                samples = 1;
                measuredAt = at;
                return;
            }
            // Let a lucky early sample age out, or nothing would ever be accepted again.
            bestRttMs = (long) Math.ceil(bestRttMs * RTT_DECAY);
            if (rttMs <= bestRttMs) {
                bestRttMs = rttMs;
            } else if (rttMs > bestRttMs * RTT_REJECT_FACTOR) {
                return;
            }
            smoothedOffsetMs = EWMA_ALPHA * offsetMs + (1 - EWMA_ALPHA) * smoothedOffsetMs;
            samples += 1;
            measuredAt = at;
        }

        synchronized Optional<ClockOffset> snapshot() {
            if (samples == 0) {
                return Optional.empty();
            }
            long rtt = bestRttMs;
            return Optional.of(
                    new ClockOffset(Math.round(smoothedOffsetMs), rtt / 2 + QUANTISATION_MS, rtt, samples, measuredAt));
        }
    }
}
