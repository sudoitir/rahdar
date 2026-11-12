package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateDestinationRequest;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets.Target;
import java.util.UUID;

/**
 * A cluster read as one whose create or delete must not go through the shared address and queue commands.
 * The shared commands re-preview and apply under the etag they just read, so a caller can skip the etag the
 * operator confirmed. A system that needs that confirmation supplies one of these.
 */
public interface ReadAsOneResource {

    /** Whether this handler owns {@code kind}, such as {@code kafka.topic}. */
    boolean supports(String kind);

    /** Create a destination of this kind. A dry run previews and changes nothing. */
    Attempt<LifecycleOutcome> createDestination(UUID clusterId, CreateDestinationRequest request, boolean dryRun);

    /**
     * Delete a destination of this kind. A real run without the preview's etag and fingerprint is refused before
     * the broker is asked.
     */
    Attempt<LifecycleOutcome> deleteDestination(
            UUID clusterId, Target target, boolean dryRun, boolean override, String etag, String fingerprint);

    /**
     * Delete a subscription of this kind. {@code disconnectConsumers} is not a parameter: this path never removes
     * members in order to delete.
     */
    Attempt<LifecycleOutcome> deleteSubscription(UUID clusterId, Target target, boolean dryRun, String etag);
}
