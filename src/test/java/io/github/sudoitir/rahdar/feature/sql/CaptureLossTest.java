package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageCaptureNodeEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageCaptureNodeRepository;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionEntity;
import io.github.sudoitir.rahdar.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Loss is measured per address, not per queue: an address with several bound queues routes each
 * message to all of them, and captured rows are stored under the address.
 */
class CaptureLossTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private final AtomicLong added = new AtomicLong(100);
    private final AtomicLong captured = new AtomicLong(0);
    private final AtomicLong ring = new AtomicLong(0);

    private MessageIndexSubscriptionEntity subscription;
    private MessageCaptureNodeEntity node;
    private CaptureLoss loss;

    @BeforeEach
    void setUp() {
        subscription = new MessageIndexSubscriptionEntity();
        subscription.setId(UUID.randomUUID());
        subscription.setClusterId(CLUSTER);
        subscription.setSubscriptionPattern("ORDERS");
        subscription.setMode(CaptureMode.CAPTURE);
        subscription.setEnabled(true);
        subscription.setRetentionDays(7);

        node = new MessageCaptureNodeEntity();
        node.setSubscriptionId(subscription.getId());
        node.setNodeId(NODE);
        node.setCaptureState(CaptureState.ACTIVE);

        MessageIndexSubscriptionRepository subscriptions = mock(MessageIndexSubscriptionRepository.class);
        when(subscriptions.findByClusterId(CLUSTER)).thenReturn(List.of(subscription));
        MessageCaptureNodeRepository nodes = mock(MessageCaptureNodeRepository.class);
        when(nodes.findBySubscriptionId(subscription.getId())).thenReturn(List.of(node));

        // A multicast address with two subscription queues, each named differently from it.
        QueueSnapshots snapshots = mock(QueueSnapshots.class);
        when(snapshots.forCluster(CLUSTER))
                .thenAnswer(invocation -> List.of(
                        queue("ORDERS.billing", added.get()),
                        queue("ORDERS.shipping", added.get()),
                        captureQueue(ring.get())));

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenAnswer(invocation ->
                        ((String) invocation.getArgument(0)).startsWith("SELECT count") ? captured.get() : 0L);

        CaptureConsumer consumers = mock(CaptureConsumer.class);
        when(consumers.takeShortfall(any(), any())).thenReturn(CaptureConsumer.Shortfall.NONE);
        CaptureAddresses addresses = mock(CaptureAddresses.class);
        when(addresses.of(CLUSTER, subscription)).thenReturn(Set.of("ORDERS"));

        loss = new CaptureLoss(subscriptions, nodes, snapshots, jdbc, consumers, addresses);
    }

    @Test
    void aMulticastAddressWhoseMessagesWereAllCapturedReportsNoLoss() {
        loss.measure(CLUSTER);

        // Fifty messages routed to the address reach both queues, so each queue's counter moves
        // by fifty. Fifty rows were captured: nothing was lost.
        added.addAndGet(50);
        captured.addAndGet(50);
        loss.measure(CLUSTER);

        assertThat(node.getDroppedEstimate()).isZero();
        assertThat(node.getCaptureState()).isEqualTo(CaptureState.ACTIVE);
    }

    @Test
    void messagesRoutedButNotCapturedAreCountedOnceAndDegradeTheNode() {
        loss.measure(CLUSTER);

        added.addAndGet(500);
        captured.addAndGet(200);
        loss.measure(CLUSTER);

        assertThat(node.getDroppedEstimate()).isEqualTo(300);
        assertThat(node.getCaptureState()).isEqualTo(CaptureState.DEGRADED);
        assertThat(node.getCaptureDetail()).contains("About 300 messages");
    }

    @Test
    void aNodeThatStopsLosingMessagesIsHealthyAgain() {
        loss.measure(CLUSTER);
        added.addAndGet(500);
        captured.addAndGet(200);
        loss.measure(CLUSTER);

        added.addAndGet(10);
        captured.addAndGet(10);
        loss.measure(CLUSTER);

        assertThat(node.getCaptureState()).isEqualTo(CaptureState.ACTIVE);
        assertThat(node.getDroppedEstimate()).isEqualTo(300);
    }

    /**
     * A drain's in-flight batch moves the figure by up to a batch either way. That swing is not
     * loss, and reporting it — as the dev-stack soak did, about 100 over 60,000 fully stored
     * messages — tells an operator a healthy capture is losing messages.
     */
    @Test
    void anInFlightSwingThatFallsBackIsNeverReported() {
        loss.measure(CLUSTER);

        added.addAndGet(60);
        loss.measure(CLUSTER);
        captured.addAndGet(60);
        loss.measure(CLUSTER);
        added.addAndGet(80);
        loss.measure(CLUSTER);

        assertThat(node.getDroppedEstimate()).isZero();
        assertThat(node.getCaptureState()).isEqualTo(CaptureState.ACTIVE);
    }

    @Test
    void aSlowLossBelowOneWindowPerPassStillAccumulatesAndIsReported() {
        loss.measure(CLUSTER);

        for (int pass = 0; pass < 3; pass++) {
            added.addAndGet(240);
            captured.addAndGet(200);
            loss.measure(CLUSTER);
        }

        assertThat(node.getDroppedEstimate()).isEqualTo(120);
        assertThat(node.getCaptureState()).isEqualTo(CaptureState.DEGRADED);
    }

    /**
     * Measured on the dev stack at ~200 msg/s: 360,000 routed, 360,000 stored, and about 2,400
     * reported as lost, because what was still waiting in the ring at each pass counted as loss
     * and the catch-up on the next pass was never taken back.
     */
    @Test
    void messagesStillInTheRingAreNotLossAndCatchingUpReportsNone() {
        loss.measure(CLUSTER);

        added.addAndGet(50);
        captured.addAndGet(20);
        ring.set(30);
        loss.measure(CLUSTER);

        added.addAndGet(50);
        captured.addAndGet(80);
        ring.set(0);
        loss.measure(CLUSTER);

        assertThat(node.getDroppedEstimate()).isZero();
        assertThat(node.getCaptureState()).isEqualTo(CaptureState.ACTIVE);
    }

    @Test
    void aCounterThatWentBackwardsResetsTheBaselineInsteadOfReportingLoss() {
        loss.measure(CLUSTER);

        // A broker restart resets MessagesAdded.
        added.set(10);
        loss.measure(CLUSTER);
        added.addAndGet(10);
        captured.addAndGet(10);
        loss.measure(CLUSTER);

        assertThat(node.getDroppedEstimate()).isZero();
        assertThat(node.getCaptureState()).isEqualTo(CaptureState.ACTIVE);
    }

    @Test
    void aFilteredCaptureSaysItsLossCannotBeEstimatedRatherThanReportingZero() {
        subscription.setFilterString("priority > 4");

        loss.measure(CLUSTER);
        added.addAndGet(50);
        loss.measure(CLUSTER);

        assertThat(node.getDroppedEstimate()).isZero();
        assertThat(node.getCaptureDetail()).isEqualTo(CaptureLoss.FILTERED);
    }

    private QueueSnapshot captureQueue(long depth) {
        String name = "rahdar.capture.abc12345.ORDERS." + subscription.getId() + ".q";
        return new QueueSnapshot(
                CLUSTER,
                NODE,
                name,
                name,
                "ANYCAST",
                false,
                false,
                Instant.now(),
                depth,
                1,
                0,
                0,
                0,
                0,
                0,
                "artemis.queue",
                Map.of());
    }

    private static QueueSnapshot queue(String name, long messagesAdded) {
        return new QueueSnapshot(
                CLUSTER,
                NODE,
                name,
                "ORDERS",
                "MULTICAST",
                false,
                false,
                Instant.now(),
                0L,
                0,
                0,
                0,
                messagesAdded,
                0,
                0,
                "artemis.queue",
                Map.of());
    }
}
