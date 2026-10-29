package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.feature.messages.MessageService;
import io.github.sudoitir.rahdar.kernel.gate.ApprovalUnavailableException;
import io.github.sudoitir.rahdar.kernel.gate.AuthKind;
import io.github.sudoitir.rahdar.kernel.gate.GateContext;
import io.github.sudoitir.rahdar.kernel.gate.OperationDeniedException;
import io.github.sudoitir.rahdar.kernel.gate.OperationHeldException;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.github.sudoitir.rahdar.platform.clusters.ClusterKinds;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The approval gate seen from an MCP tool (ADR-0179): every call is an agent's, and a held, denied or unavailable
 * operation comes back as text the agent can act on, never as a failure to retry.
 */
class McpApprovalGateTest extends PostgresIntegrationTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    @MockitoBean
    MessageService messages;

    /** The subscription the tool names is read as written: no cluster is registered behind this test. */
    @MockitoBean
    ResourceTargets targets;

    /** No cluster is registered behind this test either, so no subscription is a log. */
    @MockitoBean
    ClusterKinds kinds;

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
        when(targets.require(any(), any(), any(), any()))
                .thenAnswer(call -> new ResourceTargets.Target(
                        ResourceIds.parse(call.getArgument(1), call.getArgument(2)), ResourceClass.SUBSCRIPTION));
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

    private JsonNode purge(Map<String, Object> extra) throws Exception {
        Map<String, Object> arguments = new HashMap<>(Map.of(
                "clusterId",
                CLUSTER.toString(),
                "subscription",
                "artemis.queue/orders",
                "action",
                "purge",
                "dryRun",
                false,
                "confirm",
                "orders"));
        arguments.putAll(extra);
        return McpFixture.callTool(mvc, key, "message_action", arguments);
    }

    private static String text(JsonNode response) {
        return response.path("result").path("content").get(0).path("text").asString();
    }

    @Test
    void everyCallIsAnAgents() throws Exception {
        AtomicReference<AuthKind> origin = new AtomicReference<>();
        when(messages.purge(eq(CLUSTER), eq("orders"), any(), eq(false), anyBoolean()))
                .thenAnswer(call -> {
                    origin.set(GateContext.ORIGIN.isBound() ? GateContext.ORIGIN.get() : null);
                    return new Attempt.Ok<>(new MessageService.Outcome.Affected(3, UUID.randomUUID()));
                });

        JsonNode response = purge(Map.of());

        assertThat(response.path("result").path("isError").asBoolean())
                .describedAs("%s", response)
                .isFalse();
        assertThat(origin).hasValue(AuthKind.AGENT);
    }

    @Test
    void aHeldOperationIsTextSayingWhoMustApproveAndNotAnError() throws Exception {
        UUID heldId = UUID.randomUUID();
        when(messages.purge(eq(CLUSTER), eq("orders"), any(), eq(false), anyBoolean()))
                .thenThrow(new OperationHeldException(heldId, "Purge queue orders on prod-eu", Instant.now()));

        JsonNode response = purge(Map.of());

        assertThat(text(response))
                .isEqualTo("Held for approval: Purge queue orders on prod-eu (request " + heldId
                        + "). A second person must approve it in Rahdar: /approvals/" + heldId);
    }

    @Test
    void aDeniedOrUnavailableOperationIsAToolErrorWithItsReason() throws Exception {
        when(messages.purge(eq(CLUSTER), eq("orders"), any(), eq(false), anyBoolean()))
                .thenThrow(new OperationDeniedException("Frozen until Monday"));
        JsonNode denied = purge(Map.of());
        assertThat(denied.path("result").path("isError").asBoolean()).isTrue();
        assertThat(text(denied)).isEqualTo("Frozen until Monday");

        when(messages.purge(eq(CLUSTER), eq("orders"), any(), eq(false), anyBoolean()))
                .thenThrow(new ApprovalUnavailableException("The approval provider did not answer"));
        assertThat(text(purge(Map.of()))).isEqualTo("The approval provider did not answer");
    }
}
