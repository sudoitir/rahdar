package io.github.sudoitir.rahdar.feature.resources.web;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateDestinationRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateSubscriptionRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.UpdateResourceRequest;
import io.github.sudoitir.rahdar.feature.resources.QueueLifecycleService;
import io.github.sudoitir.rahdar.feature.resources.SubscriptionConfigurationReader;
import io.github.sudoitir.rahdar.feature.resources.SubscriptionConfigurationReader.SubscriptionConfiguration;
import io.github.sudoitir.rahdar.feature.resources.TruncateService;
import io.github.sudoitir.rahdar.feature.resources.TruncateService.Cut;
import io.github.sudoitir.rahdar.feature.resources.TruncateService.TruncateEffect;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.gate.HeldResponse;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleViews.LifecycleOutcomeView;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets.Target;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Create, destroy, reconfigure, pause and resume subscriptions, and create and destroy
 * destinations, across a whole cluster (ADR-0049).
 *
 * <p>A resource is addressed by its kind and its path, {@code /resources/{kind}/{path}}, and a custom
 * method is a literal suffix on the path segment ({@code :pause}). Every route names a cluster, never a
 * node: cluster nodes each own their own subscriptions, so the operation fans out to every live node and
 * the response is a per-node outcome list. Every mutating route takes {@code ?dryRun=true},
 * which reports the target nodes — and, for a destroy, the messages that would be
 * lost on each — without touching any broker. A destroy over the
 * {@code safety.bulk-cap} is a {@code 422 bulk-cap-exceeded} unless
 * {@code ?override=true}, because destroying a subscription destroys its messages and
 * that is a bulk destructive operation whatever the endpoint is called.
 *
 * <p>Authorization goes through {@code ClusterAccessGuard}, so a caller with no
 * grant on the cluster gets a 404 and cannot learn that the cluster exists.
 */
@RestController
@RequestMapping("/clusters/{clusterId}")
@RequiredArgsConstructor
public class ResourceLifecycleController {

    private static final String UNSUPPORTED = "unsupported-on-kind";

    private final QueueLifecycleService lifecycle;
    private final SubscriptionConfigurationReader configurations;
    private final ResourceTargets targets;
    private final TruncateService truncates;

    // ---- create ---------------------------------------------------------

    @PostMapping("/subscriptions")
    public LifecycleOutcomeView createSubscription(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody CreateSubscriptionRequest request) {
        targets.requireKind(clusterId, request.kind(), ResourceClass.SUBSCRIPTION);
        return respond(lifecycle.createQueue(clusterId, request, dryRun));
    }

    @PostMapping("/destinations")
    public LifecycleOutcomeView createDestination(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody CreateDestinationRequest request) {
        targets.requireKind(clusterId, request.kind(), ResourceClass.DESTINATION);
        return respond(lifecycle.createAddress(clusterId, request, dryRun));
    }

    // ---- one resource ---------------------------------------------------

    /**
     * What the subscription is configured as on each node that has it. An update replaces the
     * broker's whole configuration, so this is what the edit form shows before changing
     * one field of it — and what says, afterwards, that the change took.
     */
    /**
     * Cuts the oldest records off a destination's log: before an offset in each named partition, or before a time.
     * {@code ?dryRun=true} answers the effect, which names every group that loses records it has not read, and cuts
     * nothing. A compacted topic is refused with the driver's reason.
     */
    @HeldResponse
    @PostMapping("/destinations/{kind}/{path}:truncate")
    public TruncateEffect truncate(
            @PathVariable UUID clusterId,
            @PathVariable String kind,
            @PathVariable String path,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody TruncateRequest request) {
        return truncates.truncate(clusterId, kind, path, request.before().toCut(), request.etag(), dryRun);
    }

    /** {@code before} is {@code {offsets: {"0": 120}}} or {@code {time: "2026-10-28T09:30:00Z"}}. */
    public record TruncateRequest(
            @NotNull @Valid Before before,

            @Schema(description = "The dry run's etag; a real run needs it, and is refused as stale if the log moved.")
            String etag) {

        public record Before(Map<Integer, Long> offsets, Instant time) {

            Cut toCut() {
                return new Cut(offsets, time);
            }
        }
    }

    @GetMapping("/resources/{kind}/{path}")
    public SubscriptionConfiguration configuration(
            @PathVariable UUID clusterId, @PathVariable String kind, @PathVariable String path) {
        Target target = targets.require(clusterId, kind, path, ResourceClass.SUBSCRIPTION);
        return configurations.read(clusterId, target.id());
    }

    @PatchMapping("/resources/{kind}/{path}")
    public LifecycleOutcomeView update(
            @PathVariable UUID clusterId,
            @PathVariable String kind,
            @PathVariable String path,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody UpdateResourceRequest request) {
        Target target = targets.require(clusterId, kind, path, ResourceClass.SUBSCRIPTION);
        return respond(lifecycle.updateQueue(clusterId, target.name(), request, dryRun));
    }

    /**
     * Delete a subscription or a destination (ADR-0084). A subscription on a node where it has consumers is
     * refused unless {@code disconnectConsumers} is set, and the routes that forward only into it are removed
     * with it; the preview names both. A destination is force-free by design (D8): one that still has
     * subscriptions bound is refused with a message naming them, and the operator destroys those explicitly.
     * One click that destroys an unbounded amount of data with no per-subscription count in the confirmation
     * is not a safe default.
     */
    @HeldResponse
    @DeleteMapping("/resources/{kind}/{path}")
    public LifecycleOutcomeView delete(
            @PathVariable UUID clusterId,
            @PathVariable String kind,
            @PathVariable String path,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean override,
            @RequestParam(defaultValue = "false") boolean disconnectConsumers) {
        Target target = targets.require(clusterId, kind, path, ResourceClass.SUBSCRIPTION, ResourceClass.DESTINATION);
        return respond(
                switch (target.resourceClass()) {
                    case SUBSCRIPTION ->
                        lifecycle.deleteQueue(clusterId, target.name(), dryRun, override, disconnectConsumers);
                    case DESTINATION -> lifecycle.deleteAddress(clusterId, target.name(), dryRun);
                    default -> throw new ConflictException(UNSUPPORTED, "This kind cannot be deleted here.");
                });
    }

    @PostMapping("/resources/{kind}/{path}:pause")
    public LifecycleOutcomeView pause(
            @PathVariable UUID clusterId,
            @PathVariable String kind,
            @PathVariable String path,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        Target target = targets.require(clusterId, kind, path, ResourceClass.SUBSCRIPTION);
        return respond(lifecycle.setPaused(clusterId, target.name(), true, dryRun));
    }

    @PostMapping("/resources/{kind}/{path}:resume")
    public LifecycleOutcomeView resume(
            @PathVariable UUID clusterId,
            @PathVariable String kind,
            @PathVariable String path,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        Target target = targets.require(clusterId, kind, path, ResourceClass.SUBSCRIPTION);
        return respond(lifecycle.setPaused(clusterId, target.name(), false, dryRun));
    }

    @PostMapping("/resources/{kind}/{path}:reset-counters")
    public LifecycleOutcomeView resetCounters(
            @PathVariable UUID clusterId,
            @PathVariable String kind,
            @PathVariable String path,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        Target target = targets.require(clusterId, kind, path, ResourceClass.SUBSCRIPTION);
        return respond(lifecycle.resetCounter(clusterId, target.name(), dryRun));
    }

    // ---- mapping --------------------------------------------------------

    private static LifecycleOutcomeView respond(Attempt<LifecycleOutcome> attempt) {
        LifecycleOutcome outcome =
                switch (attempt) {
                    case Attempt.Ok<LifecycleOutcome>(var value) -> value;
                    case Attempt.Failed<LifecycleOutcome>(var kind, var detail) ->
                        throw new BrokerConnectionException(kind, detail);
                };
        return LifecycleOutcomeView.of(outcome);
    }
}
