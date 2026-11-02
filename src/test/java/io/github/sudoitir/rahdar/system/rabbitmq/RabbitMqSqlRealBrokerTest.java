package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.rahdar.feature.sql.BrokerQueryExecutor;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.feature.sql.SqlConsoleService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;

/**
 * The SQL console on a RabbitMQ cluster against a real broker: a stream is queried from its log with the WHERE
 * evaluated by Rahdar, and a classic queue is refused with the reason, because reading it would take its messages.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class RabbitMqSqlRealBrokerTest extends PostgresIntegrationTest {

    private static RabbitMqBrokers rabbit;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    SqlConsoleService console;

    private UUID cluster;
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String vhost = "sql-" + run;
    private final String stream = "events-" + run;
    private final String classic = "jobs-" + run;

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
        rabbit.queue(vhost, stream, "stream");
        rabbit.queue(vhost, classic, "classic");
        rabbit.publish(vhost, stream, 5);
        rabbit.publish(vhost, classic, 3);
        rabbit.awaitStreamStatistics(vhost, stream, 5);
        rabbit.awaitStatistics(vhost, classic, 3);
        MockHttpServletResponse created = webAppContextSetup(webContext)
                .build()
                .perform(post("/api/v1/clusters")
                        .contentType(APPLICATION_JSON)
                        .content("{\"system\":\"rabbitmq\",\"name\":\"rmq-sql-" + run
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

    private List<Row> query(String sql) {
        List<Row> rows = new CopyOnWriteArrayList<>();
        console.run(cluster, sql, new BrokerQueryExecutor.Sink() {
            @Override
            public void row(Row row) {
                rows.add(row);
            }

            @Override
            public void nodeFinished(NodeOutcome outcome) {
                // The rows are what these tests read.
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        });
        return rows;
    }

    @Test
    void aStreamIsQueriedFromItsLogAndOnlyTheMatchingMessagesComeBack() {
        List<Row> rows = query("SELECT * FROM \"" + stream + "\" WHERE body LIKE 'message-%' AND messageId >= 3");

        assertThat(rows).extracting(Row::body).containsExactlyInAnyOrder("message-3", "message-4");
        assertThat(rows).extracting(Row::messageId).containsExactlyInAnyOrder(3L, 4L);
    }

    @Test
    void aClassicQueueIsRefusedBecauseReadingItWouldTakeItsMessages() throws Exception {
        assertThatThrownBy(() -> query("SELECT * FROM \"" + classic + "\""))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("can browse only streams");

        assertThat(rabbit.messageCount(vhost, classic)).isEqualTo(3);
    }
}
