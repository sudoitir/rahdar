package io.github.sudoitir.rahdar.kernel.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HousekeeperTest {

    private static final Instant NOW = Instant.parse("2026-09-30T03:30:00Z");

    private final LifecycleRegistry registry = mock(LifecycleRegistry.class);
    private final AuditService audit = mock(AuditService.class);
    private final PurgeStatus status = mock(PurgeStatus.class);
    private final AuditEvent event = mock(AuditEvent.class);
    private final Housekeeper housekeeper = new Housekeeper(registry, audit, status, Clock.fixed(NOW, ZoneOffset.UTC));

    /** A store with {@code backlog} rows to purge, or one that throws when {@code backlog} is negative. */
    private static final class Fake implements ManagedStore {
        final String id;
        long backlog;
        final List<Integer> batches = new ArrayList<>();
        Instant cutoff;

        Fake(String id, long backlog) {
            this.id = id;
            this.backlog = backlog;
        }

        @Override
        public StoreDef def() {
            return new StoreDef(
                    id, id, List.of(id), StoreDef.QuotaUnit.ROWS, Duration.ofDays(7), Duration.ofDays(1), null);
        }

        @Override
        public StoreUsage usage() {
            return new StoreUsage(backlog, 0);
        }

        @Override
        public PurgeEstimate preview(Instant cutoff) {
            return new PurgeEstimate(backlog, 0);
        }

        @Override
        public long purgeBatch(Instant cutoff, int limit) {
            if (backlog < 0) {
                throw new IllegalStateException("disk on fire");
            }
            this.cutoff = cutoff;
            batches.add(limit);
            long n = Math.min(limit, backlog);
            backlog -= n;
            return n;
        }
    }

    private RegisteredStore register(Fake fake, Optional<Duration> retention) {
        RegisteredStore store = new RegisteredStore(fake.id, RegisteredStore.CORE, fake, null);
        when(registry.retention(fake.id)).thenReturn(retention);
        when(registry.retentionValue(fake.id))
                .thenReturn(retention.map(d -> d.toDays() + "d").orElse("forever"));
        return store;
    }

    @Test
    void purgesInBoundedBatchesUntilNothingIsLeftAndAuditsOnce() {
        Fake fake = new Fake("events", 12_000);
        RegisteredStore store = register(fake, Optional.of(Duration.ofDays(3)));
        when(registry.all()).thenReturn(List.of(store));
        when(audit.begin(any(), eq("PURGE_STORE"), eq("STORE"), eq("events"), isNull(), isNull(), anyMap(), eq(false)))
                .thenReturn(event);

        housekeeper.purgeAll();

        assertThat(fake.batches).containsExactly(5_000, 5_000, 5_000, 5_000);
        assertThat(fake.backlog).isZero();
        assertThat(fake.cutoff).isEqualTo(NOW.minus(Duration.ofDays(3)));
        verify(audit).succeed(event, 12_000);
        verify(status).recordRun("events", NOW, 12_000, null);
    }

    @Test
    void aStoreKeptForeverIsNotPurged() {
        Fake fake = new Fake("audit", 10);
        RegisteredStore store = register(fake, Optional.empty());
        when(registry.all()).thenReturn(List.of(store));

        housekeeper.purgeAll();

        assertThat(fake.batches).isEmpty();
        verify(audit, never()).begin(any(), anyString(), anyString(), anyString(), any(), any(), anyMap(), eq(false));
    }

    @Test
    void aFailingStoreIsRecordedAndTheOthersStillPurge() {
        Fake broken = new Fake("plugin.broken", -1);
        Fake fine = new Fake("events", 3);
        RegisteredStore first = register(broken, Optional.of(Duration.ofDays(1)));
        RegisteredStore second = register(fine, Optional.of(Duration.ofDays(1)));
        when(registry.all()).thenReturn(List.of(first, second));
        when(audit.begin(any(), anyString(), anyString(), anyString(), any(), any(), anyMap(), eq(false)))
                .thenReturn(event);

        housekeeper.purgeAll();

        verify(audit).failPartial(event, 0, "disk on fire");
        verify(status).recordRun("plugin.broken", NOW, 0, "disk on fire");
        assertThat(fine.backlog).isZero();
        verify(status).recordRun(eq("events"), eq(NOW), anyLong(), isNull());
    }

    /** A store that holds {@code rows} and gives up {@code perBatch} of its oldest on each trim. */
    private static final class Capped implements ManagedStore {
        long rows;
        final int perBatch;
        final List<Long> excesses = new ArrayList<>();

        Capped(long rows, int perBatch) {
            this.rows = rows;
            this.perBatch = perBatch;
        }

        @Override
        public StoreDef def() {
            return new StoreDef(
                    "capped", "capped", List.of("capped"), StoreDef.QuotaUnit.ROWS, null, Duration.ofDays(1), null);
        }

        @Override
        public StoreUsage usage() {
            return new StoreUsage(rows, 0);
        }

        @Override
        public PurgeEstimate preview(Instant cutoff) {
            return new PurgeEstimate(0, 0);
        }

        @Override
        public long purgeBatch(Instant cutoff, int limit) {
            return 0;
        }

        @Override
        public long trimOldest(long excessRows, int limit) {
            excesses.add(excessRows);
            long n = Math.min(perBatch, rows);
            rows -= n;
            return n;
        }
    }

    @Test
    void aStoreOverItsRowQuotaLosesItsOldestFirstUntilItIsUnderAndIsAudited() {
        Capped capped = new Capped(12_500, 1_000);
        RegisteredStore store = new RegisteredStore("capped", RegisteredStore.CORE, capped, null);
        when(registry.quota("capped")).thenReturn(10);
        when(audit.begin(any(), eq("TRIM_STORE"), eq("STORE"), eq("capped"), isNull(), isNull(), anyMap(), eq(false)))
                .thenReturn(event);

        housekeeper.trimToQuota(store);

        assertThat(capped.rows).isLessThanOrEqualTo(10_000);
        assertThat(capped.rows).isGreaterThan(9_000);
        assertThat(capped.excesses).startsWith(2_500L);
        verify(audit).succeed(eq(event), eq(12_500L - capped.rows));
    }

    @Test
    void aStoreUnderItsQuotaOrWithNoneIsLeftAlone() {
        Capped capped = new Capped(5_000, 1_000);
        RegisteredStore store = new RegisteredStore("capped", RegisteredStore.CORE, capped, null);
        when(registry.quota("capped")).thenReturn(10);
        housekeeper.trimToQuota(store);
        when(registry.quota("capped")).thenReturn(0);
        capped.rows = 50_000;
        housekeeper.trimToQuota(store);

        assertThat(capped.excesses).isEmpty();
        verify(audit, never()).begin(any(), eq("TRIM_STORE"), any(), any(), any(), any(), any(), eq(false));
    }
}
