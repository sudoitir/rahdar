package io.github.sudoitir.rahdar.feature.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.RouteNodeCell;
import io.github.sudoitir.rahdar.feature.routing.web.RoutingViews.RouteView;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.DivertSettings;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeEntity.HaObservation;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.BrokerNodeRepository;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterEntity;
import io.github.sudoitir.rahdar.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.rahdar.support.AdminAuthenticationExtension;
import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import io.github.sudoitir.rahdar.support.Targets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    DivertLifecycleService lifecycle;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerNodeRepository nodes;

    @MockitoBean
    BrokerConnections connections;

    @MockitoBean
    BrokerGateway gateway;

    @MockitoBean
    SystemExtensions extensions;

    private final DivertSettings settings = mock(DivertSettings.class);

    private UUID clusterId;
    private UUID nodeA;
    private UUID nodeB;

    @BeforeEach
    void setUp() {
        clusterId = clusters.save(new ClusterEntity("c-" + UUID.randomUUID(), null, null))
                .getId();
        nodeA = node("node-a", NODE_A_URL);
        nodeB = node("node-b", NODE_B_URL);

        when(extensions.require(any(), eq(DivertSettings.class))).thenReturn(settings);
        when(extensions.forCluster(any(), eq(DivertSettings.class))).thenReturn(Optional.of(settings));
        ClusterConnection connection = Targets.target().connection();
        when(gateway.cluster(any())).thenReturn(connection);
        when(settings.addressAvailable(any(), any())).thenReturn(true);
        nodeHolds(nodeA);
        nodeHolds(nodeB);
    }

    /** What each node reports. The forwarding address of a divert Rahdar creates is there on both. */
    private final Map<UUID, BrokerTarget> targets = new HashMap<>();

    private void nodeHolds(UUID node, Resource... held) {
        BrokerTarget target = targets.computeIfAbsent(node, n -> Targets.target());
        Resource[] all = new Resource[held.length + 1];
        System.arraycopy(held, 0, all, 0, held.length);
        all[held.length] = Targets.address("AUDIT.IN");
        Targets.holding(target, all);
        when(gateway.node(clusterId, node)).thenReturn(target);
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

    private static Resource divert(String name, String from, String to) {
        return Targets.divert(name, from, to);
    }

    private PagedView<RouteView> diverts() {
        return routing.routes(clusterId, ResourceQuery.of(null, null, null, null));
    }

    @Test
    void oneDivertOnEveryNodeIsOneRowAttributedToBoth() {
        nodeHolds(nodeA, divert("audit", "ORDER.IN", "AUDIT.IN"));
        nodeHolds(nodeB, divert("audit", "ORDER.IN", "AUDIT.IN"));

        List<RouteView> rows = diverts().data();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).nodesPresent()).isEqualTo(2);
        assertThat(rows.get(0).nodesTotal()).isEqualTo(2);
        assertThat(rows.get(0).perNode()).extracting(RouteNodeCell::nodeName).containsExactly("node-a", "node-b");
    }

    /**
     * The same name pointing somewhere else on one node is a divergence, and the
     * whole reason an operator opens this view. Collapsing it on the name alone
     * would report one of the two shapes and hide the other.
     */
    @Test
    void theSameNameRoutingDifferentlyOnTwoNodesIsTwoRows() {
        nodeHolds(nodeA, divert("audit", "ORDER.IN", "AUDIT.IN"));
        nodeHolds(nodeB, divert("audit", "ORDER.IN", "ELSEWHERE.IN"));

        List<RouteView> rows = diverts().data();

        assertThat(rows)
                .hasSize(2)
                .allMatch(r -> r.nodesPresent() == 1 && r.nodesTotal() == 2)
                .extracting(RouteView::target)
                .containsExactlyInAnyOrder(
                        Targets.id("artemis.address", "AUDIT.IN").format(),
                        Targets.id("artemis.address", "ELSEWHERE.IN").format());
    }

    /**
     * The broker records nothing about where a divert came from, so neither does
     * Rahdar. A divert Rahdar has no record of is reported without an owner — which
     * is not the same as reporting that it came from broker configuration.
     */
    @Test
    void aDivertRahdarDidNotCreateCarriesNoOriginClaim() {
        nodeHolds(nodeA, divert("someone-elses", "A", "B"));
        nodeHolds(nodeB);

        assertThat(diverts().data().get(0).owner()).isNull();
    }

    @Test
    void aDivertRahdarCreatedIsAttributedToRahdarFromItsOwnAuditTrail() {
        lifecycle.createDivert(
                clusterId,
                new CreateDivertRequest("mine", null, "ORDER.IN", "AUDIT.IN", false, null, null, null),
                false);

        nodeHolds(nodeA, divert("mine", "ORDER.IN", "AUDIT.IN"));
        nodeHolds(nodeB, divert("mine", "ORDER.IN", "AUDIT.IN"));

        assertThat(diverts().data().get(0).owner()).isEqualTo("OPERATOR");
    }

    @Test
    void aDivertRahdarCreatedAndThenDeletedIsNoLongerAttributedToRahdar() {
        CreateDivertRequest request =
                new CreateDivertRequest("mine", null, "ORDER.IN", "AUDIT.IN", false, null, null, null);
        lifecycle.createDivert(clusterId, request, false);
        lifecycle.deleteDivert(clusterId, "mine", false);

        nodeHolds(nodeA, divert("mine", "ORDER.IN", "AUDIT.IN"));
        nodeHolds(nodeB);

        assertThat(diverts().data().get(0).owner()).isNull();
    }

    /** A capture tap is Rahdar's by construction, from the namespace it reserves. */
    @Test
    void aCaptureDivertIsAttributedToCaptureAndNamesItsSubscription() {
        UUID subscription = UUID.randomUUID();
        String name = RahdarNames.CAPTURE_PREFIX + "instance." + subscription;
        nodeHolds(nodeA, divert(name, "ORDER.IN", "cap"));
        nodeHolds(nodeB);

        RouteView row = diverts().data().get(0);
        assertThat(row.owner()).isEqualTo("MESSAGE_CAPTURE");
        assertThat(row.nativeAttributes()).containsEntry("captureSubscriptionId", subscription.toString());
    }

    /** "What touches this address" has to match either end, not only the name. */
    @Test
    void filteringMatchesEitherEndOfTheDivert() {
        nodeHolds(
                nodeA,
                divert("one", "ORDER.IN", "AUDIT.IN"),
                divert("two", "PAYMENT.IN", "ORDER.IN"),
                divert("three", "PAYMENT.IN", "AUDIT.IN"));
        nodeHolds(nodeB);

        List<RouteView> rows = routing.routes(clusterId, ResourceQuery.of("ORDER.IN", null, null, null))
                .data();

        assertThat(rows).extracting(RouteView::name).containsExactlyInAnyOrder("one", "two");
    }
}
