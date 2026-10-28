package io.github.sudoitir.rahdar.kernel.jobs;

import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;

/**
 * What is known about one job's runs since Rahdar started (operational-health spec).
 *
 * @param scope whether it runs on every instance or once per installation (ADR-0125)
 * @param registeredAt when the job was scheduled; the reference for a job that has never finished
 * @param lastOutcome how the most recent run ended; {@code null} until one has
 * @param lastError why the most recent run did not succeed; {@code null} once a later run succeeds
 * @param runs every started run, successful or not
 * @param failures the runs that did not succeed, for any reason
 * @param timeouts the runs interrupted for exceeding the job's timeout
 * @param lockLost the runs interrupted because their installation-wide lock could not be extended
 * @param overruns the runs that lasted past the job's next due time
 * @param nextRun when the runner next intends to start it, once it has asked
 * @param interval the gap the runner last computed before that next run
 * @param invalidSchedule why the job's schedule cannot be read; {@code null} while it can. The job does not
 *     run until it can
 * @param lastSkippedElsewhere when this instance last found an installation-wide job's lock held by
 *     another instance, which ran it instead
 */
@Builder(toBuilder = true, access = AccessLevel.PACKAGE)
public record JobStatus(
        String id,
        String featureId,
        ScheduledJob.Scope scope,
        Instant registeredAt,
        Instant lastStart,
        Instant lastEnd,
        JobOutcome lastOutcome,
        String lastError,
        long runs,
        long failures,
        long timeouts,
        long lockLost,
        long overruns,
        Instant nextRun,
        Duration interval,
        String invalidSchedule,
        Instant lastSkippedElsewhere) {

    /** How a run ended. */
    public enum JobOutcome {
        SUCCEEDED,
        FAILED,
        /** Interrupted for exceeding the job's timeout. */
        TIMED_OUT,
        /** Interrupted because the installation-wide lock could not be extended. */
        LOCK_LOST,
        /** Interrupted because Rahdar is stopping. */
        INTERRUPTED
    }

    static JobStatus never(ScheduledJob job, Instant at) {
        return JobStatus.builder()
                .id(job.id())
                .featureId(job.featureId())
                .scope(job.scope())
                .registeredAt(at)
                .build();
    }

    JobStatus started(Instant at) {
        return toBuilder().lastStart(at).runs(runs + 1).build();
    }

    /** A run that ended without a failure. */
    JobStatus succeeded(Instant at) {
        return toBuilder()
                .lastEnd(at)
                .lastOutcome(JobOutcome.SUCCEEDED)
                .lastError(null)
                .build();
    }

    /** A run that did not succeed; {@code outcome} says why, and its own counter rises with the failure count. */
    JobStatus ended(Instant at, JobOutcome outcome, String error) {
        return toBuilder()
                .lastEnd(at)
                .lastOutcome(outcome)
                .lastError(error)
                .failures(failures + 1)
                .timeouts(outcome == JobOutcome.TIMED_OUT ? timeouts + 1 : timeouts)
                .lockLost(outcome == JobOutcome.LOCK_LOST ? lockLost + 1 : lockLost)
                .build();
    }

    JobStatus overran() {
        return toBuilder().overruns(overruns + 1).build();
    }

    JobStatus scheduled(Instant next, Duration gap) {
        return toBuilder().nextRun(next).interval(gap).invalidSchedule(null).build();
    }

    JobStatus invalid(String cause) {
        return toBuilder().nextRun(null).invalidSchedule(cause).build();
    }

    JobStatus skippedElsewhere(Instant at) {
        return toBuilder().lastSkippedElsewhere(at).build();
    }

    /**
     * When a run last finished, or another instance was found running it, or when the job was
     * registered if neither has happened. A tick another instance ran counts as completed here.
     */
    public Instant lastCompletedOrRegistered() {
        Instant latest = lastEnd != null ? lastEnd : registeredAt;
        return lastSkippedElsewhere != null && lastSkippedElsewhere.isAfter(latest) ? lastSkippedElsewhere : latest;
    }

    /**
     * Whether the job's schedule cannot be read, or no run has finished, here or on another instance, within three
     * of the job's own intervals. The interval is unknown until the runner has computed one, and a job with none
     * is never called stalled.
     */
    public boolean degraded(Instant now) {
        return invalidSchedule != null
                || interval != null
                        && !interval.isZero()
                        && now.isAfter(lastCompletedOrRegistered().plus(interval.multipliedBy(3)));
    }

    /**
     * How far past its interval the job is: the time since a run last completed, minus the interval, and zero
     * while it keeps to schedule. {@code null} until the scheduler has computed an interval, so an unknown lag is
     * never read as none.
     */
    public Duration lag(Instant now) {
        if (interval == null) {
            return null;
        }
        Duration late = Duration.between(lastCompletedOrRegistered(), now).minus(interval);
        return late.isNegative() ? Duration.ZERO : late;
    }
}
