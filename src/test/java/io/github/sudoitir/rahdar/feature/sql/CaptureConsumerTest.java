package io.github.sudoitir.rahdar.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.messaging.Blob;
import io.github.sudoitir.openbroker.messaging.Disposition;
import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.MessageBody;
import io.github.sudoitir.openbroker.messaging.MessageHeaders;
import io.github.sudoitir.openbroker.messaging.MessageRef;
import io.github.sudoitir.openbroker.messaging.Receiver;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import jakarta.jms.Connection;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Store first, acknowledge second (ADR-0077), against a real broker: a failed store loses
 * nothing and stores nothing twice, and one drain's acknowledgement never covers rows another
 * drain has not stored.
 */
class CaptureConsumerTest extends ArtemisIntegrationTest {

    private static final int BATCH = 50;

    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();
    private final List<MessageIndexWriter.Captured> stored = new CopyOnWriteArrayList<>();

    private ClusterConnection connection;
    private MessageIndexWriter writer;
    private CaptureConsumer consumer;

    @BeforeEach
    void setUp() {
        connection = cluster(nodeId);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(any())).thenAnswer(invocation -> connection);
        writer = mock(MessageIndexWriter.class);
        consumer = new CaptureConsumer(new BrokerMessages(gateway), new CaptureBus(), writer);
    }

    @AfterEach
    void tearDown() {
        consumer.closeAll();
        connection.close();
    }

    @Test
    void aFailedStoreIsRedeliveredAndStoredOnceWithoutLoss() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
                    List<MessageIndexWriter.Captured> batch = List.copyOf(invocation.getArgument(0));
                    if (calls.getAndIncrement() < 2) {
                        throw new DataAccessResourceFailureException("database unavailable");
                    }
                    stored.addAll(batch);
                    return null;
                })
                .when(writer)
                .capturedBatch(anyList());
        String queue = "capture.test." + UUID.randomUUID();
        send(queue, 3 * BATCH);

        consumer.start(spec(queue, "ORDER.IN"));

        awaitTrue(() -> stored.size() >= 3 * BATCH, Duration.ofSeconds(60));
        Set<Long> ids = stored.stream().map(c -> c.row().messageId()).collect(Collectors.toSet());
        assertThat(ids).hasSize(3 * BATCH);
        assertThat(stored).hasSize(3 * BATCH);
        assertThat(consumer.takeShortfall(nodeId, subscriptionId).storeFailure())
                .contains("database unavailable");
    }

    @Test
    void oneDrainNeverAcknowledgesRowsAnotherDrainHasNotStored() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<List<String>> batchAddresses = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
                    List<MessageIndexWriter.Captured> batch = List.copyOf(invocation.getArgument(0));
                    batchAddresses.add(batch.stream()
                            .map(c -> c.row().address())
                            .distinct()
                            .toList());
                    if (batch.getFirst().row().address().equals("SLOW")) {
                        release.await(30, TimeUnit.SECONDS);
                    }
                    stored.addAll(batch);
                    return null;
                })
                .when(writer)
                .capturedBatch(anyList());
        String slow = "capture.slow." + UUID.randomUUID();
        String fast = "capture.fast." + UUID.randomUUID();
        send(slow, BATCH);
        send(fast, BATCH);

        consumer.start(spec(slow, "SLOW"));
        consumer.start(spec(fast, "FAST"));

        awaitTrue(() -> count("FAST") == BATCH, Duration.ofSeconds(30));
        assertThat(count("SLOW")).isZero();
        release.countDown();
        awaitTrue(() -> count("SLOW") == BATCH, Duration.ofSeconds(30));
        // Every write carried one drain's rows only: no drain can commit, or acknowledge, another's.
        assertThat(batchAddresses).allSatisfy(addresses -> assertThat(addresses).hasSize(1));
    }

    @Test
    void stoppingAClusterClosesItsDrainsAndLeavesOthersRunning() throws Exception {
        String queue = "capture.cluster." + UUID.randomUUID();
        send(queue, 1);
        consumer.start(spec(queue, "ORDER.IN"));
        assertThat(consumer.isDraining(nodeId, "tap." + queue)).isTrue();

        consumer.stopCluster(UUID.randomUUID());
        assertThat(consumer.isDraining(nodeId, "tap." + queue))
                .as("another cluster's stop")
                .isTrue();

        consumer.stopCluster(clusterId);
        assertThat(consumer.isDraining(nodeId, "tap." + queue)).isFalse();
    }

    /**
     * A tap on an address that carries a handful of messages an hour would otherwise never reach
     * the acknowledge batch: its copies sat unstored in the drain and unacknowledged on the capture
     * queue, so the index answered nothing about them and the queue grew towards its ring bound.
     */
    @Test
    void aBatchTooSmallToAcknowledgeIsStoredAtTheNextFlush() throws Exception {
        doAnswer(invocation -> {
                    stored.addAll(List.copyOf(invocation.getArgument(0)));
                    return null;
                })
                .when(writer)
                .capturedBatch(anyList());
        String queue = "capture.partial." + UUID.randomUUID();
        send(queue, 3);

        consumer.start(spec(queue, "ORDER.IN"));
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(stored)
                        .as("three messages are far short of a full batch")
                        .isEmpty());

        consumer.flushAll();

        awaitTrue(() -> stored.size() == 3, Duration.ofSeconds(30));
    }

    /**
     * A stopped context can be started again, and the reconciler then installs the drains afresh.
     * A shutdown that left the flush path unusable meant partial batches were never committed
     * again: the index would go quiet and the capture queues grow to their ring bound, silently.
     */
    @Test
    void aPartialBatchIsStillFlushedAfterAShutdownAndRestart() throws Exception {
        doAnswer(invocation -> {
                    stored.addAll(List.copyOf(invocation.getArgument(0)));
                    return null;
                })
                .when(writer)
                .capturedBatch(anyList());
        consumer.closeAll();

        String queue = "capture.restart." + UUID.randomUUID();
        send(queue, 3);
        consumer.start(spec(queue, "ORDER.IN"));
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(stored)
                        .as("three messages are far short of a full batch")
                        .isEmpty());

        consumer.flushAll();

        awaitTrue(() -> stored.size() == 3, Duration.ofSeconds(30));
    }

    @Test
    void aDrainWhoseConnectionFailedIsNoLongerReportedAsDraining() throws Exception {
        String queue = "capture.dead." + UUID.randomUUID();
        send(queue, 1);
        CaptureConsumer.Spec spec = spec(queue, "ORDER.IN");
        consumer.start(spec);
        assertThat(consumer.isDraining(nodeId, spec.name())).isTrue();

        connection.close();
        connection = cluster(nodeId);

        // The reconciler installs a tap again whenever its node is not draining it.
        awaitTrue(() -> !consumer.isDraining(nodeId, spec.name()), Duration.ofSeconds(10));
        assertThat(consumer.drainingOn(nodeId)).isEmpty();
    }

    /**
     * Measured on the dev stack: after a Postgres outage the redelivered backlog ran past the
     * subscription's rate cap, and a cap that dropped acknowledged most of it unstored.
     */
    @Test
    void aBacklogOverTheRateCapIsSlowedAndStoredNotDropped() throws Exception {
        doAnswer(invocation -> {
                    stored.addAll(List.copyOf(invocation.getArgument(0)));
                    return null;
                })
                .when(writer)
                .capturedBatch(anyList());
        String queue = "capture.rate." + UUID.randomUUID();
        send(queue, 6 * BATCH);

        consumer.start(spec(queue, "ORDER.IN", 100));

        awaitTrue(() -> stored.size() >= 6 * BATCH, Duration.ofSeconds(30));
        assertThat(stored).hasSize(6 * BATCH);
    }

    @Test
    void aPoisonMessageIsRetriedThenCountedAsLostAndAcknowledged() {
        Receiver receiver = mock(Receiver.class);
        // An id that is not a number cannot be read into a row.
        Message poison = new Message(
                new MessageRef(ResourceId.of("artemis.queue", "capture.poison"), Shard.node(NodeId.of("n")), "poison"),
                MessageHeaders.builder().build(),
                Map.of(),
                MessageBody.of(Blob.of(new byte[0])),
                Set.of(),
                Map.of());
        CaptureConsumer.Drain drain = consumer.new Drain(spec("capture.poison", "ORDER.IN"), receiver);

        // The broker redelivers after each rollback; here the redeliveries are the calls themselves.
        drain.take(poison);
        drain.take(poison);
        Mockito.verify(receiver, Mockito.never()).settle(any(), any());
        drain.take(poison);

        Mockito.verify(receiver, Mockito.times(2)).rollback();
        Mockito.verify(receiver).settle(poison, Disposition.accept());
        Mockito.verify(receiver).commit();
        assertThat(drain.takeShortfall().unreadable()).isEqualTo(1);
    }

    private long count(String address) {
        return stored.stream().filter(c -> c.row().address().equals(address)).count();
    }

    private CaptureConsumer.Spec spec(String queue, String sourceAddress) {
        return spec(queue, sourceAddress, 10_000);
    }

    private CaptureConsumer.Spec spec(String queue, String sourceAddress, int maxRate) {
        return new CaptureConsumer.Spec(
                clusterId, nodeId, "primary", subscriptionId, "tap." + queue, queue, sourceAddress, 10_000, maxRate);
    }

    private static void send(String queue, int count) throws Exception {
        try (ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(coreUrl());
                Connection connection = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            for (int i = 0; i < count; i++) {
                producer.send(session.createTextMessage("message " + i));
            }
        }
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout) {
        await().atMost(timeout).until(condition::getAsBoolean);
    }
}
