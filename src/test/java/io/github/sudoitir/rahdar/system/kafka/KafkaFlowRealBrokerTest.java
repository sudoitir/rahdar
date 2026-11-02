package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowSample;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest.KafkaConnection;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;

/** Flow against a real Kafka: a group with a running member is a consumer of its topic, and no producers are listed. */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaFlowRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.image", "apache/kafka:4.4.0");

    private static KafkaContainer kafka;

    @Autowired
    ClusterService service;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    KafkaFlowSampler sampler;

    private UUID cluster;
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String topic = "flow-" + run;
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
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        }
        cluster = ((Attempt.Ok<ClusterDetail>) service.register(new RegisterClusterRequest(
                        KafkaModule.SYSTEM,
                        "kafka-flow-" + run,
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
    void aGroupWithARunningMemberIsAConsumerOfItsTopic() {
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        kafka.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        group),
                new ByteArrayDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        consumer.poll(Duration.ofMillis(500));
                        FlowSample sample = sampler.sample(cluster, UUID.randomUUID(), 100);
                        assertThat(sample.producers()).isEmpty();
                        assertThat(sample.routes()).isEmpty();
                        assertThat(sample.consumers()).anySatisfy(m -> {
                            assertThat(m.clientId()).isEqualTo(group);
                            assertThat(m.destination()).isEqualTo(topic);
                            assertThat(m.subscription()).isEqualTo(group + "/" + topic);
                        });
                    });
        }
    }
}
