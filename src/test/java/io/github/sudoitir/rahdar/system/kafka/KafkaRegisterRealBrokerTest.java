package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Kind;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.messages.MessageAction;
import io.github.sudoitir.rahdar.feature.messages.MessageService;
import io.github.sudoitir.rahdar.feature.messages.web.MessageViews.MessagePageView;
import io.github.sudoitir.rahdar.feature.resources.BacklogService;
import io.github.sudoitir.rahdar.feature.resources.CrossNodeAggregator;
import io.github.sudoitir.rahdar.feature.resources.TruncateService;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.Backlog;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.SubscriptionView;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BacklogBreakdown.Breakdown;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.ClusterAlreadyRegisteredException;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerIdentityClaims;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.UpdateClusterRequest.KafkaConnectionUpdate;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ConnectionCheck;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A Kafka cluster is registered from its bootstrap servers against a real broker (ADR-0213): the check names every
 * broker and the quorum and stores nothing, the registration stores the cluster, its brokers and its connection, the
 * same cluster is registered once, and a bootstrap server nobody listens on fails the check with its cause.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaRegisterRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.image", "apache/kafka:4.4.0");

    private static KafkaContainer kafka;

    @Autowired
    ClusterService service;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    KafkaConnectionRepository connections;

    @Autowired
    BrokerIdentityClaims claims;

    @Autowired
    BrokerGateway gateway;

    @Autowired
    BacklogService backlogs;

    @Autowired
    MessageService messages;

    @Autowired
    TruncateService truncates;

    @Autowired
    KafkaScrape scrape;

    @Autowired
    QueueSnapshotUpsert snapshotUpsert;

    @Autowired
    CrossNodeAggregator aggregator;

    @Autowired
    KafkaPartitions partitions;

    @Autowired
    JdbcClient jdbc;

    private final String run = "kafka-register-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void startKafka() {
        kafka = new KafkaContainer(IMAGE);
        kafka.start();
    }

    @AfterAll
    static void stopKafka() {
        kafka.stop();
    }

    @AfterEach
    void removeTheClusters() {
        clusters.findAll().stream()
                .filter(c -> c.getName().startsWith(run))
                .forEach(c -> clusters.deleteById(c.getId()));
    }

    private RegisterClusterRequest request(String name, String bootstrap) {
        return new RegisterClusterRequest(
                KafkaModule.SYSTEM,
                name == null ? null : run + "-" + name,
                null,
                null,
                false,
                new KafkaConnection(KafkaModule.SYSTEM, List.of(bootstrap), "PLAINTEXT", null, null, null, null));
    }

    private ConnectionCheck check(RegisterClusterRequest request) {
        return service.checkConnection(request);
    }

    @Test
    void theCheckNamesEveryBrokerAndTheQuorumAndStoresNothing() {
        int before = clusters.findAll().size();

        ConnectionCheck check = check(request("check", kafka.getBootstrapServers()));

        assertThat(check.failure()).isNull();
        assertThat(check.supported()).isTrue();
        assertThat(check.clusterId()).isNotBlank();
        assertThat(check.nodes()).hasSize(1);
        assertThat(check.nodes().getFirst().outcome()).isEqualTo("OK");
        assertThat(check.nodes().getFirst().functions()).contains("BROKER");
        assertThat(check.quorum().available()).isTrue();
        assertThat(check.version().mode()).isEqualTo("KRaft");
        assertThat(check.access()).isNotEmpty();
        assertThat(clusters.findAll()).hasSize(before);
    }

    @Test
    void registeringStoresTheClusterItsBrokersAndItsConnection() {
        Attempt<ClusterDetail> registered = service.register(request("store", kafka.getBootstrapServers()));

        ClusterDetail detail = ((Attempt.Ok<ClusterDetail>) registered).value();
        assertThat(detail.system().id()).isEqualTo("kafka");
        UUID id = detail.id();
        assertThat(clusters.findById(id))
                .get()
                .satisfies(c -> assertThat(c.getSystem()).isEqualTo("kafka"));
        List<BrokerNodeEntity> brokers = nodes.findByClusterIdOrderByNameAsc(id);
        assertThat(brokers).extracting(BrokerNodeEntity::getName).containsExactly("broker-1");
        assertThat(connections.findById(id)).get().satisfies(c -> {
            assertThat(c.getSecurityProtocol()).isEqualTo("PLAINTEXT");
            assertThat(c.getBootstrapServers())
                    .isEqualTo(kafka.getBootstrapServers().replace("PLAINTEXT://", ""));
        });
        assertThat(jdbc.sql("SELECT count(*) FROM broker_identity WHERE cluster_id = ? AND kind = 'CLUSTER_ID'")
                        .param(id)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void theRegisteredClusterIsReachedThroughItsOwnSystemsDriver() {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("reach", kafka.getBootstrapServers())))
                .value()
                .id();

        ClusterConnection connection = gateway.cluster(id);

        assertThat(connection.system().id()).isEqualTo("kafka");
        assertThat(connection.resources().kinds())
                .extracting(Kind::id)
                .anyMatch(k -> k.value().equals("kafka.topic"));
    }

    @Test
    void theSameClusterIsRegisteredOnce() {
        RegisterClusterRequest first = request("once", kafka.getBootstrapServers());
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(first)).value().id();

        ConnectionCheck again = check(request("once-again", kafka.getBootstrapServers()));

        assertThat(again.alreadyRegistered()).isNotNull();
        assertThat(again.alreadyRegistered().id()).isEqualTo(id);
        assertThatThrownBy(() -> service.register(request("once-again", kafka.getBootstrapServers())))
                .isInstanceOf(ClusterAlreadyRegisteredException.class);
    }

    private UpdateClusterRequest edit(KafkaConnectionUpdate connection) {
        return new UpdateClusterRequest(null, null, null, connection);
    }

    @Test
    void anEditIsCheckedWithoutStoringAndThenSaved() {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("edit", kafka.getBootstrapServers())))
                .value()
                .id();
        KafkaConnectionUpdate renamed =
                new KafkaConnectionUpdate(KafkaModule.SYSTEM, null, null, null, null, null, "rahdar-edited");

        ConnectionCheck dryRun = service.checkUpdate(id, edit(renamed));

        assertThat(dryRun.failure()).isNull();
        assertThat(dryRun.alreadyRegistered()).isNull();
        assertThat(connections.findById(id))
                .get()
                .satisfies(c -> assertThat(c.getClientId()).isNull());

        service.updateConnection(id, edit(renamed));

        assertThat(connections.findById(id))
                .get()
                .satisfies(c -> assertThat(c.getClientId()).isEqualTo("rahdar-edited"));
    }

    @Test
    void anEditToAServerNobodyListensOnIsRefusedAndNothingChanges() {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("moved", kafka.getBootstrapServers())))
                .value()
                .id();
        String before = connections.findById(id).orElseThrow().getBootstrapServers();

        assertThatThrownBy(() -> service.updateConnection(
                        id,
                        edit(new KafkaConnectionUpdate(
                                KafkaModule.SYSTEM, List.of("localhost:1"), null, null, null, null, null))))
                .hasMessageContaining("Check the host and port");

        assertThat(connections.findById(id).orElseThrow().getBootstrapServers()).isEqualTo(before);
    }

    @Test
    void theClusterDetailShowsHowItIsReachedAndNeverASecret() {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("detail", kafka.getBootstrapServers())))
                .value()
                .id();

        ClusterDetail detail = service.get(id);

        assertThat(detail.connection().kafka().securityProtocol()).isEqualTo("PLAINTEXT");
        assertThat(detail.connection().kafka().secretStored()).isFalse();
        assertThat(detail.capabilities().managementRead().status()).isIn("AVAILABLE", "UNKNOWN");
        assertThat(detail.health().notes()).isEmpty();
        assertThat(detail.health().level()).isEqualTo("OK");
    }

    @Test
    void aPartitionOfATopicIsBrowsedByOffsetAndTimeAndAFilterIsRefusedWithTheDriversReason() throws Exception {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("browse", kafka.getBootstrapServers())))
                .value()
                .id();
        String topic = run + "-browse";
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        }
        long before = System.currentTimeMillis();
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()),
                new ByteArraySerializer(),
                new ByteArraySerializer())) {
            for (int i = 0; i < 5; i++) {
                producer.send(new ProducerRecord<>(topic, 0, ("k" + i).getBytes(), ("v" + i).getBytes()))
                        .get();
            }
            producer.send(new ProducerRecord<>(topic, 0, "gone".getBytes(), null))
                    .get();
        }
        ResourceId log = ResourceId.of("kafka.topic", topic);
        MessageService messages = this.messages;
        MessagePageView page = null;
        for (int attempt = 0; attempt < 50 && (page == null || page.data().size() < 6); attempt++) {
            try {
                page = messages.browseLog(id, log, 0, MessageService.LogStart.parse("earliest", null), null, 100);
            } catch (RuntimeException metadataNotThereYet) {
                Thread.sleep(200);
            }
        }

        assertThat(page.data())
                .extracting(r -> r.position() + ":" + r.key())
                .containsExactly("0:k0", "1:k1", "2:k2", "3:k3", "4:k4", "5:gone");
        assertThat(page.data().get(5).tombstone()).isTrue();
        assertThat(page.data().get(0).tombstone()).isFalse();
        assertThat(page.data().get(0).bodyPreview()).isEqualTo("v0");
        assertThat(page.log().start()).isZero();
        assertThat(page.log().end()).isEqualTo(6);
        assertThat(page.log().next()).isNull();

        MessagePageView from3 = messages.browseLog(id, log, 0, MessageService.LogStart.parse("offset", "3"), null, 2);
        assertThat(from3.data()).extracting(r -> r.position()).containsExactly("3", "4");
        assertThat(from3.log().next()).isEqualTo(5L);
        assertThat(messages.browseLog(id, log, 0, MessageService.LogStart.parse("offset", "99"), null, 2)
                        .data())
                .isEmpty();
        assertThat(messages.browseLog(
                                id,
                                log,
                                0,
                                MessageService.LogStart.parse(
                                        "time",
                                        java.time.Instant.ofEpochMilli(before - 60_000)
                                                .toString()),
                                null,
                                100)
                        .data())
                .hasSize(6);
        assertThat(messages.detailLog(id, log, 0, 2).body()).isEqualTo("v2");
        assertThatThrownBy(() -> messages.browseLog(
                        id, log, 0, MessageService.LogStart.parse("earliest", null), "header.x = 1", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot filter");
    }

    @Test
    void aTruncateNamesTheGroupsThatLoseRecordsCutsTheLogAndIsAudited() throws Exception {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("truncate", kafka.getBootstrapServers())))
                .value()
                .id();
        String topic = run + "-truncate";
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get();
        }
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()),
                new ByteArraySerializer(),
                new ByteArraySerializer())) {
            for (int i = 0; i < 20; i++) {
                producer.send(new ProducerRecord<>(topic, i % 2, new byte[] {1}, new byte[] {2}))
                        .get();
            }
        }
        for (Map.Entry<String, Long> group :
                Map.of(run + "-slow", 3L, run + "-fast", 8L).entrySet()) {
            Map<String, Object> config = Map.of(
                    "bootstrap.servers",
                    kafka.getBootstrapServers(),
                    "group.id",
                    group.getKey(),
                    "enable.auto.commit",
                    "false");
            try (KafkaConsumer<byte[], byte[]> consumer =
                    new KafkaConsumer<>(config, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
                consumer.commitSync(Map.of(new TopicPartition(topic, 0), new OffsetAndMetadata(group.getValue())));
            }
        }
        TruncateService.Cut cut = new TruncateService.Cut(Map.of(0, 6L), null);
        TruncateService.TruncateEffect effect = null;
        for (int attempt = 0; attempt < 50 && effect == null; attempt++) {
            try {
                effect = truncates.truncate(id, "kafka.topic", topic, cut, null, true);
            } catch (RuntimeException metadataNotThereYet) {
                Thread.sleep(200);
            }
        }

        assertThat(effect.records()).isEqualTo(6);
        assertThat(effect.groups())
                .extracting(g -> g.group() + ":" + g.records())
                .containsExactly(run + "-slow:3");

        assertThat(effect.etag()).isNotBlank();
        assertThatThrownBy(() -> truncates.truncate(id, "kafka.topic", topic, cut, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> truncates.truncate(id, "kafka.topic", topic, cut, "stale", false))
                .isInstanceOf(StalePreviewException.class);

        TruncateService.TruncateEffect done = truncates.truncate(id, "kafka.topic", topic, cut, effect.etag(), false);

        assertThat(done.records()).isEqualTo(6);
        ResourceId log = ResourceId.of("kafka.topic", topic);
        MessagePageView afterwards = null;
        for (int attempt = 0; attempt < 50 && afterwards == null; attempt++) {
            try {
                afterwards = messages.browseLog(id, log, 0, MessageService.LogStart.parse("earliest", null), null, 10);
            } catch (RuntimeException metadataNotThereYet) {
                Thread.sleep(200);
            }
        }
        assertThat(afterwards.log().start()).isEqualTo(6);
        assertThat(jdbc.sql(
                                "SELECT outcome FROM audit_event WHERE cluster_id = ? AND action = 'TRUNCATE_DESTINATION' ORDER BY id")
                        .param(id)
                        .query(String.class)
                        .list())
                .containsExactly("SUCCESS", "FAILURE", "SUCCESS");
    }

    @Test
    void aCompactedTopicIsRefusedWithTheDriversReason() throws Exception {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("compact", kafka.getBootstrapServers())))
                .value()
                .id();
        String topic = run + "-compact";
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1).configs(Map.of("cleanup.policy", "compact"))))
                    .all()
                    .get();
        }
        TruncateService.Cut cut = new TruncateService.Cut(Map.of(0, 1L), null);
        Throwable refused = null;
        for (int attempt = 0; attempt < 50 && refused == null; attempt++) {
            try {
                truncates.truncate(id, "kafka.topic", topic, cut, null, true);
                refused = new AssertionError("a compacted topic was truncated");
            } catch (ConflictException e) {
                refused = e;
            } catch (RuntimeException metadataNotThereYet) {
                Thread.sleep(200);
            }
        }

        assertThat(refused).isInstanceOf(ConflictException.class).hasMessageContaining("compacts");
    }

    @Test
    void aGroupWithMembersIsNotPurgedAndAnEmptyOneIsMovedToTheEnd() throws Exception {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("purge", kafka.getBootstrapServers())))
                .value()
                .id();
        String topic = run + "-purge";
        String group = run + "-purger";
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        }
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()),
                new ByteArraySerializer(),
                new ByteArraySerializer())) {
            for (int i = 0; i < 10; i++) {
                producer.send(new ProducerRecord<>(topic, 0, new byte[] {1}, new byte[] {2}))
                        .get();
            }
        }
        Map<String, Object> config = Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(), "group.id", group, "enable.auto.commit", "false");
        ResourceId subscription = ResourceId.of("kafka.group-topic", group, topic);
        try (KafkaConsumer<byte[], byte[]> member =
                new KafkaConsumer<>(config, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            member.commitSync(Map.of(new TopicPartition(topic, 0), new OffsetAndMetadata(2)));
            member.subscribe(List.of(topic));
            long until = System.currentTimeMillis() + 30_000;
            while (member.assignment().isEmpty() && System.currentTimeMillis() < until) {
                member.poll(java.time.Duration.ofMillis(200));
            }

            assertThatThrownBy(() -> messages.purgeLog(id, subscription, true))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("member");
        }
        Object outcome = null;
        for (int attempt = 0; attempt < 50 && outcome == null; attempt++) {
            try {
                outcome = ((Attempt.Ok<?>) messages.purgeLog(id, subscription, true)).value();
            } catch (ConflictException stillLeaving) {
                Thread.sleep(300);
            }
        }

        // A purge of a log destroys nothing: the figure is the records the position passes over.
        assertThat(outcome).isInstanceOf(MessageService.Outcome.LogPurgeDryRun.class);
        assertThat(((MessageService.Outcome.LogPurgeDryRun) outcome).skipped()).isEqualTo(8);
        assertThat(((MessageService.Outcome.LogPurged)
                                ((Attempt.Ok<?>) messages.purgeLog(id, subscription, false)).value())
                        .skipped())
                .isEqualTo(8);
    }

    @Test
    void aSingleRecordIsNeitherMovedNorDeletedAndTheReasonIsTheDriversOwn() {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("append", kafka.getBootstrapServers())))
                .value()
                .id();

        for (MessageAction action : MessageAction.values()) {
            assertThatThrownBy(() -> messages.requireOffered(id, action))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("append-only");
        }
    }

    @Test
    void theScrapeRecordsEachBrokersStateFromWhatTheClusterSaysOfIt() {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("scrape", kafka.getBootstrapServers())))
                .value()
                .id();

        scrape.nodes(id, 1);

        assertThat(nodes.findByClusterIdOrderByNameAsc(id)).singleElement().satisfies(broker -> {
            assertThat(broker.getState()).isEqualTo("STARTED");
            assertThat(broker.getActive()).isTrue();
            assertThat(broker.getLastSeenAt()).isNotNull();
            assertThat(broker.getLastError()).isNull();
        });
        assertThat(partitions.offline(id)).hasValue(0);
    }

    @Test
    void aGroupOnATopicIsInTheSubscriptionsTableWithAnEstimatedBacklogItsGroupNamesAndAnId() throws Exception {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("table", kafka.getBootstrapServers())))
                .value()
                .id();
        String topic = run + "-table";
        String group = run + "-ledger";
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        }
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()),
                new ByteArraySerializer(),
                new ByteArraySerializer())) {
            for (int i = 0; i < 10; i++) {
                producer.send(new ProducerRecord<>(topic, 0, new byte[] {1}, new byte[] {2}))
                        .get();
            }
        }
        Map<String, Object> config = Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(), "group.id", group, "enable.auto.commit", "false");
        try (KafkaConsumer<byte[], byte[]> consumer =
                new KafkaConsumer<>(config, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            consumer.commitSync(Map.of(new TopicPartition(topic, 0), new OffsetAndMetadata(4)));
        }
        List<QueueRow> rows = List.of();
        for (int attempt = 0;
                attempt < 50 && rows.stream().noneMatch(r -> r.queueName().contains(group));
                attempt++) {
            rows = scrape.subscriptions(id);
            Thread.sleep(200);
        }
        snapshotUpsert.upsertBatch(rows);

        SubscriptionView view = aggregator.rollUp(id).stream()
                .filter(v -> v.name().equals(group))
                .findFirst()
                .orElseThrow();

        assertThat(view.id()).isEqualTo("kafka.group-topic/" + group + "/" + topic);
        assertThat(view.kind()).isEqualTo("kafka.group-topic");
        assertThat(view.destination()).isEqualTo("kafka.topic/" + topic);
        assertThat(view.backlog().accuracy()).isEqualTo(Backlog.Accuracy.ESTIMATE);
        assertThat(view.backlog().value()).isEqualTo(6);
        assertThat(view.backlog().reason()).isNotBlank();
        assertThat(view.backlog().breakdown()).isTrue();
        assertThat(view.nativeAttributes()).isEmpty();
    }

    @Test
    void aBootstrapServerNobodyListensOnFailsTheCheckWithItsCause() {
        ConnectionCheck check = check(request("refused", "localhost:1"));

        assertThat(check.failure()).isNotNull();
        assertThat(check.failure().cause()).isIn("REFUSED", "TIMEOUT");
        assertThat(check.failure().next()).isNotBlank();
        assertThatThrownBy(() -> service.register(request("refused", "localhost:1")))
                .hasMessageContaining(check.failure().next());
    }

    @Test
    void aGroupsBacklogIsReadPartitionBySlowestFirstAndAMissingPositionIsNotZero() throws Exception {
        UUID id = ((Attempt.Ok<ClusterDetail>) service.register(request("backlog", kafka.getBootstrapServers())))
                .value()
                .id();
        String topic = run + "-orders";
        String group = run + "-billing";
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
        }
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()),
                new ByteArraySerializer(),
                new ByteArraySerializer())) {
            for (int i = 0; i < 30; i++) {
                producer.send(new ProducerRecord<>(topic, i % 3, new byte[] {1}, new byte[] {2}))
                        .get();
            }
        }
        Map<String, Object> config = Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(), "group.id", group, "enable.auto.commit", "false");
        try (KafkaConsumer<byte[], byte[]> consumer =
                new KafkaConsumer<>(config, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            consumer.commitSync(Map.of(
                    new TopicPartition(topic, 0), new OffsetAndMetadata(4),
                    new TopicPartition(topic, 1), new OffsetAndMetadata(9)));
        }

        // The brokers' metadata trails the new topic by a moment, so the third partition's end may take a second look.
        Breakdown breakdown = backlogs.breakdown(id, "kafka.group-topic", group + "/" + topic);
        for (int attempt = 0; attempt < 50 && breakdown.partitions().get(2).end() == null; attempt++) {
            Thread.sleep(200);
            breakdown = backlogs.breakdown(id, "kafka.group-topic", group + "/" + topic);
        }

        assertThat(breakdown.sampledAt()).isNotNull();
        assertThat(breakdown.partitions())
                .extracting(p -> p.partition() + ":" + p.committed() + ":" + p.end() + ":" + p.lag())
                .containsExactly("0:4:10:6", "1:9:10:1", "2:null:10:null");
        assertThat(breakdown.partitions().get(2).reason()).isNotBlank();
    }

    @Test
    void aKafka3ClusterIsRegistered() {
        try (KafkaContainer older = new KafkaContainer("apache/kafka:3.9.1")) {
            older.start();

            ConnectionCheck check = check(request("three", older.getBootstrapServers()));

            assertThat(check.failure()).isNull();
            assertThat(check.supported()).isTrue();
            assertThat(check.version().text()).startsWith("3.");
            assertThat(check.version().mode()).isEqualTo("KRaft");
        }
    }
}
