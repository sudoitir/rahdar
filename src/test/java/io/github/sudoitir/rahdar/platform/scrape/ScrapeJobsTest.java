package io.github.sudoitir.rahdar.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** A pass of a scrape tier is timed by tier, so its duration can be set against its interval. */
class ScrapeJobsTest {

    @Test
    void eachPassIsRecordedUnderItsTierAndStillRuns() {
        // The timer reads this clock, so the recorded duration does not depend on a real pause.
        AtomicLong nanos = new AtomicLong();
        Clock clock = new Clock() {
            @Override
            public long wallTime() {
                return TimeUnit.NANOSECONDS.toMillis(nanos.get());
            }

            @Override
            public long monotonicTime() {
                return nanos.get();
            }
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        AtomicInteger ran = new AtomicInteger();
        Runnable pass = ScrapeJobs.timed(meters, "A", () -> {
            ran.incrementAndGet();
            nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
        });

        pass.run();
        pass.run();

        assertThat(ran).hasValue(2);
        var timer = meters.get("rahdar.scrape.cycle").tag("tier", "A").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.max(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(15);
    }
}
