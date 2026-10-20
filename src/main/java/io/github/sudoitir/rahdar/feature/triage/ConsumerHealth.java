package io.github.sudoitir.rahdar.feature.triage;

import java.time.Duration;
import java.time.Instant;

/**
 * One queue's consumer-health verdict and the evidence it rests on (ADR-0089).
 *
 * <p>A verdict is never a bare label. Every field below is what the ladder read to reach
 * it, so an operator who disagrees with the verdict can see why it was reached, and a
 * wrong threshold is diagnosable rather than mysterious.
 *
 * <p>Every rate is boxed. {@code null} means "not computable from the samples available",
 * which is a different fact from zero — a queue nobody has sampled twice yet is not a
 * queue with no throughput, and rendering the first as the second is the misreading this
 * whole feature exists to prevent.
 */
public record ConsumerHealth(
        String destination,
        String subscription,
        Verdict verdict,
        /** The likely cause and what to do about it, in words. Never null. */
        String cause,
        /** Whether the broker judged this or Rahdar derived it (ADR-0044). */
        Source source,
        /** The consumer the broker named, when {@link Source#BROKER}; null otherwise. */
        String brokerConsumerName,
        long depth,
        long consumers,
        long delivering,
        long scheduled,
        boolean paused,
        /** Change in depth per second by regression; positive is growing. Null when not measurable. */
        Double depthSlopePerSecond,
        Double addRate,
        Double ackRate,
        /** {@code addRate - ackRate}; positive means the backlog is growing. */
        Double netRate,
        Double ackRatePerConsumer,
        /** Time until the backlog clears at the current net rate. Only ever set on {@link Verdict#DRAINING}. */
        Duration drainEta,
        /** The newest sample behind the rates, so their age can be stated. */
        Instant asOf,
        /** The period the rates were measured over — a slow-tier rate averages over minutes. */
        Duration sampleSpan,
        boolean stale,
        int nodesPresent,
        int nodesTotal) {

    /** Who reached the verdict. The broker outranks Rahdar wherever it has an opinion (ADR-0044). */
    public enum Source {
        BROKER,
        DERIVED
    }

    /**
     * The ladder, in evaluation order. First match wins.
     *
     * <p>The declaration order is the evaluation order, and {@link #severity()} is what
     * ranking and alerting compare — they are deliberately not the same thing.
     * {@code PAUSED} is evaluated early (it explains a backlog before any consumer
     * verdict can claim it) but ranks low (it is expected, not a fault).
     *
     * <p>This order is a compatibility surface: it decides what an alert fires on and
     * what an agent reports. Changing it needs a superseding ADR, not an edit.
     */
    public enum Verdict {
        /** Too few samples to compute a rate. Never means healthy. */
        INSUFFICIENT_DATA(0),
        /** Paused on at least one node — the backlog is by design. */
        PAUSED(1),
        /** Nothing is attached to drain the backlog. */
        NO_CONSUMERS(4),
        /** The broker's own slow-consumer detection fired. */
        BROKER_SLOW(4),
        /** Consumers hold messages and acknowledge none. */
        STALLED(4),
        /** Consumers are attached but nothing is being dispatched to them. */
        STARVED(3),
        /** Acknowledging, but slower than the queue is filling. */
        FALLING_BEHIND(2),
        /** Acknowledging faster than the queue fills — recovering. */
        DRAINING(1),
        /** Nothing to report. */
        HEALTHY(0);

        private final int severity;

        Verdict(int severity) {
            this.severity = severity;
        }

        /**
         * How badly this queue needs attention, 0–4. Used for worst-first ranking and as
         * the value an alert rule compares — which is why it is a rank and not an
         * ordinal: {@code DRAINING} is late in the ladder but nothing is wrong.
         */
        public int severity() {
            return severity;
        }

        /** True where the verdict is a statement about the queue's health rather than the absence of one. */
        public boolean known() {
            return this != INSUFFICIENT_DATA;
        }
    }
}
