package io.github.sudoitir.rahdar.feature.brokerconfig.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET .../config-diff} — every node against the majority, the expected-vs-drift
 * split, and the cases where Rahdar must state a limitation rather than render a diff
 * (ADR-0043, ADR-0178).
 */
@ExtendWith(AdminAuthenticationExtension.class)
class ConfigDiffControllerTest extends PostgresIntegrationTest {

    private static final String A_URL = "http://a:8161/console/jolokia";
    private static final String B_URL = "http://b:8261/console/jolokia";
    private static final String C_URL = "http://c:8361/console/jolokia";
    private static final String NODE_ID = "shared-node-id";

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    AuditEventRepository auditEvents;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    ArtemisNodes artemisNodes;

    private UUID clusterId;
    private UUID aId;
    private UUID bId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        aId = node("node-a", "PRIMARY", A_URL);
        bId = node("node-b", "BACKUP", B_URL);
        node("node-c", "NONE", C_URL);
    }

    private UUID node(String name, String role, String url) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(clusterId, name, role, NODE_ID);
        n.attachSeedUrl(url);
        return nodes.save(n).getId();
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    /**
     * One batched POST per node: a search to resolve the MBean name, then the batch.
     * The mock server fails the test if more calls are made than bodies given.
     */
    private ArtemisNode client(String brokerName, String journalType, boolean active) {
        return client(brokerName, journalType, active, "[]");
    }

    private ArtemisNode client(String brokerName, String journalType, boolean active, String addressNames) {
        FixtureJolokia server = new FixtureJolokia();
        server.expect().andRespond(withSuccess(search(brokerName), MediaType.APPLICATION_JSON));
        server.expect()
                .andRespond(
                        withSuccess(batch(brokerName, journalType, active, addressNames), MediaType.APPLICATION_JSON));
        return server.node();
    }

    private ArtemisNode unauthorized() {
        FixtureJolokia server = new FixtureJolokia();
        server.expect().andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        return server.node();
    }

    /** The id of the node registered with this Jolokia URL. */
    private UUID at(String url) {
        return nodes.findByClusterIdOrderByNameAsc(clusterId).stream()
                .filter(n -> url.equals(n.getManagementUrl()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private static String search(String brokerName) {
        return "{\"value\":[\"org.apache.activemq.artemis:broker=\\\"" + brokerName + "\\\"\"],\"status\":200}";
    }

    /** The four batched entries: head read, full read, roles, address settings for "#". */
    private static String batch(String brokerName, String journalType, boolean active, String addressNames) {
        String acceptors = "[{\\\"name\\\":\\\"artemis\\\",\\\"params\\\":{\\\"host\\\":\\\"" + brokerName
                + "\\\",\\\"port\\\":\\\"61616\\\",\\\"protocols\\\":\\\"CORE\\\"}}]";
        return """
                [
                  {"status":200,"value":{"Active":%s,"AcceptorsAsJSON":"%s"}},
                  {"status":200,"value":{"Name":"%s","JournalType":"%s","TotalMessageCount":%s,"AddressNames":%s}},
                  {"status":200,"value":"[{\\"name\\":\\"amq\\",\\"consume\\":true}]"},
                  {"status":200,"value":"{\\"maxSizeBytes\\":-1}"}
                ]
                """.formatted(active, acceptors, brokerName, journalType, active ? "7" : "0", addressNames);
    }

    private static ResultMatcher nodeIs(String name, String field, Object value) {
        return jsonPath("$.nodes[?(@.nodeName == '" + name + "')]." + field).value(Matchers.hasItem(value));
    }

    private static String broker(String path) {
        return "$.sections[?(@.section == 'broker')].keys[?(@.key == '" + path + "')]";
    }

    private void threeNodes(String cType) {
        when(artemisNodes.on(clusterId, at(A_URL))).thenReturn(client("primary", "ASYNCIO", true));
        when(artemisNodes.on(clusterId, at(B_URL))).thenReturn(client("backup", "ASYNCIO", true));
        when(artemisNodes.on(clusterId, at(C_URL))).thenReturn(client("third", cType, true));
    }

    @Test
    void comparesEveryNodeAndNamesTheOutlierAgainstTheMajority() throws Exception {
        threeNodes("NIO");

        mvc.perform(get("/api/v1/clusters/{c}/artemis/config-diff", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparable").value(true))
                .andExpect(jsonPath("$.nodes.length()").value(3))
                .andExpect(jsonPath(broker("/JournalType") + ".majority").value(Matchers.hasItem("ASYNCIO")))
                .andExpect(jsonPath(broker("/JournalType") + ".outliers[0].nodeName")
                        .value(Matchers.hasItem("node-c")))
                .andExpect(
                        jsonPath(broker("/JournalType") + ".outliers[0].value").value(Matchers.hasItem("NIO")))
                .andExpect(jsonPath(broker("/JournalType") + ".drift").value(Matchers.hasItem(true)))
                // Name differs by design, and a runtime counter is not configuration.
                .andExpect(jsonPath(broker("/Name") + ".classification").value(Matchers.hasItem("EXPECTED")))
                .andExpect(jsonPath(broker("/TotalMessageCount") + ".classification")
                        .value(Matchers.hasItem("UNCLASSIFIED")))
                // The acceptor host is expected too, so JournalType is the only drift, on one node.
                .andExpect(jsonPath("$.summary.driftKeys").value(1))
                .andExpect(jsonPath("$.summary.driftNodes").value(1))
                .andExpect(jsonPath("$.summary.expectedKeys").value(Matchers.greaterThan(0)));
    }

    @Test
    void stateIsCarriedAsAWordNotOnlyAsAnEnum() throws Exception {
        threeNodes("NIO");

        mvc.perform(get("/api/v1/clusters/{c}/artemis/config-diff", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath(broker("/JournalType") + ".stateWord").value(Matchers.hasItem("different")));
    }

    @Test
    void anUnreadableNodeIsListedWithItsReasonAndTheOthersAreStillCompared() throws Exception {
        when(artemisNodes.on(clusterId, at(A_URL))).thenReturn(client("primary", "ASYNCIO", true));
        when(artemisNodes.on(clusterId, at(B_URL))).thenReturn(client("backup", "NIO", true));
        when(artemisNodes.on(clusterId, at(C_URL))).thenReturn(unauthorized());

        mvc.perform(get("/api/v1/clusters/{c}/artemis/config-diff", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparable").value(true))
                .andExpect(nodeIs("node-c", "available", false))
                .andExpect(nodeIs("node-c", "unavailableKind", "CREDENTIALS_REJECTED"))
                .andExpect(jsonPath("$.nodes[?(@.nodeName == 'node-c')].unavailableReason")
                        .value(Matchers.hasItem(Matchers.not(Matchers.emptyString()))))
                // Two answering nodes with two values have no majority; the dead node is no tiebreaker.
                .andExpect(jsonPath(broker("/JournalType") + ".valueGroups.length()")
                        .value(Matchers.hasItem(2)));
    }

    @Test
    void whenFewerThanTwoNodesAnswerNoComparisonIsMadeAndEveryReasonIsGiven() throws Exception {
        when(artemisNodes.on(clusterId, at(A_URL))).thenReturn(client("primary", "ASYNCIO", true));
        when(artemisNodes.on(clusterId, at(B_URL))).thenReturn(unauthorized());
        when(artemisNodes.on(clusterId, at(C_URL))).thenReturn(unauthorized());

        mvc.perform(get("/api/v1/clusters/{c}/artemis/config-diff", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparable").value(false))
                .andExpect(nodeIs("node-b", "unavailableKind", "CREDENTIALS_REJECTED"))
                .andExpect(nodeIs("node-c", "unavailableKind", "CREDENTIALS_REJECTED"))
                // Never a half-diff: the unreachable nodes' absent keys would read as removals.
                .andExpect(jsonPath("$.sections.length()").value(0))
                .andExpect(jsonPath("$.summary.driftKeys").value(0));
    }

    @Test
    void nodesNarrowsTheComparisonToTheNamedNodes() throws Exception {
        when(artemisNodes.on(clusterId, at(A_URL))).thenReturn(client("primary", "ASYNCIO", true));
        when(artemisNodes.on(clusterId, at(B_URL))).thenReturn(client("backup", "NIO", true));

        mvc.perform(get("/api/v1/clusters/{c}/artemis/config-diff", clusterId).param("nodes", aId + "," + bId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(2));
    }

    @Test
    void namingFewerThanTwoNodesIsRefused() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/artemis/config-diff", clusterId).param("nodes", aId.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPassiveBackupThatAnswersFullyIsStillCompared() throws Exception {
        // A passive backup reports AddressNames: [] where the primary reports entries.
        // That is a value difference, not a smaller surface, and must not suppress the
        // comparison — the live check against the dev pair caught exactly this.
        when(artemisNodes.on(clusterId, at(A_URL)))
                .thenReturn(client("primary", "ASYNCIO", true, "[\"orders\",\"events\"]"));
        when(artemisNodes.on(clusterId, at(B_URL))).thenReturn(client("backup", "ASYNCIO", false, "[]"));
        when(artemisNodes.on(clusterId, at(C_URL))).thenReturn(client("third", "ASYNCIO", true));

        mvc.perform(get("/api/v1/clusters/{c}/artemis/config-diff", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparable").value(true))
                .andExpect(nodeIs("node-b", "reducedSurface", false))
                .andExpect(jsonPath("$.notes.length()").value(0));
    }

    @Test
    void aReadOnlyComparisonWritesNoAuditEvent() throws Exception {
        long before = auditEvents.count();
        threeNodes("ASYNCIO");

        mvc.perform(get("/api/v1/clusters/{c}/artemis/config-diff", clusterId)).andExpect(status().isOk());

        Assertions.assertThat(auditEvents.count()).isEqualTo(before);
    }
}
