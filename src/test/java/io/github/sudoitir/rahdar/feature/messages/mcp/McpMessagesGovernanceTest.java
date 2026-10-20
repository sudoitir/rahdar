package io.github.sudoitir.rahdar.feature.messages.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
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
import tools.jackson.databind.json.JsonMapper;

/** {@code browse_messages} through MCP is governed exactly like the REST read (mcp-server spec, data-governance). */
class McpMessagesGovernanceTest extends PostgresIntegrationTest {

    private static final String URL_A = "http://a:8161/console/jolokia";

    private final JsonMapper mapper = new JsonMapper();

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
    ArtemisNodes artemisNodes;

    private MockMvc mvc;
    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity a = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        a.attachSeedUrl(URL_A);
        UUID nodeId = nodes.save(a).getId();
        upsert.upsertBatch(List.of(new QueueRow(
                clusterId, nodeId, "PHASE3.SRC", "PHASE3.SRC", "ANYCAST", true, 1, 0, 0, 0, 0, 0, 0, false)));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    private ArtemisNode client() {
        FixtureJolokia server = new FixtureJolokia();
        server.expect(requestTo(URL_A))
                .andRespond(withSuccess(fixture("search-broker.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL_A))
                .andRespond(withSuccess(
                        "[" + fixture("browse-sensitive.json") + ",{\"value\":1,\"status\":200}]",
                        MediaType.APPLICATION_JSON));
        return server.node();
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
        when(artemisNodes.on(eq(clusterId), any(UUID.class))).thenReturn(client());
        McpFixture.Key key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.GLOBAL,
                null,
                Set.of(Permissions.CLUSTER_READ, Permissions.QUEUE_READ, "message:read"));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "browse_messages",
                Map.of("clusterId", clusterId.toString(), "queue", "PHASE3.SRC", "messageId", "501"));

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
