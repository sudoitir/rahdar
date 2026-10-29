package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Every shared read route of a cluster answers a Kafka cluster with data, a client error or the honest "this system has
 * none" (ADR-0216), and never with a server error: a route that still went to an Artemis-only path would answer 502
 * for a cluster whose brokers have no management URL.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaSharedRoutesRealBrokerTest extends PostgresIntegrationTest {

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

    @Autowired
    @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;

    @BeforeAll
    static void startKafka() {
        kafka = new KafkaContainer(IMAGE);
        kafka.start();
    }

    @AfterAll
    static void stopKafka() {
        kafka.stop();
    }

    @Test
    void noSharedReadRouteOfAClusterAnswersAKafkaClusterWithAServerError() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        String topic = "routes-" + run;
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get();
        }
        UUID cluster = ((Attempt.Ok<ClusterDetail>) service.register(new RegisterClusterRequest(
                        KafkaModule.SYSTEM,
                        "kafka-routes-" + run,
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
        try {
            snapshotUpsert.upsertBatch(scrape.subscriptions(cluster));
            MockMvc mvc = webAppContextSetup(webContext).build();
            Map<String, String> values = Map.of(
                    "clusterId",
                    cluster.toString(),
                    "kind",
                    "kafka.topic",
                    "path",
                    topic,
                    "node",
                    UUID.randomUUID().toString(),
                    "nodeId",
                    UUID.randomUUID().toString(),
                    "topic",
                    topic,
                    "group",
                    "g-" + run);
            List<String> walked = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            List<RequestMappingInfo> gets = mapping.getHandlerMethods().keySet().stream()
                    .filter(i -> i.getMethodsCondition().getMethods().contains(RequestMethod.GET))
                    .sorted(Comparator.comparing(RequestMappingInfo::toString))
                    .toList();
            for (RequestMappingInfo info : gets) {
                for (String pattern : info.getPatternValues()) {
                    if (!pattern.contains("/clusters/{clusterId}")) {
                        continue;
                    }
                    String url = pattern.replace("{version}", "v1");
                    for (var e : values.entrySet()) {
                        url = url.replace("{" + e.getKey() + "}", e.getValue());
                    }
                    url = url.replaceAll("\\{[^}/]+\\}", "x");
                    int status = mvc.perform(get(url)).andReturn().getResponse().getStatus();
                    walked.add(url);
                    if (status >= 500) {
                        failures.add(status + " " + pattern);
                    }
                }
            }
            assertThat(walked).as("the walk sees the cluster's read routes").hasSizeGreaterThan(30);
            assertThat(failures)
                    .as("read routes answering a Kafka cluster with a server error")
                    .isEmpty();
        } finally {
            clusters.deleteById(cluster);
        }
    }
}
