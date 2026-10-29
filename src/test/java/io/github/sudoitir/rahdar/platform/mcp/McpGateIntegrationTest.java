package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The gate every MCP request passes through (mcp-server spec, ADR-0137): a token's tool
 * allow-list, the installation's read-only mode, and an audit row for every call, reads included.
 */
class McpGateIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

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

    @Autowired
    SettingsService settings;

    @Autowired
    ClusterRepository clusters;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        asAdmin(() -> settings.reset(McpSettings.READ_ONLY));
        auditEvents.deleteAll();
    }

    @Test
    void aRestrictedKeySeesOnlyItsToolsEverywhere() throws Exception {
        McpFixture.Key key = key(List.of("list_resources"));

        assertThat(listedTools(key)).containsExactlyInAnyOrder("list_resources", McpToolCatalog.HELP_TOOL);

        JsonNode help = McpFixture.callTool(mvc, key, McpToolCatalog.HELP_TOOL, Map.of());
        String index = help.path("result").path("content").get(0).path("text").asString();
        assertThat(index).contains("list_resources").doesNotContain("resource_lifecycle", "diagnose");

        JsonNode init = McpFixture.rpc(
                mvc,
                key,
                "initialize",
                Map.of(
                        "protocolVersion",
                        "2025-06-18",
                        "capabilities",
                        Map.of(),
                        "clientInfo",
                        Map.of("name", "test", "version", "1")));
        assertThat(init.path("result").path("instructions").asString())
                .contains("list_resources")
                .doesNotContain("resource_lifecycle", "diagnose");
    }

    @Test
    void aToolOutsideTheListAnswersAsUnknownAndIsAudited() throws Exception {
        McpFixture.Key key = key(List.of("list_resources"));

        JsonNode response = McpFixture.callTool(mvc, key, "diagnose", Map.of("clusterId", "x"));
        JsonNode unknown = McpFixture.callTool(mvc, key, "no_such_tool", Map.of());

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asString())
                .isEqualTo(unknown.path("error").path("message").asString());
        AuditEventEntity row = toolCalls("diagnose").getFirst();
        assertThat(row.getOutcome()).isEqualTo("FAILURE");
        assertThat(row.getUsername()).contains(key.username()).contains("[token: mcp-test-key]");
    }

    @Test
    void aReadCallIsAuditedWithTokenAndTool() throws Exception {
        McpFixture.Key key = key(List.of());

        McpFixture.callTool(mvc, key, McpToolCatalog.HELP_TOOL, Map.of());

        List<AuditEventEntity> rows = toolCalls(McpToolCatalog.HELP_TOOL);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getOutcome()).isEqualTo("SUCCESS");
        assertThat(rows.getFirst().getUsername()).contains("[token: mcp-test-key]");
    }

    @Test
    void readOnlyHidesAndRefusesMutatingToolsEvenWithConfirmation() throws Exception {
        McpFixture.Key key = key(List.of());
        asAdmin(() -> settings.put(McpSettings.READ_ONLY, "true"));

        assertThat(listedTools(key)).contains("list_resources").doesNotContain("resource_lifecycle", "message_action");

        JsonNode refused = McpFixture.callTool(
                mvc,
                key,
                "resource_lifecycle",
                Map.of(
                        "clusterId",
                        "x",
                        "resource",
                        "artemis.queue/Q",
                        "kind",
                        "delete_subscription",
                        "dryRun",
                        false,
                        "confirm",
                        "Q"));

        assertThat(refused.path("result").path("isError").asBoolean()).isTrue();
        assertThat(refused.path("result").path("content").get(0).path("text").asString())
                .contains("read-only");
        assertThat(toolCalls("resource_lifecycle").getFirst().getOutcome()).isEqualTo("FAILURE");
    }

    @Test
    void aSystemToolOnAClusterOfAnotherSystemIsAToolErrorAndIsAudited() throws Exception {
        ClusterEntity entity = new ClusterEntity(ArtemisModule.SYSTEM, "gate-other-system", null, null);
        ReflectionTestUtils.setField(entity, "system", "other");
        UUID cluster = clusters.save(entity).getId();
        try {
            McpFixture.Key key = key(List.of());

            JsonNode response =
                    McpFixture.callTool(mvc, key, "artemis_config", Map.of("clusterId", cluster.toString()));

            assertThat(response.path("result").path("isError").asBoolean())
                    .describedAs("%s", response)
                    .isTrue();
            assertThat(response.path("result")
                            .path("content")
                            .get(0)
                            .path("text")
                            .asString())
                    .isEqualTo("artemis_config applies to Artemis clusters; gate-other-system runs other.");
            assertThat(toolCalls("artemis_config").getFirst().getOutcome()).isEqualTo("FAILURE");
        } finally {
            auditEvents.deleteAll();
            clusters.deleteById(cluster);
        }
    }

    private McpFixture.Key key(List<String> tools) {
        return McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.GLOBAL,
                null,
                Set.of(Permissions.CLUSTER_READ),
                tools);
    }

    private List<String> listedTools(McpFixture.Key key) throws Exception {
        List<String> names = new ArrayList<>();
        McpFixture.rpc(mvc, key, "tools/list", Map.of())
                .path("result")
                .path("tools")
                .forEach(t -> names.add(t.path("name").asString()));
        return names;
    }

    private List<AuditEventEntity> toolCalls(String tool) {
        return auditEvents.findAll().stream()
                .filter(e -> McpGate.AUDIT_ACTION.equals(e.getAction()) && tool.equals(e.getTargetName()))
                .toList();
    }

    private static void asAdmin(Runnable action) {
        RahdarPrincipal admin = new RahdarPrincipal(
                null,
                "test-admin",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(Permissions.WILDCARD))),
                false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
