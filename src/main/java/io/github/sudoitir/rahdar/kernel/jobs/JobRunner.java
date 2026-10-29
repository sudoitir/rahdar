package io.github.sudoitir.rahdar.kernel.jobs;

import io.github.sudoitir.rahdar.kernel.replica.ReplicaSignal;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link ScheduledJob}: the host's, the scrape tiers' and the plugins' (ADR-0194, amending
 * ADR-0048). One daemon platform thread keeps the timers and does nothing else. Each run gets a virtual
 * thread named {@code job-<id>} with the host class loader as its context loader, so a slow job holds
 * no one's turn and no pool size caps how many run.
 *
 * <p>The next fire is computed only after a run ends, from the job's own {@link
 * org.springframework.scheduling.Trigger}, so a job never overlaps itself and a setting changed in the
 * meantime applies to the very next fire. A run that outlasts its {@link ScheduledJob#timeout() timeout} is
 * interrupted and recorded as timed out; the job does not run again until that thread has ended.
 *
 * <p>An {@link ScheduledJob.Scope#INSTALLATION installation-wide} run takes its ShedLock lock first, and
 * extends it itself every half of its lifetime (at least a minute), so a crashed holder frees the job within a minute. An
 * extension that fails or finds the lock gone interrupts the run and records it as lock lost (ADR-0152: no
 * fencing, the work's own idempotency covers the rare double run).
 *
 * <p>A cron that cannot be parsed marks the job's schedule invalid, degraded in health, and is asked again every
 * minute. Only a job's first failure and its recovery are logged.
 */
@Slf4j
@Component
@DependsOn("settingsService")
public class JobRunner implements SmartInitializingSingleton, DisposableBean {

    /** A floor under every lock's lifetime; the runner extends it while a run lasts. */
    private static final Duration LOCK_AT_MOST = Duration.ofSeconds(60);

    private static final Duration INVALID_RETRY = Duration.ofMinutes(1);

    /** How long the runner waits for interrupted runs to end at shutdown. */
    private static final Duration INTERRUPT_GRACE = Duration.ofSeconds(2);

    private static final ClassLoader HOST = JobRunner.class.getClassLoader();

    /** Why a run was interrupted; the first reason stands. */
    private enum Stop {
        NONE,
        TIMEOUT,
        LOCK_LOST,
        SHUTDOWN
    }

    /** One job and its schedule. Fields without a modifier are guarded by the runner's monitor. */
    private static final class Entry {
        final ScheduledJob job;
        final SimpleTriggerContext context;
        final AtomicReference<Stop> stop = new AtomicReference<>(Stop.NONE);
        ScheduledFuture<?> timer;
        Instant scheduledFor;
        /** The run's thread while it runs. */
        Thread thread;

        boolean cancelled;

        Entry(ScheduledJob job, Clock clock) {
            this.job = job;
            this.context = new SimpleTriggerContext(clock);
        }
    }

    private final List<ScheduledJob> jobs;
    private final JobStatuses statuses;
    private final LockProvider locks;
    private final ObservationRegistry observations;
    private final Clock clock = Clock.systemUTC();
    private final Duration invalidRetry;
    private final Duration lockAtMost;
    private final ScheduledThreadPoolExecutor timer;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private boolean paused;
    private int running;

    @Autowired
    JobRunner(List<ScheduledJob> jobs, JobStatuses statuses, LockProvider locks, ObservationRegistry observations) {
        this(jobs, statuses, locks, observations, INVALID_RETRY, LOCK_AT_MOST);
    }

    JobRunner(
            List<ScheduledJob> jobs,
            JobStatuses statuses,
            LockProvider locks,
            ObservationRegistry observations,
            Duration invalidRetry,
            Duration lockAtMost) {
        this.jobs = jobs;
        this.statuses = statuses;
        this.locks = locks;
        this.observations = observations;
        this.invalidRetry = invalidRetry;
        this.lockAtMost = lockAtMost;
        this.timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = Thread.ofPlatform().name("jobs-timer").daemon().unstarted(task);
            thread.setContextClassLoader(HOST);
            return thread;
        });
        // A cancelled timer would otherwise sit in the queue, holding a plugin's job, until its due time.
        timer.setRemoveOnCancelPolicy(true);
    }

    @Override
    public void afterSingletonsInstantiated() {
        jobs.forEach(this::schedule);
    }

    // ---- registering -------------------------------------------------------------------------------

    /** Starts a job's schedule; its id must not be registered. */
    void schedule(ScheduledJob job) {
        statuses.register(job);
        Entry entry = new Entry(job, clock);
        entries.put(job.id(), entry);
        arm(entry);
    }

    /**
     * Ends a job's schedule and drops its status. A run in progress finishes and is not rescheduled. An id
     * that is not scheduled is a no-op.
     */
    void cancel(String jobId) {
        Entry entry = entries.remove(jobId);
        if (entry == null) {
            return;
        }
        synchronized (this) {
            entry.cancelled = true;
            disarm(entry);
        }
        statuses.deregister(jobId);
    }

    // ---- timing ------------------------------------------------------------------------------------

    /** Computes the job's next fire and sets its timer. An unreadable schedule is retried in a minute. */
    private void arm(Entry entry) {
        String id = entry.job.id();
        synchronized (this) {
            if (paused || entry.cancelled) {
                return;
            }
            Instant now = clock.instant();
            Instant next;
            try {
                next = entry.job.trigger().nextExecution(entry.context);
            } catch (RuntimeException e) {
                JobStatus before = statuses.get(id);
                if (before != null && before.invalidSchedule() == null) {
                    log.warn("Job {} has a schedule that cannot be read, and is retried every {}", id, invalidRetry, e);
                }
                statuses.invalid(id, String.valueOf(e.getMessage()));
                entry.timer = timer.schedule(() -> arm(entry), invalidRetry.toNanos(), TimeUnit.NANOSECONDS);
                return;
            }
            JobStatus before = statuses.get(id);
            if (before != null && before.invalidSchedule() != null) {
                log.info("Job {} has a schedule that can be read again", id);
            }
            if (next == null) {
                return;
            }
            Instant from = entry.context.lastCompletion() != null ? entry.context.lastCompletion() : now;
            statuses.scheduled(id, next, Duration.between(from, next));
            entry.scheduledFor = next;
            Duration delay = Duration.between(now, next);
            entry.timer = timer.schedule(
                    () -> dispatch(entry), delay.isNegative() ? 0 : delay.toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    /**
     * A setting changed somewhere (every replica hears it, after the settings have been re-read): a waiting
     * job whose schedule now comes due sooner is re-armed at once, so moving a job from daily to hourly takes
     * effect now rather than after the old run. A schedule that moved later takes over after the next run,
     * and a running job works its next run out when it ends, as always.
     */
    @EventListener(condition = "#signal.kind() == 'settings'")
    void onSettingsChanged(ReplicaSignal signal) {
        synchronized (this) {
            for (Entry entry : entries.values()) {
                if (entry.cancelled || entry.thread != null || entry.timer == null || entry.scheduledFor == null) {
                    continue;
                }
                Instant next;
                try {
                    next = entry.job.trigger().nextExecution(entry.context);
                } catch (RuntimeException _) {
                    next = null; // an unreadable schedule is reported by arm on its own retry
                }
                if (next != null && next.isBefore(entry.scheduledFor)) {
                    disarm(entry);
                    arm(entry);
                }
            }
        }
    }

    private void disarm(Entry entry) {
        if (entry.timer != null) {
            entry.timer.cancel(false);
            entry.timer = null;
        }
    }

    /** On the timer thread: hands the run to its own virtual thread and returns. */
    private void dispatch(Entry entry) {
        synchronized (this) {
            if (paused || entry.cancelled) {
                return;
            }
            entry.timer = null;
            entry.thread = Thread.ofVirtual().name("job-" + entry.job.id()).unstarted(() -> execute(entry));
            running++;
            entry.thread.start();
        }
    }

    // ---- running -----------------------------------------------------------------------------------

    private void execute(Entry entry) {
        Thread.currentThread().setContextClassLoader(HOST);
        ScheduledJob job = entry.job;
        Instant started = clock.instant();
        Held held = null;
        ScheduledFuture<?> watchdog = null;
        boolean skipped = false;
        boolean recorded = false;
        Duration timeout = null;
        Throwable failure = null;
        boolean ended = false;
        try {
            if (job.scope() == ScheduledJob.Scope.INSTALLATION) {
                held = acquire(entry);
                skipped = held == null;
            }
            if (!skipped) {
                statuses.started(job.id(), started);
                recorded = true;
                timeout = job.timeout().get();
                watchdog =
                        timer.schedule(() -> interrupt(entry, Stop.TIMEOUT), timeout.toNanos(), TimeUnit.NANOSECONDS);
                if (held != null) {
                    held.keepAlive.start();
                }
                observation(job).observe(job.task());
            }
            ended = true;
        } catch (Exception e) {
            failure = e;
            ended = true;
        } finally {
            // An Error still leaves the run recorded and the counters settled, or pause() would wait for it forever.
            if (!ended) {
                failure = new IllegalStateException("The job ended with an Error");
            }
            if (watchdog != null) {
                watchdog.cancel(false);
            }
            if (held != null) {
                held.release();
            }
            Thread.interrupted();
            Instant completed = clock.instant();
            if (skipped) {
                statuses.skippedElsewhere(job.id(), completed);
            } else {
                if (!recorded) {
                    statuses.started(job.id(), started);
                }
                record(entry, failure, timeout, completed);
            }
            entry.context.update(entry.scheduledFor, started, completed);
            if (!skipped && overran(entry, started, completed)) {
                statuses.overran(job.id());
            }
            synchronized (this) {
                entry.thread = null;
                entry.stop.set(Stop.NONE);
                running--;
                notifyAll();
            }
            arm(entry);
        }
    }

    /** Takes the installation-wide lock, or returns {@code null} when another instance holds it. */
    private Held acquire(Entry entry) {
        Duration gap = entry.job.minimumGap().get();
        Duration atMost = gap.compareTo(lockAtMost) > 0 ? gap : lockAtMost;
        Optional<SimpleLock> lock = locks.lock(new LockConfiguration(clock.instant(), entry.job.id(), atMost, gap));
        return lock.map(l -> new Held(entry, l, atMost, gap)).orElse(null);
    }

    /** Records how a run ended, and logs the first failure and the recovery. */
    private void record(Entry entry, Throwable failure, Duration timeout, Instant at) {
        String id = entry.job.id();
        JobStatus before = statuses.get(id);
        boolean failing = before != null && before.lastError() != null;
        Stop stop = entry.stop.get();
        JobStatus.JobOutcome outcome;
        String error;
        switch (stop) {
            case TIMEOUT -> {
                outcome = JobStatus.JobOutcome.TIMED_OUT;
                error = "Timed out after " + timeout;
            }
            case LOCK_LOST -> {
                outcome = JobStatus.JobOutcome.LOCK_LOST;
                error = "Lost its lock while running";
            }
            case SHUTDOWN -> {
                outcome = JobStatus.JobOutcome.INTERRUPTED;
                error = "Interrupted at shutdown";
            }
            default -> {
                outcome = failure == null ? JobStatus.JobOutcome.SUCCEEDED : JobStatus.JobOutcome.FAILED;
                error = failure == null ? null : String.valueOf(failure.getMessage());
            }
        }
        if (outcome == JobStatus.JobOutcome.SUCCEEDED) {
            statuses.succeeded(id, at);
            if (failing) {
                log.info("Job {} succeeded again", id);
            }
            return;
        }
        statuses.ended(id, at, outcome, error);
        if (!failing) {
            log.warn("Job {} did not succeed: {}", id, error, failure);
        }
    }

    /** Whether the run lasted past the time its next run was due, counted from when it started. */
    private boolean overran(Entry entry, Instant started, Instant completed) {
        try {
            Instant due = entry.job.trigger().nextExecution(new SimpleTriggerContext(started, started, started));
            return due != null && due.isBefore(completed);
        } catch (RuntimeException _) {
            return false;
        }
    }

    private Observation observation(ScheduledJob job) {
        return Observation.createNotStarted("rahdar.job", observations)
                .contextualName("job " + job.id())
                .lowCardinalityKeyValue("job.id", job.id())
                .lowCardinalityKeyValue("feature", job.featureId());
    }

    /** Interrupts the run in progress, once, for the stated reason. A job that is not running is left alone. */
    private void interrupt(Entry entry, Stop reason) {
        synchronized (this) {
            if (entry.thread != null && entry.stop.compareAndSet(Stop.NONE, reason)) {
                entry.thread.interrupt();
            }
        }
    }

    /** An installation-wide run's lock, and the thread that extends it while the run lasts. */
    private final class Held {
        final Thread keepAlive;
        private final Entry entry;
        private final Duration atMost;
        private final Duration gap;
        private SimpleLock lock;
        private volatile boolean done;
        private volatile boolean lost;

        Held(Entry entry, SimpleLock lock, Duration atMost, Duration gap) {
            this.entry = entry;
            this.lock = lock;
            this.atMost = atMost;
            this.gap = gap;
            this.keepAlive =
                    Thread.ofVirtual().name("job-" + entry.job.id() + "-lock").unstarted(this::extendWhileRunning);
        }

        private void extendWhileRunning() {
            try {
                while (true) {
                    Thread.sleep(atMost.dividedBy(2));
                    Optional<SimpleLock> extended = lock.extend(atMost, gap);
                    if (extended.isEmpty()) {
                        lose();
                        return;
                    }
                    lock = extended.get();
                }
            } catch (InterruptedException _) {
                // the run ended
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("Job {} could not extend its lock", entry.job.id(), e);
                lose();
            }
        }

        private void lose() {
            if (!done) {
                lost = true;
                interrupt(entry, Stop.LOCK_LOST);
            }
        }

        /** Stops extending, then releases the lock unless it was lost, which means another instance may hold it. */
        void release() {
            done = true;
            keepAlive.interrupt();
            while (keepAlive.isAlive()) {
                try {
                    keepAlive.join();
                } catch (InterruptedException _) {
                    // a late timeout interrupt: the run is over, keep waiting for the keep-alive thread
                }
            }
            Thread.interrupted();
            if (!lost) {
                try {
                    lock.unlock();
                } catch (RuntimeException e) {
                    log.warn("Job {} could not release its lock; it lapses on its own", entry.job.id(), e);
                }
            }
        }
    }

    // ---- stopping ----------------------------------------------------------------------------------

    /**
     * Stops every job from starting a new run and waits up to {@code maxWait} for the runs in progress; any
     * still running then are interrupted and given a moment to record themselves interrupted.
     */
    public void pause(Duration maxWait) {
        synchronized (this) {
            paused = true;
            entries.values().forEach(this::disarm);
            if (!awaitIdle(maxWait) && running > 0) {
                entries.values().forEach(e -> interrupt(e, Stop.SHUTDOWN));
                awaitIdle(INTERRUPT_GRACE);
            }
        }
    }

    private synchronized boolean awaitIdle(Duration maxWait) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        try {
            long remaining;
            while (running > 0 && (remaining = (deadline - System.nanoTime()) / 1_000_000L) > 0) {
                wait(remaining);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        return running == 0;
    }

    /** Lets jobs run again, after a stopped context is started. */
    public synchronized void resume() {
        paused = false;
        entries.values().stream()
                .filter(e -> e.timer == null && e.thread == null)
                .forEach(this::arm);
    }

    /** The context is closing: no timer survives it, and no run does. */
    @Override
    public void destroy() throws InterruptedException {
        pause(Duration.ZERO);
        timer.shutdownNow();
        timer.awaitTermination(INTERRUPT_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    }
}
