package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.notification.EventTypes;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import jakarta.jms.Connection;
import jakarta.jms.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The notification streams against a real broker (ADR-0026): they follow "who is live", report the
 * right {@link SubscriptionVerdict}, and hand on what happened in the broker SDK's terms.
 */
class BrokerNotificationsTest extends ArtemisIntegrationTest {

    private final CopyOnWriteArrayList<BrokerEvent> received = new CopyOnWriteArrayList<>();
    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();
    private ClusterConnection connection;
    private BrokerNotifications notifications;

    private void connect(String core) {
        connection = cluster(nodeId, core);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(clusterId)).thenReturn(connection);
        ClockOffsetService clocks = mock(ClockOffsetService.class);
        when(clocks.brokerTime()).thenReturn(BrokerTime.identity());
        notifications = new BrokerNotifications(gateway, List.of(received::add), clocks);
    }

    @AfterEach
    void tearDown() {
        notifications.forget(clusterId);
        connection.close();
    }

    private NodeEndpoint endpoint(String core, boolean active) {
        return new NodeEndpoint(
                nodeId,
                "primary",
                "node-1",
                jolokiaUrl(),
                core,
                "PRIMARY",
                "STARTED",
                active,
                null,
                1L,
                "2.44.0",
                null,
                null,
                Instant.now(),
                null,
                null,
                false,
                true);
    }

    @Test
    void aLiveNodesStreamHandsOnWhatHappenedInStandardTerms() throws Exception {
        connect(coreUrl());
        notifications.reconcile(clusterId, List.of(endpoint(coreUrl(), true)));
        pollUntil(
                Duration.ofSeconds(15),
                () -> notifications.verdictFor(clusterId).isConnected());

        String queue = "notif.it." + System.nanoTime();
        consumeOnce(queue);

        pollUntil(
                Duration.ofSeconds(10),
                () -> received.stream()
                        .anyMatch(
                                e -> e.type().equals(EventTypes.CONSUMER_ATTACHED) && queue.equals(e.subscription())));
        BrokerEvent attached = received.stream()
                .filter(e -> e.type().equals(EventTypes.CONSUMER_ATTACHED) && queue.equals(e.subscription()))
                .findFirst()
                .orElseThrow();
        assertThat(attached.clusterId()).isEqualTo(clusterId);
        assertThat(attached.nodeId()).isEqualTo(nodeId);
        assertThat(attached.destination()).isEqualTo(queue);
        assertThat(attached.data()).containsKey("openbroker.consumer_count");
        assertThat(received)
                .as("the stream's own plumbing is not reported")
                .noneMatch(e -> "activemq.notifications".equals(e.destination()));
    }

    @Test
    void followsFailoverByStoppingWhenTheNodeIsNoLongerLive() {
        connect(coreUrl());
        notifications.reconcile(clusterId, List.of(endpoint(coreUrl(), true)));
        pollUntil(
                Duration.ofSeconds(15),
                () -> notifications.verdictFor(clusterId).isConnected());

        // Same node, no longer serving — its stream is closed.
        notifications.reconcile(clusterId, List.of(endpoint(coreUrl(), false)));

        pollUntil(
                Duration.ofSeconds(5),
                () -> !notifications.verdictFor(clusterId).isConnected());
        assertThat(notifications.nodeStates()).doesNotContainKey(nodeId);
    }

    @Test
    void reportsFailedWhenTheDataPlaneIsUnreachable() {
        connect("tcp://127.0.0.1:1");
        notifications.reconcile(clusterId, List.of(endpoint("tcp://127.0.0.1:1", true)));

        pollUntil(
                Duration.ofSeconds(15),
                () -> notifications.verdictFor(clusterId) instanceof SubscriptionVerdict.Failed);
        assertThat(notifications.verdictFor(clusterId))
                .isInstanceOfSatisfying(
                        SubscriptionVerdict.Failed.class,
                        f -> assertThat(f.kind()).isEqualTo(SubscriptionVerdict.Kind.UNREACHABLE));
    }

    @Test
    void reportsNotAttemptedForAnUnknownCluster() {
        connect(coreUrl());

        assertThat(notifications.verdictFor(UUID.randomUUID())).isInstanceOf(SubscriptionVerdict.NotAttempted.class);
    }

    private static void pollUntil(Duration timeout, BooleanSupplier condition) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(100)).until(condition::getAsBoolean);
    }

    private static void consumeOnce(String queue) throws Exception {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(
                coreUrl() + "?useTopologyForLoadBalancing=false", BROKER_USER, BROKER_PASSWORD);
        factory.setReconnectAttempts(0);
        try (Connection conn = factory.createConnection(BROKER_USER, BROKER_PASSWORD)) {
            conn.start();
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createConsumer(session.createQueue(queue)).close();
            session.close();
        } finally {
            factory.close();
        }
    }
}
