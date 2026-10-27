package io.github.sudoitir.rahdar.kernel.audit.internal;

import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every audit write commits on its own (ADR-0078). A separate bean so the propagation applies:
 * the pending row must be durable before the broker call it describes, whatever transaction the
 * caller is in, and an outcome must not depend on the caller's transaction committing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRowWriter {

    private final AuditEventRepository events;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEventEntity insert(AuditEventEntity row) {
        return events.save(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(Long id, long affectedCount) {
        pending(id).ifPresent(row -> row.markSuccess(affectedCount));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refuse(Long id, String reason, long count) {
        pending(id).ifPresent(row -> row.markRefused(reason, count));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recount(Long id, long count) {
        events.findById(id).ifPresent(row -> row.markRefused(row.getError(), count));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long id, String error) {
        pending(id).ifPresent(row -> row.markFailure(error));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inDoubt(Long id, String reason) {
        pending(id).ifPresent(row -> row.markInDoubt(reason));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failPartial(Long id, long affectedCount, String error) {
        pending(id).ifPresent(row -> row.markFailure(error, affectedCount));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long id, boolean anyFailed, long affectedCount, String error, String detail) {
        pending(id).ifPresent(row -> {
            row.attachOutcomeDetail(detail);
            if (anyFailed) {
                row.markFailure(error);
            } else {
                row.markSuccess(affectedCount);
            }
        });
    }

    /** Fail a row only if nothing recorded its outcome — used when the caller's own transaction rolled back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failIfPending(Long id, String error) {
        events.findById(id).filter(row -> "PENDING".equals(row.getOutcome())).ifPresent(row -> row.markFailure(error));
    }

    /**
     * The row, while it has no outcome yet. An outcome is recorded once: the database refuses rewriting a
     * finished row, so a second outcome is logged and dropped instead of failing the caller.
     */
    private Optional<AuditEventEntity> pending(Long id) {
        Optional<AuditEventEntity> row = events.findById(id);
        if (row.isPresent() && !"PENDING".equals(row.get().getOutcome())) {
            log.warn(
                    "Audit event {} already has outcome {}; a second outcome is ignored",
                    id,
                    row.get().getOutcome());
            return Optional.empty();
        }
        return row;
    }
}
