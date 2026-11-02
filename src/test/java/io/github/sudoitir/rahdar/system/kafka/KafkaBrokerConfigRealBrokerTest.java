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
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
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
 * Broker configuration against a real broker (ADR-0216): a broker's settings with their source, a dynamic setting on
 * the cluster default previewed without writing, applied and read back, and a setting only a restart changes refused.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaBrokerConfigRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.image", "apache/kafka:4.4.0");
    private static final String BASE = "/api/v1/clusters/{c}/kafka/brokers/";

    private static KafkaContainer kafka;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterService service;

    @Autowired
    ClusterRepository clusters;

    private MockMvc mvc;
    private UUID cluster;

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
                        "kafka-bc-" + UUID.randomUUID().toString().substring(0, 8),
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

    private String body(String broker, String path, String body) throws Exception {
        var response = mvc.perform(post(BASE + broker + "/" + path, cluster)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isLessThan(300);
        return response.getContentAsString();
    }

    @Test
    void aBrokerShowsItsSettingsWithTheirSourceAndWhichOnesNeedARestart() throws Exception {
        mvc.perform(get(BASE + "{b}/config", cluster, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broker").value("1"))
                .andExpect(jsonPath("$.clusterDefault").value(false))
                .andExpect(jsonPath("$.entries[?(@.name=='message.max.bytes')]").isNotEmpty())
                .andExpect(jsonPath("$.entries[?(@.name=='node.id')].readOnly").value(true));
    }

    @Test
    void aDynamicSettingOnTheClusterDefaultIsPreviewedWithoutWritingThenAppliedAndReadBack() throws Exception {
        String change = "{\"set\":{\"message.max.bytes\":\"2097152\"}";

        String preview = body("default", "config:preview", change + "}");
        assertThat((Boolean) JsonPath.read(preview, "$.valid")).isTrue();
        assertThat((String) JsonPath.read(preview, "$.changes[0].name")).isEqualTo("message.max.bytes");
        assertThat((String) JsonPath.read(preview, "$.changes[0].after")).isEqualTo("2097152");
        // The preview wrote nothing.
        mvc.perform(get(BASE + "default/config", cluster))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clusterDefault").value(true))
                .andExpect(jsonPath("$.entries[?(@.name=='message.max.bytes')]").isEmpty());

        // A stale or missing etag is refused.
        mvc.perform(post(BASE + "default/config:apply", cluster)
                        .contentType(APPLICATION_JSON)
                        .content(change + ",\"etag\":\"stale\"}"))
                .andExpect(status().isPreconditionFailed());

        String etag = JsonPath.read(preview, "$.etag");
        mvc.perform(post(BASE + "default/config:apply", cluster)
                        .contentType(APPLICATION_JSON)
                        .content(change + ",\"etag\":\"" + etag + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "default/config", cluster))
                .andExpect(jsonPath("$.entries[?(@.name=='message.max.bytes')].value")
                        .value("2097152"))
                .andExpect(jsonPath("$.entries[?(@.name=='message.max.bytes')].source")
                        .value("CLUSTER"));

        // Removing the override puts the built-in value back.
        String removal = "{\"remove\":[\"message.max.bytes\"]}";
        String removed = JsonPath.read(body("default", "config:preview", removal), "$.etag");
        mvc.perform(post(BASE + "default/config:apply", cluster)
                        .contentType(APPLICATION_JSON)
                        .content("{\"remove\":[\"message.max.bytes\"],\"etag\":\"" + removed + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "default/config", cluster))
                .andExpect(jsonPath("$.entries[?(@.name=='message.max.bytes')]").isEmpty());
    }

    @Test
    void aSettingOnlyARestartChangesIsRefusedWithTheDriversWords() throws Exception {
        mvc.perform(post(BASE + "1/config:preview", cluster)
                        .contentType(APPLICATION_JSON)
                        .content("{\"set\":{\"node.id\":\"7\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("node.id is read-only")));
    }

    @Test
    void aSecretIsRefusedAndAnUnknownBrokerIsNotFound() throws Exception {
        mvc.perform(post(BASE + "1/config:preview", cluster)
                        .contentType(APPLICATION_JSON)
                        .content("{\"set\":{\"ssl.keystore.password\":\"x\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "{b}/config", cluster, "999")).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "{b}/config", cluster, "not-a-number")).andExpect(status().isBadRequest());
    }
}
