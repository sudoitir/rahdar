package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.rahdar.platform.broker.AccountResult;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlProblem;
import io.github.sudoitir.rahdar.platform.broker.ManagementUrlSource;
import io.github.sudoitir.rahdar.platform.broker.NodeEndpoint;
import io.github.sudoitir.rahdar.platform.broker.ProbeSession;
import io.github.sudoitir.rahdar.platform.clusters.TopologyDiscovery.ProbedSeed;
import io.github.sudoitir.rahdar.platform.clusters.TopologyDiscovery.UrlDerivation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.FixtureJolokia;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.ResponseCreator;
import tools.jackson.databind.json.JsonMapper;

class TopologyDiscoveryTest extends PostgresIntegrationTest {

    private static final String SEED_URL = "http://localhost:8161/console/jolokia";
    private static final String PATTERN = "http://{host}:8161/console/jolokia";
    private static final String BACKUP_URL = "http://artemis-backup:8161/console/jolokia";
    private static final String OTHER_NODE_ID = "00000000-0000-0000-0000-000000000000";
    private final JsonMapper mapper = new JsonMapper();

    @Autowired
    TopologyDiscovery discovery;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @MockitoBean
    BrokerGateway gateway;

    /** Every probe of a derived address goes through this session; each address answers from its own fixture. */
    private final ProbeSession session = mock(ProbeSession.class);

    @BeforeEach
    void probesGoThroughTheSession() {
        when(gateway.probe(any(BrokerConnectionSettings.class))).thenReturn(session);
    }

    /** Not a rolled-back test transaction: discovery calls brokers, and a broker call inside one is refused. */
    @AfterEach
    void removeWhatTheTestSaved() {
        nodes.deleteAll();
        clusters.deleteAll();
    }

    private UUID newCluster() {
        return clusters.save(new ClusterEntity("t-" + UUID.randomUUID(), null, null))
                .getId();
    }

    private static UrlDerivation derivation(String pattern) {
        return new UrlDerivation(pattern, BrokerConnectionSettings.basicAuth(null, "admin", "secret"));
    }

    /** A fresh client + mock server answering, in order: search broker, HA read, listNetworkTopology. */
    private ProbedSeed seed(String topologyFixture) {
        return seed("ha-read-primary.json", topologyFixture);
    }

    private ProbedSeed seed(String haFixture, String topologyFixture) {
        return seed(SEED_URL, haFixture, topologyFixture);
    }

    private ProbedSeed seed(String url, String haFixture, String topologyFixture) {
        FixtureJolokia server = new FixtureJolokia();
        server.expect(requestTo(url)).andRespond(json("search-broker.json"));
        server.expect(requestTo(url)).andRespond(json(haFixture));
        server.expect(requestTo(url)).andRespond(json(topologyFixture));
        return new ProbedSeed(url, server.target());
    }

    /** A seed whose broker is down: the first call, resolving the broker's MBean, gets no answer. */
    private ProbedSeed deadSeed(String url) {
        FixtureJolokia server = new FixtureJolokia();
        server.expect(requestTo(url)).andRespond(withException(new ConnectException("Connection refused")));
        return new ProbedSeed(url, server.target());
    }

    /** The client the factory hands out for a derived URL: search broker, then HA read, answered as given. */
    private void derivedAnswers(String url, ResponseCreator search, ResponseCreator ha) {
        FixtureJolokia server = new FixtureJolokia();
        server.expect(requestTo(url)).andRespond(search);
        if (ha != null) {
            server.expect(requestTo(url)).andRespond(ha);
        }
        when(session.node(url)).thenReturn(server.target());
    }

    private void backupAnswersWithItsOwnNodeId() {
        derivedAnswers(BACKUP_URL, json("search-broker.json"), json("ha-read-backup.json"));
    }

    private static ResponseCreator json(String fixture) {
        try {
            String body = new String(
                    new ClassPathResource("jolokia/" + fixture).getContentAsByteArray(), StandardCharsets.UTF_8);
            return withSuccess(body, MediaType.APPLICATION_JSON);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private BrokerNodeEntity backup(UUID clusterId) {
        return nodes.findByClusterIdAndName(clusterId, "artemis-backup:61616").orElseThrow();
    }

    @Test
    void discoversBothSidesOfAPairFromOneSeedAndDerivesTheBackupsUrl() {
        UUID clusterId = newCluster();
        backupAnswersWithItsOwnNodeId();

        ClusterTopology topology = discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(clusterId);
        assertThat(rows)
                .extracting(BrokerNodeEntity::getName)
                .containsExactlyInAnyOrder("artemis-primary:61616", "artemis-backup:61616");

        BrokerNodeEntity primary = rows.stream()
                .filter(n -> n.getName().equals("artemis-primary:61616"))
                .findFirst()
                .orElseThrow();
        BrokerNodeEntity backup = backup(clusterId);

        // The seed's management URL is attached to the primary side, and is a seed.
        assertThat(primary.getManagementUrl()).isEqualTo(SEED_URL);
        assertThat(primary.getUrlSource()).isEqualTo(ManagementUrlSource.SEED);
        assertThat(primary.getHaDesignation()).isEqualTo("PRIMARY");

        // The backup's URL is derived from the pattern with its connector host, and proved by its NodeID.
        assertThat(backup.getManagementUrl()).isEqualTo(BACKUP_URL);
        assertThat(backup.getUrlSource()).isEqualTo(ManagementUrlSource.DERIVED);
        assertThat(backup.getUrlProblem()).isNull();
        assertThat(backup.getDataUrl()).isEqualTo("tcp://artemis-backup:61616");
        assertThat(backup.getHaDesignation()).isEqualTo("BACKUP");

        // Both sides share the NodeID → one logical node.
        assertThat(primary.getNativeNodeId()).isEqualTo(backup.getNativeNodeId());
        assertThat(topology.nodes()).hasSize(1);
        assertThat(topology.nodes().get(0).endpoints()).hasSize(2);
        assertThat(topology.unmanaged()).isEmpty();
    }

    @Test
    void aDerivedUrlThatAnswersForAnotherBrokerIsNotAttached() {
        UUID clusterId = newCluster();
        derivedAnswers(BACKUP_URL, json("search-broker.json"), haReadWithNodeId(OTHER_NODE_ID));

        ClusterTopology topology = discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        BrokerNodeEntity backup = backup(clusterId);
        assertThat(backup.getManagementUrl()).isNull();
        assertThat(backup.getUrlSource()).isNull();
        assertThat(backup.getUrlProblem()).isEqualTo(ManagementUrlProblem.OTHER_BROKER);
        assertThat(topology.unmanaged()).extracting(NodeEndpoint::name).containsExactly("artemis-backup:61616");
    }

    @Test
    void aDerivedUrlThatRejectsTheManagementAccountIsNamedForIt() {
        UUID clusterId = newCluster();
        derivedAnswers(BACKUP_URL, withStatus(HttpStatus.UNAUTHORIZED), null);

        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        assertThat(backup(clusterId).getUrlProblem()).isEqualTo(ManagementUrlProblem.CREDENTIALS_REJECTED);
    }

    @Test
    void aDerivedUrlThatDoesNotAnswerIsUnreachable() {
        UUID clusterId = newCluster();
        derivedAnswers(BACKUP_URL, withException(new ConnectException("Connection refused")), null);

        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        BrokerNodeEntity backup = backup(clusterId);
        assertThat(backup.getManagementUrl()).isNull();
        assertThat(backup.getUrlProblem()).isEqualTo(ManagementUrlProblem.UNREACHABLE);
    }

    @Test
    void aNodeThatDidNotAnswerIsNotAskedAgainOnTheNextTick() {
        UUID clusterId = newCluster();
        derivedAnswers(BACKUP_URL, withException(new ConnectException("Connection refused")), null);
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        verify(session, times(1)).node(BACKUP_URL);
        assertThat(backup(clusterId).getUrlProblem()).isEqualTo(ManagementUrlProblem.UNREACHABLE);
    }

    @Test
    void aNodeThatDidNotAnswerIsAskedAgainAfterTheBackoffAndThenProved() {
        UUID clusterId = newCluster();
        derivedAnswers(BACKUP_URL, withException(new ConnectException("Connection refused")), null);
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));
        BrokerNodeEntity waiting = backup(clusterId);
        waiting.recordUrlProblem(
                ManagementUrlProblem.UNREACHABLE,
                Instant.now().minus(TopologyDiscovery.RETRY_AFTER).minusSeconds(60));
        nodes.saveAndFlush(waiting);
        backupAnswersWithItsOwnNodeId();

        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        verify(session, times(2)).node(BACKUP_URL);
        BrokerNodeEntity after = backup(clusterId);
        assertThat(after.getManagementUrl()).isEqualTo(BACKUP_URL);
        assertThat(after.getUrlProblem()).isNull();
    }

    @Test
    void aNodeWhoseAddressRefusedTheAccountOrAnsweredForAnotherBrokerWaitsForAChange() {
        UUID clusterId = newCluster();
        derivedAnswers(BACKUP_URL, withStatus(HttpStatus.UNAUTHORIZED), null);
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));
        BrokerNodeEntity refused = backup(clusterId);
        refused.recordUrlProblem(
                ManagementUrlProblem.CREDENTIALS_REJECTED, Instant.now().minus(Duration.ofDays(2)));
        nodes.saveAndFlush(refused);

        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));
        refused.recordUrlProblem(
                ManagementUrlProblem.OTHER_BROKER, Instant.now().minus(Duration.ofDays(2)));
        nodes.saveAndFlush(refused);
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));
        refused.recordUrlProblem(
                ManagementUrlProblem.WRONG_ENDPOINT, Instant.now().minus(Duration.ofDays(2)));
        nodes.saveAndFlush(refused);
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        verify(session, times(1)).node(BACKUP_URL);
    }

    @Test
    void aClearedReasonLetsTheNodeBeAskedAgain() {
        UUID clusterId = newCluster();
        derivedAnswers(BACKUP_URL, withStatus(HttpStatus.UNAUTHORIZED), null);
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));
        BrokerNodeEntity refused = backup(clusterId);
        refused.clearUrlProblem();
        nodes.saveAndFlush(refused);
        backupAnswersWithItsOwnNodeId();

        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        assertThat(backup(clusterId).getManagementUrl()).isEqualTo(BACKUP_URL);
    }

    @Test
    void aClusterWithoutAPatternDerivesNothing() {
        UUID clusterId = newCluster();

        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(null));

        assertThat(backup(clusterId).getUrlProblem()).isEqualTo(ManagementUrlProblem.NO_PATTERN);
        verify(session, never()).node(any());
    }

    @Test
    void aManualUrlSurvivesRediscoveryUnchangedAndIsNotProbed() {
        UUID clusterId = newCluster();
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(null));

        BrokerNodeEntity backup = backup(clusterId);
        backup.applyManualUrl("http://localhost:8261/console/jolokia");
        nodes.saveAndFlush(backup);

        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        BrokerNodeEntity after = backup(clusterId);
        assertThat(after.getManagementUrl()).isEqualTo("http://localhost:8261/console/jolokia");
        assertThat(after.getUrlSource()).isEqualTo(ManagementUrlSource.MANUAL);
        verify(session, never()).node(any());
    }

    @Test
    void aManualCoreUrlSurvivesRediscovery() {
        UUID clusterId = newCluster();
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(null));
        BrokerNodeEntity backup = backup(clusterId);
        backup.applyManualDataUrl("tcp://core.example:61617");
        nodes.saveAndFlush(backup);

        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(null));

        BrokerNodeEntity after = backup(clusterId);
        assertThat(after.getDataUrl()).isEqualTo("tcp://core.example:61617");
        assertThat(after.isDataUrlManual()).isTrue();
    }

    @Test
    void aProvedDerivedUrlIsNotProbedAgainOnTheSamePattern() {
        UUID clusterId = newCluster();
        backupAnswersWithItsOwnNodeId();
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        verify(session, Mockito.times(1)).node(any());
        assertThat(backup(clusterId).getManagementUrl()).isEqualTo(BACKUP_URL);
    }

    @Test
    void aChangedPatternRederivesDerivedUrlsAndLeavesSeedsAlone() {
        UUID clusterId = newCluster();
        backupAnswersWithItsOwnNodeId();
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        String moved = "http://artemis-backup:9161/console/jolokia";
        derivedAnswers(moved, json("search-broker.json"), json("ha-read-backup.json"));
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation("http://{host}:9161/console/jolokia"));

        assertThat(backup(clusterId).getManagementUrl()).isEqualTo(moved);
        assertThat(backup(clusterId).getUrlSource()).isEqualTo(ManagementUrlSource.DERIVED);
        assertThat(nodes.findByClusterIdAndName(clusterId, "artemis-primary:61616")
                        .orElseThrow()
                        .getManagementUrl())
                .isEqualTo(SEED_URL);
    }

    @Test
    void aDerivedUrlThatStopsAnsweringAfterAPatternChangeIsClearedWithItsReason() {
        UUID clusterId = newCluster();
        backupAnswersWithItsOwnNodeId();
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(PATTERN));

        String moved = "http://artemis-backup:9161/console/jolokia";
        derivedAnswers(moved, withException(new ConnectException("Connection refused")), null);
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation("http://{host}:9161/console/jolokia"));

        BrokerNodeEntity backup = backup(clusterId);
        assertThat(backup.getManagementUrl()).isNull();
        assertThat(backup.getUrlProblem()).isEqualTo(ManagementUrlProblem.UNREACHABLE);
    }

    @Test
    void aSeedThatIsNotAttachableReportsItsNodesButNeverBecomesTheirUrl() {
        UUID clusterId = newCluster();
        ProbedSeed read = seed("topology.json");

        discovery.discover(
                clusterId, List.of(new ProbedSeed(read.managementUrl(), read.target(), false)), derivation(null));

        assertThat(nodes.findByClusterIdOrderByNameAsc(clusterId))
                .extracting(BrokerNodeEntity::getName, BrokerNodeEntity::getManagementUrl)
                .containsExactlyInAnyOrder(tuple("artemis-primary:61616", null), tuple("artemis-backup:61616", null));
    }

    @Test
    void twoSeedAddressesOfOneBrokerAreOneNodeWithTheFirstAddressAsItsUrl() {
        UUID clusterId = newCluster();
        String sameBroker = "http://[0:0:0:0:0:0:0:1]:8161/console/jolokia";

        discovery.discover(
                clusterId,
                List.of(seed("topology.json"), seed(sameBroker, "ha-read-primary.json", "topology.json")),
                derivation(null));

        List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(clusterId);
        assertThat(rows).extracting(BrokerNodeEntity::getName).hasSize(2);
        assertThat(rows).extracting(BrokerNodeEntity::getManagementUrl).containsExactlyInAnyOrder(SEED_URL, null);
        assertThat(discovery
                        .preview(
                                discovery.survey(List.of(
                                        seed("topology.json"),
                                        seed(sameBroker, "ha-read-primary.json", "topology.json"))),
                                derivation(null))
                        .topology()
                        .nodes()
                        .stream()
                        .flatMap(n -> n.endpoints().stream())
                        .map(NodeEndpoint::managementUrl))
                .containsExactlyInAnyOrder(SEED_URL, null);
    }

    @Test
    void thePreviewDerivesAndProvesLikeARegistrationWouldAndPersistsNothing() {
        UUID clusterId = newCluster();
        backupAnswersWithItsOwnNodeId();

        TopologyDiscovery.Preview preview =
                discovery.preview(discovery.survey(List.of(seed("topology.json"))), derivation(PATTERN));

        List<NodeEndpoint> endpoints = preview.topology().nodes().stream()
                .flatMap(n -> n.endpoints().stream())
                .toList();
        assertThat(endpoints)
                .extracting(NodeEndpoint::name, NodeEndpoint::managementUrl, NodeEndpoint::urlSource)
                .containsExactlyInAnyOrder(
                        tuple("artemis-primary:61616", SEED_URL, ManagementUrlSource.SEED),
                        tuple("artemis-backup:61616", BACKUP_URL, ManagementUrlSource.DERIVED));
        assertThat(preview.management())
                .containsEntry("artemis-primary:61616", AccountResult.ACCEPTED)
                .containsEntry("artemis-backup:61616", AccountResult.ACCEPTED);
        assertThat(nodes.findByClusterIdOrderByNameAsc(clusterId)).isEmpty();
    }

    @Test
    void thePreviewReportsAFoundNodeWhoseManagementAccountWasRejected() {
        derivedAnswers(BACKUP_URL, withStatus(HttpStatus.FORBIDDEN), null);

        TopologyDiscovery.Preview preview =
                discovery.preview(discovery.survey(List.of(seed("topology.json"))), derivation(PATTERN));

        assertThat(preview.management()).containsEntry("artemis-backup:61616", AccountResult.REJECTED);
        assertThat(preview.topology().unmanaged())
                .extracting(NodeEndpoint::name, NodeEndpoint::urlProblem)
                .containsExactly(tuple("artemis-backup:61616", ManagementUrlProblem.CREDENTIALS_REJECTED));
    }

    @Test
    void aSeedWhoseHaReadFailedInsideAnOkResponseIsNotTakenForAStoppedBroker() {
        UUID clusterId = newCluster();

        List<ProbedSeed> seeds = List.of(seed("ha-read-instance-not-found.json", "topology.json"));
        UrlDerivation derivation = derivation(PATTERN);
        assertThatThrownBy(() -> discovery.discover(clusterId, seeds, derivation))
                .isInstanceOf(BrokerConnectionException.class);

        assertThat(nodes.findByClusterIdOrderByNameAsc(clusterId)).isEmpty();
    }

    @Test
    void postFailoverTopologyWithNoBackupKeyKeepsTheBackupRow() {
        UUID clusterId = newCluster();
        discovery.discover(clusterId, List.of(seed("topology.json")), derivation(null));

        // The seed now reports a single-node topology (no "backup" key).
        discovery.discover(clusterId, List.of(seed("topology-after-failover.json")), derivation(null));

        assertThat(nodes.findByClusterIdAndName(clusterId, "artemis-backup:61616"))
                .isPresent();
    }

    @Test
    void afterAFailoverEveryUrlStaysOnItsOwnRowAndNoRowIsAdded() {
        String primaryUrl = "http://artemis-secondary:8161/console/jolokia";
        String backupUrl = "http://artemis-secondary-backup:8161/console/jolokia";
        UUID clusterId = newCluster();
        discovery.discover(
                clusterId,
                List.of(
                        seed(primaryUrl, "ha-read-primary.json", "topology-secondary-pair.json"),
                        seed(backupUrl, "ha-read-backup.json", "topology-secondary-pair.json")),
                derivation(PATTERN));

        // The backup took over and reports itself PRIMARY; the old primary is back as a backup.
        discovery.discover(
                clusterId,
                List.of(
                        seed(backupUrl, "ha-read-primary.json", "topology-secondary-after-failover.json"),
                        seed(primaryUrl, "ha-read-backup.json", "topology-secondary-after-failover.json")),
                derivation(PATTERN));

        List<BrokerNodeEntity> rows = nodes.findByClusterIdOrderByNameAsc(clusterId);
        assertThat(rows)
                .extracting(
                        BrokerNodeEntity::getName,
                        BrokerNodeEntity::getManagementUrl,
                        BrokerNodeEntity::getHaDesignation)
                .containsExactly(
                        tuple("artemis-secondary-backup:61616", backupUrl, "PRIMARY"),
                        tuple("artemis-secondary:61616", primaryUrl, "BACKUP"));
    }

    @Test
    void aDeadSeedDoesNotStopDiscoveryFromTheSeedsThatAnswered() {
        UUID clusterId = newCluster();

        discovery.discover(
                clusterId,
                List.of(deadSeed("http://artemis-backup:8161/console/jolokia"), seed("topology.json")),
                derivation(null));

        BrokerNodeEntity primary =
                nodes.findByClusterIdAndName(clusterId, "artemis-primary:61616").orElseThrow();
        assertThat(primary.getManagementUrl()).isEqualTo(SEED_URL);
        assertThat(nodes.findByClusterIdOrderByNameAsc(clusterId)).hasSize(2);
    }

    /** The primary's HA read with another broker's NodeID: what a load balancer or a recycled address answers. */
    private static ResponseCreator haReadWithNodeId(String nodeId) {
        try {
            String body = new String(
                            new ClassPathResource("jolokia/ha-read-backup.json").getContentAsByteArray(),
                            StandardCharsets.UTF_8)
                    .replace("f7734597-a768-11f1-aa4c-ceae3fa2df1d", nodeId);
            return withSuccess(body, MediaType.APPLICATION_JSON);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
