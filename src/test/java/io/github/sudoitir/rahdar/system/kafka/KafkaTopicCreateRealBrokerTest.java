package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Creating a topic against a real broker. The unit tests replace the driver's {@code operations}, so they never
 * exercise the driver's own precondition check — and a create previews against a state that does not exist yet, so
 * only a real driver shows that the topic is actually made.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaTopicCreateRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.image", "apache/kafka:4.4.0");

    private static KafkaContainer kafka;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterService service;

    @Autowired
    ClusterRepository clusters;

    private MockMvc mvc;
    private UUID cluster;
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String topic = "created-" + run;

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
    void register() {
        mvc = webAppContextSetup(webContext).build();
        cluster = ((Attempt.Ok<ClusterDetail>) service.register(new RegisterClusterRequest(
                        KafkaModule.SYSTEM,
                        "kafka-create-" + run,
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
    }

    @AfterEach
    void unregister() {
        clusters.deleteById(cluster);
    }

    @Test
    void aPreviewedTopicIsCreatedOnTheBroker() throws Exception {
        String preview = postJson("/api/v1/clusters/{c}/kafka/topics:create-preview", """
                {"name":"%s","partitions":1,"replicationFactor":1,"settings":{}}""".formatted(topic));
        String etag = JsonPath.<String>read(preview, "$.etag");

        String created = postJson("/api/v1/clusters/{c}/kafka/topics:create", """
                {"name":"%s","partitions":1,"replicationFactor":1,"settings":{},"etag":"%s"}""".formatted(topic, etag));

        assertThat(JsonPath.<String>read(created, "$.status")).isEqualTo("APPLIED");
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            assertThat(admin.listTopics().names().get()).contains(topic);
            assertThat(admin.describeTopics(Set.of(topic))
                            .allTopicNames()
                            .get()
                            .get(topic)
                            .partitions())
                    .hasSize(1);
        }
    }

    private String postJson(String path, String body) throws Exception {
        var response = mvc.perform(
                        post(path, cluster).contentType(APPLICATION_JSON).content(body))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isLessThan(300);
        return response.getContentAsString();
    }
}
