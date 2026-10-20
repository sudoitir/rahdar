package io.github.sudoitir.rahdar.platform.clusters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.rahdar.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.platform.broker.AccountResult;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.BrokerVersion;
import io.github.sudoitir.rahdar.platform.broker.DataPlaneCheck;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlSource;
import io.github.sudoitir.rahdar.platform.broker.ProbeSession;
import io.github.sudoitir.rahdar.platform.clusters.ClusterPermissions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterService;
import io.github.sudoitir.rahdar.platform.clusters.RegistrationAdoption;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerCredentialRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.DataPlaneChecks;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(AdminAuthenticationExtension.class)
class ClusterControllerTest extends PostgresIntegrationTest {

    private static final String SEED = "http://broker-1:8161/console/jolokia";
    private static final String OVERRIDE_URL = "http://broker-2:8261/console/jolokia";
    /** The backup's management URL: the seed's scheme, port and path with the host of its connector. */
    private static final String BACKUP_URL = "http://artemis-backup:8161/console/jolokia";

    private final JsonMapper mapper = new JsonMapper();

    MockMvc mvc;

    @Autowired
    ClusterService clusterService;

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

    @MockitoBean
    BrokerGateway gateway;

    /** Every probe with the request's or the stored account goes through this session. */
    private final ProbeSession session = mock(ProbeSession.class);

    /** Probes with the new password under test go through this one. */
    private final ProbeSession newSecretSession = mock(ProbeSession.class);

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    DataPlaneCheck dataPlaneCheck;

    @MockitoBean
    RegistrationAdoption adoption;

    @BeforeEach
    void setUp() {
        when(gateway.probe(any(BrokerConnectionSettings.class))).thenAnswer(inv -> {
            BrokerConnectionSettings s = inv.getArgument(0);
            return s != null && "\u0000".equals(s.password()) ? newSecretSession : session;
        });
        when(gateway.probe(any(UUID.class))).thenReturn(session);
        mvc = webAppContextSetup(webContext).build();
        audits.deleteAll();
        clusters.deleteAll();
        // The backup answers where the seed's pattern puts it, as its own NodeID's broker.
        when(session.node(BACKUP_URL))
                .thenAnswer(inv -> client(BACKUP_URL, "search-broker.json", "ha-read-backup.json"));
        when(dataPlaneCheck.check(any(), any())).thenAnswer(DataPlaneChecks.answering(url -> AccountResult.ACCEPTED));
    }

    /** A client whose mock server answers the given fixtures at {@link #SEED}, in order. */
    private BrokerTarget client(String url, String... fixtures) {
        FixtureJolokia server = new FixtureJolokia();
        for (String fixture : fixtures) {
            server.expect(requestTo(url)).andRespond(withSuccess(body(fixture), MediaType.APPLICATION_JSON));
        }
        return server.target();
    }

    private BrokerTarget unauthorizedClient(String url) {
        FixtureJolokia server = new FixtureJolokia();
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
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

    /** The full call sequence one client sees during register: connect, discover, capability probe. */
    private static String[] registerSequence() {
        return new String[] {
            "search-broker.json", // connectAll -> resolveBrokerObjectName
            "ha-read-primary.json", // connectAll -> the HA read, whose Version is the range check (ADR-0142)
            "ha-read-primary.json", // discover -> readBrokerAttributes
            "topology.json", // discover -> listNetworkTopology
            "capability-version-read.json", // probe -> MANAGEMENT_READ
            "topology.json", // probe -> MANAGEMENT_WRITE (listNetworkTopology)
            "acceptors.json", // probe -> CORE acceptor search
            "acceptor-params-core.json", // probe -> acceptor Parameters
            "addresses-with-notifications.json", // probe -> notifications address search
            "address-settings.json" // probe -> slow-consumer detection (ADR-0044)
        };
    }

    private String registerBody() {
        return """
                { "seedUrls": ["%s"], "name": "prod-emea",
                  "credentials": { "username": "artemis", "password": "artemis" } }
                """.formatted(SEED);
    }

    @Test
    void registerPersistsClusterNodesAndAudit() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));

        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("prod-emea"))
                .andExpect(jsonPath("$.topology.nodes.length()").value(1))
                .andExpect(jsonPath("$.capabilities.managementRead.status").value("AVAILABLE"));

        assertThat(clusters.count()).isEqualTo(1);
        var clusterId = clusters.findAll().get(0).getId();
        assertThat(nodes.findByClusterIdOrderByNameAsc(clusterId)).hasSize(2);
        assertThat(credentials.findByClusterIdAndKind(clusterId, "JOLOKIA_BASIC"))
                .isPresent();

        List<AuditEventEntity> registerEvents = audits.findAll().stream()
                .filter(e -> e.getAction().equals("REGISTER_CLUSTER"))
                .toList();
        assertThat(registerEvents).singleElement().satisfies(e -> {
            assertThat(e.getOutcome()).isEqualTo("SUCCESS");
            assertThat(e.isDryRun()).isFalse();
        });
    }

    @Test
    void dryRunProbesButPersistsNoCluster() throws Exception {
        // checkConnection order: connect, then the in-memory preview (which the Core
        // subscription pre-check needs for a Core URL), then the capability probe
        // (which is handed that check's verdict).
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));

        mvc.perform(post("/api/v1/clusters")
                        .param("dryRun", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachableSeeds").value(1))
                .andExpect(jsonPath("$.discoveredNodes").value(2))
                .andExpect(jsonPath("$.managementUrlPattern").value("http://{host}:8161/console/jolokia"));

        assertThat(clusters.count()).isZero();
        assertThat(audits.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getAction()).isEqualTo("REGISTER_CLUSTER");
            assertThat(e.getOutcome()).isEqualTo("SUCCESS");
            assertThat(e.isDryRun()).isTrue();
        });
    }

    private static final String PRIMARY = "$.nodes[?(@.name == 'artemis-primary:61616')]";
    private static final String BACKUP = "$.nodes[?(@.name == 'artemis-backup:61616')]";

    @Test
    void theCheckListsEveryNodeWithItsUrlAndWhereItCameFrom() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));

        mvc.perform(post("/api/v1/clusters")
                        .param("dryRun", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(2))
                .andExpect(jsonPath(PRIMARY + ".managementUrl").value(SEED))
                .andExpect(jsonPath(PRIMARY + ".urlSource").value("SEED"))
                .andExpect(jsonPath(PRIMARY + ".haRole").value("PRIMARY"))
                .andExpect(jsonPath(BACKUP + ".managementUrl").value(BACKUP_URL))
                .andExpect(jsonPath(BACKUP + ".urlSource").value("DERIVED"))
                .andExpect(jsonPath(BACKUP + ".haRole").value("BACKUP"))
                .andExpect(jsonPath(BACKUP + ".version").value("2.44.0"));
        assertThat(nodes.count()).isZero();
    }

    @Test
    void aCoreAccountTheBrokerRefusesDoesNotHideTheManagementAccountItAccepted() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));
        doAnswer(DataPlaneChecks.answering(
                        url -> "artemis-primary:61616".equals(url) ? AccountResult.REJECTED : AccountResult.ACCEPTED))
                .when(dataPlaneCheck)
                .check(any(), any());

        mvc.perform(post("/api/v1/clusters")
                        .param("dryRun", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PRIMARY + ".management").value("ACCEPTED"))
                .andExpect(jsonPath(PRIMARY + ".core").value("REJECTED"))
                // A passive backup opens no Core acceptor, so it is not asked.
                .andExpect(jsonPath(BACKUP + ".management").value("ACCEPTED"))
                .andExpect(jsonPath(BACKUP + ".core").value("NOT_TRIED"));
    }

    @Test
    void aManagementAccountTheBrokerRefusesDoesNotHideTheCoreAccountItAccepted() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));
        when(session.node(BACKUP_URL)).thenReturn(unauthorizedClient(BACKUP_URL));

        mvc.perform(post("/api/v1/clusters")
                        .param("dryRun", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PRIMARY + ".core").value("ACCEPTED"))
                .andExpect(jsonPath(BACKUP + ".management").value("REJECTED"))
                .andExpect(jsonPath(BACKUP + ".managementUrl").value(Matchers.contains((String) null)))
                .andExpect(jsonPath(BACKUP + ".urlProblem").value("CREDENTIALS_REJECTED"));
    }

    @Test
    void thePreviewCarriesWhatAdoptingWouldDeclareWhenTheNodesAgree() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));
        when(adoption.preview(any()))
                .thenReturn(Optional.of(new RegistrationAdoption.Preview(
                        new RegistrationAdoption.Counts(3, 2, 1, 0), List.of(), List.of("note"))));

        mvc.perform(post("/api/v1/clusters")
                        .param("dryRun", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adoption.counts.addresses").value(3))
                .andExpect(jsonPath("$.adoption.counts.addressSettings").value(2))
                .andExpect(jsonPath("$.adoption.counts.securitySettings").value(1))
                .andExpect(jsonPath("$.adoption.disagreements.length()").value(0));
    }

    @Test
    void thePreviewListsWhereTheNodesDisagree() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));
        String disagreement = "Address settings for orders differ between a and b; a's were kept.";
        when(adoption.preview(any()))
                .thenReturn(Optional.of(new RegistrationAdoption.Preview(
                        new RegistrationAdoption.Counts(1, 1, 0, 0), List.of(disagreement), List.of())));

        mvc.perform(post("/api/v1/clusters")
                        .param("dryRun", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adoption.disagreements[0]").value(disagreement));
    }

    @Test
    void thePreviewOffersNoAdoptionToACallerWhoMayNotDeclare() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));

        mvc.perform(post("/api/v1/clusters")
                        .param("dryRun", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adoption").value(Matchers.nullValue()));
    }

    private String registerBody(boolean adopt) {
        return """
                { "seedUrls": ["%s"], "name": "prod-emea", "adopt": %s,
                  "credentials": { "username": "artemis", "password": "artemis" } }
                """.formatted(SEED, adopt);
    }

    @Test
    void registeringWithTheAdoptionOnAdoptsInTheRegistrationsTransaction() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));
        RegistrationAdoption.Reading reading = new RegistrationAdoption.Reading() {};
        when(adoption.read(any())).thenReturn(Optional.of(reading));

        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.connection.managementUrlPattern").value("http://{host}:8161/console/jolokia"))
                .andExpect(jsonPath("$.connection.managementUsername").value("artemis"))
                .andExpect(jsonPath("$.connection.seedUrls[0]").value(SEED));

        UUID clusterId = clusters.findAll().get(0).getId();
        verify(adoption).adopt(clusterId, reading);
        assertThat(audits.findAll())
                .filteredOn(e -> "REGISTER_CLUSTER".equals(e.getAction()))
                .singleElement()
                .satisfies(e -> assertThat(e.getParams()).containsPattern("\"adopt\"\\s*:\\s*true"));
    }

    @Test
    void registeringWithTheAdoptionOffSavesNoRevision() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));

        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(false)))
                .andExpect(status().isCreated());

        verify(adoption, never()).adopt(any(), any());
    }

    @Test
    void anAdoptionWithNoReadableNodeIsRefusedBeforeAnythingIsSaved() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));
        when(adoption.read(any())).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(true)))
                .andExpect(status().isConflict());

        assertThat(clusters.count()).isZero();
    }

    @Test
    void anAdoptionThatFailsLeavesNoClusterBehind() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));
        when(adoption.read(any())).thenReturn(Optional.of(new RegistrationAdoption.Reading() {}));
        doThrow(new ConflictException("adoption-failed", "The first revision could not be saved."))
                .when(adoption)
                .adopt(any(), any());

        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(true)))
                .andExpect(status().isConflict());

        assertThat(clusters.count()).isZero();
        assertThat(nodes.count()).isZero();
    }

    /** Below the supported minimum: refused naming it, the check and the registration alike (ADR-0142). */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aBrokerOlderThanTheMinimumIsRefusedAndNothingIsStored(boolean dryRun) throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, "search-broker.json", "ha-read-too-old.json"));

        mvc.perform(post("/api/v1/clusters")
                        .param("dryRun", String.valueOf(dryRun))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.brokerErrorKind").value("UNSUPPORTED_VERSION"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("2.31.2")))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString(BrokerVersion.MINIMUM + " and later")));

        assertThat(clusters.count()).isZero();
        assertThat(audits.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getAction()).isEqualTo("REGISTER_CLUSTER");
            assertThat(e.getOutcome()).isEqualTo("FAILURE");
        });
    }

    @Test
    void badSeedYieldsClassifiedProblemDetailAndPersistsNothing() throws Exception {
        when(session.node(SEED)).thenReturn(unauthorizedClient(SEED));

        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.brokerErrorKind").value("CREDENTIALS_REJECTED"))
                .andExpect(jsonPath("$.account").value("MANAGEMENT"))
                .andExpect(jsonPath("$.type").value(Matchers.containsString("broker-credentials-rejected")));

        assertThat(clusters.count()).isZero();
        assertThat(audits.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getAction()).isEqualTo("REGISTER_CLUSTER");
            assertThat(e.getOutcome()).isEqualTo("FAILURE");
        });
    }

    @Test
    void discoveryKeepsAnOverriddenNode() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));
        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isCreated());
        var clusterId = clusters.findAll().get(0).getId();
        var backup =
                nodes.findByClusterIdAndName(clusterId, "artemis-backup:61616").orElseThrow();

        // PATCH the backup with a reachable management URL.
        when(session.node(OVERRIDE_URL)).thenReturn(client(OVERRIDE_URL, "search-broker.json", "ha-read-primary.json"));
        mvc.perform(patch("/api/v1/clusters/{c}/nodes/{n}", clusterId, backup.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jolokiaUrl\":\"" + OVERRIDE_URL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urlSource").value("MANUAL"));

        // A discovery tick: every manageable node answers search + HA read + topology.
        when(session.node(any()))
                .thenAnswer(inv ->
                        client(inv.getArgument(0), "search-broker.json", "ha-read-primary.json", "topology.json"));

        clusterService.rediscover(clusterId);

        var after =
                nodes.findByClusterIdAndName(clusterId, "artemis-backup:61616").orElseThrow();
        assertThat(after.getJolokiaUrl()).isEqualTo(OVERRIDE_URL);
        assertThat(after.getUrlSource()).isEqualTo(ManagementUrlSource.MANUAL);
    }

    @Test
    void deleteRemovesClusterAndCredentialsButKeepsAudit() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));
        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isCreated());
        var clusterId = clusters.findAll().get(0).getId();

        mvc.perform(delete("/api/v1/clusters/{c}", clusterId)).andExpect(status().isNoContent());

        assertThat(clusters.count()).isZero();
        assertThat(nodes.count()).isZero();
        assertThat(credentials.count()).isZero();
        assertThat(audits.findAll())
                .extracting(AuditEventEntity::getAction)
                .contains("REGISTER_CLUSTER", "DELETE_CLUSTER");
        // ADR-0072: audit has no foreign key to what it describes, so it keeps naming the removed cluster.
        assertThat(audits.findAll())
                .allSatisfy(e -> assertThat(e.getClusterId()).isEqualTo(clusterId));
        // A globally granted caller can still read the removed cluster's trail, by name (task 7.8).
        mvc.perform(get("/api/v1/clusters/{c}/audit", clusterId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.action == 'DELETE_CLUSTER')].clusterName")
                        .value("prod-emea"));
    }

    /** The reads before the identity check: connect, the version, then the one HA and topology read. */
    private static String[] untilIdentityKnown() {
        return new String[] {"search-broker.json", "ha-read-primary.json", "ha-read-primary.json", "topology.json"};
    }

    /**
     * The same brokers a second time, by the check or the registration, are refused naming the cluster that
     * holds them, and nothing is stored (ADR-0167). The seed is spelled differently: the NodeID decides.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theSameBrokersAreRegisteredOnlyOnce(boolean dryRun) throws Exception {
        String alias = "http://BROKER-1.example:8161/console/jolokia/";
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()));
        when(session.node(alias)).thenReturn(client(alias, untilIdentityKnown()));
        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isCreated());
        var existing = clusters.findAll().get(0).getId();
        audits.deleteAll();

        mvc.perform(post("/api/v1/clusters")
                        .param("dryRun", String.valueOf(dryRun))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "seedUrls": ["%s"], "name": "prod-emea-again",
                                  "credentials": { "username": "artemis", "password": "artemis" } }
                                """.formatted(alias)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(Matchers.endsWith("/problems/cluster-already-registered")))
                .andExpect(jsonPath("$.title").value("These brokers are already registered"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("\"prod-emea\"")))
                .andExpect(jsonPath("$.existingClusterId").value(existing.toString()))
                .andExpect(jsonPath("$.existingClusterName").value("prod-emea"))
                .andExpect(jsonPath("$.overlappingNodes")
                        .value(Matchers.contains("artemis-backup:61616", "artemis-primary:61616")));

        assertThat(clusters.findAll()).extracting(c -> c.getId()).containsExactly(existing);
        assertThat(audits.findAll()).singleElement().satisfies(e -> {
            assertThat(e.getAction()).isEqualTo("REGISTER_CLUSTER");
            assertThat(e.getOutcome()).isEqualTo("FAILURE");
            assertThat(e.isDryRun()).isEqualTo(dryRun);
        });
    }

    /** A caller who cannot read the cluster holding the brokers is refused without being told which it is. */
    @Test
    void aClusterTheCallerCannotSeeIsNotNamed() throws Exception {
        when(session.node(SEED)).thenReturn(client(SEED, registerSequence()), client(SEED, untilIdentityKnown()));
        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isCreated());

        RahdarPrincipal writerOnly = new RahdarPrincipal(
                null,
                "writer-only",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(ClusterPermissions.CLUSTER_WRITE))),
                false);
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        writerOnly, null, writerOnly.getAuthorities()));

        mvc.perform(post("/api/v1/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("These brokers are already registered"))
                .andExpect(jsonPath("$.detail").value(Matchers.not(Matchers.containsString("prod-emea"))))
                .andExpect(jsonPath("$.existingClusterId").doesNotExist())
                .andExpect(jsonPath("$.overlappingNodes").doesNotExist());
        assertThat(clusters.count()).isEqualTo(1);
        // The refused attempt's row belongs to no cluster, so it must not name the one the caller cannot see.
        assertThat(audits.findAll())
                .filteredOn(e -> "FAILURE".equals(e.getOutcome()))
                .singleElement()
                .satisfies(e -> assertThat(e.getError())
                        .contains("a registered cluster you do not have access to")
                        .doesNotContain("prod-emea")
                        .doesNotContain("artemis-primary"));
    }

    @Test
    void unknownClusterIs404() throws Exception {
        mvc.perform(get("/api/v1/clusters/{c}/topology", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }
}
