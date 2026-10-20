package io.github.sudoitir.rahdar.feature.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.queues.LifecycleRequests.CreateDivertRequest;
import io.github.sudoitir.rahdar.feature.queues.QueueLifecycleService;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.DivertView;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.NodeRef;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Targets;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
import io.github.sudoitir.rahdar.system.artemis.DivertOperations;
import io.github.sudoitir.rahdar.system.artemis.DivertRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * What the routing view claims, and what it refuses to claim.
 *
 * <p>The broker layer is mocked: what is under test is the merge across nodes, the
 * ownership attribution drawn from Rahdar's own audit trail, and the absence of any
 * origin claim (ADR-0065 D2) — none of which is Jolokia's business.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class RoutingServiceTest extends PostgresIntegrationTest {

    private static final String NODE_A_URL = "http://a:8161/console/jolokia";
    private static final String NODE_B_URL = "http://b:8161/console/jolokia";

    @Autowired
    RoutingService routing;

    @Autowired
    QueueLifecycleService lifecycle;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    ArtemisNodes artemisNodes;

    @MockitoBean
    BrokerGateway gateway;

    @MockitoBean
    DivertOperations divertOps;

    private UUID clusterId;
    private UUID nodeA;
    private UUID nodeB;

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        nodeA = node("node-a", NODE_A_URL);
        nodeB = node("node-b", NODE_B_URL);

        ArtemisNode client = mock(ArtemisNode.class);
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
        when(artemisNodes.on(eq(clusterId), any(UUID.class))).thenReturn(client);
        when(artemisNodes.on(any(BrokerTarget.class))).thenReturn(client);
        BrokerTarget anyNode = Targets.target();
        when(gateway.node(any(UUID.class), any(UUID.class))).thenReturn(anyNode);
        when(divertOps.addressAvailable(any(), any(), any())).thenReturn(true);
        when(divertOps.createVerified(any(), any(), any())).thenReturn(LifecycleOutcome.NodeStatus.APPLIED);
    }

    private UUID node(String name, String url) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                clusterId, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachSeedUrl(url);
        n.applyHaState(new HaObservation(true, "STARTED", "PRIMARY", null, "2.56.0", null), 1L, Instant.now());
        return nodes.save(n).getId();
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private static DivertRow divert(UUID nodeId, String nodeName, String name, String from, String to) {
        return new DivertRow(nodeId, nodeName, name, name, from, to, null, "STRIP", null, Map.of(), false, false);
    }

    private PagedView<DivertView> diverts() {
        return routing.diverts(clusterId, ResourceQuery.of(null, null, null, null));
    }

    @Test
    void oneDivertOnEveryNodeIsOneRowAttributedToBoth() {
        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(divert(nodeA, "node-a", "audit", "ORDER.IN", "AUDIT.IN")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString()))
                .thenReturn(List.of(divert(nodeB, "node-b", "audit", "ORDER.IN", "AUDIT.IN")));

        List<DivertView> rows = diverts().data();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).nodesPresent()).isEqualTo(2);
        assertThat(rows.get(0).nodesTotal()).isEqualTo(2);
        assertThat(rows.get(0).perNode()).extracting(NodeRef::nodeName).containsExactly("node-a", "node-b");
    }

    /**
     * The same name pointing somewhere else on one node is a divergence, and the
     * whole reason an operator opens this view. Collapsing it on the name alone
     * would report one of the two shapes and hide the other.
     */
    @Test
    void theSameNameRoutingDifferentlyOnTwoNodesIsTwoRows() {
        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(divert(nodeA, "node-a", "audit", "ORDER.IN", "AUDIT.IN")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString()))
                .thenReturn(List.of(divert(nodeB, "node-b", "audit", "ORDER.IN", "ELSEWHERE.IN")));

        List<DivertView> rows = diverts().data();

        assertThat(rows)
                .hasSize(2)
                .allMatch(r -> r.nodesPresent() == 1 && r.nodesTotal() == 2)
                .extracting(DivertView::forwardingAddress)
                .containsExactlyInAnyOrder("AUDIT.IN", "ELSEWHERE.IN");
    }

    /**
     * The broker records nothing about where a divert came from, so neither does
     * Rahdar. A divert Rahdar has no record of is reported without an owner — which
     * is not the same as reporting that it came from broker configuration.
     */
    @Test
    void aDivertRahdarDidNotCreateCarriesNoOriginClaim() {
        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(divert(nodeA, "node-a", "someone-elses", "A", "B")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString())).thenReturn(List.of());

        assertThat(diverts().data().get(0).owner()).isNull();
    }

    @Test
    void aDivertRahdarCreatedIsAttributedToRahdarFromItsOwnAuditTrail() {
        lifecycle.createDivert(
                clusterId,
                new CreateDivertRequest("mine", null, "ORDER.IN", "AUDIT.IN", false, null, null, null),
                false);

        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(divert(nodeA, "node-a", "mine", "ORDER.IN", "AUDIT.IN")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString()))
                .thenReturn(List.of(divert(nodeB, "node-b", "mine", "ORDER.IN", "AUDIT.IN")));

        assertThat(diverts().data().get(0).owner()).isEqualTo("OPERATOR");
    }

    @Test
    void aDivertRahdarCreatedAndThenDeletedIsNoLongerAttributedToRahdar() {
        CreateDivertRequest request =
                new CreateDivertRequest("mine", null, "ORDER.IN", "AUDIT.IN", false, null, null, null);
        lifecycle.createDivert(clusterId, request, false);
        lifecycle.deleteDivert(clusterId, "mine", false);

        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(divert(nodeA, "node-a", "mine", "ORDER.IN", "AUDIT.IN")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString())).thenReturn(List.of());

        assertThat(diverts().data().get(0).owner()).isNull();
    }

    /** A capture tap is Rahdar's by construction, from the namespace it reserves. */
    @Test
    void aCaptureDivertIsAttributedToCaptureAndNamesItsSubscription() {
        UUID subscription = UUID.randomUUID();
        String name = RahdarNames.CAPTURE_PREFIX + "instance." + subscription;
        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(divert(nodeA, "node-a", name, "ORDER.IN", "cap")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString())).thenReturn(List.of());

        DivertView row = diverts().data().get(0);
        assertThat(row.owner()).isEqualTo("MESSAGE_CAPTURE");
        assertThat(row.captureSubscriptionId()).isEqualTo(subscription);
    }

    /** "What touches this address" has to match either end, not only the name. */
    @Test
    void filteringMatchesEitherEndOfTheDivert() {
        when(divertOps.listDiverts(any(), eq(nodeA), anyString()))
                .thenReturn(List.of(
                        divert(nodeA, "node-a", "one", "ORDER.IN", "AUDIT.IN"),
                        divert(nodeA, "node-a", "two", "PAYMENT.IN", "ORDER.IN"),
                        divert(nodeA, "node-a", "three", "PAYMENT.IN", "AUDIT.IN")));
        when(divertOps.listDiverts(any(), eq(nodeB), anyString())).thenReturn(List.of());

        List<DivertView> rows = routing.diverts(clusterId, ResourceQuery.of("ORDER.IN", null, null, null))
                .data();

        assertThat(rows).extracting(DivertView::name).containsExactlyInAnyOrder("one", "two");
    }
}
