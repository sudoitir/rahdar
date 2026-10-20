package io.github.sudoitir.rahdar.feature.messages.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.FakeArtemis;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.web.context.WebApplicationContext;

/**
 * Send / move / retry / delete / expire / purge over one queue: audit lifecycle,
 * dry-run issues no mutating call, and the server-enforced bulk cap. Consolidates
 * tasks 7.6 / 8.7 / 9.10 / 10.5.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class MessageMutationControllerTest extends PostgresIntegrationTest {

    private static final String URL = "http://a:8161/console/jolokia";
    private static final String Q = "PHASE3.SRC";

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    AuditEventRepository audit;

    @Autowired
    SettingsService settings;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    BrokerGateway gateway;

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
        upsert.upsertBatch(List.of(
                new QueueRow(clusterId, nodeId, Q, Q, "ANYCAST", true, 3, 0, 0, 0, 0, 0, 0, false, "artemis.queue")));
        settings.reset(BrokerSettings.BULK_CAP);
    }

    @AfterEach
    void cleanUp() {
        audit.deleteAll();
        clusters.deleteById(clusterId);
        settings.reset(BrokerSettings.BULK_CAP);
    }

    /** The node, answered as a broker holding the queue with three messages. */
    private FakeArtemis serving() {
        FakeArtemis broker = new FakeArtemis().queue(Q).attribute("MessageCount", 3);
        when(gateway.cluster(clusterId)).thenReturn(FixtureJolokia.cluster(Map.of(nodeId, broker.agent())));
        return broker;
    }

    private void unauthorized() {
        FixtureJolokia server = new FixtureJolokia();
        server.expect().andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        when(gateway.cluster(clusterId)).thenReturn(FixtureJolokia.cluster(Map.of(nodeId, server)));
    }

    private AuditEventEntity onlyAudit() {
        List<AuditEventEntity> all = audit.findByClusterIdOrderByTsDesc(clusterId);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    // ---- send ----------------------------------------------------------

    @Test
    void sendDryRunWritesAPendingToSuccessRowAndIssuesNoBrokerCall() throws Exception {
        // No client stub → any broker POST would fail the test.
        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, Q)
                        .param("dryRun", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"TEXT\",\"durable\":true,\"body\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedCount").value(1))
                .andExpect(jsonPath("$.cap").exists());

        AuditEventEntity row = onlyAudit();
        assertThat(row.getAction()).isEqualTo("SEND_MESSAGE");
        assertThat(row.isDryRun()).isTrue();
        assertThat(row.getOutcome()).isEqualTo("SUCCESS");
    }

    @Test
    void sendRealWritesASuccessRowWithCountOne() throws Exception {
        FakeArtemis broker = serving().operation("sendMessage", "ID:1");

        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, Q)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"TEXT\",\"durable\":true,\"body\":\"hi\",\"properties\":{\"k\":\"v\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedCount").value(1));

        AuditEventEntity row = onlyAudit();
        assertThat(row.getOutcome()).isEqualTo("SUCCESS");
        assertThat(row.getAffectedCount()).isEqualTo(1);
        assertThat(row.isDryRun()).isFalse();
        assertThat(broker.operations()).containsExactly("sendMessage");
    }

    @Test
    void sendValidationFailureIsA400WithNoAuditRow() throws Exception {
        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, Q)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"durable\":true}"))
                .andExpect(status().isBadRequest());
        assertThat(audit.findByClusterIdOrderByTsDesc(clusterId)).isEmpty();
    }

    @Test
    void sendOfABytesBodyThatIsNotBase64IsA400WithNoAuditRow() throws Exception {
        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, Q)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BYTES\",\"durable\":true,\"body\":\"not base64!\"}"))
                .andExpect(status().isBadRequest());
        assertThat(audit.findByClusterIdOrderByTsDesc(clusterId)).isEmpty();
    }

    // ---- by id -------------------------------------------------------

    @Test
    void deleteByIdsDryRunNeedsNoBrokerAndAuditsTheIdCount() throws Exception {
        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages/actions/delete", clusterId, Q)
                        .param("dryRun", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[1,2,3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedCount").value(3));

        AuditEventEntity row = onlyAudit();
        assertThat(row.getAction()).isEqualTo("DELETE_MESSAGES");
        assertThat(row.isDryRun()).isTrue();
        assertThat(row.getOutcome()).isEqualTo("SUCCESS");
    }

    @Test
    void deleteByIdsRealCountsTheBrokerBooleans() throws Exception {
        FakeArtemis broker = serving().operation("removeMessage", true);

        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages/actions/delete", clusterId, Q)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[10,11]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedCount").value(2));

        assertThat(onlyAudit().getAffectedCount()).isEqualTo(2);
        assertThat(broker.operations()).containsExactly("removeMessage", "removeMessage");
    }

    @Test
    void moveByIdsWithoutTargetIsA400() throws Exception {
        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages/actions/move", clusterId, Q)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[1]}"))
                .andExpect(status().isBadRequest());
    }

    // ---- by filter + cap -------------------------------------------

    @Test
    void deleteByFilterDryRunIssuesOnlyCountMessages() throws Exception {
        FakeArtemis broker = serving().operation("countMessages", 3);

        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages/actions/delete", clusterId, Q)
                        .param("dryRun", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":\"region = 'eu'\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedCount").value(3))
                .andExpect(jsonPath("$.overCap").value(false));

        assertThat(onlyAudit().isDryRun()).isTrue();
        assertThat(broker.operations()).containsExactly("countMessages");
    }

    @Test
    void deleteByFilterOverCapIsA422WithAffectedCountAndCap() throws Exception {
        settings.put(BrokerSettings.BULK_CAP, "2");
        serving().operation("countMessages", 3);

        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages/actions/delete", clusterId, Q)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":\"region = 'eu'\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("bulk-cap-exceeded")))
                .andExpect(jsonPath("$.affectedCount").value(3))
                .andExpect(jsonPath("$.cap").value(2));

        assertThat(onlyAudit().getOutcome()).isEqualTo("FAILURE");
    }

    @Test
    void deleteByFilterExactlyAtTheCapIsAllowed() throws Exception {
        settings.put(BrokerSettings.BULK_CAP, "3");
        serving().operation("countMessages", 3).operation("removeMessages", 3);

        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages/actions/delete", clusterId, Q)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":\"region = 'eu'\"}"))
                .andExpect(status().isOk());

        assertThat(onlyAudit().getOutcome()).isEqualTo("SUCCESS");
    }

    @Test
    void deleteByFilterOverCapProceedsWithOverride() throws Exception {
        settings.put(BrokerSettings.BULK_CAP, "2");
        serving().operation("countMessages", 3).operation("removeMessages", 1);

        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages/actions/delete", clusterId, Q)
                        .param("override", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":\"region = 'eu'\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedCount").value(1));

        assertThat(onlyAudit().getOutcome()).isEqualTo("SUCCESS");
    }

    // ---- purge ---------------------------------------------------

    @Test
    void purgeDryRunReadsMessageCountAndDoesNotRemove() throws Exception {
        FakeArtemis broker = serving();

        mvc.perform(delete("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, Q)
                        .param("dryRun", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedCount").value(3));

        AuditEventEntity row = onlyAudit();
        assertThat(row.getAction()).isEqualTo("PURGE_QUEUE");
        assertThat(row.isDryRun()).isTrue();
        assertThat(broker.operations()).isEmpty();
    }

    @Test
    void purgeRealAuditsTheRemovedCount() throws Exception {
        FakeArtemis broker = serving().operation("removeAllMessages", 3);

        mvc.perform(delete("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, Q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedCount").value(3));

        assertThat(onlyAudit().getAffectedCount()).isEqualTo(3);
        assertThat(broker.operations()).containsExactly("removeAllMessages");
    }

    // ---- broker failure ---------------------------------------

    @Test
    void aBrokerFailureLeavesAFailureRowAndReturnsAClassifiedProblem() throws Exception {
        unauthorized();

        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, Q)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"TEXT\",\"durable\":true,\"body\":\"hi\"}"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.brokerErrorKind").value("CREDENTIALS_REJECTED"));

        assertThat(onlyAudit().getOutcome()).isEqualTo("FAILURE");
    }
}
