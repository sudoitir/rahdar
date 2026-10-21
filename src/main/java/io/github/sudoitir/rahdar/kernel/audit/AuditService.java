package io.github.sudoitir.rahdar.kernel.audit;

import io.github.sudoitir.rahdar.kernel.audit.internal.AuditRowWriter;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.core.PartitionProvisioner;
import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ScopeHierarchy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the audit trail for mutating actions (non-negotiable #3, ADR-0078).
 *
 * <p>{@link #begin} commits a {@code PENDING} row on its own, so it is durable before the broker
 * call it describes; pass the returned event to {@link #succeed}, {@link #fail} or {@link #finish},
 * which commit the outcome on their own too. A broker is not a participant in the caller's
 * transaction, so neither half may depend on that transaction committing.
 *
 * <p>An event begun while {@link AuditScope#PARENT} is bound names that event as its parent.
 *
 * <p>When the caller's transaction rolls back before an outcome was recorded, the row is failed
 * with that reason, so an action that did not happen never leaves a row that looks in flight.
 */
@Service
@PluginApi
@RequiredArgsConstructor
public class AuditService {

    static final String ROLLED_BACK = "The action was rolled back before its outcome was recorded.";

    private final AuditRowWriter writer;
    private final PartitionProvisioner partitions;
    private final AuditEventRepository events;
    private final ObjectMapper mapper;
    private final ScopeHierarchy clusters;
    /** Resolved lazily: the content policy that implements one itself writes audit rows. All apply, in order. */
    private final ObjectProvider<AuditParamsFilter> paramsFilter;

    public AuditEvent begin(
            Actor actor,
            String action,
            String targetType,
            String targetName,
            UUID clusterId,
            UUID nodeId,
            Map<String, ?> params,
            boolean dryRun) {
        AuditEventEntity pending =
                entity(actor, action, new Subject(targetType, targetName, clusterId, nodeId), params, dryRun);
        AuditEventEntity row = insert(pending);
        failIfCallerRollsBack(row.getId());
        return row;
    }

    /**
     * Record a request that was refused for lack of a permission, as one {@code REFUSED} event standing for
     * one refusal. {@link #recount} raises its count when the same refusal is repeated.
     */
    public AuditEvent refused(
            Actor actor,
            String action,
            String targetType,
            String targetName,
            UUID clusterId,
            Map<String, ?> params,
            String reason) {
        AuditEventEntity entity =
                entity(actor, action, new Subject(targetType, targetName, clusterId, null), params, false);
        entity.markRefused(reason, 1);
        return insert(entity);
    }

    /** The request this event was opened for was refused: {@code REFUSED}, not left {@code PENDING}. */
    public void refuse(AuditEvent event, String reason) {
        writer.refuse(event.getId(), reason, 1);
    }

    /** A refused event stands for {@code count} identical refusals now. */
    public void recount(AuditEvent event, long count) {
        writer.recount(event.getId(), count);
    }

    /**
     * The row commits on its own, so a month without a partition is created and the insert tried once more
     * (ADR-0193). Rahdar makes the partitions of the coming months ahead, so this is a missed maintenance run.
     */
    private AuditEventEntity insert(AuditEventEntity row) {
        return partitions.retryingNoPartition(() -> writer.insert(row));
    }

    /** What an event is about, as the caller names it. */
    private record Subject(String targetType, String targetName, UUID clusterId, UUID nodeId) {}

    private AuditEventEntity entity(
            Actor actor, String action, Subject subject, Map<String, ?> params, boolean dryRun) {
        Map<String, ?> written = params;
        if (AuditScope.APPROVAL.isBound()) {
            Map<String, Object> withApproval = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
            withApproval.put("approval", AuditScope.APPROVAL.get());
            written = withApproval;
        }
        if (written != null && !written.isEmpty()) {
            for (AuditParamsFilter filter : paramsFilter.orderedStream().toList()) {
                written = filter.filter(written);
            }
        }
        String paramsJson = (written == null || written.isEmpty()) ? null : mapper.writeValueAsString(written);
        Actor a = actor == null ? Actor.ofSystem() : actor;
        AuditEventEntity entity = new AuditEventEntity(
                action,
                a,
                new AuditEventEntity.Target(
                        subject.targetType(),
                        subject.targetName(),
                        subject.clusterId(),
                        subject.clusterId() == null ? null : clusters.clusterName(subject.clusterId()),
                        subject.nodeId()),
                paramsJson,
                dryRun);
        entity.attachParent(AuditScope.PARENT.isBound() ? AuditScope.PARENT.get() : null);
        return entity;
    }

    /**
     * The target names Rahdar created and has no record of removing, for one target type on a
     * cluster: the latest non-preview event per name is a creation, where only a successful
     * deletion counts. The routing view's ownership (ADR-0065 D2).
     */
    public Set<String> ownedTargetNames(UUID clusterId, String targetType, String createdAction, String deletedAction) {
        return Set.copyOf(events.findOwnedTargetNames(clusterId, targetType, createdAction, deletedAction));
    }

    /** An event by id, to record the outcome of work that outlived the thread which began it (ADR-0093). */
    public Optional<AuditEvent> byId(Long id) {
        return events.findById(id).map(AuditEvent.class::cast);
    }

    public void succeed(AuditEvent event, long affectedCount) {
        writer.succeed(event.getId(), affectedCount);
    }

    public void fail(AuditEvent event, String error) {
        writer.fail(event.getId(), error);
    }

    /** An action that failed after affecting some of its targets; the count is recorded with the error. */
    public void failPartial(AuditEvent event, long affectedCount, String error) {
        writer.failPartial(event.getId(), affectedCount, error);
    }

    /**
     * Record the outcome of a cluster-wide fan-out (ADR-0049 D4). One row carries
     * the whole command, with {@code detail} holding the per-node result, so a
     * partially applied command is reconstructable afterwards.
     *
     * <p>A command that failed on any node is recorded as failed even though other
     * nodes applied — {@code detail} says which. Recording it as success because
     * most of it worked is the lie the audit log exists to prevent.
     */
    public void finish(AuditEvent event, boolean anyFailed, long affectedCount, String error, Object detail) {
        writer.finish(
                event.getId(),
                anyFailed,
                affectedCount,
                error,
                detail == null ? null : mapper.writeValueAsString(detail));
    }

    private void failIfCallerRollsBack(Long id) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    writer.failIfPending(id, ROLLED_BACK);
                }
            }
        });
    }
}
