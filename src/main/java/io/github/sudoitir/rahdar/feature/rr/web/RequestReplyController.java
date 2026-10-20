package io.github.sudoitir.rahdar.feature.rr.web;

import io.github.sudoitir.rahdar.feature.rr.FlowQuery;
import io.github.sudoitir.rahdar.feature.rr.RequestReplyService;
import io.github.sudoitir.rahdar.feature.rr.RrMetrics;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.CreateExpectationRequest;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.ExpectationView;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.FlowView;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.RrDiagnosticsView;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.StatsResponse;
import io.github.sudoitir.rahdar.feature.rr.web.RrViews.UpdateExpectationRequest;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Request-reply tracing (request-reply-tracing spec): expectations, the
 * reconstructed flows, and their latency/coverage stats. Capability gating
 * (no NOTIFICATIONS, or no resolvable Core URL) is surfaced the same way as
 * {@code events} — via the cluster's {@code capabilities.notifications} on
 * {@code GET /clusters/{id}}, not a per-endpoint check here.
 */
@RestController
@RequestMapping("/clusters/{clusterId}/rr")
@RequiredArgsConstructor
public class RequestReplyController {

    private final RequestReplyService requestReply;
    private final RrMetrics metrics;

    @GetMapping("/expectations")
    public PagedView<ExpectationView> listExpectations(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size).paginate(requestReply.list(clusterId), null);
    }

    @PostMapping("/expectations")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpectationView createExpectation(
            @PathVariable UUID clusterId, @Valid @RequestBody CreateExpectationRequest request) {
        return requestReply.create(clusterId, request);
    }

    @PutMapping("/expectations/{expectationId}")
    public ExpectationView updateExpectation(
            @PathVariable UUID clusterId,
            @PathVariable UUID expectationId,
            @Valid @RequestBody UpdateExpectationRequest request) {
        return requestReply.update(clusterId, expectationId, request);
    }

    @DeleteMapping("/expectations/{expectationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExpectation(@PathVariable UUID clusterId, @PathVariable UUID expectationId) {
        requestReply.delete(clusterId, expectationId);
    }

    @GetMapping("/flows")
    public PagedView<FlowView> flows(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        ResourceQuery paging = ResourceQuery.ofPage(page, size);
        return requestReply.flowPage(
                clusterId, new FlowQuery(state, destination, correlationId, from, to, paging.page(), paging.size()));
    }

    @GetMapping("/flows/{flowId}")
    public FlowView flow(@PathVariable UUID clusterId, @PathVariable UUID flowId) {
        return requestReply.flow(clusterId, flowId);
    }

    /**
     * Why tracing is or is not producing flows here — the sampler's own account of
     * its last tick, plus the ranked reasons an operator should check.
     */
    @GetMapping("/diagnostics")
    public RrDiagnosticsView diagnostics(@PathVariable UUID clusterId) {
        return requestReply.diagnostics(clusterId);
    }

    @GetMapping("/stats")
    public StatsResponse stats(@PathVariable UUID clusterId, @RequestParam(defaultValue = "PT15M") String window) {
        return metrics.stats(clusterId, Duration.parse(window));
    }
}
