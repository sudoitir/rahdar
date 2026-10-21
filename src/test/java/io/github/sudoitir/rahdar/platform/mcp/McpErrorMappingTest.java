package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.SettingsPermissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The line between "the call was wrong" and "the operation failed" (ADR-0045).
 *
 * <p>It matters because the two are acted on differently. A JSON-RPC
 * {@code -32602} tells the model it built the call badly and should build it
 * again; an {@code isError} result tells it the call was fine and the world said
 * no. Collapsing them — returning {@code isError} for a malformed argument, or a
 * protocol error for a refused purge — makes both unactionable, and a model
 * answers an unactionable failure by retrying the same thing.
 */
class McpErrorMappingTest extends PostgresIntegrationTest {

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

    private MockMvc mvc;
    private McpFixture.Key key;

    @BeforeEach
    void setUp() {
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

    @Test
    void anUnknownToolIsAProtocolError() throws Exception {
        JsonNode response = McpFixture.callTool(mvc, key, "purge_everything", Map.of());
        assertThat(response.has("error")).describedAs("%s", response).isTrue();
    }

    @Test
    void aMalformedArgumentIsAProtocolErrorNamingTheField() throws Exception {
        JsonNode response = McpFixture.callTool(mvc, key, "diagnose", Map.of("clusterId", "not-a-uuid"));
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asString()).contains("clusterId");
    }

    /**
     * The SDK validates {@code inputSchema} before the tool body runs, so an
     * omitted required property is refused there rather than by {@code McpArgs}.
     * The code differs from a bad <em>value</em>'s {@code -32602}, which is why
     * this asserts only the two things that matter to a model: it was refused,
     * and the message names the field to add.
     */
    @Test
    void anOmittedRequiredArgumentIsRefusedAndNamesTheField() throws Exception {
        JsonNode response = McpFixture.callTool(mvc, key, "diagnose", Map.of());
        assertThat(response.path("result").path("isError").asBoolean(false))
                .describedAs("%s", response)
                .isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asString())
                .contains("clusterId");
    }

    @Test
    void anUnknownDiscriminatorNamesTheValidValues() throws Exception {
        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "list_resources",
                Map.of("clusterId", UUID.randomUUID().toString(), "role", "sprockets"));
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asString())
                .describedAs("the rejection is where the valid values live — they are not in the schema")
                .contains("subscription");
    }

    @Test
    void anOperationalFailureIsAResultNotAProtocolError() throws Exception {
        // A cluster this key holds no grant on: the call itself was well formed.
        JsonNode response = McpFixture.callTool(
                mvc, key, "diagnose", Map.of("clusterId", UUID.randomUUID().toString()));
        assertThat(response.has("error")).describedAs("%s", response).isFalse();
        assertThat(response.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asString())
                .isEqualTo(McpErrors.CLUSTER_DENIED);
    }

    @Test
    void anUnknownResourceUriIsAProtocolErrorAndNeverEmptyContents() throws Exception {
        JsonNode response = McpFixture.rpc(mvc, key, "resources/read", Map.of("uri", "rahdar://nothing-here"));
        assertThat(response.has("error"))
                .describedAs("a missing resource must not come back as an empty read: %s", response)
                .isTrue();
    }

    @Test
    void anUnknownSettingKeyIsAProtocolError() throws Exception {
        // settings:read, or the denial would be an authorization result and this
        // would prove nothing about the key-not-found path.
        var settingsKey = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.GLOBAL,
                null,
                Set.of(SettingsPermissions.SETTINGS_READ));
        JsonNode response = McpFixture.callTool(mvc, settingsKey, "rahdar_setting", Map.of("key", "not.a.setting"));
        assertThat(response.path("error").path("code").asInt())
                .describedAs("%s", response)
                .isEqualTo(-32602);
    }
}
