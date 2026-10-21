package io.github.sudoitir.rahdar.kernel.plugin.internal.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.rahdar.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.rahdar.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * ADR-0114: a plugin's MCP tools declare the permission they need, and Rahdar checks it before
 * the plugin's code runs. A cluster tool's denial hides the cluster exactly as a built-in tool's
 * does; a global tool's names the permission. The fixture's tools count their invocations in a
 * system property, so "the plugin never ran" is asserted, not assumed.
 */
class PluginMcpAccessIntegrationTest extends PostgresIntegrationTest {

    private static final String CALLS = "acme-access.calls";

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    PluginRuntimeFactory runtimeFactory;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginDescriptorParser descriptorParser;

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

    private PluginRuntime activeRuntime;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        System.setProperty(CALLS, "0");
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void cleanUp() {
        if (activeRuntime != null) {
            activeRuntime.close();
            registry.remove(activeRuntime.id());
        }
        SecurityContextHolder.clearContext();
        System.clearProperty(CALLS);
    }

    @Test
    void aClusterToolWithoutAGrantHidesTheClusterAndNeverRuns() throws Exception {
        String id = activate(accessJar(newId()));
        var key = key(Grant.ScopeType.CLUSTER, UUID.randomUUID(), Set.of(id + ":read"));

        JsonNode called = McpFixture.callTool(
                mvc,
                key,
                snake(id) + "_cluster",
                Map.of("clusterId", UUID.randomUUID().toString()));

        assertThat(called.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(text(called)).isEqualTo(McpErrors.CLUSTER_DENIED).doesNotContain(id + ":read");
        assertThat(System.getProperty(CALLS)).isEqualTo("0");
    }

    @Test
    void aClusterToolWithTheGrantReachesThePlugin() throws Exception {
        String id = activate(accessJar(newId()));
        UUID cluster = UUID.randomUUID();
        var key = key(Grant.ScopeType.CLUSTER, cluster, Set.of(id + ":read"));

        JsonNode called =
                McpFixture.callTool(mvc, key, snake(id) + "_cluster", Map.of("clusterId", cluster.toString()));

        assertThat(text(called)).isEqualTo("reached " + cluster);
        assertThat(System.getProperty(CALLS)).isEqualTo("1");
    }

    @Test
    void aResourceToolForAClusterTheKeyCannotSeeHidesTheClusterAndNeverRuns() throws Exception {
        String id = activate(accessJar(newId()));
        var key = key(Grant.ScopeType.CLUSTER, UUID.randomUUID(), Set.of(id + ":peek", "subscription:read"));

        JsonNode called = McpFixture.callTool(
                mvc,
                key,
                snake(id) + "_queue",
                Map.of("clusterId", UUID.randomUUID().toString(), "queue", "orders.in"));

        assertThat(text(called)).isEqualTo(McpErrors.CLUSTER_DENIED);
        assertThat(System.getProperty(CALLS)).isEqualTo("0");
    }

    @Test
    void aResourceToolForAQueueTheKeyCannotReadHidesTheQueueAndNeverRuns() throws Exception {
        String id = activate(accessJar(newId()));
        UUID cluster = UUID.randomUUID();
        // The key sees the cluster, and holds the tool's permission, but may not read queues.
        var key = key(Grant.ScopeType.CLUSTER, cluster, Set.of("cluster:read", id + ":peek"));

        JsonNode called = McpFixture.callTool(
                mvc, key, snake(id) + "_queue", Map.of("clusterId", cluster.toString(), "queue", "billing.in"));

        assertThat(called.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(text(called)).isEqualTo(McpErrors.RESOURCE_DENIED).doesNotContain(id + ":peek");
        assertThat(System.getProperty(CALLS)).isEqualTo("0");
    }

    @Test
    void aResourceToolForAQueueTheKeyMayReadButNotUseNamesThePermissionAndNeverRuns() throws Exception {
        String id = activate(accessJar(newId()));
        UUID cluster = UUID.randomUUID();
        var key = key(Grant.ScopeType.CLUSTER, cluster, Set.of("subscription:read"));

        JsonNode called = McpFixture.callTool(
                mvc, key, snake(id) + "_queue", Map.of("clusterId", cluster.toString(), "queue", "orders.in"));

        assertThat(text(called)).contains(id + ":peek").contains("subscription orders.in");
        assertThat(System.getProperty(CALLS)).isEqualTo("0");
    }

    @Test
    void aResourceToolWithTheRightOnTheQueueReachesThePlugin() throws Exception {
        String id = activate(accessJar(newId()));
        UUID cluster = UUID.randomUUID();
        var key = key(Grant.ScopeType.CLUSTER, cluster, Set.of("subscription:read", id + ":peek"));

        JsonNode called = McpFixture.callTool(
                mvc, key, snake(id) + "_queue", Map.of("clusterId", cluster.toString(), "queue", "orders.in"));

        assertThat(text(called)).isEqualTo("reached orders.in");
        assertThat(System.getProperty(CALLS)).isEqualTo("1");
    }

    @Test
    void aResourceToolWithoutAResourceNeverRuns() throws Exception {
        String id = activate(accessJar(newId()));
        UUID cluster = UUID.randomUUID();
        var key = key(Grant.ScopeType.GLOBAL, null, Set.of("subscription:read", id + ":peek"));

        // The schema refuses a missing argument before Rahdar's check; a blank one reaches the check.
        JsonNode missing = McpFixture.callTool(mvc, key, snake(id) + "_queue", Map.of("clusterId", cluster.toString()));
        JsonNode blank = McpFixture.callTool(
                mvc, key, snake(id) + "_queue", Map.of("clusterId", cluster.toString(), "queue", " "));

        assertThat(missing.has("error")
                        || missing.path("result").path("isError").asBoolean(false))
                .isTrue();
        assertThat(blank.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(blank.path("error").path("message").asString()).contains("queue");
        assertThat(System.getProperty(CALLS)).isEqualTo("0");
    }

    @Test
    void aResourceToolThatDoesNotTakeItsResourceArgumentIsRefused() {
        String id = newId();
        PluginJarBuilder jar = accessJar(id)
                .descriptorField(
                        "mcpTools",
                        List.of(
                                clusterTool(id, "read"),
                                resourceTool(id, "address"),
                                Map.of(
                                        "name",
                                        snake(id) + "_global",
                                        "posture",
                                        "write",
                                        "scope",
                                        "global",
                                        "permission",
                                        id + ":admin",
                                        "description",
                                        "Changes a global thing.")));
        assertThatThrownBy(() -> activate(jar)).hasStackTraceContaining("'address'");
    }

    @Test
    void aMalformedClusterIdIsAMalformedCall() throws Exception {
        String id = activate(accessJar(newId()));
        var key = key(Grant.ScopeType.GLOBAL, null, Set.of(id + ":read"));

        JsonNode called = McpFixture.callTool(mvc, key, snake(id) + "_cluster", Map.of("clusterId", "not-an-id"));

        assertThat(called.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(System.getProperty(CALLS)).isEqualTo("0");
    }

    @Test
    void aGlobalToolWithoutThePermissionNamesItAndNeverRuns() throws Exception {
        String id = activate(accessJar(newId()));
        var key = key(Grant.ScopeType.GLOBAL, null, Set.of(id + ":read"));

        JsonNode called = McpFixture.callTool(mvc, key, snake(id) + "_global", Map.of());

        assertThat(called.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(text(called)).contains(id + ":admin");
        assertThat(System.getProperty(CALLS)).isEqualTo("0");
    }

    @Test
    void aGlobalToolWithThePermissionReachesThePlugin() throws Exception {
        String id = activate(accessJar(newId()));
        var key = key(Grant.ScopeType.GLOBAL, null, Set.of(id + ":admin"));

        JsonNode called = McpFixture.callTool(mvc, key, snake(id) + "_global", Map.of());

        assertThat(text(called)).isEqualTo("reached");
        assertThat(System.getProperty(CALLS)).isEqualTo("1");
    }

    @Test
    void aPluginToolsMalformedCallRefusalReachesTheCallerAsIs() throws Exception {
        String id = activate(accessJar(newId()));
        var key = key(Grant.ScopeType.GLOBAL, null, Set.of(id + ":admin"));

        JsonNode called = McpFixture.callTool(mvc, key, snake(id) + "_global", Map.of("mode", "bad"));

        assertThat(called.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(called.path("error").path("message").asString()).contains("fast or slow");
    }

    @Test
    void helpShowsEachPluginToolsPermissionScopeAndParams() throws Exception {
        String id = activate(accessJar(newId()));
        var key = key(Grant.ScopeType.GLOBAL, null, Set.of(id + ":read"));

        String index = McpFixture.callTool(mvc, key, "rahdar_help", Map.of()).toString();
        assertThat(index)
                .contains(snake(id) + "_cluster")
                .contains(id + ":read")
                .contains("cluster");

        String topic = McpFixture.callTool(mvc, key, "rahdar_help", Map.of("topic", snake(id) + "_global"))
                .toString();
        assertThat(topic)
                .contains(id + ":admin")
                .contains("global")
                .contains("mode")
                .contains("fast");
    }

    @Test
    void aReadToolThatIsNotReadOnlyIsRefused() {
        String id = newId();
        PluginJarBuilder jar = accessJar(id, "read", "false", "String clusterId");
        assertThatThrownBy(() -> activate(jar)).hasStackTraceContaining("readOnlyHint");
    }

    @Test
    void aClusterToolWithoutAClusterIdIsRefused() {
        String id = newId();
        PluginJarBuilder jar = accessJar(id, "read", "true", "String queue");
        assertThatThrownBy(() -> activate(jar)).hasStackTraceContaining("clusterId");
    }

    @Test
    void anUndeclaredToolIsRefused() {
        String id = newId();
        PluginJarBuilder jar = accessJar(id).descriptorField("mcpTools", List.of(clusterTool(id, "read")));
        assertThatThrownBy(() -> activate(jar)).hasStackTraceContaining("declares");
    }

    private String activate(PluginJarBuilder builder) throws Exception {
        Path jar = builder.build();
        PluginDescriptor descriptor;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            descriptor = descriptorParser.parse(jarFile.getInputStream(jarFile.getEntry("META-INF/rahdar/plugin.json"))
                    .readAllBytes());
        }
        activeRuntime = runtimeFactory.activate(descriptor, jar, webContext.getServletContext());
        registry.set(descriptor.id(), new PluginRuntimeRegistry.Active(activeRuntime));
        return descriptor.id();
    }

    private McpFixture.Key key(Grant.ScopeType scope, UUID scopeId, Set<String> permissions) {
        return McpFixture.mintKey(users, roles, rolePermissions, userRoles, tokens, scope, scopeId, permissions);
    }

    private static String newId() {
        return "acme-access-" + Math.abs(new SecureRandom().nextInt());
    }

    private static String snake(String id) {
        return id.replace('-', '_');
    }

    private static String text(JsonNode called) {
        return called.path("result").path("content").get(0).path("text").asString();
    }

    private static Map<String, Object> clusterTool(String id, String posture) {
        return Map.of(
                "name",
                snake(id) + "_cluster",
                "posture",
                posture,
                "scope",
                "cluster",
                "permission",
                id + ":read",
                "description",
                "Reads one cluster.");
    }

    private static Map<String, Object> resourceTool(String id, String resourceArg) {
        return Map.of(
                "name",
                snake(id) + "_queue",
                "posture",
                "read",
                "scope",
                "resource",
                "permission",
                id + ":peek",
                "resourceArg",
                resourceArg,
                "resourceType",
                "subscription",
                "description",
                "Reads one queue.");
    }

    private static PluginJarBuilder accessJar(String id) {
        return accessJar(id, "read", "true", "String clusterId");
    }

    /** One cluster-scoped tool shaped by the arguments, and one global write tool guarded by {@code :admin}. */
    private static PluginJarBuilder accessJar(String id, String posture, String readOnly, String clusterParam) {
        String pkg = "com.acme.access";
        return new PluginJarBuilder(id)
                .descriptorField("basePackage", pkg)
                .descriptorField("configuration", pkg + ".Config")
                .descriptorField(
                        "permissions",
                        List.of(
                                Map.of("action", id + ":read", "scope", "cluster"),
                                Map.of("action", id + ":admin", "scope", "global"),
                                Map.of(
                                        "action",
                                        id + ":peek",
                                        "scope",
                                        "resource",
                                        "resourceTypes",
                                        List.of("subscription"),
                                        "requires",
                                        List.of("subscription:read"))))
                .descriptorField(
                        "mcpTools",
                        List.of(
                                clusterTool(id, posture),
                                resourceTool(id, "queue"),
                                Map.of(
                                        "name",
                                        snake(id) + "_global",
                                        "posture",
                                        "write",
                                        "scope",
                                        "global",
                                        "permission",
                                        id + ":admin",
                                        "description",
                                        "Changes a global thing.",
                                        "params",
                                        List.of(Map.of(
                                                "name", "mode",
                                                "values", List.of("fast", "slow"),
                                                "note", "Defaults to slow.")))))
                .source(pkg + ".Config", """
                        package com.acme.access;
                        import org.springframework.context.annotation.*;
                        @Configuration
                        @ComponentScan(basePackageClasses = Config.class)
                        public class Config {}
                        """)
                .source(pkg + ".AccessTools", """
                        package com.acme.access;
                        import org.springframework.ai.mcp.annotation.McpTool;
                        import org.springframework.ai.mcp.annotation.McpToolParam;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class AccessTools {
                            private static void count() {
                                System.setProperty("%1$s",
                                        String.valueOf(Integer.parseInt(System.getProperty("%1$s", "0")) + 1));
                            }
                            @McpTool(name = "%2$s_cluster", description = "Reads one cluster.",
                                    annotations = @McpTool.McpAnnotations(readOnlyHint = %3$s))
                            public String cluster(@McpToolParam(required = true, description = "x") %4$s) {
                                count();
                                return "reached " + %5$s;
                            }
                            @McpTool(name = "%2$s_queue", description = "Reads one queue.",
                                    annotations = @McpTool.McpAnnotations(readOnlyHint = true))
                            public String queue(
                                    @McpToolParam(required = true, description = "x") String clusterId,
                                    @McpToolParam(required = true, description = "x") String queue) {
                                count();
                                return "reached " + queue;
                            }
                            @McpTool(name = "%2$s_global", description = "Changes a global thing.",
                                    annotations = @McpTool.McpAnnotations(readOnlyHint = false))
                            public String global(@McpToolParam(required = false, description = "x") String mode) {
                                if ("bad".equals(mode)) {
                                    throw io.modelcontextprotocol.spec.McpError.builder(-32602)
                                            .message("mode is fast or slow.").build();
                                }
                                count();
                                return "reached";
                            }
                        }
                        """.formatted(
                        CALLS, snake(id), readOnly, clusterParam, clusterParam.split(" ")[1]));
    }
}
