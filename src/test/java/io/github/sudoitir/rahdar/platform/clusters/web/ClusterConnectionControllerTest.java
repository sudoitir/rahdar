package io.github.sudoitir.rahdar.platform.clusters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.SecretVault;
import io.github.sudoitir.rahdar.platform.broker.AccountResult;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.DataPlaneCheck;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlProblem;
import io.github.sudoitir.rahdar.platform.broker.ProbeSession;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerCredentialEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerCredentialRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.DataPlaneChecks;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

/** {@code PATCH /clusters/{id}}: the connection edit, checked first and saved after. */
@ExtendWith(AdminAuthenticationExtension.class)
class ClusterConnectionControllerTest extends PostgresIntegrationTest {

    private static final String PRIMARY_URL = "http://artemis-primary:8161/console/jolokia";
    private static final String BACKUP_URL = "http://artemis-backup:8161/console/jolokia";
    private static final String PATTERN = "http://{host}:8161/console/jolokia";
    private static final String NODE_ID = "f7734597-a768-11f1-aa4c-ceae3fa2df1d";
    private static final String MANAGEMENT = "MANAGEMENT";
    private static final String DATA = "DATA";
    private static final String OLD_SECRET = "old-secret";
    private static final String NEW_SECRET = "new-secret";
    private static final String CORE_SECRET = "core-secret";
    /** Both accounts, as the edit of a connection to new hosts must carry them again. */
    private static final String BOTH_ACCOUNTS = "\"management\":{\"username\":\"old-user\",\"password\":\"" + OLD_SECRET
            + "\"},\"core\":{\"username\":\"core-user\",\"password\":\"" + CORE_SECRET + "\"}";

    private static final String ROW = "$.nodes[?(@.name == '%s')]";

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    BrokerCredentialRepository credentials;

    @Autowired
    AuditEventRepository audits;

    @Autowired
    SecretVault vault;

    @MockitoBean
    BrokerGateway gateway;

    /** Every probe with the request's or the stored account goes through this session. */
    private final ProbeSession session = mock(ProbeSession.class);

    /** Probes with the new password under test go through this one. */
    private final ProbeSession newSecretSession = mock(ProbeSession.class);

    @MockitoBean
    DataPlaneCheck dataPlaneCheck;

    private UUID clusterId;

    @BeforeEach
    void setUp() {
        when(gateway.probe(any(BrokerConnectionSettings.class))).thenAnswer(inv -> {
            BrokerConnectionSettings s = inv.getArgument(0);
            return s != null && NEW_SECRET.equals(s.password()) ? newSecretSession : session;
        });
        when(gateway.probe(any(UUID.class))).thenReturn(session);
        mvc = webAppContextSetup(webContext).build();
        audits.deleteAll();
        clusters.deleteAll();

        ClusterEntity cluster = new ClusterEntity("c-" + UUID.randomUUID(), null, null);
        cluster.edit(cluster.getName(), null, PATTERN);
        clusterId = clusters.save(cluster).getId();

        BrokerNodeEntity primary = BrokerNodeEntity.discovered(clusterId, "artemis-primary:61616", "PRIMARY", NODE_ID);
        primary.attachSeedUrl(PRIMARY_URL);
        nodes.save(primary);
        BrokerNodeEntity backup = BrokerNodeEntity.discovered(clusterId, "artemis-backup:61616", "BACKUP", NODE_ID);
        backup.attachDerivedUrl(BACKUP_URL);
        nodes.save(backup);

        seal(MANAGEMENT, "old-user", OLD_SECRET);
        seal(DATA, "core-user", CORE_SECRET);

        when(dataPlaneCheck.check(any(), any())).thenAnswer(DataPlaneChecks.answering(url -> AccountResult.ACCEPTED));
        // Every discovery tick: each manageable node answers search, HA read and topology.
        when(session.node(any())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            return client(
                    url, url.contains("backup") ? "ha-read-backup.json" : "ha-read-primary.json", "topology.json");
        });
    }

    private void seal(String kind, String username, String password) {
        credentials.save(new BrokerCredentialEntity(
                clusterId, kind, username, vault.seal(SecretVault.aad(clusterId, kind), password)));
    }

    private BrokerTarget client(String url, String... fixtures) {
        FixtureJolokia server = new FixtureJolokia();
        server.expect().andRespond(withSuccess(body("search-broker.json"), MediaType.APPLICATION_JSON));
        for (String fixture : fixtures) {
            server.expect().andRespond(withSuccess(body(fixture), MediaType.APPLICATION_JSON));
        }
        return server.target();
    }

    private BrokerTarget refusing(String url) {
        FixtureJolokia server = new FixtureJolokia();
        server.expect().andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        return server.target();
    }

    private static String body(String fixture) {
        try {
            return new String(
                    new ClassPathResource("jolokia/" + fixture).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String storedPassword(String kind) {
        BrokerCredentialEntity row =
                credentials.findByClusterIdAndKind(clusterId, kind).orElseThrow();
        return vault.open(SecretVault.aad(clusterId, kind), row.getSealed());
    }

    private ResultActions edit(String body, boolean dryRun) throws Exception {
        return mvc.perform(patch("/api/v1/clusters/{id}", clusterId)
                .param("dryRun", String.valueOf(dryRun))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void aNewPasswordIsCheckedOnEveryNodeAndNothingIsStored() throws Exception {
        doReturn(refusing(PRIMARY_URL)).when(newSecretSession).node(PRIMARY_URL);
        doReturn(client(BACKUP_URL, "ha-read-backup.json"))
                .when(newSecretSession)
                .node(BACKUP_URL);

        edit("{\"management\":{\"username\":\"old-user\",\"password\":\"" + NEW_SECRET + "\"}}", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath(ROW.formatted("artemis-primary:61616") + ".management")
                        .value("REJECTED"))
                .andExpect(jsonPath(ROW.formatted("artemis-backup:61616") + ".management")
                        .value("ACCEPTED"))
                .andExpect(jsonPath(ROW.formatted("artemis-backup:61616") + ".urlSource")
                        .value("DERIVED"));

        assertThat(storedPassword(MANAGEMENT)).isEqualTo(OLD_SECRET);
        assertThat(audits.findAll())
                .filteredOn(e -> "UPDATE_CLUSTER_CONNECTION".equals(e.getAction()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.isDryRun()).isTrue();
                    assertThat(e.getParams()).doesNotContain(NEW_SECRET);
                });
    }

    @Test
    void aDryRunChangesNeitherTheClusterNorItsNodes() throws Exception {
        edit(
                        "{\"name\":\"renamed\",\"managementUrlPattern\":\"http://{host}:9161/jolokia\"," + BOTH_ACCOUNTS
                                + "}",
                        true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managementUrlPattern").value("http://{host}:9161/jolokia"));

        ClusterEntity cluster = clusters.findById(clusterId).orElseThrow();
        assertThat(cluster.getName()).startsWith("c-");
        assertThat(cluster.getManagementUrlPattern()).isEqualTo(PATTERN);
        assertThat(nodes.findByClusterIdOrderByNameAsc(clusterId))
                .extracting(BrokerNodeEntity::getManagementUrl)
                .containsExactly(BACKUP_URL, PRIMARY_URL);
    }

    @Test
    void aPasswordLeftEmptyKeepsTheStoredOneForBothAccounts() throws Exception {
        edit(
                        "{\"name\":\"renamed\",\"management\":{\"username\":\"old-user\",\"password\":\"\"},"
                                + "\"core\":{\"username\":\"core-user\",\"password\":\"\"}}",
                        false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed"))
                .andExpect(jsonPath("$.connection.managementUsername").value("old-user"))
                .andExpect(jsonPath("$.connection.coreUsername").value("core-user"));

        assertThat(storedPassword(MANAGEMENT)).isEqualTo(OLD_SECRET);
        assertThat(storedPassword(DATA)).isEqualTo(CORE_SECRET);
    }

    @Test
    void aFieldLeftOutIsLeftAsItWas() throws Exception {
        edit("{\"name\":\"renamed\"}", false).andExpect(status().isOk());

        assertThat(storedPassword(MANAGEMENT)).isEqualTo(OLD_SECRET);
        assertThat(credentials.findByClusterIdAndKind(clusterId, DATA)).isPresent();
        assertThat(clusters.findById(clusterId).orElseThrow().getManagementUrlPattern())
                .isEqualTo(PATTERN);
    }

    @Test
    void aCoreAccountSetToNullFallsBackToTheManagementAccount() throws Exception {
        edit("{\"core\":null}", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connection.coreUsername").value(Matchers.nullValue()));

        assertThat(credentials.findByClusterIdAndKind(clusterId, DATA)).isEmpty();
        assertThat(credentials.findByClusterIdAndKind(clusterId, MANAGEMENT)).isPresent();
    }

    @Test
    void aNewSecretIsSealedAuditedWithTheMaskAndNeverReturned() throws Exception {
        String response = edit(
                        "{\"management\":{\"username\":\"new-user\",\"password\":\"" + NEW_SECRET + "\"},"
                                + "\"core\":{\"username\":\"core-user\",\"password\":\"" + NEW_SECRET + "2\"}}",
                        false)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain(NEW_SECRET);
        assertThat(storedPassword(MANAGEMENT)).isEqualTo(NEW_SECRET);
        assertThat(storedPassword(DATA)).isEqualTo(NEW_SECRET + "2");
        AuditEventEntity event = audits.findAll().stream()
                .filter(e -> "UPDATE_CLUSTER_CONNECTION".equals(e.getAction()))
                .findFirst()
                .orElseThrow();
        assertThat(event.getOutcome()).isEqualTo("SUCCESS");
        assertThat(event.isDryRun()).isFalse();
        assertThat(event.getParams())
                .doesNotContain(NEW_SECRET)
                .contains("[redacted]")
                .contains("new-user");
    }

    @Test
    void aChangedPatternRederivesTheDerivedUrlOnTheSpotAndLeavesTheSeedAlone() throws Exception {
        String moved = "http://artemis-backup:9161/jolokia";
        doReturn(client(moved, "ha-read-backup.json")).when(session).node(moved);

        edit("{\"managementUrlPattern\":\"http://{host}:9161/jolokia\"," + BOTH_ACCOUNTS + "}", false)
                .andExpect(status().isOk());

        assertThat(nodes.findByClusterIdAndName(clusterId, "artemis-backup:61616")
                        .orElseThrow()
                        .getManagementUrl())
                .isEqualTo(moved);
        assertThat(nodes.findByClusterIdAndName(clusterId, "artemis-primary:61616")
                        .orElseThrow()
                        .getManagementUrl())
                .isEqualTo(PRIMARY_URL);
    }

    @Test
    void aSeedOnANewHostIsRefusedWithoutThePasswordAndNothingIsSentToIt() throws Exception {
        String attacker = "http://evil.example:8161/console/jolokia";

        edit("{\"seedUrls\":[\"" + attacker + "\"]}", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enter the management password again to check new hosts."));

        verify(session, never()).node(attacker);
    }

    @Test
    void aSeedOnANewHostIsRefusedToTheCommitToo() throws Exception {
        edit(
                        "{\"seedUrls\":[\"http://evil.example:8161/console/jolokia\"],"
                                + "\"management\":{\"username\":\"old-user\",\"password\":\"\"}}",
                        false)
                .andExpect(status().isBadRequest());

        assertThat(storedPassword(MANAGEMENT)).isEqualTo(OLD_SECRET);
    }

    @Test
    void aSeedOnANewHostIsCheckedOnceThePasswordIsEnteredAgain() throws Exception {
        String other = "http://other.example:8161/console/jolokia";
        doReturn(client(other, "ha-read-primary.json")).when(session).node(other);

        edit("{\"seedUrls\":[\"" + other + "\"]," + BOTH_ACCOUNTS + "}", true).andExpect(status().isOk());
    }

    @Test
    void aSeedAlreadyUsedByTheClusterNeedsNoPassword() throws Exception {
        edit("{\"seedUrls\":[\"" + PRIMARY_URL + "\"]}", true).andExpect(status().isOk());
    }

    @Test
    void aTlsBundleChangeNeedsThePasswordAgain() throws Exception {
        edit("{\"tlsBundle\":\"other\"}", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enter the management password again to check new hosts."));
    }

    @Test
    void aChangedPatternNeedsTheCorePasswordAgainToo() throws Exception {
        edit(
                        "{\"managementUrlPattern\":\"http://{host}:9161/jolokia\",\"management\":"
                                + "{\"username\":\"old-user\",\"password\":\"" + OLD_SECRET + "\"}}",
                        true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enter the Core password again to check new hosts."));
    }

    @Test
    void aChangedUserNameWithoutAPasswordIsRefused() throws Exception {
        edit("{\"management\":{\"username\":\"someone-else\"}}", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enter the management password again to check new hosts."));

        edit("{\"core\":{\"username\":\"someone-else\"}}", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enter the Core password again to check new hosts."));
    }

    @Test
    void aSeedWithAnAccountInItsUrlIsRefused() throws Exception {
        edit("{\"seedUrls\":[\"http://admin:secret@broker:8161/console/jolokia\"]," + BOTH_ACCOUNTS + "}", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Put the account in the Management account fields, not in the URL."));
    }

    @Test
    void aPatternThatCouldNameAnotherHostIsRefused() throws Exception {
        edit("{\"managementUrlPattern\":\"http://evil/?h={host}\"," + BOTH_ACCOUNTS + "}", true)
                .andExpect(status().isBadRequest());
        edit("{\"managementUrlPattern\":\"http://user@{host}:8161/jolokia\"," + BOTH_ACCOUNTS + "}", true)
                .andExpect(status().isBadRequest());
    }

    @Test
    void aChangedPatternClearsTheReasonANodeHadNoUrl() throws Exception {
        BrokerNodeEntity lost =
                nodes.findByClusterIdAndName(clusterId, "artemis-backup:61616").orElseThrow();
        lost.recordUrlProblem(ManagementUrlProblem.CREDENTIALS_REJECTED, Instant.now());
        nodes.save(lost);
        String moved = "http://artemis-backup:9161/jolokia";
        doReturn(client(moved, "ha-read-backup.json")).when(session).node(moved);

        edit("{\"managementUrlPattern\":\"http://{host}:9161/jolokia\"," + BOTH_ACCOUNTS + "}", false)
                .andExpect(status().isOk());

        BrokerNodeEntity after =
                nodes.findByClusterIdAndName(clusterId, "artemis-backup:61616").orElseThrow();
        assertThat(after.getUrlProblem()).isNull();
        assertThat(after.getManagementUrl()).isEqualTo(moved);
    }

    /** Makes every connection the factory hands out note whether a database transaction was open when it was asked for. */
    private AtomicBoolean watchingForTransactions() {
        AtomicBoolean inTransaction = new AtomicBoolean();
        doAnswer(inv -> {
                    if (TransactionSynchronizationManager.isActualTransactionActive()) {
                        inTransaction.set(true);
                    }
                    String url = inv.getArgument(0);
                    return client(
                            url,
                            url.contains("backup") ? "ha-read-backup.json" : "ha-read-primary.json",
                            "topology.json");
                })
                .when(session)
                .node(any());
        return inTransaction;
    }

    @Test
    void aCheckCallsNoBrokerInsideATransaction() throws Exception {
        AtomicBoolean inTransaction = watchingForTransactions();

        edit("{\"name\":\"renamed\"}", true).andExpect(status().isOk());

        assertThat(inTransaction).isFalse();
        verify(session, atLeastOnce()).node(any());
    }

    @Test
    void aSaveRediscoversAfterItsTransactionHasCommitted() throws Exception {
        AtomicBoolean inTransaction = watchingForTransactions();

        edit("{\"managementUrlPattern\":\"http://{host}:9161/jolokia\"," + BOTH_ACCOUNTS + "}", false)
                .andExpect(status().isOk());

        assertThat(inTransaction).isFalse();
        verify(session, atLeastOnce()).node(any());
    }

    @Test
    void aPatternWithoutTheHostPlaceholderIsRefused() throws Exception {
        edit("{\"managementUrlPattern\":\"http://broker:8161/jolokia\"}", false).andExpect(status().isBadRequest());
    }

    @Test
    void aCallerWhoMayNotRegisterClustersMayNotEditThem() throws Exception {
        RahdarPrincipal nobody = new RahdarPrincipal(null, "nobody", Set.of(), false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(nobody, null, nobody.getAuthorities()));

        edit("{\"name\":\"renamed\"}", false).andExpect(status().is4xxClientError());

        assertThat(clusters.findById(clusterId).orElseThrow().getName()).startsWith("c-");
    }
}
