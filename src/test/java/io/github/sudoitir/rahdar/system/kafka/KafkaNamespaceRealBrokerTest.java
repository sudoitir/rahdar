package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The Kafka namespace against a real broker (ADR-0216): a group that lags, an offset reset that is previewed, refused
 * while the group has members or the preview is stale, and run; topic settings with their source and the broker's
 * validation; ACLs on a cluster with no authorizer; transactions; and the brokers with their quorum.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaNamespaceRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.image", "apache/kafka:4.4.0");

    private static KafkaContainer kafka;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterService service;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    KafkaScrape scrape;

    @Autowired
    QueueSnapshotUpsert snapshotUpsert;

    private MockMvc mvc;
    private UUID cluster;
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String topic = "orders-" + run;
    private final String group = "g-" + run;

    @BeforeAll
    static void startKafka() {
        kafka = new KafkaContainer(IMAGE);
        kafka.start();
    }

    @AfterAll
    static void stopKafka() {
        kafka.stop();
    }

    @BeforeEach
    void register() throws Exception {
        mvc = webAppContextSetup(webContext).build();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get();
        }
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()),
                new ByteArraySerializer(),
                new ByteArraySerializer())) {
            for (int i = 0; i < 10; i++) {
                producer.send(new ProducerRecord<>(topic, i % 2, null, ("v" + i).getBytes()));
            }
            producer.flush();
        }
        // The driver reads the topology on a timer, so the topic exists before the cluster is registered.
        cluster = ((Attempt.Ok<ClusterDetail>) service.register(new RegisterClusterRequest(
                        KafkaModule.SYSTEM,
                        "kafka-ns-" + run,
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
                                null))))
                .value()
                .id();
        commit(Map.of(0, 1L, 1, 4L));
    }

    @AfterEach
    void unregister() {
        clusters.deleteById(cluster);
    }

    private void commit(Map<Integer, Long> offsets) {
        try (KafkaConsumer<byte[], byte[]> consumer = consumer(group)) {
            Map<TopicPartition, OffsetAndMetadata> commits = new java.util.HashMap<>();
            offsets.forEach((p, o) -> commits.put(new TopicPartition(topic, p), new OffsetAndMetadata(o)));
            consumer.commitSync(commits);
        }
    }

    private KafkaConsumer<byte[], byte[]> consumer(String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return new KafkaConsumer<>(props, new ByteArrayDeserializer(), new ByteArrayDeserializer());
    }

    private String json(String path, String body) throws Exception {
        var response = mvc.perform(post("/api/v1/clusters/{c}/kafka/" + path, cluster)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isLessThan(300);
        return response.getContentAsString();
    }

    @Test
    void findingHangingTransactionsOnACleanClusterFindsNoneAndNeverFails() throws Exception {
        String detail = mvc.perform(get("/api/v1/clusters/{c}", cluster))
                .andReturn()
                .getResponse()
                .getContentAsString();
        // The kind arrives with OpenBroker 0.6.2; on an older driver there is nothing to find with.
        Assumptions.assumeTrue(detail.contains("kafka.hanging-transaction"));

        mvc.perform(post("/api/v1/clusters/{c}/kafka/hanging-transactions:find?topic=" + topic, cluster))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(post("/api/v1/clusters/{c}/kafka/hanging-transactions:find", cluster))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void aGroupsSubscriptionIsBrowsedLikeItsTopicAndSaysWhereTheGroupStands() throws Exception {
        commit(Map.of(0, 3L, 1, 2L));
        String id = "kafka.group-topic/" + group + "%2F" + topic;

        mvc.perform(get(URI.create(
                        "/api/v1/clusters/" + cluster + "/resources/" + id + "/messages?partition=0&from=earliest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.log.partition").value(0))
                .andExpect(jsonPath("$.log.subscriptionPosition").value(3));
        mvc.perform(get(URI.create(
                        "/api/v1/clusters/" + cluster + "/resources/" + id + "/messages?partition=1&from=earliest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.log.subscriptionPosition").value(2));
        // The detail of one record, through the same subscription.
        mvc.perform(get(URI.create("/api/v1/clusters/" + cluster + "/resources/" + id + "/messages/1?partition=0")))
                .andExpect(status().isOk());
        // Read as the topic, no group stands anywhere.
        mvc.perform(get(
                        "/api/v1/clusters/{c}/resources/kafka.topic/{t}/messages?partition=0&from=earliest",
                        cluster,
                        topic))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.log.subscriptionPosition").doesNotExist());
    }

    @Test
    void aLaggingGroupShowsItsLagPerPartitionSlowestFirst() throws Exception {
        // The list's lag is the scrape's last sample, so the scrape runs once before the list is asked.
        snapshotUpsert.upsertBatch(scrape.subscriptions(cluster));
        mvc.perform(get("/api/v1/clusters/{c}/kafka/groups?q=" + group, cluster))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(group))
                .andExpect(jsonPath("$.items[0].lag.value").value(5))
                .andExpect(jsonPath("$.items[0].lag.accuracy").value("ESTIMATE"));

        mvc.perform(get("/api/v1/clusters/{c}/kafka/groups/{g}", cluster, group))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0].topic").value(topic))
                .andExpect(jsonPath("$.topics[0].deleted").value(false))
                .andExpect(jsonPath("$.topics[0].partitions[0].partition").value(0))
                .andExpect(jsonPath("$.topics[0].partitions[0].lag").value(4))
                .andExpect(jsonPath("$.members").isEmpty());
    }

    @Test
    void anEmptyGroupIsResetToTheEarliestAfterAPreview() throws Exception {
        String body = "{\"topics\":[\"" + topic + "\"],\"to\":\"earliest\"}";
        String preview = json("groups/" + group + "/offsets:preview", body);
        assertThat((Integer) JsonPath.read(preview, "$.partitions.length()")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(preview, "$.partitions[0].current")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(preview, "$.partitions[0].next")).isZero();
        assertThat((Integer) JsonPath.read(preview, "$.partitions[0].reread")).isEqualTo(1);
        String etag = JsonPath.read(preview, "$.etag");

        mvc.perform(post("/api/v1/clusters/{c}/kafka/groups/{g}/offsets:reset", cluster, group)
                        .contentType(APPLICATION_JSON)
                        .content("{\"topics\":[\"" + topic + "\"],\"to\":\"earliest\",\"etag\":\"" + etag + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved").value(2));

        mvc.perform(get("/api/v1/clusters/{c}/kafka/groups/{g}", cluster, group))
                .andExpect(jsonPath("$.topics[0].partitions[0].committed").value(0));
    }

    @Test
    void aResetIsRefusedAsStaleWhenTheGroupMovedAfterThePreview() throws Exception {
        String body = "{\"to\":\"latest\"}";
        String etag = JsonPath.read(json("groups/" + group + "/offsets:preview", body), "$.etag");
        commit(Map.of(0, 2L));

        mvc.perform(post("/api/v1/clusters/{c}/kafka/groups/{g}/offsets:reset", cluster, group)
                        .contentType(APPLICATION_JSON)
                        .content("{\"to\":\"latest\",\"etag\":\"" + etag + "\"}"))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void anOffsetOutsideAPartitionIsRefusedNamingItsRange() throws Exception {
        String preview = json("groups/" + group + "/offsets:preview", "{\"to\":\"offset\",\"value\":\"5000\"}");

        assertThat((String) JsonPath.read(preview, "$.partitions[0].refused")).contains("outside partition");
        String etag = JsonPath.read(preview, "$.etag");
        mvc.perform(post("/api/v1/clusters/{c}/kafka/groups/{g}/offsets:reset", cluster, group)
                        .contentType(APPLICATION_JSON)
                        .content("{\"to\":\"offset\",\"value\":\"5000\",\"etag\":\"" + etag + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aGroupWithAMemberNamesItAndIsNotReset() throws Exception {
        String busy = "busy-" + run;
        try (KafkaConsumer<byte[], byte[]> member = consumer(busy)) {
            member.subscribe(List.of(topic));
            for (int i = 0; i < 20 && member.assignment().isEmpty(); i++) {
                member.poll(Duration.ofMillis(500));
            }
            member.commitSync();
            String preview = json("groups/" + busy + "/offsets:preview", "{\"to\":\"earliest\"}");
            assertThat((String) JsonPath.read(preview, "$.blocked")).contains("active member");
            assertThat((Integer) JsonPath.read(preview, "$.members.length()")).isEqualTo(1);
            String etag = JsonPath.read(preview, "$.etag");
            mvc.perform(post("/api/v1/clusters/{c}/kafka/groups/{g}/offsets:reset", cluster, busy)
                            .contentType(APPLICATION_JSON)
                            .content("{\"to\":\"earliest\",\"etag\":\"" + etag + "\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(get("/api/v1/clusters/{c}/kafka/groups/{g}", cluster, busy))
                    .andExpect(jsonPath("$.members[0].assignments").isNotEmpty());
        }
    }

    @Test
    void aTopicShowsItsSettingsWithTheirSourceAndTheBrokerValidatesAChange() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/kafka/topics/{t}/config", cluster, topic))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.internal").value(false))
                .andExpect(
                        jsonPath("$.entries[?(@.name=='retention.ms')].source").isNotEmpty());

        String bad = json("topics/" + topic + "/config:preview", "{\"set\":{\"retention.ms\":\"-5 days\"}}");
        assertThat((Boolean) JsonPath.read(bad, "$.valid")).isFalse();
        assertThat((String) JsonPath.read(bad, "$.changes[0].error")).isNotBlank();

        String ok = json("topics/" + topic + "/config:preview", "{\"set\":{\"retention.ms\":\"60000\"}}");
        assertThat((Boolean) JsonPath.read(ok, "$.valid")).isTrue();
        String etag = JsonPath.read(ok, "$.etag");
        mvc.perform(post("/api/v1/clusters/{c}/kafka/topics/{t}/config:apply", cluster, topic)
                        .contentType(APPLICATION_JSON)
                        .content("{\"set\":{\"retention.ms\":\"60000\"},\"etag\":\"" + etag + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/clusters/{c}/kafka/topics/{t}/config", cluster, topic))
                .andExpect(
                        jsonPath("$.entries[?(@.name=='retention.ms')].value").value("60000"))
                .andExpect(
                        jsonPath("$.entries[?(@.name=='retention.ms')].source").value("TOPIC"));
    }

    @Test
    void aClusterWithNoAuthorizerSaysAclsAreUnavailable() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/kafka/acls", cluster))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value(containsString("no authorizer")));
    }

    @Test
    void transactionsAndBrokersAreListed() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/kafka/transactions", cluster))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/v1/clusters/{c}/kafka/brokers", cluster))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("KRaft"))
                .andExpect(jsonPath("$.brokers[0].reachable").value(true))
                .andExpect(jsonPath("$.brokers[0].leaders").isNumber())
                .andExpect(jsonPath("$.quorum.available").value(true))
                .andExpect(jsonPath("$.quorum.leader").isNotEmpty())
                .andExpect(jsonPath("$.quorum.voters.length()").value(1))
                .andExpect(jsonPath("$.quorum.majority").value(true));
    }
}
