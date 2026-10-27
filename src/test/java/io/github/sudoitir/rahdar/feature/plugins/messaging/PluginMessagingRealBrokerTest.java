package io.github.sudoitir.rahdar.feature.plugins.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.PluginMessagingReconciler;
import io.github.sudoitir.rahdar.feature.sql.CaptureMode;
import io.github.sudoitir.rahdar.feature.sql.CaptureReconciler;
import io.github.sudoitir.rahdar.feature.sql.MessageIndexService;
import io.github.sudoitir.rahdar.kernel.plugin.PluginPurged;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginRuntime;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginRuntimeFactory;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.rahdar.kernel.security.AccessChanges;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.NodeOverrideRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.DivertOperations;
import io.github.sudoitir.rahdar.system.artemis.MBeanNames;
import io.github.sudoitir.rahdar.system.artemis.ManagementRequest;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Plugin messaging against a real broker (ADR-0111): a fixture plugin with a message handler is
 * activated for real, registers taps and consumers through its own {@link PluginMessaging}, and
 * everything the plugin-messaging spec promises is checked on the broker itself.
 *
 * <p>The scheduled pass is pushed out of reach; each test drives the reconciler itself.
 */
@ExtendWith(AdminAuthenticationExtension.class)
@TestPropertySource(
        properties = {
            "rahdar.capture.broker-role=amq",
            "rahdar.plugins.messaging.reconcile-interval=1h",
            "rahdar.plugins.messaging.tap-ring-size=5",
            "rahdar.capture.reconcile-interval=1h",
            "rahdar.capture.flush-interval=200ms"
        })
class PluginMessagingRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    PluginRuntimeFactory runtimeFactory;

    @Autowired
    PluginRuntimeRegistry runtimes;

    @Autowired
    PluginDescriptorParser descriptorParser;

    @Autowired
    PluginMessagingReconciler reconciler;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterDirectory directory;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerConnections connections;

    @Autowired
    QueueLifecycleOperations queueOps;

    @Autowired
    DivertOperations divertOps;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    AccessChanges accessChanges;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MessageIndexService indexes;

    @Autowired
    CaptureReconciler captureReconciler;

    @Autowired
    QueueSnapshotUpsert snapshots;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final List<PluginRuntime> active = new ArrayList<>();
    private final List<String> queues = new ArrayList<>();

    private UUID clusterId;
    private ArtemisNode client;
    private String broker;
    private UUID operator;
    private Connection jms;

    @BeforeEach
    void setUp() throws Exception {
        var attempt = clusterService.register(new RegisterClusterRequest(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "plugin-messaging-" + run,
                null,
                new RegisterClusterRequest.Credentials(
                        ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                null,
                null,
                null,
                null,
                false));
        if (!(attempt instanceof Attempt.Ok<ClusterDetail> ok)) {
            throw new IllegalStateException("could not register the container broker: " + attempt);
        }
        clusterId = ok.value().id();
        ClusterNode node = directory.nodes(clusterId).getFirst();
        clusterService.overrideNodeUrl(
                clusterId, node.getId(), new NodeOverrideRequest(null, ArtemisIntegrationTest.coreUrl()));
        client = ArtemisIntegrationTest.jolokiaClient(ArtemisIntegrationTest.jolokiaUrl());
        broker = client.resolveBrokerObjectName();
        operator = user(Set.of("message:read", "message:send", "subscription:purge"));

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(ArtemisIntegrationTest.coreUrl());
        jms = factory.createConnection(ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD);
        jms.start();
    }

    @AfterEach
    void tearDown() {
        for (PluginRuntime runtime : active) {
            quietly(runtime::close);
            runtimes.remove(runtime.id());
            events.publishEvent(new PluginPurged(runtime.id()));
            MessagingProbe.forget(runtime.id());
        }
        reconciler.reconcileNow(clusterId);
        for (String queue : queues) {
            quietly(() -> queueOps.destroyQueue(client, broker, queue, true));
            quietly(() -> queueOps.deleteAddress(client, broker, queue));
        }
        quietly(jms::close);
        clusters.deleteById(clusterId);
    }

    // ---- tap -----------------------------------------------------------------

    @Test
    void aTapCopiesEveryMessageAndLeavesTheExistingConsumerUntouched() throws Exception {
        String queue = queue("TAP");
        String plugin = activate("tap");
        MessageRegistration reg = messaging(plugin)
                .register(new RegistrationSpec(
                        "orders", clusterId, ResourceRef.subscription(queue), RegistrationMode.TAP, 1, operator));
        assertThat(reg.state()).isEqualTo(RegistrationState.ACTIVE);
        assertThat(tapQueues()).hasSize(1);

        Session session = jms.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageConsumer application = session.createConsumer(session.createQueue(queue));
        send(queue, 3, 16);

        for (int i = 0; i < 3; i++) {
            assertThat(application.receive(10_000))
                    .as("application message %d", i)
                    .isNotNull();
        }
        for (int i = 0; i < 3; i++) {
            PluginMessage copy = MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS);
            assertThat(copy).as("copy %d", i).isNotNull();
            assertThat(copy.properties()).containsEntry("type", "A");
            assertThat(copy.registrationKey()).isEqualTo("orders");
        }
        assertThat(application.receive(500)).isNull();
    }

    @Test
    void aPluginThatStopsKeepingUpNeverBlocksProducersAndItsDropsAreCounted() throws Exception {
        String queue = queue("SLOW");
        String plugin = activate("slow");
        MessagingProbe.GATE.put(plugin, new Semaphore(0));
        messaging(plugin)
                .register(new RegistrationSpec(
                        "slow", clusterId, ResourceRef.subscription(queue), RegistrationMode.TAP, 1, operator));

        long started = System.nanoTime();
        send(queue, 100, 64 * 1024);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
        assertThat(queueCount(queue)).isEqualTo(100);

        reconciler.reconcileNow(clusterId);
        MessageRegistration reg = messaging(plugin).registration("slow").orElseThrow();
        assertThat(reg.droppedCopies()).isNotNull().isGreaterThan(0L);
    }

    // ---- consume -------------------------------------------------------------

    @Test
    void aRejectedMessageIsRedeliveredAndAnAcceptedOneLeavesTheQueue() throws Exception {
        String queue = queue("CONSUME");
        String plugin = activate("consume");
        MessagingProbe.ANSWER.put(plugin, Disposition.REJECT);
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 1, operator));

        send(queue, 1, 16);
        PluginMessage first = MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(first.deliveryCount()).isEqualTo(1);

        MessagingProbe.ANSWER.put(plugin, Disposition.ACCEPT);
        PluginMessage again = null;
        for (int i = 0; i < 20 && (again == null || again.deliveryCount() < 2); i++) {
            again = MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS);
        }
        assertThat(again).isNotNull();
        assertThat(again.deliveryCount()).isGreaterThanOrEqualTo(2);
        assertThat(again.headers()).containsEntry("messageId", first.headers().get("messageId"));
        awaitQueueCount(queue, 0);
    }

    @Test
    void aPluginThatStopsItsOwnRegistrationFromItsHandlerDoesNotWaitOnItself() throws Exception {
        String queue = queue("CONSUME");
        String plugin = activate("consume");
        CountDownLatch stopped = new CountDownLatch(1);
        MessagingProbe.ON_RECEIVE.put(plugin, () -> {
            messaging(plugin).unregister("work");
            stopped.countDown();
        });
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 1, operator));

        send(queue, 1, 16);

        // A stop that waited for the handler asking for it would hold it for the whole stop grace (30 s).
        assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS)).isNotNull();
        awaitQueueCount(queue, 0);
    }

    /** The broker's default {@code max-delivery-attempts}, which the container broker keeps. */
    private static final int DELIVERY_ATTEMPTS = 10;

    @Test
    void aReleasedMessageNeverSpendsADeliveryAttempt() throws Exception {
        String queue = queue("RELEASE");
        String plugin = activate("release");
        MessagingProbe.ANSWER.put(plugin, Disposition.RELEASE);
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 1, operator));

        send(queue, 1, 16);
        for (int i = 0; i < DELIVERY_ATTEMPTS + 5; i++) {
            PluginMessage m = MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS);
            assertThat(m).as("delivery %d", i + 1).isNotNull();
            assertThat(m.deliveryCount()).as("delivery %d", i + 1).isEqualTo(1);
        }
        MessagingProbe.ANSWER.put(plugin, Disposition.ACCEPT);
        awaitQueueCount(queue, 0);
    }

    @Test
    void aRejectedMessageStillRunsOutOfAttempts() throws Exception {
        String queue = queue("EXHAUST");
        String plugin = activate("exhaust");
        MessagingProbe.ANSWER.put(plugin, Disposition.REJECT);
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 1, operator));

        send(queue, 1, 16);
        int last = 0;
        for (PluginMessage m; (m = MessagingProbe.inbox(plugin).poll(5, TimeUnit.SECONDS)) != null; ) {
            last = m.deliveryCount();
        }
        assertThat(last).isEqualTo(DELIVERY_ATTEMPTS);
        awaitQueueCount(queue, 0);
    }

    @Test
    void messagesAPluginHadNotSettledWhenItStoppedAreDeliveredAgain() throws Exception {
        String queue = queue("STOP");
        String plugin = activate("stop");
        Semaphore gate = new Semaphore(0);
        MessagingProbe.GATE.put(plugin, gate);
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 1, operator));
        send(queue, 2, 16);
        assertThat(MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS)).isNotNull();

        // Stop the plugin while it holds the first message and the second is still unsettled.
        PluginRuntime runtime = active.getFirst();
        Thread stopping = Thread.ofVirtual().start(runtime::close);
        await().atMost(Duration.ofSeconds(10)).until(() -> stopping.getState() != Thread.State.RUNNABLE);
        gate.release(1);
        stopping.join(Duration.ofSeconds(30));
        runtimes.remove(plugin);
        active.remove(runtime);
        events.publishEvent(new PluginPurged(plugin));
        MessagingProbe.forget(plugin);

        // The one it accepted is gone; the one it never answered is back on the queue.
        awaitQueueCount(queue, 1);

        String again = activate("stop2");
        messaging(again)
                .register(new RegistrationSpec(
                        "work", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 1, operator));
        PluginMessage redelivered = MessagingProbe.inbox(again).poll(10, TimeUnit.SECONDS);
        assertThat(redelivered).isNotNull();
        awaitQueueCount(queue, 0);
    }

    // ---- concurrency and flow control (ADR-0112) ------------------------------

    @Test
    void aConsumerRunsAsManyHandlersAtOnceAsItsConcurrencyAndAChangedConcurrencyTakesEffect() throws Exception {
        String queue = queue("PAR");
        String plugin = activate("par");
        Semaphore gate = new Semaphore(0);
        MessagingProbe.GATE.put(plugin, gate);
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 2, operator));
        MessageRegistration reg = messaging(plugin)
                .register(new RegistrationSpec(
                        "work", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 4, operator));
        assertThat(reg.concurrency()).isEqualTo(4);
        assertThat(reg.state()).isEqualTo(RegistrationState.ACTIVE);

        send(queue, 5, 16);
        // Every handler blocks, so four in the inbox at once means four ran at the same time.
        for (int i = 0; i < 4; i++) {
            assertThat(MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS))
                    .as("handler %d", i)
                    .isNotNull();
        }
        assertThat(MessagingProbe.inbox(plugin).poll(1, TimeUnit.SECONDS))
                .as("a fifth while four are busy")
                .isNull();
        gate.release(10_000);
        awaitQueueCount(queue, 0);
    }

    @Test
    void aStalledConsumerHoldsAtMostItsConcurrencyAndTheRestStayOnTheQueue() throws Exception {
        String queue = queue("STALL");
        String plugin = activate("stall");
        Semaphore gate = new Semaphore(0);
        MessagingProbe.GATE.put(plugin, gate);
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 3, operator));

        send(queue, 100, 16);
        for (int i = 0; i < 3; i++) {
            assertThat(MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS)).isNotNull();
        }
        // Nothing more arrives, and the broker holds back the rest, for a full second.
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(MessagingProbe.inbox(plugin)).isEmpty();
            assertThat(queueAttribute(queue, "DeliveringCount")).isLessThanOrEqualTo(3);
            assertThat(queueAttribute(queue, "MessageCount")).isEqualTo(100);
        });

        gate.release(10_000);
        awaitQueueCount(queue, 0);
    }

    @Test
    void aMessageGroupIsHandledInOrderAtAConcurrencyAboveOne() throws Exception {
        String queue = queue("GROUP");
        String plugin = activate("group");
        Semaphore gate = new Semaphore(0);
        MessagingProbe.GATE.put(plugin, gate);
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 4, operator));

        try (Session session = jms.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            for (int seq = 1; seq <= 3; seq++) {
                for (String group : List.of("G", "H")) {
                    TextMessage m = session.createTextMessage(group + seq);
                    m.setStringProperty("JMSXGroupID", group);
                    producer.send(m);
                }
            }
        }
        // The first of each group is being handled; free consumers do not take the rest of a group.
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            PluginMessage first = MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS);
            assertThat(first).isNotNull();
            order.add(new String(first.body(), StandardCharsets.UTF_8));
        }
        assertThat(order).containsExactlyInAnyOrder("G1", "H1");
        assertThat(MessagingProbe.inbox(plugin).poll(1, TimeUnit.SECONDS)).isNull();

        gate.release(10_000);
        awaitQueueCount(queue, 0);
        MessagingProbe.inbox(plugin).forEach(m -> order.add(new String(m.body(), StandardCharsets.UTF_8)));
        assertThat(order).hasSize(6);
        assertThat(order.stream().filter(b -> b.startsWith("G"))).containsExactly("G1", "G2", "G3");
        assertThat(order.stream().filter(b -> b.startsWith("H"))).containsExactly("H1", "H2", "H3");
    }

    @Test
    void blockedPluginHandlersDoNotDelayCaptureOnTheSameCluster() throws Exception {
        // More blocked handlers than the Core client's global pool has threads: if plugin handlers
        // ran there, capture's deliveries would wait for one of them.
        int globalThreads = ActiveMQClient.DEFAULT_GLOBAL_THREAD_POOL_MAX_SIZE;
        int registrations = globalThreads / 32 + 1;
        String plugin = activate("block");
        Semaphore gate = new Semaphore(0);
        MessagingProbe.GATE.put(plugin, gate);
        for (int i = 0; i < registrations; i++) {
            String queue = queue("BLOCK" + i);
            messaging(plugin)
                    .register(new RegistrationSpec(
                            "work" + i,
                            clusterId,
                            ResourceRef.subscription(queue),
                            RegistrationMode.CONSUME,
                            32,
                            operator));
            send(queue, 32, 16);
        }
        int running = Math.min(registrations * 32, 64);
        for (int i = 0; i < running; i++) {
            assertThat(MessagingProbe.inbox(plugin).poll(20, TimeUnit.SECONDS))
                    .as("blocked handler %d", i)
                    .isNotNull();
        }

        String captured = queue("CAPTURED");
        ClusterNode node = directory.nodes(clusterId).getFirst();
        snapshots.upsertBatch(List.of(new QueueRow(
                clusterId,
                node.getId(),
                captured,
                captured,
                "ANYCAST",
                true,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                "artemis.queue")));
        UUID subscription = indexes.create(
                        clusterId,
                        new MessageIndexService.Spec(
                                captured, null, 1, null, CaptureMode.CAPTURE, 1000L, null, null, null, null))
                .entity()
                .getId();
        try {
            captureReconciler.reconcileNow(clusterId);
            send(captured, 20, 16);
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(capturedCount())
                            .as("copies captured while plugin handlers block")
                            .isEqualTo(20));
        } finally {
            gate.release(10_000);
            quietly(() -> indexes.delete(clusterId, subscription));
            captureReconciler.reconcileNow(clusterId);
        }
    }

    @Test
    void aConcurrencyOutsideItsRangeIsRefusedWithTheReason() throws Exception {
        String queue = queue("RANGE");
        PluginMessaging messaging = messaging(activate("range"));
        RegistrationSpec none = new RegistrationSpec(
                "x", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 0, operator);
        RegistrationSpec tooMany = new RegistrationSpec(
                "x", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 33, operator);
        RegistrationSpec tapped = new RegistrationSpec(
                "x", clusterId, ResourceRef.subscription(queue), RegistrationMode.TAP, 2, operator);
        assertThatThrownBy(() -> messaging.register(none))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("1 to 32");
        assertThatThrownBy(() -> messaging.register(tooMany))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("1 to 32");
        assertThatThrownBy(() -> messaging.register(tapped))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("concurrency is 1");
        assertThat(messaging.registrations()).isEmpty();
    }

    // ---- send ----------------------------------------------------------------

    @Test
    void aSentMessageArrivesWithItsBodyAndHeaders() throws Exception {
        String queue = queue("SEND");
        String plugin = activate("send");
        Session session = jms.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageConsumer consumer = session.createConsumer(session.createQueue(queue));

        messaging(plugin)
                .send(new OutboundMessage(
                        clusterId,
                        ResourceRef.destination(queue),
                        "{\"amount\":5000}".getBytes(StandardCharsets.UTF_8),
                        true,
                        Map.of("type", "A"),
                        Map.of("attempt", 3),
                        true,
                        operator));

        Message received = consumer.receive(10_000);
        assertThat(received).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) received).getText()).isEqualTo("{\"amount\":5000}");
        assertThat(received.getStringProperty("type")).isEqualTo("A");
        assertThat(received.getIntProperty("attempt")).isEqualTo(3);
    }

    @Test
    void aSendWithAnInvalidPropertyNameIsRefusedWithTheReason() throws Exception {
        String queue = queue("SENDBAD");
        PluginMessaging messaging = messaging(activate("sendbad"));
        OutboundMessage badHeader = new OutboundMessage(
                clusterId,
                ResourceRef.destination(queue),
                new byte[0],
                true,
                Map.of("x-header", "v"),
                Map.of(),
                false,
                operator);
        OutboundMessage badProperty = new OutboundMessage(
                clusterId,
                ResourceRef.destination(queue),
                new byte[0],
                true,
                Map.of(),
                Map.of("JMSType", 1),
                false,
                operator);
        assertThatThrownBy(() -> messaging.send(badHeader))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("Invalid property name 'x-header'");
        assertThatThrownBy(() -> messaging.send(badProperty))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("Invalid property name 'JMSType'");
    }

    @Test
    void sendingNeedsMessageSendAndNeverReachesRahdarsOwnAddresses() throws Exception {
        String queue = queue("SENDNO");
        PluginMessaging messaging = messaging(activate("sendno"));
        UUID reader = user(Set.of("message:read"));
        OutboundMessage unauthorised = new OutboundMessage(
                clusterId, ResourceRef.destination(queue), new byte[0], true, Map.of(), Map.of(), false, reader);
        OutboundMessage reserved = new OutboundMessage(
                clusterId,
                ResourceRef.destination("rahdar.capture.x"),
                new byte[0],
                true,
                Map.of(),
                Map.of(),
                false,
                operator);
        assertThatThrownBy(() -> messaging.send(unauthorised))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("message:send");
        assertThatThrownBy(() -> messaging.send(reserved))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void checkSendGivesTheReasonASendWouldBeRefusedWithoutSending() throws Exception {
        String queue = queue("CHECK");
        String plugin = activate("check");
        PluginMessaging messaging = messaging(plugin);
        UUID reader = user(Set.of("message:read"));
        Session session = jms.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageConsumer consumer = session.createConsumer(session.createQueue(queue));

        assertThat(messaging.checkSend(clusterId, ResourceRef.destination(queue), operator))
                .isEmpty();
        assertThat(messaging.checkSend(clusterId, ResourceRef.destination(queue), reader))
                .get(STRING)
                .contains("message:send");
        assertThat(messaging.checkSend(clusterId, ResourceRef.destination("rahdar.capture.x"), operator))
                .get(STRING)
                .contains("reserved");
        assertThat(messaging.checkSend(UUID.randomUUID(), ResourceRef.destination(queue), operator))
                .get(STRING)
                .contains("not registered");
        assertThat(messaging.checkSend(clusterId, ResourceRef.destination(" "), operator))
                .get(STRING)
                .contains("a target");
        assertThat(messaging.checkSend(clusterId, ResourceRef.destination("q".repeat(1_001)), operator))
                .get(STRING)
                .contains("longer than");
        assertThat(consumer.receive(500)).isNull();
    }

    // ---- lifecycle and permissions ------------------------------------------

    @Test
    void aStoppedPluginLeavesNothingOnTheBroker() throws Exception {
        String queue = queue("GONE");
        String plugin = activate("gone");
        messaging(plugin)
                .register(new RegistrationSpec(
                        "orders", clusterId, ResourceRef.subscription(queue), RegistrationMode.TAP, 1, operator));
        assertThat(tapQueues()).hasSize(1);
        PluginMessaging handle = messaging(plugin);

        PluginRuntime runtime = active.getFirst();
        runtime.close();
        runtimes.remove(plugin);
        reconciler.reconcileNow(clusterId);

        assertThat(tapQueues()).isEmpty();
        assertThat(tapDiverts()).isEmpty();
        assertThat(handle.registration("orders").orElseThrow().state()).isEqualTo(RegistrationState.INACTIVE);

        events.publishEvent(new PluginPurged(plugin));
        assertThat(handle.registrations()).isEmpty();
        active.remove(runtime);
        MessagingProbe.forget(plugin);
    }

    @Test
    void unregisteringRemovesTheTapAtOnce() throws Exception {
        String queue = queue("UNREG");
        String plugin = activate("unreg");
        messaging(plugin)
                .register(new RegistrationSpec(
                        "orders", clusterId, ResourceRef.subscription(queue), RegistrationMode.TAP, 1, operator));
        assertThat(tapQueues()).hasSize(1);
        assertThat(messaging(plugin).unregister("orders")).isTrue();
        assertThat(tapQueues()).isEmpty();
        assertThat(messaging(plugin).registrations()).isEmpty();
    }

    @Test
    void aRevokedGrantSuspendsTheRegistrationAndARestoredOneResumesIt() throws Exception {
        String queue = queue("REVOKE");
        String plugin = activate("revoke");
        UUID reader = user(Set.of("message:read"));
        messaging(plugin)
                .register(new RegistrationSpec(
                        "orders", clusterId, ResourceRef.subscription(queue), RegistrationMode.TAP, 1, reader));
        assertThat(tapQueues()).hasSize(1);

        List<UserRoleEntity> grants = tx.execute(s -> userRoles.findByIdUserId(reader));
        userRoles.deleteAll(grants);
        accessChanges.changedFor(reader);
        reconciler.reconcileNow(clusterId);

        MessageRegistration suspended = messaging(plugin).registration("orders").orElseThrow();
        assertThat(suspended.state()).isEqualTo(RegistrationState.SUSPENDED);
        assertThat(suspended.detail()).contains("message:read").contains(clusterId.toString());
        assertThat(tapQueues()).isEmpty();

        userRoles.saveAll(grants.stream()
                .map(g -> new UserRoleEntity(
                        reader,
                        g.getId().getRoleId(),
                        g.getId().getScopeType(),
                        g.getId().getScopeId()))
                .toList());
        accessChanges.changedFor(reader);
        reconciler.reconcileNow(clusterId);
        assertThat(messaging(plugin).registration("orders").orElseThrow().state())
                .isEqualTo(RegistrationState.ACTIVE);
    }

    @Test
    void aRegistrationIsRefusedWithoutThePermissionsItsModeNeeds() throws Exception {
        String queue = queue("DENY");
        PluginMessaging messaging = messaging(activate("deny"));
        UUID nobody = user(Set.of());
        UUID reader = user(Set.of("message:read"));
        RegistrationSpec unreadable =
                new RegistrationSpec("x", clusterId, ResourceRef.subscription(queue), RegistrationMode.TAP, 1, nobody);
        RegistrationSpec unpurgeable = new RegistrationSpec(
                "x", clusterId, ResourceRef.subscription(queue), RegistrationMode.CONSUME, 1, reader);
        RegistrationSpec reserved = new RegistrationSpec(
                "x", clusterId, ResourceRef.subscription("rahdar.capture.abc"), RegistrationMode.TAP, 1, operator);
        assertThatThrownBy(() -> messaging.register(unreadable))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("message:read");
        assertThatThrownBy(() -> messaging.register(unpurgeable))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("subscription:purge");
        assertThatThrownBy(() -> messaging.register(reserved))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("reserved");
        assertThat(messaging.registrations()).isEmpty();
    }

    @Test
    void pluginsSeeAndReceiveOnlyTheirOwnRegistrations() throws Exception {
        String queueA = queue("ISOA");
        String queueB = queue("ISOB");
        String a = activate("isoa");
        String b = activate("isob");
        messaging(a)
                .register(new RegistrationSpec(
                        "same", clusterId, ResourceRef.subscription(queueA), RegistrationMode.TAP, 1, operator));
        messaging(b)
                .register(new RegistrationSpec(
                        "same", clusterId, ResourceRef.subscription(queueB), RegistrationMode.TAP, 1, operator));

        assertThat(messaging(a).registrations())
                .extracting(r -> r.source().name())
                .containsExactly(queueA);
        assertThat(messaging(b).registrations())
                .extracting(r -> r.source().name())
                .containsExactly(queueB);

        send(queueA, 1, 16);
        assertThat(MessagingProbe.inbox(a).poll(10, TimeUnit.SECONDS)).isNotNull();
        assertThat(MessagingProbe.inbox(b).poll(1, TimeUnit.SECONDS)).isNull();

        messaging(b).unregister("same");
        assertThat(messaging(a).registration("same")).isPresent();
    }

    // ---- fixture -------------------------------------------------------------

    private String activate(String name) throws Exception {
        String id = "acme-msg" + name + "-" + Math.abs(new SecureRandom().nextInt(1_000_000));
        String pkg = "com.acme.msg" + name + Math.abs(id.hashCode());
        Path jar = new PluginJarBuilder(id)
                .descriptorField("basePackage", pkg)
                .descriptorField("configuration", pkg + ".PluginConfig")
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                        </databaseChangeLog>
                        """)
                .source(pkg + ".Handler", """
                        package %s;
                        import io.github.sudoitir.rahdar.feature.plugins.messaging.*;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class Handler implements PluginMessageHandler {
                            public Disposition onMessage(PluginMessage m) {
                                return MessagingProbe.received("%s", m);
                            }
                        }
                        """.formatted(pkg, id))
                .source(pkg + ".Holder", """
                        package %s;
                        import io.github.sudoitir.rahdar.feature.plugins.messaging.*;
                        import io.github.sudoitir.rahdar.kernel.security.PluginSecrets;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class Holder {
                            public Holder(PluginMessaging messaging, PluginSecrets secrets) {
                                MessagingProbe.MESSAGING.put("%s", messaging);
                                MessagingProbe.SECRETS.put("%s", secrets);
                            }
                        }
                        """.formatted(pkg, id, id))
                .build();
        PluginDescriptor descriptor;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            descriptor = descriptorParser.parse(jarFile.getInputStream(jarFile.getEntry("META-INF/rahdar/plugin.json"))
                    .readAllBytes());
        }
        PluginRuntime runtime = runtimeFactory.activate(descriptor, jar, webContext.getServletContext());
        runtimes.set(id, new PluginRuntimeRegistry.Active(runtime));
        active.add(runtime);
        return id;
    }

    private static PluginMessaging messaging(String pluginId) {
        return MessagingProbe.MESSAGING.get(pluginId);
    }

    private UUID user(Set<String> permissions) {
        String username = "pm-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(username, username + "@example.test", "{noop}unused");
        user.setMustChangePassword(false);
        users.save(user);
        RoleEntity role = roles.save(new RoleEntity("role-" + UUID.randomUUID(), false));
        for (String action : permissions) {
            rolePermissions.save(new RolePermissionEntity(role.getId(), action));
        }
        userRoles.save(new UserRoleEntity(user.getId(), role.getId(), Grant.ScopeType.GLOBAL.name(), ScopeIds.GLOBAL));
        return user.getId();
    }

    private String queue(String suffix) {
        String name = "PM." + suffix + "." + run;
        queueOps.createAddress(client, broker, name, "ANYCAST");
        queueOps.createQueue(
                client, broker, Map.of("name", name, "address", name, "routing-type", "ANYCAST", "durable", true));
        queues.add(name);
        return name;
    }

    private void send(String queue, int count, int bytes) throws Exception {
        try (Session session = jms.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            String body = "x".repeat(bytes);
            for (int i = 0; i < count; i++) {
                TextMessage m = session.createTextMessage(body);
                m.setStringProperty("type", "A");
                producer.send(m);
            }
        }
    }

    private long queueCount(String queue) {
        return queueAttribute(queue, "MessageCount");
    }

    private long queueAttribute(String queue, String attribute) {
        JsonNode value = client.single(
                        ManagementRequest.read(MBeanNames.queue(broker, queue, queue, "anycast"), attribute))
                .attribute(attribute);
        return value == null ? -1 : value.asLong();
    }

    private long capturedCount() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM message_index WHERE cluster_id = ? AND origin = 'CAPTURED'",
                Long.class,
                clusterId);
        return count == null ? 0 : count;
    }

    private void awaitQueueCount(String queue, long expected) {
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(queueCount(queue)).isEqualTo(expected));
    }

    private List<String> tapQueues() {
        JsonNode value =
                client.single(ManagementRequest.read(broker, "QueueNames")).attribute("QueueNames");
        return value.valueStream()
                .map(JsonNode::asString)
                .filter(n -> n.startsWith(RahdarNames.PLUGIN_TAP_PREFIX))
                .toList();
    }

    private List<String> tapDiverts() {
        return divertOps.listDiverts(client, null, null).stream()
                .map(d -> d.uniqueName())
                .filter(n -> n != null && n.startsWith(RahdarNames.PLUGIN_TAP_PREFIX))
                .toList();
    }

    private static void quietly(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception _) {
            // already gone, or never created
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
