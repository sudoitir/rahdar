package io.github.sudoitir.rahdar.system.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.rahdar.feature.sql.BrokerQueryExecutor;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.feature.sql.SqlConsoleService;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
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
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The SQL console on a Kafka cluster against a real broker: a topic is read from its log, the WHERE is evaluated by
 * Rahdar, and each row carries its offset and its partition.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class KafkaSqlRealBrokerTest extends PostgresIntegrationTest {

    private static final String IMAGE = System.getProperty("kafka.image", "apache/kafka:4.4.0");

    private static KafkaContainer kafka;

    @Autowired
    ClusterService service;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    SqlConsoleService console;

    private final String run = "kafka-sql-" + UUID.randomUUID().toString().substring(0, 8);
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

    @AfterEach
    void removeTheCluster() {
        clusters.deleteById(cluster);
    }

    private List<Row> query(String sql) {
        List<Row> rows = new CopyOnWriteArrayList<>();
        console.run(cluster, sql, new BrokerQueryExecutor.Sink() {
            @Override
            public void row(Row row) {
                rows.add(row);
            }

            @Override
            public void nodeFinished(NodeOutcome outcome) {
                // The rows are what this test reads.
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        });
        return rows;
    }

    @Test
    void aTopicIsQueriedFromItsLogAndOnlyTheMatchingRecordsComeBackWithTheirOffsetAndPartition() throws Exception {
        cluster = ((Attempt.Ok<ClusterDetail>) service.register(new RegisterClusterRequest(
                        KafkaModule.SYSTEM,
                        run,
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
        String topic = run + "-orders";
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1))).all().get();
        }
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(
                Map.of("bootstrap.servers", kafka.getBootstrapServers()),
                new ByteArraySerializer(),
                new ByteArraySerializer())) {
            // Partition 0 holds offsets 0..2 and partition 1 holds offsets 0..2.
            for (int i = 0; i < 6; i++) {
                String state = i % 3 == 2 ? "late" : "paid";
                producer.send(new ProducerRecord<>(topic, i % 2, ("k" + i).getBytes(), (state + "-" + i).getBytes()))
                        .get();
            }
        }

        List<Row> rows = await().pollInSameThread()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(300))
                .ignoreExceptions()
                .until(
                        () -> query("SELECT * FROM \"" + topic + "\" WHERE body LIKE 'paid%' AND props.partition = 1"),
                        found -> !found.isEmpty());

        // Partition 1 holds records 1, 3 and 5 at offsets 0, 1 and 2; record 5 is "late".
        assertThat(rows).extracting(Row::body).containsExactlyInAnyOrder("paid-1", "paid-3");
        assertThat(rows).extracting(Row::messageId).containsExactlyInAnyOrder(0L, 1L);
        assertThat(rows).allSatisfy(row -> assertThat(row.properties()).containsEntry("partition", 1L));
    }
}
