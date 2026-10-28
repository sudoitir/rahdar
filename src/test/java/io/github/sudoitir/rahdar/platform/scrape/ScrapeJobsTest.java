package io.github.sudoitir.rahdar.platform.scrape;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A pass of a scrape tier is timed by tier, so its duration can be set against its interval. */
class ScrapeJobsTest {

    @Test
    void eachPassIsRecordedUnderItsTierAndStillRuns() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicInteger ran = new AtomicInteger();
        Runnable pass = ScrapeJobs.timed(meters, "A", () -> {
            ran.incrementAndGet();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        pass.run();
        pass.run();

        assertThat(ran).hasValue(2);
        var timer = meters.get("rahdar.scrape.cycle").tag("tier", "A").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.max(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(15);
    }
}
