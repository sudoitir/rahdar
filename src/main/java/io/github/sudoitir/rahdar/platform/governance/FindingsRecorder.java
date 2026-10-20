package io.github.sudoitir.rahdar.platform.governance;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Aggregates detections in fields no rule covers, and writes them in one batched upsert per flush
 * (ADR-0075 D4). Never one write per message: detection runs on the capture drain path.
 */
@Component
@RequiredArgsConstructor
class FindingsRecorder {

    /** Distinct pending fields held between flushes; beyond it new fields are dropped and counted. */
    static final int MAX_PENDING = 10_000;

    private static final String UPSERT = """
            INSERT INTO classification_finding
                (first_seen_at, last_seen_at, hit_count, destination, location, field_path, data_class)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (destination, location, field_path, data_class) DO UPDATE
               SET last_seen_at = EXCLUDED.last_seen_at,
                   hit_count = classification_finding.hit_count + EXCLUDED.hit_count
            """;

    record Key(String destination, Location location, String path, DataClass dataClass) {}

    record Tally(Instant firstSeen, Instant lastSeen, long hits) {}

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<Key, Tally> pending = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();

    void recordFinding(String address, Location location, String path, DataClass dataClass) {
        if (address == null) {
            return;
        }
        Key key = new Key(address, location, path, dataClass);
        if (pending.size() >= MAX_PENDING && !pending.containsKey(key)) {
            dropped.incrementAndGet();
            return;
        }
        Instant now = Instant.now();
        pending.merge(key, new Tally(now, now, 1), (a, b) -> new Tally(a.firstSeen(), b.lastSeen(), a.hits() + 1));
    }

    long dropped() {
        return dropped.get();
    }

    int pendingCount() {
        return pending.size();
    }

    void flush() {
        List<Object[]> batch = new ArrayList<>();
        for (Key key : List.copyOf(pending.keySet())) {
            Tally tally = pending.remove(key);
            if (tally != null) {
                batch.add(new Object[] {
                    Timestamp.from(tally.firstSeen()),
                    Timestamp.from(tally.lastSeen()),
                    tally.hits(),
                    key.destination(),
                    key.location().name(),
                    key.path(),
                    key.dataClass().name()
                });
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(UPSERT, batch);
        }
    }
}
