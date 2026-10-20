package io.github.sudoitir.rahdar.feature.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.McpFixture;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.DivertOperations;
import io.github.sudoitir.rahdar.system.artemis.DivertRow;
import io.github.sudoitir.rahdar.system.artemis.ManagementRequest;
import io.github.sudoitir.rahdar.system.artemis.QueueLifecycleOperations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Divert creation against a real Artemis: what the broker deployed is what is reported, and
 * the diverts that would break routing are refused before anything is created.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class DivertLifecycleRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    DivertLifecycleService lifecycle;

    @Autowired
    DivertOperations divertOps;

    @Autowired
    QueueLifecycleOperations queueOps;

    @Autowired
    BrokerConnections connections;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository auditEvents;

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

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final List<String> created = new ArrayList<>();
    private UUID clusterId;
    private ArtemisNode client;
    private String broker;

    @BeforeEach
    void setUp() {
        var attempt = clusterService.register(new RegisterClusterRequest(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "divert-real-" + run,
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
        client = ArtemisIntegrationTest.jolokiaClient(ArtemisIntegrationTest.jolokiaUrl());
        broker = client.resolveBrokerObjectName();
    }

    @AfterEach
    void cleanUp() {
        for (String name : created) {
            try {
                divertOps.destroyDivert(client, broker, name);
            } catch (RuntimeException _) {
                // already gone
            }
        }
        auditEvents.deleteAll();
        clusters.deleteById(clusterId);
    }

    private String address(String suffix) {
        String name = "DIVERT.REAL." + run + "." + suffix;
        queueOps.createAddress(client, broker, name, "MULTICAST");
        return name;
    }

    private NodeOutcome create(String name, String from, String to, boolean dryRun) {
        created.add(name);
        LifecycleOutcome outcome = ((Attempt.Ok<LifecycleOutcome>) lifecycle.createDivert(
                        clusterId, new CreateDivertRequest(name, null, from, to, false, null, null, null), dryRun))
                .value();
        assertThat(outcome.nodes()).hasSize(1);
        return outcome.nodes().get(0);
    }

    @Test
    void anIdenticalDivertIsAlreadyThereAndADifferentOneUnderTheSameNameFailsNamingTheField() {
        String a = address("A");
        String b = address("B");
        String c = address("C");
        String name = "copy-" + run;

        assertThat(create(name, a, b, false).status()).isEqualTo(NodeStatus.APPLIED);
        assertThat(divertOps.find(client, name)).isPresent();
        assertThat(create(name, a, b, false).status()).isEqualTo(NodeStatus.ALREADY);

        NodeOutcome different = create(name, a, c, false);
        assertThat(different.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(different.error()).contains("forwarding-address");
    }

    /**
     * The nested {@code transformer-configuration} deploys on a divert, and the read-back
     * holds the broker to it: the flat {@code transformer-class-name} Rahdar once sent was
     * accepted with a 200 and deployed the divert without its transformer.
     */
    @Test
    void aTransformerDeploysNestedAndAFlatOneIsCaughtByTheReadBack() {
        String a = address("TA");
        String b = address("TB");
        String nested = "nested-" + run;
        String flat = "flat-" + run;
        created.add(nested);
        created.add(flat);
        String transformer = "org.apache.activemq.artemis.core.server.transformer.AddHeadersTransformer";

        Map<String, Object> config =
                new LinkedHashMap<>(DivertOperations.divertConfig(nested, null, a, b, false, null, null));
        config.put("transformer-configuration", Map.of("class-name", transformer, "properties", Map.of("k", "v")));
        assertThat(divertOps.createVerified(client, broker, config)).isEqualTo(NodeStatus.APPLIED);
        DivertRow row = divertOps.find(client, nested).orElseThrow();
        assertThat(row.transformerClassName()).isEqualTo(transformer);
        assertThat(row.transformerProperties()).containsEntry("k", "v");

        Map<String, Object> flatConfig =
                new LinkedHashMap<>(DivertOperations.divertConfig(flat, null, a, b, false, null, null));
        flatConfig.put("transformer-class-name", transformer);
        // What the broker deploys for the flat key is a divert with no transformer, so
        // asking for the nested one afterwards reads back as different, naming it.
        divertOps.createDivert(client, broker, flatConfig);
        assertThat(divertOps.find(client, flat).orElseThrow().transformerClassName())
                .isNull();
        Map<String, Object> wanted =
                new LinkedHashMap<>(DivertOperations.divertConfig(flat, null, a, b, false, null, null));
        wanted.put("transformer-configuration", Map.of("class-name", transformer, "properties", Map.of()));
        assertThatThrownBy(() -> divertOps.createVerified(client, broker, wanted))
                .hasMessageContaining("transformer class-name");
    }

    @Test
    void aForwardingAddressThatDoesNotExistAndIsNotAutoCreatedIsRefusedInThePreviewAndForReal() {
        String match = "NOAUTO." + run + ".#";
        client.single(ManagementRequest.exec(
                broker,
                "addAddressSettings(java.lang.String,java.lang.String)",
                match,
                "{\"autoCreateAddresses\":false}"));
        try {
            String a = address("SRC");
            String missing = "NOAUTO." + run + ".MISSING";
            String name = "to-nowhere-" + run;

            NodeOutcome preview = create(name, a, missing, true);
            assertThat(preview.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(preview.error()).contains(missing).contains("<address name=");

            assertThat(create(name, a, missing, false).status()).isEqualTo(NodeStatus.FAILED);
            assertThat(divertOps.find(client, name)).isEmpty();
        } finally {
            client.single(ManagementRequest.exec(broker, "removeAddressSettings(java.lang.String)", match));
        }
    }

    @Test
    void aDivertThatWouldCloseACycleIsRefusedNamingTheCycle() {
        String a = address("A");
        String b = address("B");
        assertThat(create("ab-" + run, a, b, false).status()).isEqualTo(NodeStatus.APPLIED);

        NodeOutcome back = create("ba-" + run, b, a, false);

        assertThat(back.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(back.error()).contains("cycle").contains(b + " → " + a + " → " + b);
        assertThat(divertOps.find(client, "ba-" + run)).isEmpty();
    }

    @Test
    void anInvalidRequestIsRejectedPerFieldAndACaptureNameIsRefusedOverRest() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        String path = "/api/v1/clusters/" + clusterId + "/diverts";

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"bad,name","address":"A","forwardingAddress":"B","routingType":"SIDEWAYS"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'routingType')]").exists());

        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"rahdar.capture.x","address":"A","forwardingAddress":"B"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("reserved")));
    }

    @Test
    void aCaptureNameIsRefusedOverMcp() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        McpFixture.Key key = McpFixture.mintKey(
                users,
                roles,
                rolePermissions,
                userRoles,
                tokens,
                Grant.ScopeType.CLUSTER,
                clusterId,
                Set.of(Permissions.CLUSTER_READ, ResourcePermissions.DIVERT_WRITE));

        JsonNode response = McpFixture.callTool(
                mvc,
                key,
                "divert_lifecycle",
                Map.of(
                        "clusterId", clusterId.toString(),
                        "kind", "create_divert",
                        "name", "rahdar.capture.x",
                        "config", "{\"address\":\"A\",\"forwardingAddress\":\"B\"}",
                        "dryRun", false));

        assertThat(response.toString()).contains("reserved for message capture");
    }
}
