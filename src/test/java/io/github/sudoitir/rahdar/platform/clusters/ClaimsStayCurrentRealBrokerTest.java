package io.github.sudoitir.rahdar.platform.clusters;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.Designation;
import io.github.sudoitir.openbroker.model.Membership;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Serving;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.NodeOverrideRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.rahdar.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.ArtemisBrokers;
import io.github.sudoitir.rahdar.support.ArtemisIntegrationTest;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Registrations;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A cluster's claims follow its nodes (ADR-0167), against a real broker: an overridden URL moves its claim,
 * discovery claims the nodes it finds, a NodeID read that differs moves the claim, and a claim no node backs
 * any more is released by the next registration of those brokers instead of refusing it.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class ClaimsStayCurrentRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    ClusterService service;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @Autowired
    ClusterIdentityClaims clusterClaims;

    @Autowired
    NodeStateRecorder recorder;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcClient jdbc;

    private final String run = "claims-current-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void removeTheClusters() {
        clusters.findAll().stream()
                .filter(c -> c.getName().startsWith(run))
                .forEach(c -> clusters.deleteById(c.getId()));
    }

    private UUID registered(String name, String seed) {
        var attempt = service.register(Registrations.artemis(
                List.of(seed),
                run + "-" + name,
                null,
                new RegisterClusterRequest.Credentials(
                        ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                null,
                null,
                null,
                null,
                false));
        if (!(attempt instanceof Attempt.Ok<ClusterDetail> ok)) {
            throw new IllegalStateException("could not register " + seed + ": " + attempt);
        }
        return ok.value().id();
    }

    /** The cluster's claims, as {@code KIND:identity}. */
    private Set<String> claimsOf(UUID cluster) {
        return jdbc.sql("SELECT kind || ':' || identity FROM broker_identity WHERE cluster_id = ?")
                .param(cluster)
                .query(String.class)
                .set();
    }

    /** The seed's row: the one node of the cluster with a management URL. */
    private BrokerNodeEntity seedNode(UUID cluster) {
        return nodes.findByClusterIdOrderByNameAsc(cluster).stream()
                .filter(n -> n.getManagementUrl() != null)
                .findFirst()
                .orElseThrow();
    }

    private static String url(String jolokiaUrl) {
        return ClusterIdentity.URL + ":" + ClusterIdentity.normalise(jolokiaUrl);
    }

    @Test
    void anOverriddenUrlMovesTheClaimOfANodeKnownByItsUrl() {
        UUID first = registered("first", ArtemisIntegrationTest.jolokiaUrl());
        // A broker that reports no NodeID is known by its management URL alone.
        jdbc.sql("UPDATE broker_node SET native_node_id = NULL WHERE cluster_id = ?")
                .param(first)
                .update();
        new TransactionTemplate(transactions).executeWithoutResult(status -> clusterClaims.sync(first));
        assertThat(claimsOf(first)).containsExactly(url(ArtemisIntegrationTest.jolokiaUrl()));

        String moved = ArtemisBrokers.second().jolokiaUrl();
        assertThat(service.overrideNodeUrl(first, seedNode(first).getId(), new NodeOverrideRequest(moved, null)))
                .isInstanceOf(Attempt.Ok.class);

        // The old URL is free for whatever broker answers there next; the new one is claimed.
        assertThat(claimsOf(first)).containsExactly(url(moved));
    }

    @Test
    void aClaimNoNodeBacksAnyMoreIsReleasedByTheNextRegistration() {
        UUID first = registered("first", ArtemisIntegrationTest.jolokiaUrl());
        String nodeId = seedNode(first).getNativeNodeId();
        assertThat(claimsOf(first)).contains(ClusterIdentity.NODE_ID + ":" + nodeId);
        // The first cluster's nodes no longer carry the broker (its journal replaced, its URL moved), but its
        // claim was left behind: the state a claim from before claims followed their nodes would be in.
        jdbc.sql("UPDATE broker_node SET native_node_id = 'replaced-' || id WHERE cluster_id = ?")
                .param(first)
                .update();
        jdbc.sql("""
                        UPDATE broker_node_endpoint SET url = 'http://elsewhere.invalid:8161/console/jolokia'
                        WHERE plane = 'MANAGEMENT' AND node_id IN (SELECT id FROM broker_node WHERE cluster_id = ?)
                        """).param(first).update();

        UUID second = registered("second", ArtemisIntegrationTest.jolokiaUrl());

        assertThat(claimsOf(second)).contains(ClusterIdentity.NODE_ID + ":" + nodeId);
        assertThat(claimsOf(first)).doesNotContain(ClusterIdentity.NODE_ID + ":" + nodeId);
    }

    @Test
    void discoveryClaimsTheNodesItFinds() {
        UUID first = registered("first", ArtemisIntegrationTest.jolokiaUrl());
        Set<String> registeredClaims = claimsOf(first);
        // As if the nodes had been found after registration, with nothing claimed for them yet.
        jdbc.sql("DELETE FROM broker_identity WHERE cluster_id = ?")
                .param(first)
                .update();

        service.rediscover(first);

        assertThat(claimsOf(first)).isNotEmpty().isEqualTo(registeredClaims);
    }

    @Test
    void aNodeIdThatChangesMovesTheClaim() {
        UUID first = registered("first", ArtemisIntegrationTest.jolokiaUrl());
        String replaced = run + "-new-journal";

        UUID node = seedNode(first).getId();
        recorder.applyTierA(
                node,
                new NodeDescription(
                        BrokerGateway.idOf(node),
                        replaced,
                        new Membership(Optional.empty(), Designation.NONE, Serving.ACTIVE, Optional.empty()),
                        Set.of(NodeFunction.BROKER),
                        List.of(),
                        Optional.empty(),
                        List.of(new Condition(ConditionTypes.STARTED, ConditionStatus.TRUE, "", "", Optional.empty())),
                        Attributes.empty(),
                        Optional.empty()),
                1L);

        assertThat(claimsOf(first)).contains(ClusterIdentity.NODE_ID + ":" + replaced);
    }
}
