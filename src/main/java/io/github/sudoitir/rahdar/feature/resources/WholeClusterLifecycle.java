package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.github.sudoitir.rahdar.platform.broker.IdentityBasis;
import io.github.sudoitir.rahdar.platform.broker.WholeClusterOperations;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Creating and deleting a resource of a cluster read as one (ADR-0224): the driver changes the cluster as a whole,
 * by resource id, so there is one outcome and no node to fan out to. A dry run is the driver's preview, with its
 * estimate and the basis of its etag. A real run opens the audit row, previews again, executes under the etag it
 * just previewed, and closes the row; a run whose etag no longer matches is refused as stale.
 */
@Component
@RequiredArgsConstructor
class WholeClusterLifecycle {

    /** The one entry of a whole-cluster outcome, which names no node. */
    static final String WHOLE_CLUSTER = "whole cluster";

    private final WholeClusterOperations operations;
    private final AuditService audit;
    private final ActorResolver actors;
    private final SettingsService settings;

    /** What deleting the resource would take, and the state that was counted, for the gate's estimate. */
    record Reach(Optional<Long> messages, String etag, IdentityBasis basis) {}

    Reach reachOfDelete(UUID clusterId, ResourceId id, boolean disconnectConsumers) {
        try {
            Preview preview = operations.preview(clusterId, id, new Operation.Delete(false, disconnectConsumers));
            return new Reach(WholeClusterOperations.messages(preview), preview.etag(), operations.basis(preview));
        } catch (BrokerException e) {
            throw failure(e, id);
        }
    }

    Attempt<LifecycleOutcome> delete(
            UUID clusterId,
            ResourceId id,
            LifecycleKind kind,
            boolean dryRun,
            boolean override,
            boolean disconnectConsumers,
            Runnable signal) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", id.format());
        params.put("disconnectConsumers", disconnectConsumers);
        AuditEvent event = begin(clusterId, kind, id, params, dryRun);
        long cap = settings.intValue(BrokerSettings.BULK_CAP);
        try {
            Operation operation = new Operation.Delete(false, disconnectConsumers);
            Preview preview = operations.preview(clusterId, id, operation);
            Optional<Long> messages = WholeClusterOperations.messages(preview);
            IdentityBasis basis = operations.basis(preview);
            long total = messages.orElse(0L);
            boolean overCap = total > cap;
            if (dryRun) {
                NodeOutcome node = new NodeOutcome(
                        null, WHOLE_CLUSTER, NodeStatus.WOULD_APPLY, messages.orElse(null), basis.note());
                audit.finish(event, false, total, null, List.of(node));
                return ok(true, cap, overCap, node, basis);
            }
            if (overCap && !override) {
                audit.finish(event, true, total, "Over the safety cap (" + total + " > " + cap + ").", List.of());
                throw new BulkCapExceededException(total, cap);
            }
            OperationResult result = operations.execute(clusterId, id, operation, preview.etag());
            Long affected = result.affected().isPresent()
                    ? Long.valueOf(result.affected().getAsLong())
                    : messages.orElse(null);
            NodeOutcome node = new NodeOutcome(
                    null,
                    WHOLE_CLUSTER,
                    result.alreadyInState() ? NodeStatus.ALREADY : NodeStatus.APPLIED,
                    affected,
                    null);
            return finished(event, signal, cap, overCap, node, basis);
        } catch (BrokerException e) {
            return refused(event, dryRun, cap, e);
        } catch (BulkCapExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    Attempt<LifecycleOutcome> create(
            UUID clusterId,
            ResourceId id,
            LifecycleKind kind,
            Attributes attributes,
            Map<String, Object> params,
            boolean dryRun,
            Runnable signal) {
        AuditEvent event = begin(clusterId, kind, id, params, dryRun);
        long cap = settings.intValue(BrokerSettings.BULK_CAP);
        try {
            Operation operation = new Operation.Create(attributes);
            if (dryRun) {
                Preview preview = operations.preview(clusterId, id, operation);
                NodeOutcome node = new NodeOutcome(null, WHOLE_CLUSTER, NodeStatus.WOULD_APPLY, null, null);
                audit.finish(event, false, 0, null, List.of(node));
                return ok(true, cap, false, node, operations.basis(preview));
            }
            OperationResult result = operations.execute(clusterId, id, operation, null);
            NodeOutcome node = new NodeOutcome(
                    null, WHOLE_CLUSTER, result.alreadyInState() ? NodeStatus.ALREADY : NodeStatus.APPLIED, null, null);
            return finished(event, signal, cap, false, node, IdentityBasis.INCARNATION);
        } catch (BrokerException e) {
            return refused(event, dryRun, cap, e);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    private AuditEvent begin(
            UUID clusterId, LifecycleKind kind, ResourceId id, Map<String, Object> params, boolean dryRun) {
        return audit.begin(
                actors.resolve(), kind.auditName(), kind.targetType(), id.name(), clusterId, null, params, dryRun);
    }

    private Attempt<LifecycleOutcome> finished(
            AuditEvent event, Runnable signal, long cap, boolean overCap, NodeOutcome node, IdentityBasis basis) {
        audit.finish(event, false, node.affected() == null ? 0 : node.affected(), null, List.of(node));
        signal.run();
        return ok(false, cap, overCap, node, basis);
    }

    /** The driver refused or could not do it: the one outcome is a failure carrying its words, and the row says so. */
    private Attempt<LifecycleOutcome> refused(AuditEvent event, boolean dryRun, long cap, BrokerException e) {
        String reason = BrokerFailures.translate(e).getMessage();
        NodeOutcome node = NodeOutcome.failed(null, WHOLE_CLUSTER, reason);
        audit.finish(event, true, 0, reason, List.of(node));
        return ok(dryRun, cap, false, node, IdentityBasis.INCARNATION);
    }

    private static Attempt<LifecycleOutcome> ok(
            boolean dryRun, long cap, boolean overCap, NodeOutcome node, IdentityBasis basis) {
        return new Attempt.Ok<>(new LifecycleOutcome(dryRun, cap, overCap, List.of(node), basis));
    }

    private static RuntimeException failure(BrokerException e, ResourceId id) {
        return ErrorReasons.RESOURCE_GONE.equals(e.reason())
                ? new NotFoundException("resource", id.format())
                : BrokerFailures.translate(e);
    }
}
