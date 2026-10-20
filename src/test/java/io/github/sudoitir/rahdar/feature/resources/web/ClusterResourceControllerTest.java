package io.github.sudoitir.rahdar.feature.resources.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** {@code /api/v1/clusters/{id}/{queues,consumers,...}} — one endpoint per resource, plus the capability-gated path. */
@ExtendWith(AdminAuthenticationExtension.class)
class ClusterResourceControllerTest extends PostgresIntegrationTest {

    private static final String URL_A = "http://a:8161/console/jolokia";
    private static final String URL_B = "http://b:8161/console/jolokia";

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    BrokerGateway gateway;

    private UUID clusterId;
    private UUID nodeAId;
    private UUID nodeBId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity a = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        a.attachSeedUrl(URL_A);
        BrokerNodeEntity b = BrokerNodeEntity.fromSeed(
                clusterId, "node-b", "PRIMARY", UUID.randomUUID().toString());
        b.attachSeedUrl(URL_B);
        nodeAId = nodes.save(a).getId();
        nodeBId = nodes.save(b).getId();
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    private static FixtureJolokia client(String... fixtures) {
        FixtureJolokia server = new FixtureJolokia();
        for (String fixture : fixtures) {
            server.expect().andRespond(withSuccess(body(fixture), MediaType.APPLICATION_JSON));
        }
        return server;
    }

    private static FixtureJolokia unauthorized() {
        FixtureJolokia server = new FixtureJolokia();
        server.expect().andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        return server;
    }

    /** The cluster as the gateway gives it: node A answered by one agent, node B by the other. */
    private void serving(FixtureJolokia a, FixtureJolokia b) {
        Map<UUID, FixtureJolokia> agents = new LinkedHashMap<>();
        agents.put(nodeAId, a);
        agents.put(nodeBId, b);
        when(gateway.cluster(clusterId)).thenReturn(FixtureJolokia.cluster(agents));
    }

    private static String body(String fixture) {
        try {
            return new String(
                    new ClassPathResource("jolokia/" + fixture).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private QueueRow row(UUID node, String queue, long messageCount) {
        return new QueueRow(
                clusterId, node, queue, queue, "ANYCAST", true, messageCount, 1, 0, 0, 0, 0, 0, false, "artemis.queue");
    }

    @Test
    void queuesAggregatesTheSnapshotCacheAcrossNodes() throws Exception {
        upsert.upsertBatch(List.of(row(nodeAId, "ORDERS", 10), row(nodeBId, "ORDERS", 5)));

        mvc.perform(get("/api/v1/clusters/{id}/queues", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].queueName").value("ORDERS"))
                .andExpect(jsonPath("$.data[0].totalMessageCount").value(15))
                .andExpect(jsonPath("$.data[0].nodesPresent").value(2));
    }

    @Test
    void consumersFansOutLiveAndTagsEachRowWithItsNode() throws Exception {
        serving(
                client("search-broker.json", "list-consumers.json"),
                client("search-broker.json", "list-consumers.json"));

        mvc.perform(get("/api/v1/clusters/{id}/consumers", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.data[0].nodeName").value(Matchers.startsWith("node-")))
                .andExpect(jsonPath("$.data[0].queueName").value("SPIKE.A.q000"));
    }

    @Test
    void aClusterWithNoReadableNodeSurfacesAClassifiedProblem() throws Exception {
        serving(unauthorized(), unauthorized());

        mvc.perform(get("/api/v1/clusters/{id}/sessions", clusterId))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.brokerErrorKind").value("CREDENTIALS_REJECTED"));
    }
}
