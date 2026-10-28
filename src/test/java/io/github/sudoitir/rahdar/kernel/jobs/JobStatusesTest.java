package io.github.sudoitir.rahdar.kernel.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JobStatusesTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final JobStatuses statuses = new JobStatuses(meters);

    private ScheduledJob register(String id, ScheduledJob.Scope scope) {
        ScheduledJob job = ScheduledJob.fixedDelay(id, "rr", scope, () -> Duration.ofSeconds(10), () -> {});
        statuses.register(job);
        return job;
    }

    @Test
    void everyRunIsRecordedWithItsOutcome() {
        register("demo", ScheduledJob.Scope.INSTANCE);

        statuses.started("demo", Instant.now());
        statuses.succeeded("demo", Instant.now());
        JobStatus ok = statuses.all().getFirst();
        assertThat(ok.runs()).isEqualTo(1);
        assertThat(ok.failures()).isZero();
        assertThat(ok.lastError()).isNull();
        assertThat(ok.lastEnd()).isNotNull();
        assertThat(ok.lastOutcome()).isEqualTo(JobStatus.JobOutcome.SUCCEEDED);

        statuses.started("demo", Instant.now());
        statuses.ended("demo", Instant.now(), JobStatus.JobOutcome.FAILED, "boom");
        JobStatus failed = statuses.all().getFirst();
        assertThat(failed.runs()).isEqualTo(2);
        assertThat(failed.failures()).isEqualTo(1);
        assertThat(failed.lastError()).isEqualTo("boom");
        assertThat(failed.lastOutcome()).isEqualTo(JobStatus.JobOutcome.FAILED);
        assertThat(failed.timeouts()).isZero();
    }

    @Test
    void aTimeoutALostLockAndAnOverrunAreCountedAsStatusAndMetric() {
        register("slow", ScheduledJob.Scope.INSTALLATION);

        statuses.started("slow", Instant.now());
        statuses.ended("slow", Instant.now(), JobStatus.JobOutcome.TIMED_OUT, "Timed out after PT5M");
        statuses.started("slow", Instant.now());
        statuses.ended("slow", Instant.now(), JobStatus.JobOutcome.LOCK_LOST, "Lost its lock while running");
        statuses.overran("slow");

        JobStatus s = statuses.all().getFirst();
        assertThat(s.timeouts()).isEqualTo(1);
        assertThat(s.lockLost()).isEqualTo(1);
        assertThat(s.overruns()).isEqualTo(1);
        assertThat(s.failures()).isEqualTo(2);
        assertThat(s.lastOutcome()).isEqualTo(JobStatus.JobOutcome.LOCK_LOST);
        assertThat(meters.get("rahdar.job.timeouts")
                        .tag("job.id", "slow")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("rahdar.job.lock_lost")
                        .tag("job.id", "slow")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("rahdar.job.overruns")
                        .tag("job.id", "slow")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void anInvalidScheduleIsDegradedUntilTheNextRunIsScheduled() {
        register("cron", ScheduledJob.Scope.INSTANCE);

        statuses.invalid("cron", "Invalid cron expression");
        JobStatus invalid = statuses.all().getFirst();
        assertThat(invalid.invalidSchedule()).isEqualTo("Invalid cron expression");
        assertThat(invalid.degraded(Instant.now())).isTrue();
        assertThat(meters.get("rahdar.job.degraded")
                        .tag("job.id", "cron")
                        .gauge()
                        .value())
                .isEqualTo(1.0);

        statuses.scheduled("cron", Instant.now().plusSeconds(10), Duration.ofSeconds(10));
        assertThat(statuses.all().getFirst().invalidSchedule()).isNull();
        assertThat(statuses.all().getFirst().degraded(Instant.now())).isFalse();
    }

    @Test
    void aTickAnotherInstanceRanDoesNotMakeTheJobDegraded() {
        register("elsewhere", ScheduledJob.Scope.INSTALLATION);
        statuses.scheduled("elsewhere", Instant.now(), Duration.ofSeconds(1));

        statuses.skippedElsewhere("elsewhere", Instant.now());

        JobStatus status = statuses.all().getFirst();
        assertThat(status.lastEnd()).isNull();
        assertThat(status.degraded(status.lastSkippedElsewhere().plusMillis(2_900)))
                .isFalse();
        assertThat(status.degraded(status.lastSkippedElsewhere().plusSeconds(4)))
                .isTrue();
    }

    @Test
    void theMinimumGapIsMostOfTheIntervalAndAtMostFiveMinutes() {
        assertThat(ScheduledJob.fixedDelay(
                                "a", "rr", ScheduledJob.Scope.INSTALLATION, () -> Duration.ofSeconds(10), () -> {})
                        .minimumGap()
                        .get())
                .isEqualTo(Duration.ofSeconds(9));
        assertThat(ScheduledJob.fixedDelay(
                                "b", "rr", ScheduledJob.Scope.INSTALLATION, () -> Duration.ofHours(1), () -> {})
                        .minimumGap()
                        .get())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(ScheduledJob.cron("c", "rr", ScheduledJob.Scope.INSTALLATION, () -> "0 0 4 * * *", () -> {})
                        .minimumGap()
                        .get())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void theDefaultTimeoutIsTheLargerOfFiveMinutesAndThreeIntervalsAndCronGetsAnHour() {
        assertThat(ScheduledJob.fixedDelay(
                                "a", "rr", ScheduledJob.Scope.INSTANCE, () -> Duration.ofSeconds(10), () -> {})
                        .timeout()
                        .get())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(ScheduledJob.fixedDelay(
                                "b", "rr", ScheduledJob.Scope.INSTANCE, () -> Duration.ofMinutes(10), () -> {})
                        .timeout()
                        .get())
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(ScheduledJob.cron("c", "rr", ScheduledJob.Scope.INSTANCE, () -> "0 0 4 * * *", () -> {})
                        .timeout()
                        .get())
                .isEqualTo(Duration.ofHours(1));
        assertThat(ScheduledJob.cron("d", "rr", ScheduledJob.Scope.INSTANCE, () -> "0 0 4 * * *", () -> {})
                        .withTimeout(() -> Duration.ofSeconds(7))
                        .timeout()
                        .get())
                .isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void aDuplicateJobIdIsRefused() {
        ScheduledJob job = register("dup", ScheduledJob.Scope.INSTANCE);
        assertThatThrownBy(() -> statuses.register(job)).hasMessageContaining("'dup'");
    }

    @Test
    void schedulingRecordsTheNextRunAndItsInterval() {
        register("tick", ScheduledJob.Scope.INSTANCE);
        Instant next = Instant.now().plusSeconds(15);

        statuses.scheduled("tick", next, Duration.ofSeconds(15));

        JobStatus s = statuses.all().getFirst();
        assertThat(s.nextRun()).isEqualTo(next);
        assertThat(s.interval()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void aJobThatHasNotFinishedWithinThreeIntervalsIsDegraded() {
        Instant registered = Instant.parse("2026-09-13T10:00:00Z");
        JobStatus s = JobStatus.builder()
                .id("tick")
                .featureId("rr")
                .scope(ScheduledJob.Scope.INSTANCE)
                .registeredAt(registered)
                .interval(Duration.ofSeconds(15))
                .build();

        assertThat(s.degraded(registered.plusSeconds(44))).isFalse();
        assertThat(s.degraded(registered.plusSeconds(46))).isTrue();

        JobStatus finished = s.started(registered.plusSeconds(40)).succeeded(registered.plusSeconds(41));
        assertThat(finished.degraded(registered.plusSeconds(46))).isFalse();
        assertThat(finished.degraded(registered.plusSeconds(87))).isTrue();
    }

    @Test
    void aJobWithoutAKnownIntervalIsNeverCalledStalled() {
        JobStatus s = JobStatus.builder()
                .id("tick")
                .featureId("rr")
                .scope(ScheduledJob.Scope.INSTANCE)
                .registeredAt(Instant.EPOCH)
                .build();
        assertThat(s.degraded(Instant.now())).isFalse();
    }

    @Test
    void lagIsTheTimePastTheIntervalAndNeverNegative() {
        Instant done = Instant.parse("2026-01-01T00:00:00Z");
        JobStatus status = JobStatus.builder()
                .id("poll")
                .featureId("scrape")
                .scope(ScheduledJob.Scope.INSTANCE)
                .registeredAt(done)
                .lastStart(done)
                .lastEnd(done)
                .runs(1)
                .interval(Duration.ofSeconds(10))
                .build();

        assertThat(status.lag(done.plusSeconds(4))).isEqualTo(Duration.ZERO);
        assertThat(status.lag(done.plusSeconds(25))).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void lagIsUnknownBeforeTheRunnerHasComputedAnInterval() {
        register("fresh", ScheduledJob.Scope.INSTANCE);

        assertThat(statuses.all().getFirst().lag(Instant.now())).isNull();
        assertThat(meters.get("rahdar.job.lag").tag("job.id", "fresh").gauge().value())
                .isNaN();
    }

    @Test
    void deregisteringAJobRemovesItsMeters() {
        register("gone", ScheduledJob.Scope.INSTANCE);
        statuses.deregister("gone");

        assertThat(statuses.all()).isEmpty();
        for (String name : new String[] {
            "rahdar.job.lag",
            "rahdar.job.degraded",
            "rahdar.job.timeouts",
            "rahdar.job.lock_lost",
            "rahdar.job.overruns"
        }) {
            assertThat(meters.find(name).tag("job.id", "gone").meter()).isNull();
        }
    }

    @Test
    void aJobKeepingToScheduleIsNotDegraded() {
        register("steady", ScheduledJob.Scope.INSTANCE);
        statuses.scheduled("steady", Instant.now().plusSeconds(10), Duration.ofSeconds(10));

        assertThat(meters.get("rahdar.job.degraded")
                        .tag("job.id", "steady")
                        .gauge()
                        .value())
                .isEqualTo(0.0);
    }
}
