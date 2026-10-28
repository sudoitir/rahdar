package io.github.sudoitir.rahdar.platform.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.feature.brokerconfig.BrokerConfigPermissions;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Registrations;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The MCP configuration loop against a real Apache ActiveMQ Artemis 2.44.0
 * (ADR-0067 D11): declare, plan, acknowledge, apply, verify by read-back, and
 * read every kind back — with nothing about the broker simulated.
 *
 * <p>{@link McpBrokerConfigIntegrationTest} mocks {@code BrokerConfigOperations},
 * so it proves Rahdar's half of the contract: the hazard ids, the refusals, the
 * shape of a halted run. What it cannot prove is the half that belongs to the
 * broker — that {@code addAddressSettings} takes what the planner sends, that the
 * read-back reports the same values, and that a second apply therefore converges.
 * That is this test, and it is the same measurement the live end-to-end script
 * makes, run in the build.
 *
 * <p>The cluster is registered through the product's own REST API rather than
 * assembled with repositories: credential sealing and node discovery are part of
 * what an agent depends on, and a fixture that skips them proves less.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class McpBrokerConfigRealBrokerTest extends PostgresIntegrationTest {

    /** A match of Rahdar's own making, so the test never touches what the broker ships with. */
    private static final String MATCH = "MCP.REAL.#";

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

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
    ClusterService clusterService;

    private MockMvc mvc;
    private UUID clusterId;
    private String clusterName;
    private McpFixture.Key key;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        clusterName = "mcp-real-" + UUID.randomUUID();
        // Registered through the service rather than the HTTP endpoint: the MCP
        // calls below go through the security filter chain with a minted key, and
        // one MockMvc cannot be both that and an authenticated browser session.
        // What matters here is that discovery and credential sealing are real.
        var attempt = clusterService.register(Registrations.artemis(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                clusterName,
                null,
                new RegisterClusterRequest.Credentials(
                        ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                null,
                null,
                null,
                null,
                false));
        if (!(attempt instanceof Attempt.Ok<ClusterDetail> ok)) {
            throw new IllegalStateException("could not register the container broker: " + attempt);
        }
        clusterId = ok.value().id();
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
                        BrokerConfigPermissions.CONFIG_WRITE,
                        BrokerConfigPermissions.CONFIG_APPLY));
    }

    @AfterEach
    void cleanUp() {
        auditEvents.deleteAll();
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private JsonNode structured(JsonNode response) {
        return response.path("result").path("structuredContent");
    }

    private JsonNode call(String tool, Map<String, Object> args) throws Exception {
        return McpFixture.callTool(mvc, key, tool, args);
    }

    @Test
    void declaresPlansAcknowledgesAppliesAndConvergesAgainstARealBroker() throws Exception {
        String document = """
                {"version":1,"addresses":[],"addressSettings":[{"match":"%s","values":{"maxDeliveryAttempts":7}}],
                 "securitySettings":[],"diverts":[]}""".formatted(MATCH);

        // 1. Declare. The dry run by default is the contract an agent relies on.
        JsonNode preview = structured(call(
                "artemis_config_change",
                Map.of("clusterId", clusterId.toString(), "op", "declare", "document", document)));
        assertThat(preview.path("valid").asBoolean()).isTrue();
        assertThat(preview.path("message").asString()).contains("Nothing was saved");
        assertThat(structured(call("artemis_config", Map.of("clusterId", clusterId.toString(), "kind", "declaration")))
                        .path("declared")
                        .asBoolean())
                .describedAs("a dry-run declare saved nothing")
                .isFalse();

        Map<String, Object> save = new HashMap<>(Map.of(
                "clusterId",
                clusterId.toString(),
                "op",
                "declare",
                "document",
                document,
                "dryRun",
                false,
                "confirm",
                clusterName));
        assertThat(structured(call("artemis_config_change", save))
                        .path("revision")
                        .asInt())
                .isEqualTo(1);

        // 2. Plan. A real broker's own catch-all is what the step is computed against.
        JsonNode plan =
                structured(call("artemis_config_change", Map.of("clusterId", clusterId.toString(), "op", "apply")));
        assertThat(plan.path("dryRun").asBoolean()).isTrue();
        assertThat(plan.path("stepCount").asInt()).isPositive();
        List<String> acknowledge =
                plan.path("acknowledge").valueStream().map(JsonNode::asString).toList();

        // 3. Apply for real, echoing the plan hash the preview returned.
        Map<String, Object> real = new HashMap<>(Map.of(
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
        if (!acknowledge.isEmpty()) {
            real.put("acknowledge", String.join(",", acknowledge));
        }
        JsonNode applied = structured(call("artemis_config_change", real));
        assertThat(applied.path("outcome").asString()).isEqualTo("APPLIED");

        // 4. The broker echoed what was written: every applied step verified by a
        // read-back the broker itself answered.
        assertThat(applied.path("nodes")
                        .valueStream()
                        .flatMap(n -> n.path("steps").valueStream()))
                .isNotEmpty()
                .allMatch(s -> !"APPLIED".equals(s.path("status").asString())
                        || "VERIFIED".equals(s.path("verified").asString()));

        // 5. Re-running converges: the plan is now empty because the broker agrees.
        assertThat(structured(call("artemis_config_change", Map.of("clusterId", clusterId.toString(), "op", "apply")))
                        .path("stepCount")
                        .asInt())
                .isZero();
    }

    @Test
    void everyReadKindAnswersFromTheRealBroker() throws Exception {
        String document = """
                {"version":1,"addresses":[],"addressSettings":[{"match":"%s","values":{"maxDeliveryAttempts":7}}],
                 "securitySettings":[],"diverts":[]}""".formatted(MATCH);
        call(
                "artemis_config_change",
                new HashMap<>(Map.of(
                        "clusterId",
                        clusterId.toString(),
                        "op",
                        "declare",
                        "document",
                        document,
                        "dryRun",
                        false,
                        "confirm",
                        clusterName)));
        call("artemis_config_change", Map.of("clusterId", clusterId.toString(), "op", "apply"));

        JsonNode declaration =
                structured(call("artemis_config", Map.of("clusterId", clusterId.toString(), "kind", "declaration")));
        assertThat(declaration.path("declared").asBoolean()).isTrue();
        assertThat(declaration.path("document").path("addressSettings")).hasSize(1);

        // Drift is the same read the scheduled pass makes, against a live broker.
        JsonNode drift = structured(call("artemis_config", Map.of("clusterId", clusterId.toString(), "kind", "drift")));
        assertThat(drift.path("nodes")).isNotEmpty();

        JsonNode xml = call("artemis_config", Map.of("clusterId", clusterId.toString(), "kind", "xml"));
        assertThat(xml.toString()).contains(MATCH).contains("address-setting");

        // The dry run above is an apply and is in the history, flagged as one. The
        // kind returns a bare list, so the assertion is on the payload rather than
        // on a named field.
        JsonNode applies = call("artemis_config", Map.of("clusterId", clusterId.toString(), "kind", "applies"));
        assertThat(applies.toString()).contains("DRY_RUN");
    }
}
