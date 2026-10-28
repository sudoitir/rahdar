package io.github.sudoitir.rahdar.kernel.jobs.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.jobs.JobStatus;
import io.github.sudoitir.rahdar.kernel.jobs.JobStatuses;
import io.github.sudoitir.rahdar.kernel.jobs.ScheduledJob;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/system/jobs}: every background job's status (operational-health spec). */
@RestController
@RequiredArgsConstructor
public class JobsController {

    private final JobStatuses statuses;

    public record JobStatusView(
            @Schema(requiredMode = REQUIRED) String id,

            @Schema(requiredMode = REQUIRED, description = "The module that owns the job.")
            String featureId,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "INSTANCE runs on every instance; INSTALLATION on one instance per tick.")
            ScheduledJob.Scope scope,

            @Schema(nullable = true) Instant lastStart,
            @Schema(nullable = true) Instant lastEnd,

            @Schema(nullable = true, description = "How the most recent run ended; absent until one has.")
            JobStatus.JobOutcome lastOutcome,

            @Schema(
                    nullable = true,
                    description = "Why the most recent run did not succeed; absent once a later run succeeds.")
            String lastError,

            @Schema(requiredMode = REQUIRED) long runs,

            @Schema(requiredMode = REQUIRED, description = "Runs that did not succeed, for any reason.")
            long failures,

            @Schema(requiredMode = REQUIRED, description = "Runs interrupted for exceeding the job's timeout.")
            long timeouts,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Runs interrupted because the installation-wide lock could not be extended.")
            long lockLost,

            @Schema(requiredMode = REQUIRED, description = "Runs that lasted past the time the next was due.")
            long overruns,

            @Schema(nullable = true, description = "When the scheduler next intends to start it.")
            Instant nextRun,

            @Schema(
                    nullable = true,
                    description = "When this instance last found an installation-wide run held by another instance.")
            Instant lastSkippedElsewhere,

            @Schema(
                    nullable = true,
                    description = "Why the job's schedule cannot be read; the job does not run until it can.")
            String invalidSchedule,

            @Schema(
                    requiredMode = REQUIRED,
                    description =
                            "Whether the schedule is invalid, or no run has finished, here or elsewhere, within three of the job's intervals.")
            boolean degraded) {

        static JobStatusView of(JobStatus s, Instant now) {
            return new JobStatusView(
                    s.id(),
                    s.featureId(),
                    s.scope(),
                    s.lastStart(),
                    s.lastEnd(),
                    s.lastOutcome(),
                    s.lastError(),
                    s.runs(),
                    s.failures(),
                    s.timeouts(),
                    s.lockLost(),
                    s.overruns(),
                    s.nextRun(),
                    s.lastSkippedElsewhere(),
                    s.invalidSchedule(),
                    s.degraded(now));
        }
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.kernel.security.SettingsPermissions).SETTINGS_READ)")
    @GetMapping("/system/jobs")
    public PagedView<JobStatusView> jobs(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        Instant now = Instant.now();
        return ResourceQuery.ofPage(page, size)
                .paginate(
                        statuses.all().stream()
                                .map(s -> JobStatusView.of(s, now))
                                .toList(),
                        null);
    }
}
