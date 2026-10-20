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
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import tools.jackson.databind.json.JsonMapper;

/** {@code GET .../dlq} — addresses from broker settings, per-node depth from the cache, honest unavailable path. */
@ExtendWith(AdminAuthenticationExtension.class)
class DlqControllerTest extends PostgresIntegrationTest {

    private static final String URL = "http://a:8161/console/jolokia";

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

    private UUID clusterId;
    private UUID nodeId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity a = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        a.attachSeedUrl(URL);
        nodeId = nodes.save(a).getId();
        // DLQ fixture reports deadLetterAddress "DLQ"; seed a queue on it.
        upsert.upsertBatch(
                List.of(new QueueRow(clusterId, nodeId, "DLQ", "DLQ", "ANYCAST", true, 7, 0, 0, 0, 0, 0, 0, false)));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    private ArtemisNode client(String... bodies) {
        FixtureJolokia server = new FixtureJolokia();
        for (String b : bodies) {
            server.expect(requestTo(URL)).andRespond(withSuccess(b, MediaType.APPLICATION_JSON));
        }
        return server.node();
    }

    private ArtemisNode unauthorized() {
        FixtureJolokia server = new FixtureJolokia();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        return server.node();
    }

    private static final String SEARCH =
            "{\"value\":[\"org.apache.activemq.artemis:broker=\\\"primary\\\"\"],\"status\":200}";

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("jolokia/" + name).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void listsTheDlaFromSettingsWithItsQueuesAndPerNodeDepth() throws Exception {
        when(artemisNodes.on(eq(clusterId), any(UUID.class)))
                .thenReturn(client(SEARCH, fixture("address-settings.json")));

        mvc.perform(get("/api/v1/clusters/{c}/dlq", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settingsAvailable").value(true))
                .andExpect(jsonPath("$.addresses[?(@.address == 'DLQ')].kind").value(Matchers.hasItem("dead-letter")))
                .andExpect(jsonPath("$.addresses[?(@.address == 'DLQ')].queues[0].queues", Matchers.anything()))
                .andExpect(jsonPath("$..queues[?(@.queueName == 'DLQ')].totalDepth")
                        .value(Matchers.hasItem(7)));
    }

    @Test
    void whenTheSettingsReadFailsItSaysSoAndInfersNothing() throws Exception {
        when(artemisNodes.on(eq(clusterId), any(UUID.class))).thenReturn(unauthorized());

        mvc.perform(get("/api/v1/clusters/{c}/dlq", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settingsAvailable").value(false))
                .andExpect(jsonPath("$.addresses.length()").value(0));
    }
}
