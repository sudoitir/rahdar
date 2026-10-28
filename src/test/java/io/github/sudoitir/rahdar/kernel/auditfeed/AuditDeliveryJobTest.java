package io.github.sudoitir.rahdar.kernel.auditfeed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.kernel.audit.AuditDelivery;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditFeed;
import io.github.sudoitir.rahdar.kernel.audit.AuditGap;
import io.github.sudoitir.rahdar.kernel.audit.AuditPosition;
import io.github.sudoitir.rahdar.kernel.audit.AuditReceiver;
import io.github.sudoitir.rahdar.kernel.audit.AuditRecord;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditReceiverFixtures.FakeHandle;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditReceiverFixtures.TestReceiver;
import io.github.sudoitir.rahdar.kernel.auditfeed.AuditRetentionGuard.Purged;
import io.github.sudoitir.rahdar.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.rahdar.kernel.plugin.PluginStatusChanged;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Audit records reach a receiver in commit order, at least once, and a receiver never holds up the audited operation. */
@ExtendWith(AdminAuthenticationExtension.class)
class AuditDeliveryJobTest extends PostgresIntegrationTest {

    @Autowired
    AuditDeliveryJob job;

    @Autowired
    AuditReceivers receivers;

    @Autowired
    AuditService audit;

    @Autowired
    AuditRetentionGuard guard;

    @Autowired
    AuditFeed feed;

    @Autowired
    SettingsService settings;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LockProvider jobLocks;

    private final String pluginId =
            "audit-receiver-" + UUID.randomUUID().toString().substring(0, 8);
    private final String prefix =
            "DELIVERY_TEST_" + UUID.randomUUID().toString().substring(0, 8) + "_";
    private final TestReceiver receiver = new TestReceiver();
    private FakeHandle handle;
    private SimpleLock scheduled;

    @BeforeEach
    void attach() {
        // The scheduled delivery job runs in this context too, every two seconds, and would deliver to this
        // test's receiver between the test's own calls. Holding its lock, as another replica would, keeps it out.
        Awaitility.await("the audit-delivery job's lock")
                .atMost(Duration.ofSeconds(10))
                .until(() -> {
                    scheduled = jobLocks.lock(new LockConfiguration(
                                    Instant.now(), "audit-delivery", Duration.ofMinutes(5), Duration.ZERO))
                            .orElse(null);
                    return scheduled != null;
                });
        handle = new FakeHandle(pluginId, true, false, Map.of(AuditReceiver.class, receiver));
        receivers.attach(handle);
        // A new receiver starts at the beginning of the trail; this test cares about its own records only.
        jdbc.update("""
                UPDATE audit_receiver SET pos_txid = head.txid, pos_seq = head.seq
                FROM (SELECT pos_txid AS txid, pos_seq AS seq FROM audit_event
                      ORDER BY pos_txid DESC, pos_seq DESC LIMIT 1) head
                WHERE plugin_id = ?""", pluginId);
    }

    @AfterEach
    void detach() {
        if (scheduled != null) {
            scheduled.unlock();
        }
        receivers.detach(handle);
        jdbc.update("DELETE FROM audit_receiver WHERE plugin_id = ?", pluginId);
        jdbc.update("UPDATE audit_feed_state SET retained_from = NULL");
    }

    private AuditEvent write(String name) {
        AuditEvent event = audit.begin(Actor.ofSystem(), prefix + name, "TEST", name, null, null, Map.of(), false);
        audit.succeed(event, 1);
        return event;
    }

    @Test
    void aReceiverThatWasDownGetsEverythingInOrderWhenItIsBack() {
        receiver.failure = new IllegalStateException("down");
        AuditEvent first = write("a");
        AuditEvent second = write("b");
        AuditEvent third = write("c");

        job.deliver(pluginId);

        assertThat(jdbc.queryForObject(
                        "SELECT failures FROM audit_receiver WHERE plugin_id = ?", Integer.class, pluginId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT last_error FROM audit_receiver WHERE plugin_id = ?", String.class, pluginId))
                .isEqualTo("down");

        receiver.failure = null;
        receiver.batches.clear();
        job.deliver(pluginId);

        assertThat(receiver.ids(prefix)).containsExactly(first.getId(), second.getId(), third.getId());
        assertThat(jdbc.queryForObject(
                        "SELECT failures FROM audit_receiver WHERE plugin_id = ?", Integer.class, pluginId))
                .isZero();
        receiver.batches.clear();
        job.deliver(pluginId);
        assertThat(receiver.batches).isEmpty();
    }

    @Test
    void recordsAfterTheCursorAreDeliveredAgainWithStableIdsWhenTheAcknowledgementIsLost() {
        receiver.failure = new IllegalStateException("crashed before acknowledging");
        write("a");
        write("b");

        job.deliver(pluginId);
        job.deliver(pluginId);

        assertThat(receiver.batches).hasSizeGreaterThanOrEqualTo(2);
        assertThat(receiver.records(1, prefix))
                .isEqualTo(receiver.records(0, prefix))
                .hasSize(2);
        assertThat(receiver.records(0, prefix))
                .extracting(AuditRecord::position)
                .doesNotHaveDuplicates();
    }

    @Test
    void aSlowReceiverDoesNotBlockTheAuditedOperation() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        receiver.beforeAnswer = () -> {
            inside.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        };
        write("a");
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> delivering = pool.submit(() -> job.deliver(pluginId));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            AuditEvent during = write("during");

            assertThat(during.getId()).isNotNull();
            release.countDown();
            delivering.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void aTimeoutCountsAsAFailureAndBacksTheReceiverOff() {
        jdbc.update("UPDATE audit_receiver SET failures = 20 WHERE plugin_id = ?", pluginId);
        receiver.failure = new IllegalStateException("Bearer abcdefghijklmnop failed");
        write("a");

        job.deliver(pluginId);

        assertThat(jdbc.queryForObject(
                        "SELECT failures FROM audit_receiver WHERE plugin_id = ?", Integer.class, pluginId))
                .isEqualTo(21);
        assertThat(jdbc.queryForObject(
                        "SELECT last_error FROM audit_receiver WHERE plugin_id = ?", String.class, pluginId))
                .doesNotContain("abcdefghijklmnop");
        assertThat(jdbc.queryForObject(
                        "SELECT next_attempt_at > now() + interval '4 minutes' FROM audit_receiver WHERE plugin_id = ?",
                        Boolean.class,
                        pluginId))
                .isTrue();
    }

    @Test
    void aPluginThatProvidesAReceiverWithoutDeclaringItIsRefused() {
        FakeHandle undeclared =
                new FakeHandle("undeclared-" + pluginId, false, false, Map.of(AuditReceiver.class, receiver));

        assertThatThrownBy(() -> receivers.attach(undeclared))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not declare audit.receive");
    }

    @Test
    void aPluginThatDeclaresReceivingWithoutProvidingOneIsRefused() {
        FakeHandle missing = new FakeHandle("missing-" + pluginId, true, false, Map.of());

        assertThatThrownBy(() -> receivers.attach(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one AuditReceiver");
    }

    @Test
    void theCursorGoesWhenThePluginIsUninstalledButNotWhenItIsDetached() {
        receivers.detach(handle);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_receiver WHERE plugin_id = ?", Long.class, pluginId))
                .isEqualTo(1);

        receivers.onStatusChanged(
                new PluginStatusChanged(pluginId, PluginInstallStatus.ACTIVE, PluginInstallStatus.UNINSTALLED, "test"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_receiver WHERE plugin_id = ?", Long.class, pluginId))
                .isZero();
    }

    @Test
    void aGapIsToldToTheReceiverAndAuditedOnce() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant last = Instant.parse("2026-01-02T00:00:00Z");
        guard.recordPurged(List.of(
                new Purged(new AuditPosition(Long.MAX_VALUE / 2, 1), first),
                new Purged(new AuditPosition(Long.MAX_VALUE / 2, 2), last)));

        job.tick();
        job.tick();

        assertThat(jdbc.queryForList(
                        "SELECT affected_count FROM audit_event WHERE action = 'AUDIT_GAP' AND target_type = 'PLUGIN' AND target_name = ?",
                        Long.class,
                        pluginId))
                .containsExactly(2L);
        assertThat(receiver.batches).extracting(AuditDelivery::gap).contains(new AuditGap(2, first, last));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_receiver_gap WHERE plugin_id = ?", Long.class, pluginId))
                .isZero();
        assertThat(feed.retainedFrom()).contains(last);
    }

    @Test
    void aGapPurgedAfterADeliveryReadTheEarlierOneIsNotLostToItsAcknowledgement() {
        Instant ts = Instant.parse("2026-01-01T00:00:00Z");
        guard.recordPurged(List.of(new Purged(new AuditPosition(Long.MAX_VALUE / 2, 1), ts)));
        // A delivery has read the first gap when the next purge lands: the new count is its own row.
        guard.recordPurged(List.of(new Purged(new AuditPosition(Long.MAX_VALUE / 2, 2), ts)));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_receiver_gap WHERE plugin_id = ?", Long.class, pluginId))
                .isEqualTo(2);

        job.deliver(pluginId);
        guard.recordPurged(List.of(new Purged(new AuditPosition(Long.MAX_VALUE / 2, 3), ts)));

        assertThat(receiver.batches).extracting(AuditDelivery::gap).contains(new AuditGap(2, ts, ts));
        assertThat(jdbc.queryForObject(
                        "SELECT sum(count) FROM audit_receiver_gap WHERE plugin_id = ? AND delivered_at IS NULL",
                        Long.class,
                        pluginId))
                .isEqualTo(1);
    }

    @Test
    void aReceiverThatIgnoresItsTimeoutIsNotCalledAgainWhileItIsStillRunning() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        receiver.beforeAnswer = () -> {
            boolean waiting = true;
            while (waiting) {
                try {
                    waiting = !release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    // keeps running, as a stuck plugin does
                }
            }
        };
        settings.put(AuditFeedSettings.TIMEOUT, "1s");
        try {
            write("a");
            job.deliver(pluginId);
            job.deliver(pluginId);

            assertThat(receiver.batches).hasSize(1);
            assertThat(jdbc.queryForObject(
                            "SELECT failures FROM audit_receiver WHERE plugin_id = ?", Integer.class, pluginId))
                    .isEqualTo(1);

            release.countDown();
            receiver.beforeAnswer = () -> {};
            // The stuck call returns on its own thread; until it has, the receiver is skipped.
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> {
                job.deliver(pluginId);
                return receiver.batches.size() >= 2;
            });
        } finally {
            release.countDown();
            settings.reset(AuditFeedSettings.TIMEOUT);
        }
    }
}
