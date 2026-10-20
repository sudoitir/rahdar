package io.github.sudoitir.rahdar.feature.queues.web;

import io.github.sudoitir.rahdar.feature.queues.LifecycleRequests.CreateAddressRequest;
import io.github.sudoitir.rahdar.feature.queues.LifecycleRequests.CreateQueueRequest;
import io.github.sudoitir.rahdar.feature.queues.LifecycleRequests.UpdateQueueRequest;
import io.github.sudoitir.rahdar.feature.queues.QueueLifecycleService;
import io.github.sudoitir.rahdar.kernel.gate.HeldResponse;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleViews.LifecycleOutcomeView;
import io.github.sudoitir.rahdar.system.artemis.QueueConfigurationReader;
import io.github.sudoitir.rahdar.system.artemis.QueueConfigurationReader.QueueConfiguration;
import jakarta.validation.Valid;
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
 * Create, destroy, reconfigure, pause and resume queues, and create and destroy
 * addresses, across a whole cluster (ADR-0049).
 *
 * <p>Every route names a cluster, never a node: Artemis cluster nodes each own
 * their own queues, so the operation fans out to every live node and the response
 * is a per-node outcome list. Every mutating route takes {@code ?dryRun=true},
 * which reports the target nodes — and, for a destroy, the messages that would be
 * lost on each — without touching any broker. A destroy over the
 * {@code safety.bulk-cap} is a {@code 422 bulk-cap-exceeded} unless
 * {@code ?override=true}, because destroying a queue destroys its messages and
 * that is a bulk destructive operation whatever the endpoint is called.
 *
 * <p>Authorization goes through {@code ClusterAccessGuard}, so a caller with no
 * grant on the cluster gets a 404 and cannot learn that the cluster exists.
 */
@RestController
@RequestMapping("/clusters/{clusterId}")
@RequiredArgsConstructor
public class QueueLifecycleController {

    private final QueueLifecycleService lifecycle;
    private final QueueConfigurationReader configurations;

    // ---- queues ---------------------------------------------------------

    @PostMapping("/queues")
    public LifecycleOutcomeView createQueue(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody CreateQueueRequest request) {
        return respond(lifecycle.createQueue(clusterId, request, dryRun));
    }

    /**
     * What the queue is configured as on each node that has it. An update replaces the
     * broker's whole configuration, so this is what the edit form shows before changing
     * one field of it — and what says, afterwards, that the change took.
     */
    @GetMapping("/queues/{queueName}/configuration")
    public QueueConfiguration configuration(@PathVariable UUID clusterId, @PathVariable String queueName) {
        return configurations.read(clusterId, queueName);
    }

    @PatchMapping("/queues/{queueName}")
    public LifecycleOutcomeView updateQueue(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody UpdateQueueRequest request) {
        return respond(lifecycle.updateQueue(clusterId, queueName, request, dryRun));
    }

    /**
     * Delete a queue (ADR-0084). A node where the queue has consumers is refused unless
     * {@code disconnectConsumers} is set, and the diverts that forward only into this queue
     * are removed with it; the preview names both.
     */
    @HeldResponse
    @DeleteMapping("/queues/{queueName}")
    public LifecycleOutcomeView deleteQueue(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean override,
            @RequestParam(defaultValue = "false") boolean disconnectConsumers) {
        return respond(lifecycle.deleteQueue(clusterId, queueName, dryRun, override, disconnectConsumers));
    }

    @PostMapping("/queues/{queueName}/pause")
    public LifecycleOutcomeView pauseQueue(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(lifecycle.setPaused(clusterId, queueName, true, dryRun));
    }

    @PostMapping("/queues/{queueName}/resume")
    public LifecycleOutcomeView resumeQueue(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(lifecycle.setPaused(clusterId, queueName, false, dryRun));
    }

    @PostMapping("/queues/{queueName}/reset-counter")
    public LifecycleOutcomeView resetCounter(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(lifecycle.resetCounter(clusterId, queueName, dryRun));
    }

    // ---- addresses ------------------------------------------------------

    @PostMapping("/addresses")
    public LifecycleOutcomeView createAddress(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody CreateAddressRequest request) {
        return respond(lifecycle.createAddress(clusterId, request, dryRun));
    }

    /**
     * Delete an address. Force-free by design (D8): one that still has queues bound
     * is refused with a message naming them, and the operator destroys those
     * explicitly. One click that destroys an unbounded amount of data with no
     * per-queue count in the confirmation is not a safe default.
     */
    @HeldResponse
    @DeleteMapping("/addresses/{address}")
    public LifecycleOutcomeView deleteAddress(
            @PathVariable UUID clusterId,
            @PathVariable String address,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(lifecycle.deleteAddress(clusterId, address, dryRun));
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
