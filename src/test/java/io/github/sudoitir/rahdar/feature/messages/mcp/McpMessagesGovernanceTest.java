package io.github.sudoitir.rahdar.feature.messages.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/** {@code browse_messages} through MCP is governed exactly like the REST read (mcp-server spec, data-governance). */
class McpMessagesGovernanceTest extends PostgresIntegrationTest {

    private static final String URL_A = "http://a:8161/console/jolokia";

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    ApiTokenService tokens;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    BrokerGateway gateway;

    private MockMvc mvc;
    private UUID clusterId;
    private UUID nodeId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "c-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity a = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        a.attachSeedUrl(URL_A);
        nodeId = nodes.save(a).getId();
        upsert.upsertBatch(List.of(new QueueRow(
                clusterId,
                nodeId,
                "PHASE3.SRC",
                "PHASE3.SRC",
                "ANYCAST",
                true,
                1,
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

    /** The node as the broker SDK reads it: the broker and queue searches, then one batched browse. */
    private void serving() {
        FixtureJolokia server = new FixtureJolokia();
        server.expect().andRespond(withSuccess(fixture("search-broker.json"), MediaType.APPLICATION_JSON));
        server.expect()
                .andRespond(withSuccess(
                        "{\"status\":200,\"value\":[\"org.apache.activemq.artemis:address=\\\"PHASE3.SRC\\\","
                                + "broker=\\\"primary\\\",component=addresses,queue=\\\"PHASE3.SRC\\\","
                                + "routing-type=\\\"anycast\\\",subcomponent=queues\"]}",
                        MediaType.APPLICATION_JSON));
        server.expect()
                .andRespond(withSuccess(
                        "[" + fixture("browse-sensitive.json") + ",{\"value\":1,\"status\":200}]",
                        MediaType.APPLICATION_JSON));
        when(gateway.cluster(clusterId)).thenReturn(FixtureJolokia.cluster(Map.of(nodeId, server)));
    }

    private static String fixture(String name) {
        try {
            return new String(new ClassPathResource("jolokia/" + name).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anAssistantNeverReceivesTheCredentialOrTheCardNumber() throws Exception {
        serving();
        McpFixture.Key key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.GLOBAL,
                null,
                Set.of(Permissions.CLUSTER_READ, Permissions.SUBSCRIPTION_READ, "message:read"));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "browse_messages",
                Map.of(
                        "clusterId",
                        clusterId.toString(),
                        "subscription",
                        "artemis.queue/PHASE3.SRC",
                        "position",
                        "501"));

        String text = response.toString();
        assertThat(response.path("result").path("isError").asBoolean(false))
                .describedAs("%s", response)
                .isFalse();
        assertThat(text)
                .doesNotContain("dXNlcjpwYXNzd29yZA")
                .doesNotContain("4242 4242 4242 4242")
                .doesNotContain("jane.doe@example.com")
                .contains("[dropped credential]")
                .contains("[payment card number ending 4242]")
                .contains("[redacted email]")
                .contains("CREDENTIAL");
    }
}
