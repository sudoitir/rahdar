package io.github.sudoitir.rahdar.feature.messages.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.json.JsonMapper;

/** {@code GET .../queues/{q}/messages} — page shape, single-message detail + 404, node echo, bad filter, capability path. */
@ExtendWith(AdminAuthenticationExtension.class)
class MessageBrowseControllerTest extends PostgresIntegrationTest {

    private static final String URL_A = "http://a:8161/console/jolokia";

    private final JsonMapper mapper = new JsonMapper();

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
    ArtemisNodes artemisNodes;

    @MockitoBean
    BrokerGateway gateway;

    private UUID clusterId;
    private UUID nodeAId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity a = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        a.attachSeedUrl(URL_A);
        nodeAId = nodes.save(a).getId();
        upsert.upsertBatch(List.of(new QueueRow(
                clusterId, nodeAId, "PHASE3.SRC", "PHASE3.SRC", "ANYCAST", true, 4, 0, 0, 0, 0, 0, 0, false)));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    /**
     * The node as the broker SDK reads it: a search for the broker MBean, one for the queue's, then one
     * batched browse POST.
     */
    private void serving(UUID node, String queue, String batchBody) {
        FixtureJolokia server = new FixtureJolokia();
        server.expect(requestTo(server.url()))
                .andRespond(withSuccess(fixture("search-broker.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(server.url())).andRespond(withSuccess(queueSearch(queue), MediaType.APPLICATION_JSON));
        server.expect(requestTo(server.url())).andRespond(withSuccess(batchBody, MediaType.APPLICATION_JSON));
        when(gateway.cluster(clusterId)).thenReturn(FixtureJolokia.cluster(Map.of(node, server)));
    }

    private static String queueSearch(String queue) {
        return "{\"status\":200,\"value\":[\"org.apache.activemq.artemis:address=\\\"" + queue
                + "\\\",broker=\\\"primary\\\",component=addresses,queue=\\\"" + queue
                + "\\\",routing-type=\\\"anycast\\\",subcomponent=queues\"]}";
    }

    private void unauthorized() {
        FixtureJolokia server = new FixtureJolokia();
        server.expect(requestTo(server.url())).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        when(gateway.cluster(clusterId)).thenReturn(FixtureJolokia.cluster(Map.of(nodeAId, server)));
    }

    private static String batch(String browseFixture, long count) {
        return "[" + fixture(browseFixture) + ",{\"value\":" + count + ",\"status\":200}]";
    }

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("jolokia/" + name).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void browseReturnsAPageAndEchoesTheNode() throws Exception {
        serving(nodeAId, "PHASE3.SRC", batch("browse.json", 4));

        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, "PHASE3.SRC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].messageId").value(127))
                .andExpect(jsonPath("$.data[3].bodyTruncated").value(true))
                .andExpect(jsonPath("$.node").value(nodeAId.toString()));
    }

    @Test
    void detailFindsOneMessageById() throws Exception {
        serving(nodeAId, "PHASE3.SRC", batch("browse.json", 4));

        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages/{id}", clusterId, "PHASE3.SRC", 133))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(133))
                .andExpect(jsonPath("$.stringProperties.orderId").value("A-2"))
                .andExpect(jsonPath("$.node").value(nodeAId.toString()));
    }

    @Test
    void detailIsA404WhenTheIdIsNotInRange() throws Exception {
        serving(nodeAId, "PHASE3.SRC", batch("browse.json", 4));

        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages/{id}", clusterId, "PHASE3.SRC", 999999))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInvalidFilterIsA400() throws Exception {
        serving(nodeAId, "PHASE3.SRC", batch("browse.json", 4));

        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, "PHASE3.SRC")
                        .param("filter", "this is not a filter =="))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid value"));
    }

    @Test
    void aBrokerThatRefusesTheReadSurfacesAClassifiedProblem() throws Exception {
        unauthorized();

        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, "PHASE3.SRC"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.brokerErrorKind").value("CREDENTIALS_REJECTED"));
    }

    /**
     * Two live nodes, each with its own copy of the queue: the messages are on the second.
     * With no node asked for, the browse opens the node that holds them — not whichever
     * live node the snapshot happens to list first, which showed an empty queue.
     */
    @Test
    void withNoNodeAskedForTheBrowseOpensTheLiveNodeHoldingTheMessages() throws Exception {
        String urlB = "http://b:8161/console/jolokia";
        BrokerNodeEntity a = nodes.findById(nodeAId).orElseThrow();
        a.applyHaState(new HaObservation(true, "STARTED", "PRIMARY", null, "2.44.0", null), 1L, Instant.now());
        nodes.save(a);
        BrokerNodeEntity b = BrokerNodeEntity.fromSeed(
                clusterId, "node-b", "PRIMARY", UUID.randomUUID().toString());
        b.attachSeedUrl(urlB);
        b.applyHaState(new HaObservation(true, "STARTED", "PRIMARY", null, "2.44.0", null), 1L, Instant.now());
        UUID nodeBId = nodes.save(b).getId();
        upsert.upsertBatch(List.of(
                new QueueRow(clusterId, nodeAId, "SPLIT", "SPLIT", "ANYCAST", true, 0, 0, 0, 0, 0, 0, 0, false),
                new QueueRow(clusterId, nodeBId, "SPLIT", "SPLIT", "ANYCAST", true, 4, 0, 0, 0, 0, 0, 0, false)));

        serving(nodeBId, "SPLIT", batch("browse.json", 4));

        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, "SPLIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node").value(nodeBId.toString()))
                .andExpect(jsonPath("$.count").value(4))
                .andExpect(jsonPath("$.data.length()").value(4));
    }

    /**
     * A queue created a moment ago is on the broker before the scrape has reached it. It
     * is found with one live read on the node, not answered with "does not exist".
     */
    @Test
    void aQueueTheScrapeHasNotReachedIsFoundOnTheLiveNodeAndBrowsed() throws Exception {
        BrokerNodeEntity a = nodes.findById(nodeAId).orElseThrow();
        a.applyHaState(new HaObservation(true, "STARTED", "PRIMARY", null, "2.44.0", null), 1L, Instant.now());
        nodes.save(a);
        String located = "{\"status\":200,\"value\":{"
                + "\"org.apache.activemq.artemis:address=\\\"FRESH.ADDR\\\",broker=\\\"primary\\\",component=addresses,"
                + "queue=\\\"FRESH\\\",routing-type=\\\"anycast\\\",subcomponent=queues\":{\"MessageCount\":4}}}";

        FixtureJolokia server = new FixtureJolokia();
        server.expect(requestTo(URL_A))
                .andRespond(withSuccess(fixture("search-broker.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL_A)).andRespond(withSuccess(located, MediaType.APPLICATION_JSON));
        when(artemisNodes.on(eq(clusterId), any(UUID.class))).thenReturn(server.node());
        serving(nodeAId, "FRESH", batch("browse.json", 4));

        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, "FRESH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node").value(nodeAId.toString()))
                .andExpect(jsonPath("$.data.length()").value(4));
        server.verify();
    }

    @Test
    void anUnknownQueueIsA404() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, "NOPE"))
                .andExpect(status().isNotFound());
    }
}
