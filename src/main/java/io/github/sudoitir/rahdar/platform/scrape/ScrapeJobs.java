package io.github.sudoitir.rahdar.platform.scrape;

import io.github.sudoitir.rahdar.kernel.jobs.ScheduledJob;
import io.github.sudoitir.rahdar.kernel.lifecycle.partition.PartitionSpec;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The scrape tiers (ADR-0015) as jobs on the one runner (ADR-0194), each with the cadence Settings holds
 * now, re-read after every run (ADR-0025).
 */
@Configuration(proxyBeanMethods = false)
class ScrapeJobs {

    private static final String FEATURE = "scrape";

    @Bean
    ScheduledJob scrapeTierAJob(ScrapeScheduler scheduler, SettingsService settings, MeterRegistry meters) {
        return ScheduledJob.fixedDelay(
                "scrape-tier-a",
                FEATURE,
                ScheduledJob.Scope.INSTANCE,
                () -> settings.duration(ScrapeSettings.TIER_A),
                timed(meters, "A", scheduler::tierA));
    }

    @Bean
    ScheduledJob scrapeTierBJob(ScrapeScheduler scheduler, SettingsService settings, MeterRegistry meters) {
        return ScheduledJob.fixedDelay(
                "scrape-tier-b",
                FEATURE,
                ScheduledJob.Scope.INSTANCE,
                () -> settings.duration(ScrapeSettings.TIER_B),
                timed(meters, "B", scheduler::tierB));
    }

    @Bean
    ScheduledJob scrapeTierCJob(ScrapeScheduler scheduler, SettingsService settings, MeterRegistry meters) {
        return ScheduledJob.fixedDelay(
                "scrape-tier-c",
                FEATURE,
                ScheduledJob.Scope.INSTALLATION,
                () -> settings.duration(ScrapeSettings.TIER_C),
                timed(meters, "C", scheduler::tierC));
    }

    @Bean
    ScheduledJob scrapeDiscoveryJob(ScrapeScheduler scheduler, SettingsService settings, MeterRegistry meters) {
        return ScheduledJob.fixedDelay(
                "scrape-discovery",
                FEATURE,
                ScheduledJob.Scope.INSTALLATION,
                () -> settings.duration(ScrapeSettings.DISCOVERY),
                timed(meters, "discovery", scheduler::discovery));
    }

    /**
     * A pass of one tier over every cluster this replica scrapes, timed as {@code rahdar.scrape.cycle} tagged by
     * tier, so that how long a cycle takes can be set against the interval it must fit in.
     */
    static Runnable timed(MeterRegistry meters, String tier, Runnable pass) {
        Timer timer = Timer.builder("rahdar.scrape.cycle")
                .description("How long one pass of a scrape tier takes, over every cluster of the replica")
                .tag("tier", tier)
                .register(meters);
        return () -> timer.record(pass);
    }

    @Bean
    PartitionSpec metricSamplePartitions() {
        return MetricSampleStore.PARTITIONS;
    }
}
