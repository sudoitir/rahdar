package io.github.sudoitir.rahdar.feature.transfer;

import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunEntity;
import io.github.sudoitir.rahdar.feature.transfer.internal.persistence.TransferRunRepository;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.replica.ReplicaRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A run still recorded as executing by a replica that is gone was cut off by its stop or crash
 * (transfer design D6, ADR-0152). A run another live replica is executing is left alone; the others
 * are marked interrupted, once at startup and then every 30 seconds on one replica, and offered for resume: a move's held messages are safe in its durable
 * staging queue, and a copy's ledger says what it has copied. The large-message spool is swept by
 * {@code BrokerRelay}; a staging queue whose run is gone shows as orphaned in the transfers list.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TransferRecovery {

    static final String INTERRUPTED =
            "Rahdar stopped while this run was executing. Its messages are intact: resume it to continue.";

    private final TransferRunRepository runs;
    private final AuditService audit;
    private final ReplicaRegistry replicas;

    @EventListener(ApplicationReadyEvent.class)
    void recover() {
        // Read the runs first: a replica that registers after this read is not one of their owners.
        List<TransferRunEntity> executing = runs.findByStateIn(TransferState.ACTIVE);
        Set<UUID> alive = replicas.aliveIds();
        for (TransferRunEntity run : executing) {
            if (alive.contains(run.getReplicaId())
                    || runs.transition(run.getId(), TransferState.ACTIVE, TransferState.INTERRUPTED) == 0) {
                continue;
            }
            run.finish(TransferState.INTERRUPTED, INTERRUPTED, null, Instant.now());
            runs.save(run);
            for (Long id : new Long[] {run.getAuditEventId(), run.getTargetAuditEventId()}) {
                if (id != null) {
                    audit.byId(id).ifPresent(event -> audit.fail(event, INTERRUPTED));
                }
            }
            log.warn("Transfer {} was executing when Rahdar stopped; marked interrupted", run.getId());
        }
    }
}
