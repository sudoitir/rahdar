package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * Messages of a RabbitMQ cluster against a real broker (ADR-0224): a stream is browsed as one partition with the log's
 * bounds, a classic queue is not browsed because the broker would take the messages from its consumers, and the
 * backlog is an estimate for a classic and a quorum queue and unavailable for a stream, with the reason.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class RabbitMqMessagesRealBrokerTest extends PostgresIntegrationTest {

    private static final String CLASSIC = "rabbitmq.classic-queue";
    private static final String STREAM = "rabbitmq.stream";

    private static RabbitMqBrokers rabbit;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    RabbitMqScrape scrape;

    @Autowired
    QueueSnapshotUpsert snapshotUpsert;

    private MockMvc mvc;
    private UUID cluster;
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String vhost = "messages-" + run;
    private final String stream = "events-" + run;
    private final String classic = "jobs-" + run;
    private final String quorum = "audit-" + run;

    @BeforeAll
    static void startRabbit() {
        rabbit = new RabbitMqBrokers().start();
    }

    @AfterAll
    static void stopRabbit() {
        rabbit.close();
    }

    @BeforeEach
    void register() throws Exception {
        mvc = webAppContextSetup(webContext).build();
        rabbit.vhost(vhost);
        rabbit.queue(vhost, stream, "stream");
        rabbit.queue(vhost, classic, "classic");
        rabbit.queue(vhost, quorum, "quorum");
        rabbit.publish(vhost, stream, 5);
        rabbit.publish(vhost, classic, 3);
        rabbit.publish(vhost, quorum, 2);
        rabbit.awaitStreamStatistics(vhost, stream, 5);
        rabbit.awaitStatistics(vhost, classic, 3);
        rabbit.awaitStatistics(vhost, quorum, 2);
        MockHttpServletResponse created = mvc.perform(post("/api/v1/clusters")
                        .contentType(APPLICATION_JSON)
                        .content("{\"system\":\"rabbitmq\",\"name\":\"rmq-messages-" + run
                                + "\",\"connection\":{\"system\":\"rabbitmq\",\"managementUrls\":[\""
                                + rabbit.managementUrl() + "\"],\"amqpEndpoints\":[\"" + rabbit.amqpEndpoint()
                                + "\"],\"credentials\":{\"username\":\"" + RabbitMqBrokers.USER + "\",\"password\":\""
                                + RabbitMqBrokers.PASSWORD + "\"}}}"))
                .andReturn()
                .getResponse();
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        cluster = UUID.fromString(JsonPath.read(created.getContentAsString(), "$.id"));
        snapshotUpsert.upsertBatch(scrape.subscriptions(cluster));
    }

    @AfterEach
    void unregister() {
        clusters.deleteById(cluster);
    }

    private MockHttpServletResponse browse(String kind, String name, String query) throws Exception {
        return mvc.perform(get(URI.create("/api/v1/clusters/" + cluster + "/resources/" + kind + "/" + vhost + "%2F"
                        + name + "/messages" + query)))
                .andReturn()
                .getResponse();
    }

    // OpenBroker 0.9.0-SNAPSHOT: reading the stream with ResourceQuery.of(rabbitmq.stream).where(vhost).named(name)
    // .project({rabbitmq.committed_offset, openbroker.backlog}) answers the stream with no attributes at all (the
    // driver lists with disable_stats=true), so RabbitMqLogBounds sees a stream of nothing: end 0, an empty page.
    // Without .project(...) this test passes. Enable it when the driver answers the projection.
    @Disabled("The driver answers a projected stream read with no attributes; see the comment above")
    @Test
    void aStreamIsBrowsedAsOnePartitionWithTheBoundsOfItsLog() throws Exception {
        MockHttpServletResponse response = browse(STREAM, stream, "?from=earliest&size=10");

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        String page = response.getContentAsString();
        assertThat((Integer) JsonPath.read(page, "$.data.length()")).isEqualTo(5);
        assertThat((String) JsonPath.read(page, "$.data[0].bodyPreview")).isEqualTo("message-0");
        assertThat((Integer) JsonPath.read(page, "$.log.partition")).isZero();
        assertThat(((Number) JsonPath.read(page, "$.log.start")).longValue()).isZero();
        assertThat(((Number) JsonPath.read(page, "$.log.end")).longValue()).isEqualTo(5);

        String tail = browse(STREAM, stream, "?from=offset&value=3&size=10").getContentAsString();
        assertThat((Integer) JsonPath.read(tail, "$.data.length()")).isEqualTo(2);
        assertThat((String) JsonPath.read(tail, "$.data[0].bodyPreview")).isEqualTo("message-3");
    }

    @Test
    void aClassicQueueIsNotBrowsedBecauseTheBrokerCannotReadWithoutTakingTheMessages() throws Exception {
        MockHttpServletResponse response = browse(CLASSIC, classic, "");

        assertThat(response.getStatus()).as(response.getContentAsString()).isBetween(400, 499);
        assertThat(response.getContentAsString()).isNotBlank();
        assertThat(rabbit.call("GET", "/api/queues/" + RabbitMqBrokers.encode(vhost) + "/" + classic, null)
                        .body())
                .contains("\"messages\":3");
    }

    @Test
    void theBacklogOfAClassicAndAQuorumQueueIsAnEstimateAndAStreamsIsUnavailableWithItsReason() throws Exception {
        String subscriptions = mvc.perform(get("/api/v1/clusters/{c}/subscriptions?q=" + run + "&size=50", cluster))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(all(subscriptions, "$.data[?(@.name=='" + classic + "')].backlog.accuracy"))
                .containsExactly("ESTIMATE");
        assertThat(all(subscriptions, "$.data[?(@.name=='" + classic + "')].backlog.value"))
                .containsExactly(3);
        assertThat(all(subscriptions, "$.data[?(@.name=='" + quorum + "')].backlog.accuracy"))
                .containsExactly("ESTIMATE");
        assertThat(all(subscriptions, "$.data[?(@.name=='" + quorum + "')].backlog.value"))
                .containsExactly(2);
        assertThat(all(subscriptions, "$.data[?(@.name=='" + stream + "')].backlog.accuracy"))
                .containsExactly("UNAVAILABLE");
        assertThat(all(subscriptions, "$.data[?(@.name=='" + stream + "')].backlog.value"))
                .singleElement()
                .isNull();
        assertThat(all(subscriptions, "$.data[?(@.name=='" + stream + "')].backlog.reason"))
                .singleElement()
                .asString()
                .isNotBlank();
    }

    private static <T> List<T> all(String json, String path) {
        return JsonPath.read(json, path);
    }
}
