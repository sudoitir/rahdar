package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.feature.resources.ConnectionOperations;
import io.github.sudoitir.rahdar.feature.resources.ConnectionOperations.ConnectionSnapshot;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Targets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The {@code connection_action} contract from the model's side of the wire
 * (ADR-0057).
 *
 * <p>Two things are specific to this tool and neither is covered elsewhere. The
 * confirmation is against a name the model cannot know before it has previewed —
 * the client id the broker reports — rather than the identifier it was given, so
 * a model that guesses cannot close anything. And a target that has already gone
 * is reported as gone rather than as an error, because an agent that reads a
 * failure will retry, and a retry may land on a connection that has since been
 * issued the same identifier.
 */
class McpConnectionControlIntegrationTest extends PostgresIntegrationTest {

    private static final String CONNECTION = "a3f1c9de";
    private static final String CLIENT_ID = "orders-worker-7";

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    AuditEventRepository auditEvents;

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

    @MockitoBean
    ConnectionOperations ops;

    private MockMvc mvc;
    private UUID clusterId;
    private UUID nodeId;
    private McpFixture.Key key;
    private BrokerTarget client;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        clusterId = clusters.save(new ClusterEntity("conn-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        node.attachSeedUrl("http://a:8161/console/jolokia");
        node.applyHaState(new HaObservation(true, "STARTED", "PRIMARY", null, "2.56.0", null), 1L, Instant.now());
        nodeId = nodes.save(node).getId();

        client = Targets.target();
        when(gateway.node(eq(clusterId), any(UUID.class))).thenReturn(client);

        key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.CLUSTER,
                clusterId,
                Set.of(Permissions.CLUSTER_READ, ResourcePermissions.CONNECTION_CLOSE));
    }

    @AfterEach
    void cleanUp() {
        auditEvents.deleteAll();
        clusters.deleteById(clusterId);
    }

    private Map<String, Object> args(Object... extra) {
        Map<String, Object> base = new LinkedHashMap<>(Map.of(
                "clusterId",
                clusterId.toString(),
                "kind",
                "connection",
                "target",
                CONNECTION,
                "nodeId",
                nodeId.toString()));
        for (int i = 0; i < extra.length; i += 2) {
            base.put((String) extra[i], extra[i + 1]);
        }
        return base;
    }

    private static ConnectionSnapshot snapshot() {
        return new ConnectionSnapshot(CONNECTION, CLIENT_ID, "10.4.2.9:53160", "apps", "CORE", 2, 3, 41L);
    }

    @Test
    void theDefaultInvocationPreviewsAndClosesNothing() throws Exception {
        when(ops.readConnection(client, CONNECTION)).thenReturn(snapshot());

        JsonNode response = McpFixture.callTool(mvc, key, "connection_action", args());
        JsonNode outcome = response.path("result").path("structuredContent");

        assertThat(response.path("result").path("isError").asBoolean(false))
                .describedAs("%s", response)
                .isFalse();
        assertThat(outcome.path("dryRun").asBoolean()).isTrue();
        assertThat(outcome.path("label").asString()).isEqualTo(CLIENT_ID);
        assertThat(outcome.path("messagesInTransit").asInt()).isEqualTo(41);
        verify(ops, never()).close(any(), any());
    }

    /** The identifier is not the confirmation: only the client id the preview reported arms a real run. */
    @Test
    void aRealRunConfirmedWithTheConnectionIdIsRefused() throws Exception {
        when(ops.readConnection(client, CONNECTION)).thenReturn(snapshot());

        JsonNode response =
                McpFixture.callTool(mvc, key, "connection_action", args("dryRun", false, "confirm", CONNECTION));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asString()).contains(CLIENT_ID);
        verify(ops, never()).close(any(), any());
    }

    @Test
    void aRealRunConfirmedWithTheClientIdCloses() throws Exception {
        when(ops.readConnection(client, CONNECTION)).thenReturn(snapshot());
        when(ops.close(client, Targets.id("artemis.connection", CONNECTION))).thenReturn(true);

        JsonNode response =
                McpFixture.callTool(mvc, key, "connection_action", args("dryRun", false, "confirm", CLIENT_ID));
        JsonNode outcome = response.path("result").path("structuredContent");

        assertThat(outcome.path("dryRun").asBoolean()).isFalse();
        assertThat(outcome.path("alreadyGone").asBoolean()).isFalse();
        verify(ops).close(client, Targets.id("artemis.connection", CONNECTION));
    }

    /** An agent must be able to tell "gone" from "closed", and from a failure. */
    @Test
    void aTargetThatHasAlreadyGoneIsReportedAsGoneRatherThanAsAnError() throws Exception {
        when(ops.readConnection(client, CONNECTION)).thenReturn(null);

        JsonNode response = McpFixture.callTool(mvc, key, "connection_action", args("dryRun", false));
        JsonNode outcome = response.path("result").path("structuredContent");

        assertThat(response.path("result").path("isError").asBoolean(false)).isFalse();
        assertThat(outcome.path("alreadyGone").asBoolean()).isTrue();
        assertThat(outcome.path("message").asString()).contains("do not retry");
        verify(ops, never()).close(any(), any());
    }

    /** Every message permission in the world does not add up to this one. */
    @Test
    void aKeyWithEveryMessagePermissionAndNotThisOneCannotClose() throws Exception {
        McpFixture.Key messageOnly = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.CLUSTER,
                clusterId,
                Set.of(
                        Permissions.CLUSTER_READ,
                        MessagePermissions.MESSAGE_READ,
                        MessagePermissions.MESSAGE_SEND,
                        MessagePermissions.MESSAGE_MOVE,
                        MessagePermissions.MESSAGE_DELETE,
                        MessagePermissions.SUBSCRIPTION_PURGE));

        JsonNode response = McpFixture.callTool(mvc, messageOnly, "connection_action", args());

        assertThat(response.path("result").path("isError").asBoolean()).isTrue();
        // Denial by cluster id never names the permission — that would confirm the id exists.
        assertThat(response.path("result").path("content").get(0).path("text").asString())
                .isEqualTo(McpErrors.CLUSTER_DENIED);
        verify(ops, never()).readConnection(any(), anyString());
    }
}
