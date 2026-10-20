package io.github.sudoitir.rahdar.feature.diagnostics.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.feature.diagnostics.DiagnosticsService;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.clusters.ClusterSecrets;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.FakeArtemis;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The support bundle and the bug-report summary (diagnostics spec): redacted, trimmed, admin-only, audited. */
@ExtendWith(AdminAuthenticationExtension.class)
class DiagnosticsControllerTest extends PostgresIntegrationTest {

    private static final String URL = "http://diag:8161/console/jolokia";
    private static final String Q = "DIAG.Q";
    private final JsonMapper json = new JsonMapper();

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    QueueSnapshotUpsert upsert;

    @Autowired
    ClusterSecrets secrets;

    @Autowired
    AuditEventRepository audit;

    @Autowired
    DiagnosticsService diagnostics;

    @Autowired
    Environment environment;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    BrokerGateway gateway;

    private UUID clusterId;
    private UUID nodeId;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
        clusterId = clusters.save(new ClusterEntity("diag-" + UUID.randomUUID(), null, null))
                .getId();
        BrokerNodeEntity node = BrokerNodeEntity.fromSeed(
                clusterId, "node-a", "PRIMARY", UUID.randomUUID().toString());
        node.attachSeedUrl(URL);
        nodeId = nodes.save(node).getId();
        upsert.upsertBatch(List.of(
                new QueueRow(clusterId, nodeId, Q, Q, "ANYCAST", true, 0, 0, 0, 0, 0, 0, 0, false, "artemis.queue")));
    }

    @AfterEach
    void cleanUp() {
        clusters.deleteById(clusterId);
    }

    @Test
    void theBundleHoldsNoSecretAndNoMessageContent() throws Exception {
        String loggedPassword = "hunter-" + UUID.randomUUID();
        String storedPassword = "vault-" + UUID.randomUUID();
        String body = "body-" + UUID.randomUUID();
        String property = "prop-" + UUID.randomUUID();
        LoggerFactory.getLogger(DiagnosticsControllerTest.class)
                .warn("broker login failed password={} for tcp://bob:{}@broker:61616", loggedPassword, loggedPassword);
        secrets.store(clusterId, "diag", "bob", storedPassword);
        FakeArtemis broker = new FakeArtemis().queue(Q).operation("sendMessage", "ID:1");
        when(gateway.cluster(clusterId)).thenReturn(FixtureJolokia.cluster(Map.of(nodeId, broker.agent())));
        mvc.perform(post("/api/v1/clusters/{c}/queues/{q}/messages", clusterId, Q)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"TEXT\",\"durable\":true,\"body\":\"%s\",\"properties\":{\"k\":\"%s\"}}"
                                .formatted(body, property)))
                .andExpect(status().isOk());

        JsonNode bundle = prepare();
        List<String> keys = keys(bundle);
        assertThat(keys).containsExactly("about", "settings", "health", "plugins", "threads", "logs");
        Map<String, String> files = download(bundle.get("id").asString(), keys);

        assertThat(files)
                .containsOnlyKeys(
                        "about.json", "settings.json", "health.json", "plugins.json", "threads.txt", "logs.txt");
        String all = String.join("\n", files.values());
        assertThat(files.get("logs.txt")).contains("broker login failed password=[redacted]");
        assertThat(all)
                .doesNotContain(loggedPassword, storedPassword, body, property)
                .doesNotContain(environment.getRequiredProperty("rahdar.secret-key"));
        assertThat(bundle.get("sections").get(5).get("redactions").asInt()).isPositive();
    }

    @Test
    void theDownloadHoldsOnlyTheKeptSectionsOfThePreviewAndIsAudited() throws Exception {
        JsonNode bundle = prepare();
        String id = bundle.get("id").asString();
        String previewedLogs = bundle.get("sections").get(5).get("content").asString();
        LoggerFactory.getLogger(DiagnosticsControllerTest.class).warn("written after the preview");

        Map<String, String> files = download(id, List.of("about", "threads", "logs"));

        assertThat(files).containsOnlyKeys("about.json", "threads.txt", "logs.txt");
        assertThat(files.get("logs.txt")).isEqualTo(previewedLogs).doesNotContain("written after the preview");
        AuditEventEntity row = audit.findAll().stream()
                .filter(e -> id.equals(e.getTargetName()))
                .findFirst()
                .orElseThrow();
        assertThat(row.getAction()).isEqualTo("CREATE_DIAGNOSTICS_BUNDLE");
        assertThat(row.getUsername()).isEqualTo("test-admin");
        assertThat(row.getOutcome()).isEqualTo("SUCCESS");
        assertThat(row.getParams()).contains("about", "threads", "logs").doesNotContain("settings");
    }

    @Test
    void anUnknownSectionOrAnEmptySelectionIsRefused() throws Exception {
        String id = prepare().get("id").asString();
        mvc.perform(post("/api/v1/admin/diagnostics/bundles/{id}/download", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sections\":[\"messages\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/diagnostics/bundles/{id}/download", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sections\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherAdministratorsSnapshotIsNotFound() throws Exception {
        String id = prepare().get("id").asString();
        signIn("other-admin", Set.of(Permissions.WILDCARD));

        mvc.perform(post("/api/v1/admin/diagnostics/bundles/{id}/download", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sections\":[\"about\"]}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/diagnostics/bundles/{id}/download", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sections\":[\"about\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aUserWithoutThePermissionGetsTheSummaryButNoBundle() throws Exception {
        signIn("viewer", Set.of(Permissions.CLUSTER_READ));

        assertThatThrownBy(diagnostics::prepare).isInstanceOf(AccessDeniedException.class);
        UUID anyBundle = UUID.randomUUID();
        List<String> about = List.of("about");
        assertThatThrownBy(() -> diagnostics.take(anyBundle, about)).isInstanceOf(AccessDeniedException.class);
        mvc.perform(get("/api/v1/diagnostics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rahdarVersion").isNotEmpty())
                .andExpect(jsonPath("$.java").isNotEmpty())
                .andExpect(jsonPath("$.database").value(Matchers.startsWith("PostgreSQL")));
    }

    private JsonNode prepare() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/diagnostics/bundles"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body);
    }

    private static List<String> keys(JsonNode bundle) {
        return bundle.get("sections")
                .valueStream()
                .map(s -> s.get("key").asString())
                .toList();
    }

    private Map<String, String> download(String id, List<String> keys) throws Exception {
        MvcResult started = mvc.perform(post("/api/v1/admin/diagnostics/bundles/{id}/download", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("sections", keys))))
                .andExpect(request().asyncStarted())
                .andReturn();
        byte[] zip = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", Matchers.containsString(".zip")))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                files.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static void signIn(String username, Set<String> permissions) {
        RahdarPrincipal principal = new RahdarPrincipal(
                null, username, Set.of(new Grant(Grant.ScopeType.GLOBAL, null, permissions)), false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
}
