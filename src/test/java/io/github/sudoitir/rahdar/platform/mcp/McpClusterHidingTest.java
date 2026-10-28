package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Two properties in one fixture, because each is only meaningful given the other.
 *
 * <p><b>The security context reaches the tool body.</b> {@code PermissionResolver}
 * reads {@code SecurityContextHolder}, a {@code ThreadLocal}. Under
 * {@code spring.ai.mcp.server.type=ASYNC} the tool would run on a Reactor
 * scheduler thread, the context would be empty, every permission check would
 * return false, and <em>every</em> tool would answer "no cluster visible" — a
 * configuration mistake that presents as a permissions bug. The granted half of
 * this test is the regression guard for that (design D3): it can only pass if
 * the token's grants were visible where the check runs.
 *
 * <p><b>A denial does not confirm the cluster exists.</b>
 * {@code ClusterAccessGuard} answers a missing grant with not-found rather than
 * forbidden, on purpose, so a caller cannot enumerate cluster ids. The MCP error
 * contract has to preserve that: the message must name no permission and must not
 * distinguish "no such cluster" from "no grant". A well-meaning change to make the
 * error "more helpful" would silently turn it into an enumeration oracle, which is
 * why this asserts on the message text and not merely on {@code isError}.
 */
class McpClusterHidingTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

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

    private UUID visible;
    private UUID hidden;

    @AfterEach
    void cleanUp() {
        if (visible != null) {
            clusters.deleteById(visible);
        }
        if (hidden != null) {
            clusters.deleteById(hidden);
        }
    }

    @Test
    void aScopedKeySeesItsClusterAndCannotTellTheOtherApartFromAbsence() throws Exception {
        // Both clusters must really exist, or the denial below would be genuine
        // absence and would prove nothing about the guard.
        visible = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "visible-" + UUID.randomUUID(), null, null))
                .getId();
        hidden = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "hidden-" + UUID.randomUUID(), null, null))
                .getId();

        var key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.CLUSTER,
                visible,
                Set.of(Permissions.CLUSTER_READ));

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();

        JsonNode granted = McpFixture.callTool(mvc, key, "diagnose", Map.of("clusterId", visible.toString()));
        assertThat(granted.path("result").path("isError").asBoolean(false))
                .describedAs(
                        "the granted cluster came back as an error, so the token's grants were not "
                                + "visible inside the tool body: %s",
                        granted)
                .isFalse();
        assertThat(granted.path("result")
                        .path("structuredContent")
                        .path("clusterId")
                        .asString())
                .isEqualTo(visible.toString());

        JsonNode denied = McpFixture.callTool(mvc, key, "diagnose", Map.of("clusterId", hidden.toString()));
        assertThat(denied.path("result").path("isError").asBoolean(false)).isTrue();

        String message =
                denied.path("result").path("content").get(0).path("text").asString();
        assertThat(message).isEqualTo(McpErrors.CLUSTER_DENIED);
        assertThat(message)
                .describedAs("a denial must not name the permission the caller lacks")
                .doesNotContain("cluster:read")
                .doesNotContain("permission");
        assertThat(message)
                .describedAs("a denial must not echo the id back, which would confirm it exists")
                .doesNotContain(hidden.toString());
    }
}
