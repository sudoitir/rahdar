package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunRepository;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.rahdar.platform.broker.NodeAddress;
import io.github.sudoitir.rahdar.platform.broker.NodeCallLimiter;
import io.github.sudoitir.rahdar.system.artemis.StagingQueues;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 5.8: a whole queue of 100,000 messages moves to another cluster. The messages parked in staging never
 * exceed twice the batch size, and Rahdar's management calls to each node stay within its limiter.
 * Small bodies keep the suite's runtime down; the size of a message is not what is under test.
 *
 * <p>The bound is checked on the run's own count of what it holds in staging (staged, less delivered,
 * expired and returned), which is exact. The broker's {@code MessageCount} is not: it adds its
 * pending, scheduled and delivering counters without a lock ({@code QueueImpl.getMessageCount}), so
 * every message it hands to the relay's consumer between those reads is counted twice. With a 64 KiB
 * consumer window and small bodies, that is a burst of messages, not one (issue #75).
 */
@Tag("large")
class TransferLargeQueueTest extends TransferTestSupport {

    private static final int COUNT = 100_000;
    private static final int BATCH = 1_000;

    @Autowired
    MeterRegistry meters;

    @Autowired
    NodeCallLimiter limiter;

    @Autowired
    TransferRunRepository runs;

    /** What the run holds in staging by its own count: the service's figure, read without its access check. */
    private long held(TransferRunView run) {
        return runs.findById(run.id()).map(transfers::view).orElseThrow().held();
    }

    private double requests(String node) {
        var counter = meters.find("rahdar.broker.requests")
                .tag("node", NodeAddress.hostPort(node))
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aHundredThousandMessagesMoveWithBoundedStagingWithinTheLimiter() throws Exception {
        settings.put(TransferSettings.BATCH_SIZE, Integer.toString(BATCH));
        String src = queue(p, "large.src");
        String dst = queue(d, "large.dst");
        p.produce(src, COUNT);
        assertThat(p.depth(src)).isEqualTo(COUNT);

        TransferRunView preview = preview(TransferMode.MOVE, src, all(), dst);
        assertThat(preview.estimate()).isEqualTo(COUNT);
        String stagingQueue = StagingQueues.queueName(preview.id());
        AtomicBoolean sampling = new AtomicBoolean(true);
        AtomicLong mostHeld = new AtomicLong();
        AtomicLong brokerSaw = new AtomicLong();
        double sourceCallsBefore = requests(p.jolokiaUrl());
        double targetCallsBefore = requests(d.jolokiaUrl());
        long started = System.nanoTime();
        TransferRunView executing = execute(preview, true);
        // Paced: the run saves its counts once per batch, and an unpaced loop would compete with it.
        Thread sampler = Thread.ofVirtual()
                .start(() -> Awaitility.await()
                        .pollInSameThread()
                        .pollInterval(Duration.ofMillis(5))
                        .forever()
                        .until(() -> {
                            mostHeld.accumulateAndGet(held(executing), Math::max);
                            try {
                                brokerSaw.accumulateAndGet(p.depth(stagingQueue), Math::max);
                            } catch (RuntimeException _) {
                                // before the run creates staging, and after it removes it
                            }
                            return !sampling.get();
                        }));
        TransferRunView run;
        try {
            run = awaitEnded(executing, Duration.ofMinutes(5));
        } finally {
            sampling.set(false);
            sampler.join();
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        assertThat(run.state()).as(run.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.delivered()).isEqualTo(COUNT);
        assertThat(d.depth(dst)).isEqualTo(COUNT);
        assertThat(p.depth(src)).isZero();
        assertThat(mostHeld.get())
                .as("messages parked in staging at once")
                .isPositive()
                .isLessThanOrEqualTo(2L * BATCH);
        assertThat(brokerSaw.get()).as("staging was used on the broker").isPositive();
        // A node's bucket holds a second's permits and is refilled once a second.
        double ceiling = limiter.permitsPerSecond() * (Math.ceil(seconds) + 1);
        assertThat(requests(p.jolokiaUrl()) - sourceCallsBefore).isPositive().isLessThanOrEqualTo(ceiling);
        assertThat(requests(d.jolokiaUrl()) - targetCallsBefore).isPositive().isLessThanOrEqualTo(ceiling);
        System.out.printf("5.8: %d messages moved in %.1f s%n", COUNT, seconds);
    }
}
