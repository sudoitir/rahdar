package io.github.sudoitir.rahdar.feature.messages;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.platform.broker.IdentityBasis;
import io.github.sudoitir.rahdar.platform.broker.WholeClusterOperations;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The purge of a queue of a cluster read as one, over the whole cluster (ADR-0224): its preview and the run that the
 * preview's etag guards. The broker's errors pass through untranslated; {@link MessageService} audits and words them.
 */
@Component
public class WholeClusterPurge {

    private static final Operation PURGE = new Operation.Purge();

    private final WholeClusterOperations operations;

    WholeClusterPurge(WholeClusterOperations operations) {
        this.operations = operations;
    }

    /** What a purge would remove now, the etag that holds that reading, and what the etag tracks. */
    record Estimate(long messages, String etag, IdentityBasis basis) {}

    Estimate estimate(UUID clusterId, ResourceId id) {
        Preview preview = operations.preview(clusterId, id, PURGE);
        return new Estimate(
                WholeClusterOperations.messages(preview).orElse(0L), preview.etag(), operations.basis(preview));
    }

    /** Runs the purge the estimate previewed: what the broker removed, or the estimate when it does not say. */
    long run(UUID clusterId, ResourceId id, Estimate estimate) {
        OperationResult result = operations.execute(clusterId, id, PURGE, estimate.etag());
        return result.affected().isPresent() ? result.affected().getAsLong() : estimate.messages();
    }
}
