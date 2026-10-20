package io.github.sudoitir.rahdar.feature.metrics.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** {@code GET .../metrics?splitBy=NODE}: the per-node series of one queue (ADR-0110). */
@ExtendWith(AdminAuthenticationExtension.class)
class MetricsControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @MockitoBean
    BrokerConnections connections;

    private UUID clusterId;
    private UUID nodeA;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("metrics-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity a = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        nodeA = nodes.save(a).getId();
        Instant base = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(600);
        sample(base, 100);
        sample(base.plusSeconds(30), 400);
    }

    private void sample(Instant ts, double value) {
        jdbc.update("""
                INSERT INTO metric_sample (ts, value, subject_kind, subject_path, metric, cluster_id, node_id)
                VALUES (:ts, :v, 'QUEUE', 'orders', 'openbroker.backlog', :c, :n)
                """, Map.of("ts", Timestamp.from(ts), "v", value, "c", clusterId, "n", nodeA));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM metric_sample WHERE cluster_id = :c", Map.of("c", clusterId));
        clusters.deleteById(clusterId);
    }

    @Test
    void aQueueSplitByNodeNamesEachNodeBesideTheUnchangedTotal() throws Exception {
        mvc.perform(get("/api/v1/clusters/{id}/metrics", clusterId)
                        .param("metric", "openbroker.backlog")
                        .param("resource", "artemis.queue/orders")
                        .param("splitBy", "NODE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splitBy").value("NODE"))
                .andExpect(jsonPath("$.series[0].metric").value("openbroker.backlog"))
                .andExpect(jsonPath("$.byNode[0].nodeName").value("node-a"))
                .andExpect(jsonPath("$.byNode[0].sampled").value(true))
                .andExpect(jsonPath("$.byNode[0].series[0].points[0].peak").value(400.0));
    }

    @Test
    void anUnsplitQueryCarriesNoSplit() throws Exception {
        mvc.perform(get("/api/v1/clusters/{id}/metrics", clusterId).param("metric", "openbroker.backlog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splitBy").doesNotExist())
                .andExpect(jsonPath("$.byNode").doesNotExist());
    }

    @Test
    void aSplitOfTheClusterScopeIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/clusters/{id}/metrics", clusterId)
                        .param("metric", "openbroker.backlog")
                        .param("splitBy", "NODE"))
                .andExpect(status().isBadRequest());
    }
}
