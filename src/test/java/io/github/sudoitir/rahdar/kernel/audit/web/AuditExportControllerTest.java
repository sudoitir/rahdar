package io.github.sudoitir.rahdar.kernel.audit.web;

import static io.github.sudoitir.rahdar.support.SignedInSession.authentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditPermissions;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.internal.RoleService;
import io.github.sudoitir.rahdar.kernel.security.internal.TeamService;
import io.github.sudoitir.rahdar.kernel.security.internal.UserService;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.rahdar.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.GrantRequest;
import io.github.sudoitir.rahdar.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.TeamAccessFixture;
import io.github.sudoitir.rahdar.system.artemis.ArtemisModule;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET .../audit/export}: the trail as a streamed file, limited to what the caller may read, and itself audited. */
@ExtendWith(AdminAuthenticationExtension.class)
class AuditExportControllerTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository audit;

    @Autowired
    AuditService auditService;

    @Autowired
    TeamService teamService;

    @Autowired
    RoleService roleService;

    @Autowired
    UserService userService;

    @Autowired
    RoleRepository roles;

    @Autowired
    AppUserRepository users;

    final JsonMapper json = new JsonMapper();

    MockMvc mvc;
    UUID clusterId;
    UsernamePasswordAuthenticationToken admin;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).apply(springSecurity()).build();
        clusterId = clusters.save(new ClusterEntity(ArtemisModule.SYSTEM, "export-" + UUID.randomUUID(), null, null))
                .getId();
        RahdarPrincipal principal = new RahdarPrincipal(
                null, "exporter", Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(Permissions.WILDCARD))), false);
        admin = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    @AfterEach
    void cleanUp() {
        audit.deleteAll();
        clusters.deleteById(clusterId);
    }

    private void event(String user, String action, String targetType, String targetName, Map<String, ?> params) {
        AuditEvent e = auditService.begin(
                new Actor(user, "127.0.0.1", "req", null),
                action,
                targetType,
                targetName,
                clusterId,
                null,
                params,
                false);
        auditService.succeed(e, 1);
    }

    /** The streamed body of a call, once the response has finished. */
    private MockHttpServletResponse export(String query, UsernamePasswordAuthenticationToken caller) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/clusters/{c}/audit/export?" + query, clusterId)
                        .with(authentication(caller)))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult();
        }
        return result.getResponse();
    }

    @Test
    void ndjsonHoldsExactlyTheRowsMatchingTheFiltersOldestFirst() throws Exception {
        event("alice", "FIRST", "QUEUE", "Q", Map.of());
        event("bob", "OTHER", "QUEUE", "Q", Map.of());
        event("alice", "SECOND", "QUEUE", "Q", Map.of());

        MockHttpServletResponse response = export("format=ndjson&user=alice&from=2000-01-01T00:00:00Z", admin);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("application/x-ndjson");
        assertThat(response.getHeader("Content-Disposition"))
                .contains("attachment")
                .contains("audit-export-")
                .endsWith(".ndjson\"");
        List<JsonNode> rows =
                response.getContentAsString().lines().map(json::readTree).toList();
        assertThat(rows).extracting(r -> r.get("action").asString()).containsExactly("FIRST", "SECOND");
        assertThat(rows).extracting(r -> r.get("user").asString()).containsOnly("alice");
    }

    @Test
    void jsonIsOneArray() throws Exception {
        event("alice", "FIRST", "QUEUE", "Q", Map.of("n", 1));

        JsonNode rows = json.readTree(export("format=json", admin).getContentAsString());

        assertThat(rows.isArray()).isTrue();
        assertThat(rows.get(0).get("action").asString()).isEqualTo("FIRST");
        assertThat(rows.get(0).get("params").get("n").asInt()).isEqualTo(1);
    }

    @Test
    void csvNeutralisesValuesASpreadsheetWouldRun() throws Exception {
        event("=1+1", "FIRST", "QUEUE", "@sum", Map.of());

        String csv = export("format=csv", admin).getContentAsString();

        assertThat(csv)
                .startsWith("id,ts,user,action,targetType,targetName,cluster,outcome,affectedCount,error,dryRun,"
                        + "requestId,parentId,sourceIp,params,outcomeDetail");
        assertThat(csv).contains("'=1+1").contains("'@sum").doesNotContain(",=1+1");
    }

    @Test
    void anUnknownFormatIsABadRequest() throws Exception {
        assertThat(export("format=xml", admin).getStatus()).isEqualTo(400);
    }

    @Test
    void theExportIsAuditedWithItsFormatAndFiltersAndIsNotInItself() throws Exception {
        event("alice", "FIRST", "QUEUE", "Q", Map.of());

        String body = export("format=ndjson&user=alice", admin).getContentAsString();

        assertThat(body).doesNotContain("EXPORT_AUDIT");
        AuditEventEntity row = audit.findByClusterIdOrderByTsDesc(clusterId).getFirst();
        assertThat(row.getAction()).isEqualTo("EXPORT_AUDIT");
        assertThat(row.getUsername()).isEqualTo("exporter");
        assertThat(row.getOutcome()).isEqualTo("SUCCESS");
        assertThat(row.getAffectedCount()).isEqualTo(1);
        JsonNode params = json.readTree(row.getParams());
        assertThat(params.get("format").asString()).isEqualTo("ndjson");
        assertThat(params.get("filters").get("user").asString()).isEqualTo("alice");
    }

    @Test
    void aCallerWithoutAuditExportIsRefusedAndNothingIsExported() throws Exception {
        event("alice", "FIRST", "QUEUE", "Q", Map.of());
        RahdarPrincipal reader = new RahdarPrincipal(
                null,
                "reader",
                Set.of(new Grant(Grant.ScopeType.CLUSTER, clusterId, Set.of(Permissions.CLUSTER_READ))),
                false);
        UsernamePasswordAuthenticationToken caller =
                UsernamePasswordAuthenticationToken.authenticated(reader, null, reader.getAuthorities());

        MockHttpServletResponse response = export("format=ndjson", caller);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).doesNotContain("FIRST");
        assertThat(audit.findByClusterIdOrderByTsDesc(clusterId))
                .extracting(AuditEventEntity::getAction)
                .doesNotContain("EXPORT_AUDIT");
    }

    @Test
    void aReaderOfOneTeamsQueuesGetsOnlyTheirEventsWithOtherNamesRedacted() throws Exception {
        TeamAccessFixture fixture = new TeamAccessFixture(teamService, roles, users);
        UUID orders = fixture.team("orders", clusterId, "orders.#");
        UUID user = fixture.member(orders, "TEAM_VIEWER");
        UUID role = roleService
                .create(new RoleRequest(
                        "exporter-" + UUID.randomUUID(), List.of(AuditPermissions.EXPORT), false, false))
                .id();
        userService.addGrant(user, new GrantRequest(role, "CLUSTER", clusterId));
        event("alice", "MINE", "SUBSCRIPTION", "orders.in", Map.of("target", "billing.in"));
        event("alice", "THEIRS", "SUBSCRIPTION", "billing.in", Map.of());
        event("alice", "CLUSTERWIDE", "CLUSTER", "prod", Map.of());

        String body = export("format=ndjson", fixture.session(user)).getContentAsString();

        List<JsonNode> rows = body.lines().map(json::readTree).toList();
        assertThat(rows).extracting(r -> r.get("action").asString()).containsExactly("MINE");
        assertThat(rows.getFirst().get("params").get("target").asString()).doesNotContain("billing");
    }
}
