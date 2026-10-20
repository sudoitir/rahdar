package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The grant intersection has to hold at the far end of the MCP path, not just at
 * the REST controllers.
 *
 * <p>A key that can see a cluster and read its messages is exactly the key that
 * makes this interesting: the cluster resolves, the queue resolves, the tool body
 * runs, and the only thing standing between the call and a real purge is
 * {@code ClusterAccessGuard}. If the token's permission set were widened anywhere
 * along the transport — a principal rebuilt from the owner rather than the key, a
 * grant set read before intersection — this is where it would show, as a purge
 * that succeeds instead of a clean refusal.
 */
class McpAuthorizationIntegrationTest extends PostgresIntegrationTest {

    private static final String QUEUE = "MCP.AUTHZ";

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

    private MockMvc mvc;
    private UUID clusterId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        clusterId = clusters.save(new ClusterEntity("authz-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        node.attachSeedUrl("http://a:8161/console/jolokia");
        UUID nodeId = nodes.save(node).getId();
        upsert.upsertBatch(List.of(new QueueRow(
                clusterId, nodeId, QUEUE, QUEUE, "ANYCAST", true, 7, 0, 0, 0, 0, 0, 0, false, "artemis.queue")));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    @Test
    void aKeyWithoutQueuePurgeIsRefusedRatherThanFailing() throws Exception {
        var key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.CLUSTER,
                clusterId,
                Set.of(Permissions.CLUSTER_READ, Permissions.SUBSCRIPTION_READ, MessagePermissions.MESSAGE_READ));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "message_action",
                Map.of(
                        "clusterId",
                        clusterId.toString(),
                        "subscription",
                        "artemis.queue/" + QUEUE,
                        "action",
                        "purge",
                        "dryRun",
                        true));

        // A refusal is a result the model can act on, not a transport fault: an
        // error envelope here would mean the exception escaped the tool body.
        assertThat(response.has("error"))
                .describedAs("a missing permission must not surface as a JSON-RPC error: %s", response)
                .isFalse();
        assertThat(response.path("result").path("isError").asBoolean(false))
                .describedAs("purge without subscription:purge came back as a success: %s", response)
                .isTrue();
    }

    @Test
    void theSameKeyCanStillReadWhatItWasGranted() throws Exception {
        // The negative above is only evidence if the same key is not simply blind
        // to the cluster: this proves the grant it does hold works.
        var key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.CLUSTER,
                clusterId,
                Set.of(Permissions.CLUSTER_READ, Permissions.SUBSCRIPTION_READ, MessagePermissions.MESSAGE_READ));

        JsonNode response = McpFixture.callTool(
                mvc, key, "list_resources", Map.of("clusterId", clusterId.toString(), "role", "subscription"));

        assertThat(response.path("result").path("isError").asBoolean(false))
                .describedAs("%s", response)
                .isFalse();
        assertThat(response.path("result")
                        .path("structuredContent")
                        .path("items")
                        .toString())
                .contains(QUEUE);
    }
}
