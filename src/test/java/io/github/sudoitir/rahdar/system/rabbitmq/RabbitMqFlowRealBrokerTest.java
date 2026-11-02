package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.jayway.jsonpath.JsonPath;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowRoute;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowSample;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/** Flow against a real RabbitMQ: a consumer meets its queue by the snapshot's name, and the binding routes to it. */
@ExtendWith(AdminAuthenticationExtension.class)
class RabbitMqFlowRealBrokerTest extends PostgresIntegrationTest {

    private static RabbitMqBrokers rabbit;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    RabbitMqScrape scrape;

    @Autowired
    RabbitMqFlowSampler sampler;

    private UUID cluster;
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String vhost = "flow-" + run;

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
        rabbit.vhost(vhost);
        rabbit.queue(vhost, "q-" + run, "classic");
        rabbit.exchange(vhost, "x-" + run, "topic");
        rabbit.bind(vhost, "x-" + run, "q-" + run, "eu.#");
        MockHttpServletResponse created = webAppContextSetup(webContext)
                .build()
                .perform(post("/api/v1/clusters")
                        .contentType(APPLICATION_JSON)
                        .content("{\"system\":\"rabbitmq\",\"name\":\"rmq-flow-" + run
                                + "\",\"connection\":{\"system\":\"rabbitmq\",\"managementUrls\":[\""
                                + rabbit.managementUrl() + "\"],\"amqpEndpoints\":[\"" + rabbit.amqpEndpoint()
                                + "\"],\"credentials\":{\"username\":\"" + RabbitMqBrokers.USER + "\",\"password\":\""
                                + RabbitMqBrokers.PASSWORD + "\"}}}"))
                .andReturn()
                .getResponse();
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        cluster = UUID.fromString(JsonPath.read(created.getContentAsString(), "$.id"));
    }

    @AfterEach
    void unregister() {
        clusters.deleteById(cluster);
    }

    @Test
    void aConsumerAndABindingMeetTheQueueUnderTheNameTheSnapshotUses() throws Exception {
        try (Connection connection = rabbit.connect(vhost);
                Channel channel = connection.createChannel()) {
            channel.basicConsume("q-" + run, true, (tag, delivery) -> {}, tag -> {});

            FlowSample[] sample = new FlowSample[1];
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                sample[0] = sampler.sample(cluster, UUID.randomUUID(), 100);
                assertThat(sample[0].consumers()).isNotEmpty();
            });
            String queueName = scrape.subscriptions(cluster).stream()
                    .map(QueueRow::queueName)
                    .filter(n -> n.endsWith("q-" + run))
                    .findFirst()
                    .orElseThrow();

            assertThat(sample[0].error()).isNull();
            assertThat(sample[0].consumers())
                    .anySatisfy(m -> assertThat(m.subscription()).isEqualTo(queueName));
            assertThat(sample[0].routes())
                    .filteredOn(r -> r.kind().equals("BINDING") && r.target().equals(queueName))
                    .singleElement()
                    .satisfies((FlowRoute r) -> {
                        assertThat(r.source()).endsWith("x-" + run);
                        assertThat(r.filter()).isEqualTo("eu.#");
                    });
        }
    }
}
