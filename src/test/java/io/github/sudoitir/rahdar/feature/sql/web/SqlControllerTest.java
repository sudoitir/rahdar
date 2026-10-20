package io.github.sudoitir.rahdar.feature.sql.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code POST .../sql/plan} — the operation that runs while the operator is still
 * typing.
 *
 * <p>Its whole contract is that it contacts no broker (ADR-0058 D6): it resolves
 * targets from {@code subscription_snapshot}, splits the predicates, and states the cost.
 * These tests therefore stub nothing — a plan that reached a broker would fail here
 * for want of one, which is the point.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class SqlControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        node.attachSeedUrl("http://a:8161/console/jolokia");
        UUID nodeId = nodes.save(node).getId();
        upsert.upsertBatch(List.of(
                new QueueRow(
                        clusterId,
                        nodeId,
                        "ORDER.IN",
                        "ORDER.IN",
                        "ANYCAST",
                        true,
                        120,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        false,
                        "artemis.queue"),
                new QueueRow(
                        clusterId,
                        nodeId,
                        "ORDER.OUT",
                        "ORDER.OUT",
                        "ANYCAST",
                        true,
                        5,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        false,
                        "artemis.queue")));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    private ResultActions plan(String sql) throws Exception {
        return mvc.perform(post("/api/v1/clusters/{c}/sql/plan", clusterId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":" + quote(sql) + "}"));
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    @Test
    void resolvesTargetsFromTheSnapshotWithoutContactingABroker() throws Exception {
        plan("SELECT * FROM \"ORDER.*\" LIMIT 10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets.length()").value(2))
                .andExpect(jsonPath("$.targets[*].queueName", Matchers.hasItem("ORDER.IN")));
    }

    @Test
    void aHeaderPredicateBecomesASelectorAndABodyPredicateDoesNot() throws Exception {
        // The distinction the cost line exists to show: one costs the broker nothing,
        // the other is a scan of everything the broker returned (ADR-0058 D4).
        plan("SELECT * FROM \"ORDER.IN\" WHERE priority = 9")
                .andExpect(status().isOk())
                // The catalogue name is rewritten to the JMS header name the broker
                // actually understands; identifiers come from the catalogue, never
                // from the query text (ADR-0058 D5).
                .andExpect(jsonPath("$.selector").value("JMSPriority = 9"))
                .andExpect(jsonPath("$.requiresScan").value(false));

        plan("SELECT * FROM \"ORDER.IN\" WHERE body LIKE '%4471%'")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selector").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.requiresScan").value(true))
                .andExpect(jsonPath("$.scanned.length()").value(1));
    }

    @Test
    void aPatternThatMatchesNoQueueIsANoticeAndNotAnEmptySuccess() throws Exception {
        // An empty target list is not an empty queue, and the difference is the whole
        // reason the console reports notices rather than just rows.
        plan("SELECT * FROM \"NOTHING.HERE\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets.length()").value(0))
                .andExpect(jsonPath("$.notices[*].kind", Matchers.hasItem("NO_QUEUE_MATCHED")));
    }

    @Test
    void aStatementThatIsNotASelectIsRefusedByTheParser() throws Exception {
        // Rejection is by AST walk, not by pattern-matching the text (ADR-0058 D2).
        plan("DELETE FROM \"ORDER.IN\"").andExpect(status().isBadRequest());
        plan("SELECT * FROM \"ORDER.IN\" UNION SELECT * FROM \"ORDER.OUT\"").andExpect(status().isBadRequest());
    }

    @Test
    void aColumnOutsideTheCatalogueIsRefused() throws Exception {
        // The catalogue is the whitelist; anything not on it cannot be queried at all.
        plan("SELECT * FROM \"ORDER.IN\" WHERE nosuchcolumn = 1").andExpect(status().isBadRequest());
    }

    @Test
    void anIndexOnlyColumnAgainstTheBrokerIsRefusedByName() throws Exception {
        // observedAt describes an observation, not a message, so it has no meaning
        // against a live broker. The planner refuses rather than quietly dropping the
        // predicate — a dropped predicate returns more rows than were asked for and
        // looks like a correct answer.
        plan("SELECT * FROM broker.\"ORDER.IN\" WHERE observedAt > now() - interval '1 hour'")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", Matchers.containsString("observedAt")))
                .andExpect(jsonPath("$.detail", Matchers.containsString("index.")));
    }

    @Test
    void theSourceIsTheFromQualifierAndTheResultSaysWhichAnswered() throws Exception {
        // The source is in the query text, not in a request parameter, so a query
        // pasted into a ticket carries which backend it asked (ADR-0058 D3).
        plan("SELECT * FROM broker.\"ORDER.IN\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("BROKER"));
    }
}
