package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Previews and runs an operation on a resource of a cluster read as one, by id, over the whole cluster (ADR-0224).
 * It has no gate and writes no audit row: the feature that calls it owns both. A refusal is the driver's
 * {@link BrokerException}, which the caller translates; a run whose etag no longer matches is a
 * {@link StalePreviewException}, a 412.
 */
@Component
@RequiredArgsConstructor
public class WholeClusterOperations {

    private final BrokerGateway gateway;

    /** What the operation would do to the resource, its etag over the whole cluster, and what the etag holds. */
    public Preview preview(UUID clusterId, ResourceId id, Operation operation) {
        return gateway.cluster(clusterId).operations().preview(id, operation, Scope.whole());
    }

    /**
     * Runs the operation on the whole cluster. With an {@code etag} it runs only on what that preview saw; a
     * {@code null} etag runs unconditionally, as a create does.
     */
    public OperationResult execute(UUID clusterId, ResourceId id, Operation operation, String etag) {
        Precondition precondition = etag == null ? Precondition.none() : Precondition.ifMatch(etag);
        try {
            return gateway.cluster(clusterId)
                    .operations()
                    .execute(id, operation, Scope.whole(), precondition)
                    .orThrow();
        } catch (BrokerException e) {
            if (ErrorReasons.PRECONDITION_FAILED.equals(e.reason()) && etag != null) {
                throw new StalePreviewException(id.format() + " changed since the preview. Preview again.");
            }
            throw e;
        }
    }

    /** What the preview's etag holds. */
    public IdentityBasis basis(Preview preview) {
        return IdentityBasis.of(preview.basis());
    }

    /** The preview's count when it counts messages, else empty: a count of topics is not a count of messages. */
    public static Optional<Long> messages(Preview preview) {
        if (!preview.estimate().unit().contains("message")) {
            return Optional.empty();
        }
        return switch (preview.estimate().count()) {
            case Count.Exact(var n) -> Optional.of(n);
            case Count.Estimate(var n) -> Optional.of(n);
            case Count.Unavailable(var _) -> Optional.empty();
        };
    }
}
