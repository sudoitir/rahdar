package io.github.sudoitir.rahdar.kernel.lifecycle;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Purges every store to its retention (ADR-0134). Run by the {@code housekeeping} job, once per
 * installation. Deliberately not transactional: each batch commits on its own, so writers to the
 * store wait for one batch at most, and a store that fails leaves the others to run.
 */
@Component
@Slf4j
public class Housekeeper {

    /** Rows per batch: small enough that a batch holds its locks for milliseconds. */
    public static final int BATCH = 5_000;

    private final LifecycleRegistry registry;
    private final AuditService audit;
    private final PurgeStatus status;
    private final Clock clock;

    Housekeeper(LifecycleRegistry registry, AuditService audit, PurgeStatus status, Clock clock) {
        this.registry = registry;
        this.audit = audit;
        this.status = status;
        this.clock = clock;
    }

    public void purgeAll() {
        for (RegisteredStore store : registry.all()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            Optional<Duration> retention = registry.retention(store.id());
            if (retention.isPresent()) {
                purge(store, retention.get());
            }
        }
    }

    /** One store, in batches, audited as one {@code PURGE_STORE} event whether it succeeds or fails. */
    void purge(RegisteredStore store, Duration retention) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(retention);
        AuditEvent event = audit.begin(
                Actor.ofSystem(),
                "PURGE_STORE",
                "STORE",
                store.id(),
                null,
                null,
                Map.of("retention", registry.retentionValue(store.id()), "cutoff", cutoff.toString()),
                false);
        long purged = 0;
        try {
            long batch;
            do {
                batch = store.call(s -> s.purgeBatch(cutoff, BATCH));
                purged += batch;
            } while (batch > 0 && !Thread.currentThread().isInterrupted());
            audit.succeed(event, purged);
            status.recordRun(store.id(), now, purged, null);
            if (purged > 0) {
                log.info("Purged {} rows from store {} older than {}", purged, store.id(), cutoff);
            }
        } catch (RuntimeException e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Purge of store {} failed after {} rows", store.id(), purged, e);
            audit.failPartial(event, purged, error);
            status.recordRun(store.id(), now, purged, error);
        }
    }
}
