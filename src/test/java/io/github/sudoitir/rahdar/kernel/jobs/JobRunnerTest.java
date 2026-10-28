package io.github.sudoitir.rahdar.kernel.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The runner against real threads and short real times: a job never overlaps itself, its next fire
 * waits for the run to end, a timeout or a lost lock interrupts the run, and a malformed cron degrades one job and
 * recovers (operational-health spec, ADR-0194).
 */
class JobRunnerTest {

    private static final Duration LONG = Duration.ofSeconds(10);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final JobStatuses statuses = new JobStatuses(meters);
    private final FakeLocks locks = new FakeLocks();
    private final JobRunner runner = new JobRunner(
            List.of(), statuses, locks, ObservationRegistry.NOOP, Duration.ofMillis(50), Duration.ofMillis(100));

    @AfterEach
    void stop() throws Exception {
        runner.destroy();
    }

    /** A lock provider that records what the runner asks of it. */
    private static final class FakeLocks implements LockProvider {
        volatile boolean heldElsewhere;
        volatile boolean extendable = true;
        final AtomicInteger extended = new AtomicInteger();
        final AtomicInteger unlocked = new AtomicInteger();
        final List<LockConfiguration> taken = new CopyOnWriteArrayList<>();

        @Override
        public Optional<SimpleLock> lock(LockConfiguration configuration) {
            if (heldElsewhere) {
                return Optional.empty();
            }
            taken.add(configuration);
            return Optional.of(new Lock());
        }

        private final class Lock implements SimpleLock {
            @Override
            public void unlock() {
                unlocked.incrementAndGet();
            }

            @Override
            public Optional<SimpleLock> extend(Duration lockAtMostFor, Duration lockAtLeastFor) {
                extended.incrementAndGet();
                return extendable ? Optional.of(new Lock()) : Optional.empty();
            }
        }
    }

    private static ScheduledJob every(String id, Duration interval, ScheduledJob.Scope scope, Runnable task) {
        return ScheduledJob.fixedDelay(id, "test", scope, () -> interval, task);
    }

    private static void sleep(Duration time) {
        try {
            new CountDownLatch(1).await(time.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private JobStatus status(String id) {
        return statuses.get(id);
    }

    @Test
    void aSlowRunNeverOverlapsItselfAndTheNextFireWaitsForItsEnd() {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger deepest = new AtomicInteger();
        List<Instant> starts = new CopyOnWriteArrayList<>();
        List<Instant> ends = new CopyOnWriteArrayList<>();
        runner.schedule(every("slow", Duration.ofMillis(30), ScheduledJob.Scope.INSTANCE, () -> {
            deepest.accumulateAndGet(inside.incrementAndGet(), Math::max);
            starts.add(Instant.now());
            sleep(Duration.ofMillis(120));
            ends.add(Instant.now());
            inside.decrementAndGet();
        }));

        await().atMost(LONG).until(() -> ends.size() >= 3);

        assertThat(deepest).hasValue(1);
        for (int i = 1; i < 3; i++) {
            assertThat(Duration.between(ends.get(i - 1), starts.get(i))).isGreaterThanOrEqualTo(Duration.ofMillis(25));
        }
        // each run lasted far past the 30 ms the next was due after it started
        assertThat(status("slow").overruns()).isPositive();
        assertThat(meters.get("rahdar.job.overruns")
                        .tag("job.id", "slow")
                        .counter()
                        .count())
                .isPositive();
    }

    @Test
    void aRunIsAVirtualThreadNamedForTheJobOnTheHostClassLoader() {
        AtomicReference<Thread> seen = new AtomicReference<>();
        AtomicReference<ClassLoader> loader = new AtomicReference<>();
        runner.schedule(every("named", Duration.ofMillis(20), ScheduledJob.Scope.INSTANCE, () -> {
            seen.set(Thread.currentThread());
            loader.set(Thread.currentThread().getContextClassLoader());
        }));

        await().atMost(LONG).until(() -> seen.get() != null);

        assertThat(seen.get().isVirtual()).isTrue();
        assertThat(seen.get().getName()).isEqualTo("job-named");
        assertThat(loader.get()).isSameAs(JobRunner.class.getClassLoader());
    }

    @Test
    void aRunThatExceedsItsTimeoutIsInterruptedRecordedAndNotRepeatedUntilItsThreadEnds() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ScheduledJob job = every("hang", Duration.ofMillis(20), ScheduledJob.Scope.INSTANCE, () -> {
                    if (runs.incrementAndGet() > 1) {
                        return;
                    }
                    try {
                        new CountDownLatch(1).await(LONG.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException _) {
                        interrupted.countDown();
                    }
                    try {
                        finish.await(); // the interrupt is seen, and the thread still takes its time to end
                    } catch (InterruptedException _) {
                        // already interrupted once
                    }
                })
                .withTimeout(() -> Duration.ofMillis(100));
        runner.schedule(job);

        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        sleep(Duration.ofMillis(300));
        assertThat(runs).hasValue(1); // no new run while the old thread lives

        finish.countDown();
        await().atMost(LONG).until(() -> runs.get() >= 2);

        JobStatus s = status("hang");
        assertThat(s.timeouts()).isEqualTo(1);
        assertThat(meters.get("rahdar.job.timeouts")
                        .tag("job.id", "hang")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aTimedOutRunShowsAsTimedOutInTheStatus() {
        runner.schedule(every("timed", Duration.ofMillis(10), ScheduledJob.Scope.INSTANCE, () -> sleep(LONG))
                .withTimeout(() -> Duration.ofMillis(50)));

        await().atMost(LONG).until(() -> status("timed").lastOutcome() == JobStatus.JobOutcome.TIMED_OUT);

        assertThat(status("timed").timeouts()).isEqualTo(1);
        assertThat(status("timed").lastError()).contains("Timed out");
    }

    @Test
    void anInstallationRunExtendsItsLockAndReleasesItWhenDone() {
        CountDownLatch release = new CountDownLatch(1);
        runner.schedule(ScheduledJob.fixedDelay(
                "held", "test", ScheduledJob.Scope.INSTALLATION, () -> Duration.ofMillis(10), () -> {
                    try {
                        release.await();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }));

        await().atMost(LONG).until(() -> locks.extended.get() >= 2);
        assertThat(locks.unlocked).hasValue(0);
        release.countDown();
        await().atMost(LONG).until(() -> locks.unlocked.get() >= 1);

        assertThat(locks.taken.getFirst().getName()).isEqualTo("held");
        assertThat(status("held").lockLost()).isZero();
    }

    @Test
    void aRunThatCannotExtendItsLockIsInterruptedAndCountedAsLockLost() {
        locks.extendable = false;
        AtomicBoolean sawInterrupt = new AtomicBoolean();
        runner.schedule(ScheduledJob.fixedDelay(
                "lost", "test", ScheduledJob.Scope.INSTALLATION, () -> Duration.ofMillis(10), () -> {
                    try {
                        new CountDownLatch(1).await(LONG.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException _) {
                        sawInterrupt.set(true);
                    }
                }));

        await().atMost(LONG).until(() -> status("lost").lockLost() >= 1);

        assertThat(sawInterrupt).isTrue();
        assertThat(status("lost").lastOutcome()).isEqualTo(JobStatus.JobOutcome.LOCK_LOST);
        assertThat(meters.get("rahdar.job.lock_lost")
                        .tag("job.id", "lost")
                        .counter()
                        .count())
                .isPositive();
        assertThat(locks.unlocked).hasValue(0); // a lost lock may be another instance's now
    }

    @Test
    void aTickAnotherInstanceHoldsIsSkippedNotRunNorDegraded() {
        locks.heldElsewhere = true;
        AtomicInteger ran = new AtomicInteger();
        runner.schedule(every("shared", Duration.ofMillis(20), ScheduledJob.Scope.INSTALLATION, ran::incrementAndGet));

        await().atMost(LONG).until(() -> status("shared").lastSkippedElsewhere() != null);

        assertThat(ran).hasValue(0);
        assertThat(status("shared").runs()).isZero();
        assertThat(status("shared").degraded(Instant.now())).isFalse();
    }

    @Test
    void aMalformedCronDegradesOneJobAndRecoversWhenCorrected() {
        AtomicReference<String> expression = new AtomicReference<>("not a cron");
        AtomicInteger cronRuns = new AtomicInteger();
        AtomicInteger otherRuns = new AtomicInteger();
        runner.schedule(ScheduledJob.cron(
                "bad", "test", ScheduledJob.Scope.INSTANCE, expression::get, cronRuns::incrementAndGet));
        runner.schedule(every("other", Duration.ofMillis(20), ScheduledJob.Scope.INSTANCE, otherRuns::incrementAndGet));

        await().atMost(LONG).until(() -> status("bad").invalidSchedule() != null);
        assertThat(status("bad").degraded(Instant.now())).isTrue();
        await().atMost(LONG).until(() -> otherRuns.get() >= 2);

        expression.set("* * * * * *");
        await().atMost(LONG).until(() -> status("bad").invalidSchedule() == null);
        await().atMost(LONG).until(() -> cronRuns.get() >= 1);

        assertThat(status("bad").degraded(Instant.now())).isFalse();
        assertThat(status("bad").nextRun()).isNotNull();
    }

    @Test
    void aJobThatKeepsFailingLogsOnceAndItsRecoveryOnce() {
        Logger logger = (Logger) LoggerFactory.getLogger(JobRunner.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            AtomicBoolean failing = new AtomicBoolean(true);
            AtomicInteger runs = new AtomicInteger();
            runner.schedule(every("flaky", Duration.ofMillis(10), ScheduledJob.Scope.INSTANCE, () -> {
                runs.incrementAndGet();
                if (failing.get()) {
                    throw new IllegalStateException("boom");
                }
            }));
            await().atMost(LONG).until(() -> status("flaky").failures() >= 4);
            failing.set(false);
            await().atMost(LONG)
                    .until(() -> status("flaky").lastError() == null
                            && status("flaky").runs() > 4);
            runner.pause(Duration.ofSeconds(1));

            assertThat(status("flaky").lastError()).isNull();
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).hasSize(2);
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void pausingWaitsThenInterruptsWhatIsLeftAndRecordsItInterruptedAndResumeRunsJobsAgain() {
        AtomicInteger runs = new AtomicInteger();
        AtomicBoolean hang = new AtomicBoolean(true);
        runner.schedule(every("busy", Duration.ofMillis(10), ScheduledJob.Scope.INSTANCE, () -> {
            runs.incrementAndGet();
            if (hang.get()) {
                sleep(LONG);
            }
        }));
        await().atMost(LONG).until(() -> runs.get() == 1);

        runner.pause(Duration.ofMillis(100));

        assertThat(status("busy").lastOutcome()).isEqualTo(JobStatus.JobOutcome.INTERRUPTED);
        sleep(Duration.ofMillis(100));
        assertThat(runs).hasValue(1); // paused: nothing starts

        hang.set(false);
        runner.resume();
        await().atMost(LONG).until(() -> runs.get() >= 3);
        assertThat(status("busy").lastOutcome()).isEqualTo(JobStatus.JobOutcome.SUCCEEDED);
    }

    @Test
    void aCancelledJobNeverRunsAgainAndLeavesNoStatus() {
        AtomicInteger runs = new AtomicInteger();
        runner.schedule(every("gone", Duration.ofMillis(10), ScheduledJob.Scope.INSTANCE, runs::incrementAndGet));
        await().atMost(LONG).until(() -> runs.get() >= 2);

        runner.cancel("gone");
        int after = runs.get();
        sleep(Duration.ofMillis(100));

        assertThat(runs.get()).isLessThanOrEqualTo(after + 1);
        assertThat(statuses.all()).isEmpty();
    }

    @Test
    void aScheduleMovedSoonerTakesEffectWhenTheSettingChangesNotAfterTheOldRun() {
        AtomicReference<String> cron = new AtomicReference<>("0 0 3 * * *");
        AtomicInteger runs = new AtomicInteger();
        runner.schedule(
                ScheduledJob.cron("moved", "test", ScheduledJob.Scope.INSTANCE, cron::get, runs::incrementAndGet));
        Instant planned = status("moved").nextRun();

        cron.set("* * * * * *");
        runner.onSettingsChanged(new ReplicaSignal("settings", ""));

        assertThat(status("moved").nextRun()).isBefore(planned);
        await().atMost(LONG).until(() -> runs.get() >= 1);
    }

    @Test
    void anUnrelatedSettingChangeDoesNotPostponeAJobThatHasNotRunYet() {
        runner.schedule(every("waiting", Duration.ofHours(1), ScheduledJob.Scope.INSTANCE, () -> {}));
        Instant planned = status("waiting").nextRun();

        runner.onSettingsChanged(new ReplicaSignal("settings", ""));

        assertThat(status("waiting").nextRun()).isEqualTo(planned);
    }

    @Test
    void aScheduleMadeUnreadableBySettingChangesKeepsItsPlannedRunUntilThatRunReportsIt() {
        AtomicReference<String> cron = new AtomicReference<>("0 0 3 * * *");
        runner.schedule(ScheduledJob.cron("broken", "test", ScheduledJob.Scope.INSTANCE, cron::get, () -> {}));
        Instant planned = status("broken").nextRun();

        cron.set("not a schedule");
        runner.onSettingsChanged(new ReplicaSignal("settings", ""));

        assertThat(status("broken").nextRun()).isEqualTo(planned);
        assertThat(status("broken").invalidSchedule()).isNull();
    }
}
