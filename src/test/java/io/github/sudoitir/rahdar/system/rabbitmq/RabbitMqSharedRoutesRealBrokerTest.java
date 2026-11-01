package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.jayway.jsonpath.JsonPath;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/**
 * The shared routes of a cluster against a real RabbitMQ (ADR-0224): the three queue kinds in the shared lists, a
 * whole-cluster delete and purge of classic and quorum queues previewed and then run, a stream that is not purged
 * and says why, and a message sent to an exchange with a routing key.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class RabbitMqSharedRoutesRealBrokerTest extends PostgresIntegrationTest {

    private static final String CLASSIC = "rabbitmq.classic-queue";
    private static final String QUORUM = "rabbitmq.quorum-queue";
    private static final String STREAM = "rabbitmq.stream";
    private static final String EXCHANGE = "rabbitmq.exchange";

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
    private final String vhost = "shared-" + run;

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
        rabbit.queue(vhost, "classic-" + run, "classic");
        rabbit.queue(vhost, "quorum-" + run, "quorum");
        rabbit.queue(vhost, "stream-" + run, "stream");
        rabbit.publish(vhost, "classic-" + run, 4);
        rabbit.publish(vhost, "quorum-" + run, 3);
        rabbit.awaitStatistics(vhost, "classic-" + run, 4);
        rabbit.awaitStatistics(vhost, "quorum-" + run, 3);
        MockHttpServletResponse created = mvc.perform(post("/api/v1/clusters")
                        .contentType(APPLICATION_JSON)
                        .content("{\"system\":\"rabbitmq\",\"name\":\"rmq-shared-" + run
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

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(
                        body == null
                                ? request
                                : request.contentType(APPLICATION_JSON).content(body))
                .andReturn()
                .getResponse();
    }

    private String ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        MockHttpServletResponse response = send(request, body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isLessThan(300);
        return response.getContentAsString();
    }

    /** The route of a resource of the virtual host: its id's segments are one path segment, joined by an encoded slash. */
    private URI resource(String kind, String name, String suffix) {
        return URI.create("/api/v1/clusters/" + cluster + "/resources/" + kind + "/" + vhost + "%2F" + name + suffix);
    }

    private static List<String> all(String json, String path) {
        return JsonPath.read(json, path);
    }

    @Test
    void theSharedListsShowTheThreeQueueKindsTheConsumersAndTheConnections() throws Exception {
        try (Connection connection = rabbit.connect(vhost);
                Channel channel = connection.createChannel()) {
            channel.basicConsume("classic-" + run, true, (tag, delivery) -> {}, tag -> {});

            String subscriptions = ok(get("/api/v1/clusters/{c}/subscriptions?q=" + run + "&size=50", cluster), null);
            assertThat(all(subscriptions, "$.data[*].kind")).containsExactlyInAnyOrder(CLASSIC, QUORUM, STREAM);
            assertThat(all(subscriptions, "$.data[*].name"))
                    .containsExactlyInAnyOrder("classic-" + run, "quorum-" + run, "stream-" + run);

            String destinations = ok(get("/api/v1/clusters/{c}/destinations?q=amq.&size=200", cluster), null);
            assertThat(all(destinations, "$.data[*].kind")).contains(EXCHANGE);

            // The broker publishes the consumers and connections it lists with its next statistics.
            await().pollInSameThread()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        assertThat(ok(get("/api/v1/clusters/{c}/consumers?size=100", cluster), null))
                                .contains("classic-" + run);
                        assertThat(ok(get("/api/v1/clusters/{c}/connections?size=100", cluster), null))
                                .contains("rahdar-test");
                    });
        }
    }

    @Test
    void aClassicAndAQuorumQueueArePurgedWholeClusterAfterAPreview() throws Exception {
        for (Object[] queue : new Object[][] {{CLASSIC, "classic-" + run, 4}, {QUORUM, "quorum-" + run, 3}}) {
            String kind = (String) queue[0];
            String name = (String) queue[1];
            String preview = ok(post(resource(kind, name, ":purge?dryRun=true")), null);
            assertThat((Integer) JsonPath.read(preview, "$.affectedCount")).isEqualTo(queue[2]);
            assertThat((String) JsonPath.read(preview, "$.identityBasis")).isEqualTo("CONFIGURATION");
            assertThat(rabbit.messageCount(vhost, name)).isEqualTo(queue[2]);

            String done = ok(post(resource(kind, name, ":purge")), null);

            assertThat((Boolean) JsonPath.read(done, "$.dryRun")).isFalse();
            assertThat(rabbit.messageCount(vhost, name)).isZero();
        }
    }

    @Test
    void aStreamIsNotPurgedAndTheDriversReasonIsSaid() throws Exception {
        MockHttpServletResponse response = send(post(resource(STREAM, "stream-" + run, ":purge")), null);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
        assertThat(response.getContentAsString())
                .contains("unsupported-on-kind")
                .contains("a stream keeps its messages until retention removes them");
    }

    @Test
    void aClassicAndAQuorumQueueAreDeletedWholeClusterWithTheirIdentityBasis() throws Exception {
        for (String[] queue : new String[][] {{CLASSIC, "classic-" + run}, {QUORUM, "quorum-" + run}}) {
            String preview = ok(delete(resource(queue[0], queue[1], "?dryRun=true")), null);
            assertThat((Boolean) JsonPath.read(preview, "$.dryRun")).isTrue();
            assertThat((String) JsonPath.read(preview, "$.identityBasis")).isEqualTo("CONFIGURATION");
            assertThat((Integer) JsonPath.read(preview, "$.nodes.length()")).isEqualTo(1);

            String done = ok(delete(resource(queue[0], queue[1], "")), null);
            assertThat((Boolean) JsonPath.read(done, "$.dryRun")).isFalse();
            assertThat(rabbit.call("GET", "/api/queues/" + RabbitMqBrokers.encode(vhost) + "/" + queue[1], null)
                            .status())
                    .isEqualTo(404);
        }
    }

    @Test
    void aMessageIsSentToAnExchangeWithARoutingKeyAndAnUnboundOneIsNotRouted() throws Exception {
        rabbit.exchange(vhost, "orders-" + run, "direct");
        rabbit.bind(vhost, "orders-" + run, "classic-" + run, "paid");
        String body = "{\"kind\":\"TEXT\",\"durable\":true,\"body\":\"hello\",\"routingKey\":\"%s\"}";

        ok(post(resource(EXCHANGE, "orders-" + run, "/messages")), body.formatted("paid"));
        assertThat(rabbit.messageCount(vhost, "classic-" + run)).isEqualTo(5);

        MockHttpServletResponse unrouted =
                send(post(resource(EXCHANGE, "orders-" + run, "/messages")), body.formatted("nobody"));
        assertThat(unrouted.getStatus()).as(unrouted.getContentAsString()).isEqualTo(409);
        assertThat(unrouted.getContentAsString()).contains("not-routed");
    }
}
