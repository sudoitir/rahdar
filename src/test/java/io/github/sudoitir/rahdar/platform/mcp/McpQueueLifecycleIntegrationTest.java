package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.Operations;
import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
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
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Targets;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Instant;
import java.util.List;
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
 * The {@code queue_lifecycle} tool's contract from the model's side of the wire
 * (ADR-0049): preview by default, a matching {@code confirm} before anything is
 * destroyed, the caller's own permissions, and the full per-node outcome.
 */
class McpQueueLifecycleIntegrationTest extends PostgresIntegrationTest {

    private static final String QUEUE = "MCP.LIFECYCLE";

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

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
    ArtemisNodes artemisNodes;

    @MockitoBean
    BrokerGateway gateway;

    private MockMvc mvc;
    private UUID clusterId;
    private BrokerTarget target;
    private Operations ops;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        clusterId = clusters.save(new ClusterEntity("lifecycle-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        node.attachSeedUrl("http://a:8161/console/jolokia");
        node.applyHaState(new HaObservation(true, "STARTED", "PRIMARY", null, "2.44.0", null), 1L, Instant.now());
        UUID nodeId = nodes.save(node).getId();
        upsert.upsertBatch(List.of(new QueueRow(
                clusterId, nodeId, QUEUE, QUEUE, "ANYCAST", true, 9, 0, 0, 0, 0, 0, 0, false, "artemis.queue")));

        target = Targets.target();
        ops = Targets.operations(target);
        when(gateway.node(any(UUID.class), any(UUID.class))).thenReturn(target);
        queueHolds(0);
    }

    /** The queue as the node lists it: nine messages and these consumers. */
    private void queueHolds(long consumers) {
        Targets.holding(target, Targets.queue(QUEUE, QUEUE, consumers, 9));
    }

    @AfterEach
    void cleanUp() {
        auditEvents.deleteAll();
        clusters.deleteById(clusterId);
    }

    private McpFixture.Key keyWith(Set<String> permissions) {
        return McpFixture.mintKey(
                users, roles, rolePermissions, userRoles, tokens, Grant.ScopeType.CLUSTER, clusterId, permissions);
    }

    @Test
    void theDefaultInvocationPreviewsAndDestroysNothing() throws Exception {
        McpFixture.Key key = keyWith(Set.of(
                Permissions.CLUSTER_READ, Permissions.SUBSCRIPTION_READ, ResourcePermissions.SUBSCRIPTION_DELETE));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "queue_lifecycle",
                Map.of("clusterId", clusterId.toString(), "kind", "delete_subscription", "name", QUEUE));

        JsonNode outcome = response.path("result").path("structuredContent");
        assertThat(response.path("result").path("isError").asBoolean(false))
                .describedAs("%s", response)
                .isFalse();
        assertThat(outcome.path("dryRun").asBoolean()).isTrue();
        assertThat(outcome.path("totalAffected").asLong()).isEqualTo(9L);
        verify(ops, never()).execute(any(), any(), any());
    }

    @Test
    void aRealDestroyWithoutAMatchingConfirmationIsRefused() throws Exception {
        McpFixture.Key key = keyWith(Set.of(
                Permissions.CLUSTER_READ, Permissions.SUBSCRIPTION_READ, ResourcePermissions.SUBSCRIPTION_DELETE));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "queue_lifecycle",
                Map.of(
                        "clusterId",
                        clusterId.toString(),
                        "kind",
                        "delete_subscription",
                        "name",
                        QUEUE,
                        "dryRun",
                        false,
                        "confirm",
                        "some-other-queue"));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asString()).contains(QUEUE);
        verify(ops, never()).execute(any(), any(), any());
    }

    @Test
    void theToolInheritsTheCallersPermissions() throws Exception {
        // Every read permission, but no destroy.
        McpFixture.Key key = keyWith(
                Set.of(Permissions.CLUSTER_READ, Permissions.SUBSCRIPTION_READ, MessagePermissions.MESSAGE_READ));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "queue_lifecycle",
                Map.of("clusterId", clusterId.toString(), "kind", "delete_subscription", "name", QUEUE));

        // Refused on the same basis as the HTTP request: a not-found that reveals
        // nothing about whether the cluster exists.
        assertThat(response.path("result").path("isError").asBoolean(false)
                        || response.path("error").isObject())
                .describedAs("%s", response)
                .isTrue();
        verify(ops, never()).execute(any(), any(), any());
    }

    @Test
    void aPartialApplicationIsReturnedPerNode() throws Exception {
        // A second node that is not live, so the fan-out is uneven by construction.
        BrokerNodeEntity dead = BrokerNodeEntity.fromSeed(
                clusterId, "node-b", "PRIMARY", UUID.randomUUID().toString());
        dead.attachSeedUrl("http://b:8161/console/jolokia");
        nodes.save(dead);

        McpFixture.Key key = keyWith(Set.of(
                Permissions.CLUSTER_READ, Permissions.SUBSCRIPTION_READ, ResourcePermissions.SUBSCRIPTION_PAUSE));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "queue_lifecycle",
                Map.of(
                        "clusterId",
                        clusterId.toString(),
                        "kind",
                        "pause_subscription",
                        "name",
                        QUEUE,
                        "dryRun",
                        false));

        JsonNode nodesOut = response.path("result").path("structuredContent").path("nodes");
        assertThat(nodesOut).describedAs("%s", response).hasSize(2);
        assertThat(nodesOut.valueStream().map(n -> n.path("status").asString()).toList())
                .contains("SKIPPED_NOT_LIVE");
    }

    @Test
    void aQueueWithConsumersIsDeletedOnlyWithDisconnectConsumers() throws Exception {
        queueHolds(1);
        McpFixture.Key key = keyWith(Set.of(
                Permissions.CLUSTER_READ, Permissions.SUBSCRIPTION_READ, ResourcePermissions.SUBSCRIPTION_DELETE));

        JsonNode refused = McpFixture.callTool(
                mvc,
                key,
                "queue_lifecycle",
                Map.of("clusterId", clusterId.toString(), "kind", "delete_subscription", "name", QUEUE));
        assertThat(refused.path("result")
                        .path("structuredContent")
                        .path("nodes")
                        .get(0)
                        .path("error")
                        .asString())
                .describedAs("%s", refused)
                .contains("disconnectConsumers");

        McpFixture.callTool(
                mvc,
                key,
                "queue_lifecycle",
                Map.of(
                        "clusterId",
                        clusterId.toString(),
                        "kind",
                        "delete_subscription",
                        "name",
                        QUEUE,
                        "dryRun",
                        false,
                        "confirm",
                        QUEUE,
                        "disconnectConsumers",
                        true));
        verify(ops)
                .execute(
                        eq(Targets.id("artemis.queue", QUEUE)),
                        eq(new Operation.Delete(false, true)),
                        any(Scope.class));
    }
}
