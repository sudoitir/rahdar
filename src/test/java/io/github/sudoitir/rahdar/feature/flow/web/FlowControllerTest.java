package io.github.sudoitir.rahdar.feature.flow.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.feature.flow.ClientSampler;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** {@code GET .../flow}: the bounded graph, its ETag, the server-side clamp and a malformed focus. */
@ExtendWith(AdminAuthenticationExtension.class)
class FlowControllerTest extends PostgresIntegrationTest {

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

    /** A background sweep would change the graph between the two reads the ETag test makes. */
    @MockitoBean
    ClientSampler sampler;

    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "flow-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity a = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        a.attachSeedUrl("http://a:8161/console/jolokia");
        UUID nodeId = nodes.save(a).getId();
        upsert.upsertBatch(List.of(new QueueRow(
                clusterId,
                nodeId,
                "orders",
                "orders.dead",
                "ANYCAST",
                true,
                12,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                "artemis.queue",
                Map.of())));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    @Test
    void theGraphIsBoundedAndStatesItsFaults() throws Exception {
        mvc.perform(get("/api/v1/clusters/{id}/flow", clusterId))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.totals.paths").value(1))
                .andExpect(jsonPath("$.totals.limit").value(40))
                .andExpect(jsonPath("$.measuring").value(true))
                .andExpect(jsonPath("$.nodes[?(@.id == 'subscription:orders.dead')].faults[0]")
                        .value("NO_CONSUMER"))
                .andExpect(jsonPath("$.kpis.backlog").value(12));
    }

    @Test
    void anUnchangedGraphAnswersNotModified() throws Exception {
        String etag = mvc.perform(get("/api/v1/clusters/{id}/flow", clusterId))
                .andReturn()
                .getResponse()
                .getHeader("ETag");

        mvc.perform(get("/api/v1/clusters/{id}/flow", clusterId).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void aLimitAboveTheServersIsClampedAndSaysSo() throws Exception {
        mvc.perform(get("/api/v1/clusters/{id}/flow", clusterId).param("limit", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.limit").value(200))
                .andExpect(jsonPath("$.totals.clamped").value(true));
    }

    @Test
    void aMalformedFocusIsRefused() throws Exception {
        mvc.perform(get("/api/v1/clusters/{id}/flow", clusterId).param("focus", "orders"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theBreakdownIsOptInAndCarriesEachNodesShare() throws Exception {
        String plain = mvc.perform(get("/api/v1/clusters/{id}/flow", clusterId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("ETag");

        var splitResponse = mvc.perform(
                        get("/api/v1/clusters/{id}/flow", clusterId).param("byNode", "true"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        String split = splitResponse.getHeader("ETag");
        Assertions.assertThat(splitResponse.getContentAsString())
                .contains("\"byNode\":[{")
                .contains("\"node\":\"node-a\"");

        Assertions.assertThat(split).isNotEqualTo(plain);
    }
}
