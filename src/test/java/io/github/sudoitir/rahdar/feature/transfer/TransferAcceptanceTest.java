package io.github.sudoitir.rahdar.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.Finding;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.FindingKind;
import io.github.sudoitir.rahdar.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 5.7: the target is checked before a transfer, refusing what would lose messages, and before every
 * batch, waiting while it is near full.
 */
class TransferAcceptanceTest extends TransferTestSupport {

    @Autowired
    BrokerNodeRepository brokerNodes;

    private static Finding refusal(TransferRunView preview, String code) {
        return preview.findings().stream()
                .filter(f -> f.kind() == FindingKind.REFUSE && f.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + code + " refusal in " + preview.findings()));
    }

    private void settingsFor(String address, String json) {
        d.addressSettings(address, json);
        afterTest(() -> d.removeAddressSettings(address));
    }

    @Test
    void aTargetThatWouldDropOrCannotTakeTheMessagesIsRefusedWithTheReason() {
        String src = queue(p, "accept.src");
        p.produce(src, 3);

        String drop = queue(d, "accept.drop");
        settingsFor(drop, "{\"addressFullMessagePolicy\":\"DROP\",\"maxSizeBytes\":1048576,\"pageSizeBytes\":65536}");
        TransferRunView dropping = preview(TransferMode.MOVE, src, all(), drop);
        assertThat(refusal(dropping, "address-full-drop").snippet()).contains("<address-full-policy>PAGE");
        assertThatThrownBy(() -> execute(dropping))
                .isInstanceOfSatisfying(
                        TransferRefusedException.class,
                        e -> assertThat(e.slug()).isEqualTo("transfer-refused"));

        String filtered = queue(d, "accept.filtered", "color = 'red'");
        refusal(preview(TransferMode.COPY, src, all(), filtered), "target-filtered");

        String missing = "accept.missing." + sfx;
        settingsFor(missing, "{\"autoCreateQueues\":false}");
        refusal(preview(TransferMode.COPY, src, all(), missing), "subscription-missing");

        refusal(
                preview(TransferMode.MOVE, clusterP, clusterP.node(), src, all(), clusterP, clusterP.node(), src),
                "same-subscription");

        // What Rahdar last read of the target node: it has become the backup of its pair.
        BrokerNodeEntity node = brokerNodes.findById(clusterD.node()).orElseThrow();
        node.applyHaState(
                new HaObservation(false, "STARTED", "BACKUP", true, null, node.getNativeNodeId()), 1L, Instant.now());
        brokerNodes.save(node);
        refusal(preview(TransferMode.COPY, src, all(), queue(d, "accept.backup")), "target-backup");

        assertThat(p.depth(src)).as("nothing was touched").isEqualTo(3);
    }

    /** The target address refuses messages past a small size: 50% of it makes the run wait. */
    private String nearlyFullTarget() {
        settings.put(TransferSettings.CAPACITY_THRESHOLD_PERCENT, "50");
        String dst = queue(d, "accept.full");
        settingsFor(dst, "{\"addressFullMessagePolicy\":\"FAIL\",\"maxSizeBytes\":200000,\"pageSizeBytes\":65536}");
        return dst;
    }

    private String sourceOf(int count) {
        String src = queue(p, "accept.full.src");
        p.produce(src, count, (session, i) -> {
            var m = session.createTextMessage("x".repeat(1000));
            m.setIntProperty("seq", i);
            return m;
        });
        return src;
    }

    @Test
    void aRunWaitsWhileTheTargetIsNearFullAndContinuesOnceItDrains() throws Exception {
        String dst = nearlyFullTarget();
        String src = sourceOf(80);

        TransferRunView preview = preview(TransferMode.MOVE, src, all(), dst);
        assertThat(preview.findings()).noneMatch(f -> f.kind() == FindingKind.REFUSE);
        TransferRunView run = execute(preview);
        TransferRunView waiting =
                await(run, r -> r.state() == TransferState.WAITING_FOR_CAPACITY, Duration.ofSeconds(60));
        assertThat(waiting.lastError()).contains(dst);

        AtomicInteger consumed = new AtomicInteger();
        AtomicBoolean draining = new AtomicBoolean(true);
        Thread consumer = Thread.ofVirtual().start(() -> drainWhile(dst, draining, consumed));
        TransferRunView done;
        try {
            done = awaitEnded(run, Duration.ofSeconds(120));
            await(run, r -> consumed.get() >= 80, Duration.ofSeconds(30));
        } finally {
            draining.set(false);
            consumer.join();
        }

        assertThat(done.state()).as(done.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(consumed.get()).isEqualTo(80);
        assertThat(p.depth(src)).isZero();
    }

    @Test
    void aRunStopsResumablyWhenTheTargetStaysFullPastTheWait() {
        String dst = nearlyFullTarget();
        settings.put(TransferSettings.CAPACITY_WAIT, "PT3S");
        String src = sourceOf(80);

        TransferRunView run = awaitEnded(execute(preview(TransferMode.MOVE, src, all(), dst)));

        assertThat(run.state()).isEqualTo(TransferState.STOPPED);
        assertThat(run.lastError()).contains("stayed full").contains(dst);
        assertThat(run.resumable()).isTrue();
        assertThat(p.depth(src) + p.depth(run.stagingQueue()) + d.depth(dst))
                .as("nothing is lost")
                .isEqualTo(80);
    }

    private void drainWhile(String queue, AtomicBoolean draining, AtomicInteger consumed) {
        try (ActiveMQConnectionFactory factory = d.jms();
                Connection connection = factory.createConnection()) {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createQueue(queue));
            while (draining.get()) {
                if (consumer.receive(200) != null) {
                    consumed.incrementAndGet();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
