package io.github.sudoitir.rahdar.kernel.auditfeed;

import io.github.sudoitir.rahdar.kernel.audit.AuditDelivery;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditFeed;
import io.github.sudoitir.rahdar.kernel.audit.AuditGap;
import io.github.sudoitir.rahdar.kernel.audit.AuditPosition;
import io.github.sudoitir.rahdar.kernel.audit.AuditReceiver;
import io.github.sudoitir.rahdar.kernel.audit.AuditRecord;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditReceivers.Attached;
import io.github.sudoitir.rahdar.kernel.core.SecretRedactor;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pushes the audit trail to every receiver attached on this replica (ADR-0190). Each tick takes a
 * due receiver's batches after its cursor, 500 at a time and at most ten, and calls it on a virtual
 * thread bounded by {@code audit.delivery.timeout}. The cursor moves only when the call returns,
 * and only if nobody moved it meanwhile, so a record is delivered again after a crash or a lost
 * acknowledgement. A call that throws or times out backs the receiver off, 2 s doubling up to 5
 * minutes; the audited operation never waits for any of this. A receiver attached on another
 * replica is skipped here and served when that replica holds the job's lock.
 */
@Component
@Slf4j
class AuditDeliveryJob {

    static final int BATCH = 500;
    static final int MAX_BATCHES = 10;

    private static final int ERROR_LIMIT = 500;

    /** A gap not yet delivered, with the id that orders it. */
    private record Gap(long id, long count, Instant from, Instant to, String pluginId) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditFeed feed;
    private final AuditService audit;
    private final AuditReceivers receivers;
    private final SettingsService settings;
    private final PluginCalls calls;

    /** One delivery to a receiver at a time, whichever thread asks. */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    AuditDeliveryJob(
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            AuditFeed feed,
            AuditService audit,
            AuditReceivers receivers,
            PluginCalls calls,
            SettingsService settings) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.feed = feed;
        this.audit = audit;
        this.receivers = receivers;
        this.calls = calls;
        this.settings = settings;
    }

    /** One tick: records the gaps retention left, then serves every due receiver attached here, in parallel. */
    void tick() {
        auditGaps();
        List<String> due =
                jdbc.queryForList("SELECT plugin_id FROM audit_receiver WHERE next_attempt_at <= now()", String.class);
        List<Future<Void>> running = receivers.receivers().stream()
                .map(a -> a.handle().id())
                .filter(due::contains)
                .map(id -> calls.run(() -> deliver(id)))
                .toList();
        for (Future<Void> f : running) {
            try {
                f.get();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                log.warn("Audit delivery failed unexpectedly", e.getCause());
            }
        }
    }

    /** Delivers batches to one receiver until it is caught up, fails, or ten batches have gone. */
    void deliver(String pluginId) {
        synchronized (locks.computeIfAbsent(pluginId, k -> new Object())) {
            for (int i = 0; i < MAX_BATCHES && deliverBatch(pluginId); i++) {
                // next batch
            }
        }
    }

    /** Whether the receiver took a full batch, so that more is probably waiting. */
    private boolean deliverBatch(String pluginId) {
        Attached<AuditReceiver> target = receivers.receiver(pluginId);
        AuditPosition from = position(pluginId);
        if (target == null || from == null) {
            return false;
        }
        List<AuditRecord> records = feed.after(from, BATCH);
        List<Gap> gaps = gaps(pluginId);
        if (records.isEmpty() && gaps.isEmpty()) {
            return false;
        }
        try {
            AuditDelivery delivery = new AuditDelivery(records, merge(gaps));
            calls.call(
                    "receive:" + pluginId,
                    target.handle(),
                    () -> {
                        target.bean().receive(delivery);
                        return null;
                    },
                    settings.duration(AuditFeedSettings.TIMEOUT));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (PluginCalls.Busy _) {
            return false;
        } catch (Exception e) {
            fail(pluginId, e);
            return false;
        }
        AuditPosition to = records.isEmpty() ? from : records.getLast().position();
        return acknowledge(pluginId, from, to, gaps) && records.size() == BATCH;
    }

    /** Moves the cursor from {@code from} to {@code to} and marks the gaps told; false when another replica moved it first. */
    private boolean acknowledge(String pluginId, AuditPosition from, AuditPosition to, List<Gap> gaps) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            int moved = jdbc.update(
                    """
                    UPDATE audit_receiver
                    SET pos_txid = ?::text::xid8, pos_seq = ?, acked_at = now(), next_attempt_at = now(),
                        failures = 0, last_error = NULL
                    WHERE plugin_id = ? AND pos_txid = ?::text::xid8 AND pos_seq = ?""", Long.toString(to.txid()), to.seq(), pluginId, Long.toString(from.txid()), from.seq());
            if (moved == 0) {
                return false;
            }
            if (!gaps.isEmpty()) {
                jdbc.update(
                        "UPDATE audit_receiver_gap SET delivered_at = now() WHERE plugin_id = ? AND delivered_at IS NULL AND id <= ?",
                        pluginId,
                        gaps.getLast().id());
                removeSettledGaps();
            }
            return true;
        }));
    }

    private void fail(String pluginId, Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String error = SecretRedactor.redact(message);
        jdbc.update("""
                UPDATE audit_receiver
                SET failures = failures + 1, last_error = ?,
                    next_attempt_at = now() + make_interval(secs => least(2 * power(2::float8, least(failures, 20)), 300))
                WHERE plugin_id = ?""", error.length() > ERROR_LIMIT ? error.substring(0, ERROR_LIMIT) : error, pluginId);
        log.warn("Audit receiver {} did not acknowledge: {}", pluginId, error);
    }

    private AuditPosition position(String pluginId) {
        return jdbc
                .query(
                        "SELECT pos_txid::text, pos_seq FROM audit_receiver WHERE plugin_id = ?",
                        (rs, i) -> new AuditPosition(Long.parseLong(rs.getString(1)), rs.getLong(2)),
                        pluginId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private List<Gap> gaps(String pluginId) {
        return jdbc.query(
                "SELECT id, count, first_ts, last_ts FROM audit_receiver_gap WHERE plugin_id = ? AND delivered_at IS NULL ORDER BY id",
                (rs, i) -> new Gap(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4).toInstant(),
                        pluginId),
                pluginId);
    }

    private static AuditGap merge(List<Gap> gaps) {
        if (gaps.isEmpty()) {
            return null;
        }
        return new AuditGap(
                gaps.stream().mapToLong(Gap::count).sum(),
                gaps.stream().map(Gap::from).min(Comparator.naturalOrder()).orElseThrow(),
                gaps.stream().map(Gap::to).max(Comparator.naturalOrder()).orElseThrow());
    }

    /**
     * Writes one {@code AUDIT_GAP} event for every gap retention left since the last tick, so that
     * records lost to retention are never silent. The gap is claimed before the event is written, so
     * it is written once.
     */
    private void auditGaps() {
        for (Long id : jdbc.queryForList(
                "SELECT id FROM audit_receiver_gap WHERE audited_at IS NULL ORDER BY id", Long.class)) {
            List<Gap> claimed = jdbc.query(
                    "UPDATE audit_receiver_gap SET audited_at = now() WHERE id = ? AND audited_at IS NULL RETURNING count, first_ts, last_ts, plugin_id",
                    (rs, i) -> new Gap(
                            id,
                            rs.getLong(1),
                            rs.getTimestamp(2).toInstant(),
                            rs.getTimestamp(3).toInstant(),
                            rs.getString(4)),
                    id);
            for (Gap gap : claimed) {
                writeGapEvent(gap);
            }
        }
        removeSettledGaps();
    }

    /** A gap that is audited and delivered has nothing left to do, so the table holds only what is open. */
    private void removeSettledGaps() {
        jdbc.update("DELETE FROM audit_receiver_gap WHERE audited_at IS NOT NULL AND delivered_at IS NOT NULL");
    }

    /** Only a failed {@code begin} gives the claim back; once the event exists, a second one would be a duplicate. */
    private void writeGapEvent(Gap gap) {
        AuditEvent event;
        try {
            event = audit.begin(
                    Actor.ofSystem(),
                    "AUDIT_GAP",
                    "PLUGIN",
                    gap.pluginId(),
                    null,
                    null,
                    Map.of(
                            "count",
                            gap.count(),
                            "from",
                            gap.from().toString(),
                            "to",
                            gap.to().toString()),
                    false);
        } catch (RuntimeException e) {
            jdbc.update("UPDATE audit_receiver_gap SET audited_at = NULL WHERE id = ?", gap.id());
            throw e;
        }
        audit.succeed(event, gap.count());
    }
}
