package io.github.sudoitir.rahdar.kernel.jobs;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * The status of every registered job: start, end, how the last run ended, run, failure, timeout,
 * lock-lost and overrun counts, the next scheduled run, and whether the schedule can be read. The
 * {@link JobRunner} keeps it current as it schedules and runs jobs, and publishes the lag, degraded and
 * timeout, lock-lost and overrun meters tagged with the job.
 */
@Component
@PluginApi
public class JobStatuses {

    private static final String JOB_ID = "job.id";

    private static final List<String> COUNTERS =
            List.of("rahdar.job.timeouts", "rahdar.job.lock_lost", "rahdar.job.overruns");

    private final Map<String, JobStatus> byId = new ConcurrentHashMap<>();
    private final MeterRegistry meters;

    public JobStatuses(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Starts tracking a job: an empty status and its meters. A job id registered twice is a bug. */
    void register(ScheduledJob job) {
        if (byId.putIfAbsent(job.id(), JobStatus.never(job, Instant.now())) != null) {
            throw new IllegalStateException("Job id '" + job.id() + "' is registered twice");
        }
        Gauge.builder("rahdar.job.lag", () -> lagSeconds(job.id()))
                .tag(JOB_ID, job.id())
                .baseUnit("seconds")
                .description(
                        "Seconds a job is past its interval since it last completed; NaN until its interval is known")
                .register(meters);
        Gauge.builder("rahdar.job.degraded", () -> degraded(job.id()))
                .tag(JOB_ID, job.id())
                .description(
                        "1 while no run has finished within three of the job's intervals or its schedule is invalid, else 0")
                .register(meters);
        // Counters exist from registration, so a rate over them never starts at the first event.
        COUNTERS.forEach(name -> meters.counter(name, JOB_ID, job.id()));
    }

    /** Stops tracking a job and drops its meters. A job id that was never registered is a no-op. */
    void deregister(String jobId) {
        byId.remove(jobId);
        for (String name : List.of("rahdar.job.lag", "rahdar.job.degraded")) {
            meters.find(name).tag(JOB_ID, jobId).meters().forEach(meters::remove);
        }
        COUNTERS.forEach(name -> meters.find(name).tag(JOB_ID, jobId).meters().forEach(meters::remove));
    }

    /** The job's status now, or {@code null} once it is deregistered. */
    JobStatus get(String jobId) {
        return byId.get(jobId);
    }

    private void update(String jobId, UnaryOperator<JobStatus> change) {
        byId.computeIfPresent(jobId, (k, s) -> change.apply(s));
    }

    void started(String jobId, Instant at) {
        update(jobId, s -> s.started(at));
    }

    void succeeded(String jobId, Instant at) {
        update(jobId, s -> s.succeeded(at));
    }

    void ended(String jobId, Instant at, JobStatus.JobOutcome outcome, String error) {
        update(jobId, s -> s.ended(at, outcome, error));
        String counter =
                switch (outcome) {
                    case TIMED_OUT -> "rahdar.job.timeouts";
                    case LOCK_LOST -> "rahdar.job.lock_lost";
                    default -> null;
                };
        if (counter != null) {
            meters.counter(counter, JOB_ID, jobId).increment();
        }
    }

    void overran(String jobId) {
        update(jobId, JobStatus::overran);
        meters.counter("rahdar.job.overruns", JOB_ID, jobId).increment();
    }

    void scheduled(String jobId, Instant next, Duration gap) {
        update(jobId, s -> s.scheduled(next, gap.isNegative() ? Duration.ZERO : gap));
    }

    void invalid(String jobId, String cause) {
        update(jobId, s -> s.invalid(cause));
    }

    void skippedElsewhere(String jobId, Instant at) {
        update(jobId, s -> s.skippedElsewhere(at));
    }

    private double lagSeconds(String jobId) {
        JobStatus status = byId.get(jobId);
        Duration lag = status == null ? null : status.lag(Instant.now());
        return lag == null ? Double.NaN : lag.toMillis() / 1000.0;
    }

    private double degraded(String jobId) {
        JobStatus status = byId.get(jobId);
        return status != null && status.degraded(Instant.now()) ? 1 : 0;
    }

    /** Every registered job, ordered by id. */
    public List<JobStatus> all() {
        return byId.values().stream()
                .sorted(Comparator.comparing(JobStatus::id))
                .toList();
    }
}
