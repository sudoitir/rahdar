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
import io.github.sudoitir.rahdar.kernel.gate.OperationHeldException;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.kafka.KafkaOffsetResets;
import io.github.sudoitir.rahdar.system.kafka.KafkaOffsets;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The Kafka tools over the wire (ADR-0216): an offset reset is previewed first and, from that preview's etag, held by
 * the approval gate like its screen's; a reset with no confirmation or while the installation is read-only is refused
 * before any service runs.
 */
class McpKafkaToolsTest extends PostgresIntegrationTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    /** No cluster is registered behind this test, so the tools are offered as if a Kafka cluster were readable. */
    @MockitoBean
    McpSystems systems;

    @MockitoBean
    KafkaOffsets offsets;

    @MockitoBean
    KafkaOffsetResets resets;

    @Autowired
    WebApplicationContext webContext;

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

    private MockMvc mvc;
    private McpFixture.Key key;

    @BeforeEach
    void setUp() {
        when(systems.readsAny(anyString())).thenReturn(true);
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.GLOBAL,
                null,
                Set.of(Permissions.CLUSTER_READ));
    }

    @AfterEach
    void cleanUp() {
        asAdmin(() -> settings.reset(McpSettings.READ_ONLY));
    }

    private JsonNode call(String tool, Map<String, Object> arguments) throws Exception {
        return McpFixture.callTool(mvc, key, tool, arguments);
    }

    private static String text(JsonNode response) {
        return response.path("result").path("content").get(0).path("text").asString();
    }

    private static Map<String, Object> reset(Map<String, Object> extra) {
        Map<String, Object> arguments = new HashMap<>(
                Map.of("clusterId", CLUSTER.toString(), "group", "billing", "to", "earliest", "etag", "etag-1"));
        arguments.putAll(extra);
        return arguments;
    }

    @Test
    void aResetIsPreviewedFirstAndThenHeldByTheApprovalGate() throws Exception {
        OffsetPreview preview = new OffsetPreview(
                List.of(new OffsetRow("orders", 0, 90L, 0L, 0, 90, null, null)), List.of(), null, "etag-1");
        when(offsets.preview(eq(CLUSTER), eq("billing"), any(OffsetRequest.class)))
                .thenReturn(preview);
        UUID heldId = UUID.randomUUID();
        when(resets.reset(eq(CLUSTER), eq("billing"), any(OffsetRequest.class)))
                .thenThrow(new OperationHeldException(heldId, "Reset billing to earliest on events", Instant.now()));

        JsonNode previewed = call(
                "kafka_preview_offset_reset",
                Map.of("clusterId", CLUSTER.toString(), "group", "billing", "to", "earliest"));
        assertThat(previewed.path("result").path("isError").asBoolean()).isFalse();
        assertThat(text(previewed)).contains("etag-1").contains("orders");

        JsonNode held = call("kafka_reset_offsets", reset(Map.of("confirm", "billing")));

        assertThat(text(held))
                .isEqualTo("Held for approval: Reset billing to earliest on events (request " + heldId
                        + "). A second person must approve it in Rahdar: /approvals/" + heldId);
        assertThat(held.path("result").path("isError").asBoolean()).isTrue();
    }

    @Test
    void aResetWithoutItsConfirmationOrItsPreviewIsRefusedBeforeTheService() throws Exception {
        JsonNode unconfirmed = call("kafka_reset_offsets", reset(Map.of("confirm", "billin")));
        JsonNode noEtag = call(
                "kafka_reset_offsets",
                Map.of("clusterId", CLUSTER.toString(), "group", "billing", "to", "earliest", "confirm", "billing"));

        assertThat(unconfirmed.toString()).contains("confirm");
        assertThat(noEtag.toString()).contains("etag");
        verify(resets, never()).reset(any(), any(), any());
    }

    @Test
    void readOnlyHidesAndRefusesTheMutatingKafkaToolsButNotTheReads() throws Exception {
        asAdmin(() -> settings.put(McpSettings.READ_ONLY, "true"));

        assertThat(listedTools())
                .contains("kafka_list_groups", "kafka_preview_offset_reset", "kafka_topic_config")
                .doesNotContain("kafka_reset_offsets", "kafka_set_topic_config", "kafka_abort_transaction");

        JsonNode refused = call("kafka_reset_offsets", reset(Map.of("confirm", "billing")));

        assertThat(refused.path("result").path("isError").asBoolean()).isTrue();
        assertThat(text(refused)).contains("read-only");
        verify(resets, never()).reset(any(), any(), any());
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

    private List<String> listedTools() throws Exception {
        List<String> names = new ArrayList<>();
        McpFixture.rpc(mvc, key, "tools/list", Map.of())
                .path("result")
                .path("tools")
                .forEach(t -> names.add(t.path("name").asString()));
        return names;
    }
}
