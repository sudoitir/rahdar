package io.github.sudoitir.rahdar.feature.plugins.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.PluginMessagingReconciler;
import io.github.sudoitir.rahdar.kernel.plugin.PluginPurged;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginRuntime;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginRuntimeFactory;
import io.github.sudoitir.rahdar.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.rahdar.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.rahdar.kernel.security.Grant;
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
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.kafka.KafkaModule;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.FeatureUpdate;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.UpdateFeaturesOptions;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Plugin messaging on a Kafka consumer group against a real broker (ADR-0111, ADR-0216): a fixture plugin
 * registers a group on a topic, the log is read once for the whole cluster, and rejected records are retried.
 * Taps are refused, since Kafka cannot copy traffic. Runs on the Kafka image of
 * {@code kafka.image}; the scheduled pass is pushed out of reach and each test drives the reconciler itself.
 */
@ExtendWith(AdminAuthenticationExtension.class)
@TestPropertySource(properties = {"rahdar.plugins.messaging.reconcile-interval=1h"})
class PluginMessagingKafkaRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.image", "apache/kafka:4.4.0");
    private static final String KIND = "kafka.group-topic";

    private static KafkaContainer kafka;

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
    ClusterRepository clusters;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    ApplicationEventPublisher events;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final List<PluginRuntime> active = new ArrayList<>();

    private UUID clusterId;
    private UUID operator;

    @BeforeAll
    static void startKafka() {
        // One broker holds the share groups' state topic, so it is not replicated three times.
        kafka = new KafkaContainer(IMAGE)
                .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR", "1")
                .withEnv("KAFKA_GROUP_SHARE_MIN_RECORD_LOCK_DURATION_MS", "1000");
        kafka.start();
        finalizeShareVersion();
    }

    @AfterAll
    static void stopKafka() {
        kafka.stop();
    }

    @BeforeEach
    void setUp() {
        var attempt = clusterService.register(new RegisterClusterRequest(
                KafkaModule.SYSTEM,
                "plugin-kafka-" + run,
                null,
                null,
                false,
                new KafkaConnection(
                        KafkaModule.SYSTEM,
                        List.of(kafka.getBootstrapServers()),
                        "PLAINTEXT",
                        null,
                        null,
                        null,
                        null)));
        if (!(attempt instanceof Attempt.Ok<ClusterDetail> ok)) {
            throw new IllegalStateException("could not register the container broker: " + attempt);
        }
        clusterId = ok.value().id();
        operator = user(Set.of("message:read", "subscription:purge"));
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
        clusters.deleteById(clusterId);
    }

    @Test
    void aGroupIsReadOnceForTheWholeClusterAndAcceptedRecordsAdvanceItsOffset() throws Exception {
        String topic = topic("accept", 1);
        String group = "g-accept-" + run;
        send(topic, 0, "k", 3);
        String plugin = activate("accept");

        MessageRegistration registered = messaging(plugin)
                .register(new RegistrationSpec(
                        "orders",
                        clusterId,
                        SubscriptionId.of(KIND, group, topic),
                        RegistrationMode.CONSUME,
                        1,
                        operator));
        assertThat(registered.source().id()).isEqualTo(KIND + "/" + group + "/" + topic);

        for (int i = 0; i < 3; i++) {
            PluginMessage message = MessagingProbe.inbox(plugin).poll(30, TimeUnit.SECONDS);
            assertThat(message).isNotNull();
            assertThat(message.source()).isEqualTo(registered.source());
            assertThat(message.message().nodeId()).isNull();
            assertThat(message.bodyText()).isEqualTo("m" + i);
        }
        MessageRegistration reg = messaging(plugin).registration("orders").orElseThrow();
        assertThat(reg.state()).isEqualTo(RegistrationState.ACTIVE);
        assertThat(reg.nodes()).singleElement().satisfies(node -> {
            assertThat(node.nodeId()).isNull();
            assertThat(node.nodeName()).isEqualTo("whole cluster");
            assertThat(node.state()).isEqualTo(RegistrationState.ACTIVE);
        });
        // A consumer group counts only failed deliveries; a release is free.
        assertThat(reg.releasesCounted()).isFalse();
        await().atMost(30, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(committed(group, topic, 0)).isEqualTo(3));
    }

    @Test
    void aRejectedRecordIsRedeliveredAndItsOffsetAdvancesOnceAccepted() throws Exception {
        String topic = topic("reject", 1);
        String group = "g-reject-" + run;
        send(topic, 0, "k", 1);
        String plugin = activate("reject");
        MessagingProbe.ANSWER.put(plugin, Disposition.REJECT);
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work",
                        clusterId,
                        SubscriptionId.of(KIND, group, topic),
                        RegistrationMode.CONSUME,
                        1,
                        operator));

        PluginMessage first = MessagingProbe.inbox(plugin).poll(30, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(first.deliveryCount()).isEqualTo(1);

        MessagingProbe.ANSWER.put(plugin, Disposition.ACCEPT);
        PluginMessage again = MessagingProbe.inbox(plugin).poll(60, TimeUnit.SECONDS);
        assertThat(again).isNotNull();
        assertThat(again.bodyText()).isEqualTo(first.bodyText());
        await().atMost(30, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(committed(group, topic, 0)).isEqualTo(1));
    }

    @Test
    void recordsWithOneKeyKeepTheirOrderAtAnyConcurrency() throws Exception {
        String topic = topic("order", 3);
        String group = "g-order-" + run;
        List<String> keys = List.of("a", "b", "c");
        try (KafkaProducer<byte[], byte[]> producer = producer()) {
            for (int i = 0; i < 5; i++) {
                for (String key : keys) {
                    producer.send(new ProducerRecord<>(topic, key.getBytes(), (key + i).getBytes()))
                            .get();
                }
            }
        }
        String plugin = activate("order");
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work",
                        clusterId,
                        SubscriptionId.of(KIND, group, topic),
                        RegistrationMode.CONSUME,
                        3,
                        operator));

        Map<String, List<Integer>> seen = new HashMap<>();
        for (int i = 0; i < 15; i++) {
            PluginMessage message = MessagingProbe.inbox(plugin).poll(30, TimeUnit.SECONDS);
            assertThat(message).isNotNull();
            String body = message.bodyText();
            seen.computeIfAbsent(body.substring(0, 1), k -> new ArrayList<>()).add(Integer.parseInt(body.substring(1)));
        }
        assertThat(seen.keySet()).containsExactlyInAnyOrderElementsOf(keys);
        seen.values().forEach(sequence -> assertThat(sequence).isSorted().hasSize(5));
    }

    @Test
    void aRecordThatIsAlwaysRejectedIsRetriedInPlaceThenDeadLetteredAndOnlyThenPassedByTheGroup() throws Exception {
        String topic = topic("dlt", 1);
        String group = "g-dlt-" + run;
        send(topic, 0, "k", 1);
        String plugin = activate("dlt");
        MessagingProbe.ANSWER.put(plugin, Disposition.REJECT);
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work",
                        clusterId,
                        SubscriptionId.of(KIND, group, topic),
                        RegistrationMode.CONSUME,
                        1,
                        operator));

        // The group's committed position is read before the dead-letter topic's end, so a committed record
        // whose dead letter was not yet there would show as a zero end.
        long until = System.currentTimeMillis() + 180_000;
        long committed = -1;
        long deadLettered = 0;
        while (System.currentTimeMillis() < until && committed < 1) {
            committed = committed(group, topic, 0);
            deadLettered = endOffset(topic + ".dlt");
            if (committed >= 1) {
                assertThat(deadLettered)
                        .as("a record is passed only once it is dead-lettered")
                        .isEqualTo(1);
            }
            TimeUnit.MILLISECONDS.sleep(500);
        }
        assertThat(committed).isEqualTo(1);
        assertThat(MessagingProbe.inbox(plugin))
                .as("deliveries before the dead letter")
                .hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void aReleaseOnAShareGroupIsRedeliveredWithTheCountRaisedAndTheRegistrationSaysReleasesCount() throws Exception {
        String topic = topic("share", 1);
        String group = shareGroup(topic, true);
        send(topic, 0, "k", 1);
        String plugin = activate("share");
        MessagingProbe.ANSWER.put(plugin, Disposition.RELEASE);
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work",
                        clusterId,
                        SubscriptionId.of("kafka.share-group-topic", group, topic),
                        RegistrationMode.CONSUME,
                        1,
                        operator));

        PluginMessage first = MessagingProbe.inbox(plugin).poll(60, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        MessagingProbe.ANSWER.put(plugin, Disposition.ACCEPT);
        PluginMessage again = MessagingProbe.inbox(plugin).poll(60, TimeUnit.SECONDS);
        assertThat(again).isNotNull();
        assertThat(again.deliveryCount()).isGreaterThan(first.deliveryCount());

        MessageRegistration reg = messaging(plugin).registration("work").orElseThrow();
        assertThat(reg.state()).isEqualTo(RegistrationState.ACTIVE);
        assertThat(reg.releasesCounted()).isTrue();
        assertThat(reg.detail()).contains("release counts as a delivery attempt");
    }

    @Test
    void aShareGroupWithoutADeadLetterTopicFailsTheRegistrationWithTheBrokersPrecondition() throws Exception {
        String topic = topic("sharenodlq", 1);
        String group = shareGroup(topic, false);
        String plugin = activate("sharenodlq");
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work",
                        clusterId,
                        SubscriptionId.of("kafka.share-group-topic", group, topic),
                        RegistrationMode.CONSUME,
                        1,
                        operator));

        MessageRegistration reg = messaging(plugin).registration("work").orElseThrow();
        assertThat(reg.state()).isEqualTo(RegistrationState.FAILED);
        assertThat(reg.detail()).contains("errors.deadletterqueue.topic.name");
    }

    @Test
    void aTapIsRefusedBecauseKafkaCannotCopyTraffic() throws Exception {
        String topic = topic("tap", 1);
        PluginMessaging messaging = messaging(activate("tap"));
        RegistrationSpec tap = new RegistrationSpec(
                "x", clusterId, SubscriptionId.of(KIND, "g-tap-" + run, topic), RegistrationMode.TAP, 1, operator);

        assertThatThrownBy(() -> messaging.register(tap))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("Kafka")
                .hasMessageContaining("consume");
        assertThat(messaging.registrations()).isEmpty();
    }

    @Test
    void aTopicThatDoesNotExistKeepsTheRegistrationPendingUntilItDoes() throws Exception {
        String topic = "late-" + run;
        String group = "g-late-" + run;
        String plugin = activate("late");
        messaging(plugin)
                .register(new RegistrationSpec(
                        "work",
                        clusterId,
                        SubscriptionId.of(KIND, group, topic),
                        RegistrationMode.CONSUME,
                        1,
                        operator));

        assertThat(messaging(plugin).registration("work").orElseThrow().state()).isEqualTo(RegistrationState.PENDING);

        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        }
        send(topic, 0, "k", 1);
        reconciler.reconcileNow(clusterId);

        assertThat(MessagingProbe.inbox(plugin).poll(30, TimeUnit.SECONDS)).isNotNull();
        assertThat(messaging(plugin).registration("work").orElseThrow().state()).isEqualTo(RegistrationState.ACTIVE);
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

    private String topic(String suffix, int partitions) throws Exception {
        String name = "pm-" + suffix + "-" + run;
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1)))
                    .all()
                    .get();
        }
        return name;
    }

    private void send(String topic, int partition, String key, int count) throws Exception {
        try (KafkaProducer<byte[], byte[]> producer = producer()) {
            for (int i = 0; i < count; i++) {
                producer.send(new ProducerRecord<>(topic, partition, key.getBytes(), ("m" + i).getBytes()))
                        .get();
            }
        }
    }

    private long committed(String group, String topic, int partition) throws Exception {
        try (Admin admin = admin()) {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata()
                    .get()
                    .get(new TopicPartition(topic, partition));
            return offset == null ? -1 : offset.offset();
        }
    }

    /** The end of the topic's only partition, or 0 while the topic does not exist yet. */
    private static long endOffset(String topic) throws Exception {
        try (Admin admin = admin()) {
            return admin.listOffsets(Map.of(new TopicPartition(topic, 0), OffsetSpec.latest()))
                    .partitionResult(new TopicPartition(topic, 0))
                    .get()
                    .offset();
        } catch (ExecutionException _) {
            return 0;
        }
    }

    /** A share group on the topic, with the broker's dead-letter topic set unless {@code deadLetters} is false. */
    private String shareGroup(String topic, boolean deadLetters) throws Exception {
        Assumptions.assumeTrue(shareVersion() >= 2, "The broker's share.version is below 2, where it dead-letters");
        String group = "g-share-" + UUID.randomUUID().toString().substring(0, 8);
        try (Admin admin = admin()) {
            if (deadLetters) {
                admin.createTopics(List.of(new NewTopic("dlq." + topic, 1, (short) 1)
                                .configs(Map.of("errors.deadletterqueue.group.enable", "true"))))
                        .all()
                        .get();
                admin.incrementalAlterConfigs(Map.of(
                                new ConfigResource(ConfigResource.Type.GROUP, group),
                                List.of(new AlterConfigOp(
                                        new ConfigEntry("errors.deadletterqueue.topic.name", "dlq." + topic),
                                        AlterConfigOp.OpType.SET))))
                        .all()
                        .get();
            }
        }
        return group;
    }

    /** Finalizes the share groups' feature level 2 where the image supports it; the share tests skip otherwise. */
    private static void finalizeShareVersion() {
        try (Admin admin = admin()) {
            admin.updateFeatures(
                            Map.of("share.version", new FeatureUpdate((short) 2, FeatureUpdate.UpgradeType.UPGRADE)),
                            new UpdateFeaturesOptions())
                    .all()
                    .get();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException _) {
            // The tests that need it report the level they found.
        }
    }

    private static int shareVersion() throws Exception {
        try (Admin admin = admin()) {
            var finalized = admin.describeFeatures().featureMetadata().get().finalizedFeatures();
            return finalized.containsKey("share.version")
                    ? finalized.get("share.version").maxVersionLevel()
                    : 0;
        }
    }

    private static Admin admin() {
        return Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()));
    }

    private static KafkaProducer<byte[], byte[]> producer() {
        return new KafkaProducer<>(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()),
                new ByteArraySerializer(),
                new ByteArraySerializer());
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
