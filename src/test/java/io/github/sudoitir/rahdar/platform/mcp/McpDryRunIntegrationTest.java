package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.feature.messages.MessagePermissions;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
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
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
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
 * The safe-by-default contract, from the model's side of the wire.
 *
 * <p>Non-negotiable #2 says every destructive operation dry-runs on request; for
 * a model caller that is inverted — it dry-runs unless told otherwise, because
 * the caller is a text generator that can produce {@code dryRun: false} as
 * readily as any other token. The second gate, {@code confirm}, is what a model
 * cannot supply by accident: it has to have read the queue's real name.
 *
 * <p>By-id actions are used here deliberately. Their dry run is answered from the
 * id count without a broker call at all, so this proves the MCP layer's contract
 * and not a broker stub's behaviour.
 */
class McpDryRunIntegrationTest extends PostgresIntegrationTest {

    private static final String QUEUE = "MCP.DRYRUN";

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

    private MockMvc mvc;
    private UUID clusterId;
    private McpFixture.Key key;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "dryrun-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        node.attachSeedUrl("http://a:8161/console/jolokia");
        UUID nodeId = nodes.save(node).getId();
        upsert.upsertBatch(List.of(new QueueRow(
                clusterId,
                nodeId,
                QUEUE,
                QUEUE,
                "ANYCAST",
                true,
                9,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                "artemis.queue",
                Map.of())));
        key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.CLUSTER,
                clusterId,
                Set.of(
                        Permissions.CLUSTER_READ,
                        Permissions.SUBSCRIPTION_READ,
                        MessagePermissions.MESSAGE_READ,
                        MessagePermissions.MESSAGE_DELETE));
    }

    @AfterEach
    void cleanUp() {
        auditEvents.deleteAll();
        clusters.deleteById(clusterId);
    }

    @Test
    void theDefaultInvocationEstimatesAndChangesNothing() throws Exception {
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
                        "delete",
                        "positions",
                        "11,12,13"));

        JsonNode outcome = response.path("result").path("structuredContent");
        assertThat(response.path("result").path("isError").asBoolean(false))
                .describedAs("%s", response)
                .isFalse();
        assertThat(outcome.path("dryRun").asBoolean()).isTrue();
        assertThat(outcome.path("affected").asInt()).isEqualTo(3);

        // Non-negotiable #3: a dry run is still a command, so it is still audited.
        List<AuditEventEntity> audited = messageAudits();
        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst().isDryRun()).isTrue();
        assertThat(audited.getFirst().getUsername()).contains(key.username());
    }

    @Test
    void aRealRunWithoutTheQueueNameIsRefused() throws Exception {
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
                        "delete",
                        "positions",
                        "11",
                        "dryRun",
                        false,
                        "confirm",
                        "some-other-queue"));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asString()).contains(QUEUE);
        // The refusal happens before the service is reached, so nothing is audited
        // and nothing was touched. (Minting the key itself audits, hence the filter.)
        assertThat(messageAudits()).isEmpty();
    }

    @Test
    void aRealRunWithNoConfirmAtAllIsRefused() throws Exception {
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
                        "delete",
                        "positions",
                        "11",
                        "dryRun",
                        false));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(messageAudits()).isEmpty();
    }

    private List<AuditEventEntity> messageAudits() {
        return auditEvents.findAll().stream()
                .filter(e -> "DELETE_MESSAGES".equals(e.getAction()))
                .toList();
    }
}
