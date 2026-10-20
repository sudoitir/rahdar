package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigOperations;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigPermissions;
import io.github.sudoitir.rahdar.feature.brokerconfig.ObservedNodeConfig;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import java.time.Instant;
import java.util.HashMap;
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
 * The {@code artemis_config} / {@code artemis_config_change} contract from the
 * model's side of the wire (ADR-0067 D11): a dry-run apply names the hazard ids a
 * real run must acknowledge, a real run needs the cluster's name, and a halted
 * run says what stopped it, what was not attempted, and that re-running converges.
 */
class McpBrokerConfigIntegrationTest extends PostgresIntegrationTest {

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
    ArtemisNodes artemisNodes;

    @MockitoBean
    BrokerConfigOperations ops;

    private MockMvc mvc;
    private UUID clusterId;
    private String clusterName;
    private final Map<UUID, Map<String, Map<String, Object>>> broker = new HashMap<>();
    private final Map<ArtemisNode, UUID> clientToNode = new HashMap<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        clusterName = "config-" + UUID.randomUUID();
        clusterId = clusters.save(new ClusterEntity(clusterName, null, null)).getId();
        node("node-a");
        node("node-b");

        when(ops.read(any(), any(), anyString(), any()))
                .thenAnswer(inv -> new ObservedNodeConfig(
                        inv.getArgument(1),
                        inv.getArgument(2),
                        true,
                        Map.of(),
                        Map.of(),
                        broker.getOrDefault(inv.getArgument(1), Map.of()),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        null));
        doAnswer(inv -> {
                    UUID nodeId = clientToNode.get(inv.getArgument(0));
                    broker.computeIfAbsent(nodeId, k -> new HashMap<>()).put(inv.getArgument(2), inv.getArgument(3));
                    return null;
                })
                .when(ops)
                .addAddressSettings(any(), anyString(), anyString(), any());
    }

    private void node(String name) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                clusterId, name, "PRIMARY", UUID.randomUUID().toString());
        String url = "http://" + name + ":8161/console/jolokia";
        n.attachSeedUrl(url);
        n.applyHaState(new HaObservation(true, "STARTED", "PRIMARY", null, "2.44.0", null), 1L, Instant.now());
        UUID id = nodes.save(n).getId();
        ArtemisNode client = mock(ArtemisNode.class);
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
        when(artemisNodes.on(clusterId, id)).thenReturn(client);
        clientToNode.put(client, id);
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

    /** A catch-all match with a message-dropping policy: two High hazards on every node. */
    private static final String DOCUMENT = """
            {"version":1,"addresses":[],"addressSettings":[{"match":"#","values":{"addressFullMessagePolicy":"DROP","maxSizeBytes":1048576}}],"securitySettings":[],"diverts":[]}""";

    private JsonNode declare(McpFixture.Key key, boolean dryRun) throws Exception {
        Map<String, Object> args = new HashMap<>(
                Map.of("clusterId", clusterId.toString(), "op", "declare", "document", DOCUMENT, "dryRun", dryRun));
        if (!dryRun) {
            args.put("confirm", clusterName);
        }
        return McpFixture.callTool(mvc, key, "artemis_config_change", args);
    }

    @Test
    void declaringPreviewsByDefaultAndSavesWithTheClusterName() throws Exception {
        McpFixture.Key key = keyWith(Set.of(Permissions.CLUSTER_READ, BrokerConfigPermissions.CONFIG_WRITE));

        JsonNode preview = declare(key, true).path("result").path("structuredContent");
        assertThat(preview.path("valid").asBoolean()).isTrue();
        assertThat(preview.path("message").asString())
                .contains("Nothing was saved")
                .contains(clusterName);

        JsonNode saved = declare(key, false).path("result").path("structuredContent");
        assertThat(saved.path("revision").asInt()).isEqualTo(1);

        JsonNode read = McpFixture.callTool(
                        mvc, key, "artemis_config", Map.of("clusterId", clusterId.toString(), "kind", "declaration"))
                .path("result")
                .path("structuredContent");
        assertThat(read.path("declared").asBoolean()).isTrue();
        assertThat(read.path("revision").asInt()).isEqualTo(1);
        assertThat(read.path("document").path("addressSettings")).hasSize(1);
    }

    @Test
    void aDryRunApplyNamesWhatToAcknowledgeAndARealRunWithoutItIsRefused() throws Exception {
        McpFixture.Key key = keyWith(Set.of(
                Permissions.CLUSTER_READ, BrokerConfigPermissions.CONFIG_WRITE, BrokerConfigPermissions.CONFIG_APPLY));
        declare(key, false);

        JsonNode plan = McpFixture.callTool(
                        mvc, key, "artemis_config_change", Map.of("clusterId", clusterId.toString(), "op", "apply"))
                .path("result")
                .path("structuredContent");
        assertThat(plan.path("dryRun").asBoolean()).isTrue();
        assertThat(plan.path("hazards").valueStream().map(h -> h.path("id").asString()))
                .anyMatch(id -> id.startsWith("MESSAGE_LOSS_POLICY:"))
                .anyMatch(id -> id.startsWith("BROAD_MATCH:"));
        List<String> acknowledge =
                plan.path("acknowledge").valueStream().map(JsonNode::asString).toList();
        assertThat(acknowledge).isNotEmpty();
        assertThat(plan.path("message").asString())
                .contains("acknowledge=")
                .contains(acknowledge.get(0))
                .contains(clusterName);
        verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), any());

        JsonNode refused = McpFixture.callTool(
                mvc,
                key,
                "artemis_config_change",
                Map.of(
                        "clusterId",
                        clusterId.toString(),
                        "op",
                        "apply",
                        "dryRun",
                        false,
                        "confirm",
                        clusterName,
                        "expectedPlanHash",
                        plan.path("planHash").asString()));
        assertThat(refused.path("result").path("isError").asBoolean(false)
                        || refused.path("error").isObject())
                .describedAs("%s", refused)
                .isTrue();
        assertThat(refused.toString()).contains(acknowledge.get(0));
        verify(ops, never()).addAddressSettings(any(), anyString(), anyString(), any());

        JsonNode applied = McpFixture.callTool(
                        mvc,
                        key,
                        "artemis_config_change",
                        Map.of(
                                "clusterId",
                                clusterId.toString(),
                                "op",
                                "apply",
                                "dryRun",
                                false,
                                "confirm",
                                clusterName,
                                "expectedPlanHash",
                                plan.path("planHash").asString(),
                                "acknowledge",
                                String.join(",", acknowledge)))
                .path("result")
                .path("structuredContent");
        assertThat(applied.path("outcome").asString()).isEqualTo("APPLIED");
        assertThat(applied.path("nodes")).hasSize(2);
    }

    @Test
    void aHaltedRunIsLegibleToTheAgent() throws Exception {
        McpFixture.Key key = keyWith(Set.of(
                Permissions.CLUSTER_READ, BrokerConfigPermissions.CONFIG_WRITE, BrokerConfigPermissions.CONFIG_APPLY));
        declare(key, false);
        JsonNode plan = McpFixture.callTool(
                        mvc, key, "artemis_config_change", Map.of("clusterId", clusterId.toString(), "op", "apply"))
                .path("result")
                .path("structuredContent");
        // The canary refuses the write.
        doThrow(new ManagementRefusal(ManagementRefusal.Kind.ARGUMENT, "Error while parsing MetaData"))
                .when(ops)
                .addAddressSettings(any(), anyString(), anyString(), any());

        JsonNode halted = McpFixture.callTool(
                        mvc,
                        key,
                        "artemis_config_change",
                        Map.of(
                                "clusterId",
                                clusterId.toString(),
                                "op",
                                "apply",
                                "dryRun",
                                false,
                                "confirm",
                                clusterName,
                                "expectedPlanHash",
                                plan.path("planHash").asString(),
                                "acknowledge",
                                String.join(
                                        ",",
                                        plan.path("acknowledge")
                                                .valueStream()
                                                .map(JsonNode::asString)
                                                .toList())))
                .path("result")
                .path("structuredContent");

        assertThat(halted.path("outcome").asString()).isEqualTo("HALTED");
        assertThat(halted.path("message").asString())
                .contains("node-a")
                .contains("not attempted")
                .contains("Nothing was rolled back")
                .contains("converges");
        assertThat(halted.path("nodes")
                        .valueStream()
                        .filter(n -> n.path("node").asString().equals("node-b"))
                        .flatMap(n -> n.path("steps").valueStream())
                        .map(s -> s.path("status").asString()))
                .containsOnly("NOT_ATTEMPTED");
    }

    @Test
    void theToolInheritsTheCallersPermissions() throws Exception {
        McpFixture.Key key = keyWith(Set.of(Permissions.CLUSTER_READ, BrokerConfigPermissions.CONFIG_WRITE));
        declare(key, false);

        JsonNode response = McpFixture.callTool(
                mvc, key, "artemis_config_change", Map.of("clusterId", clusterId.toString(), "op", "apply"));
        assertThat(response.path("result").path("isError").asBoolean(false)
                        || response.path("error").isObject())
                .describedAs("%s", response)
                .isTrue();
    }
}
