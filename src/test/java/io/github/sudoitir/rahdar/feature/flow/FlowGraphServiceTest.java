package io.github.sudoitir.rahdar.feature.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Edge;
import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Kind;
import io.github.sudoitir.rahdar.feature.flow.FlowQuery.GroupBy;
import io.github.sudoitir.rahdar.feature.flow.FlowQuery.Rank;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.NodeSample;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.StoredEdge;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.Delivery;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.EdgeKind;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.Fault;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowEdgeView;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowGraphView;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.FlowNodeView;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.NodeKind;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.NodeRole;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.NodeSampleState;
import io.github.sudoitir.rahdar.feature.flow.web.FlowViews.RateSource;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceFilter;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.scrape.MetricSamples;
import io.github.sudoitir.rahdar.platform.scrape.MetricSamples.SubjectRate;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshots;
import io.github.sudoitir.rahdar.platform.scrape.ScrapeSettings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FlowGraphServiceTest {

    private final Instant now = Instant.parse("2026-09-14T10:00:00Z");
    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeA = UUID.randomUUID();

    private final FlowStore store = mock(FlowStore.class);
    private final QueueSnapshots snapshots = mock(QueueSnapshots.class);
    private final MetricSamples metrics = mock(MetricSamples.class);
    private final ClusterDirectory directory = mock(ClusterDirectory.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final FlowDemand demand = mock(FlowDemand.class);
    private final ClusterAccessGuard access = mock(ClusterAccessGuard.class);

    private final List<QueueSnapshot> queues = new ArrayList<>();
    private final List<StoredEdge> edges = new ArrayList<>();
    private final List<NodeSample> samples = new ArrayList<>();
    private final List<FlowStore.StoredRoute> routes = new ArrayList<>();
    private final UUID nodeB = UUID.randomUUID();
    private final Map<String, SubjectRate> added = new HashMap<>();
    private final Map<String, SubjectRate> acked = new HashMap<>();

    private FlowGraphService service;

    @BeforeEach
    void wire() {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(nodeA);
        when(node.getName()).thenReturn("node-a");
        when(directory.nodes(clusterId)).thenReturn(List.of(node));
        when(settings.duration(FlowSettings.SAMPLE_INTERVAL)).thenReturn(Duration.ofSeconds(15));
        when(settings.duration(ScrapeSettings.TIER_C)).thenReturn(Duration.ofMinutes(5));
        when(snapshots.forCluster(clusterId)).thenReturn(queues);
        when(store.edges(clusterId)).thenReturn(edges);
        when(store.nodeSamples(clusterId)).thenReturn(samples);
        when(store.routes(clusterId)).thenReturn(routes);
        when(metrics.latestRateWithTimeBySubject(eq(clusterId), eq("openbroker.enqueued"), any(), any()))
                .thenReturn(added);
        when(metrics.latestRateWithTimeBySubject(eq(clusterId), eq("openbroker.acknowledged"), any(), any()))
                .thenReturn(acked);
        ResourceFilter everything = mock(ResourceFilter.class);
        when(everything.everything()).thenReturn(true);
        when(everything.readable(any())).thenReturn(true);
        PermissionResolver permissions = mock(PermissionResolver.class);
        when(permissions.filter(eq(clusterId), any())).thenReturn(everything);
        service = new FlowGraphService(
                store,
                snapshots,
                metrics,
                directory,
                settings,
                demand,
                access,
                permissions,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void aReadChecksPermissionAndCountsTheClusterAsObserved() {
        service.graph(clusterId, query(null, 40));

        verify(access).requireVisible(clusterId);
        verify(demand).renew(clusterId);
    }

    @Test
    void aProduceRouteConsumePathIsDrawnWithDeliverySemanticsAndSampledRates() {
        queue("orders", "orders", "ANYCAST", 3, 1);
        rate(added, "orders", 42.0, Duration.ofSeconds(15));
        edge(Kind.PRODUCE, "order-svc", "orders", "", 40.0, 2, false);
        edge(Kind.CONSUME, "billing", "orders", "orders", 41.0, 3, false);
        samples.add(sample(null, 5, 5));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes())
                .extracting(FlowNodeView::id)
                .containsExactlyInAnyOrder(
                        "destination:orders", "subscription:orders", "producer:order-svc", "consumer:billing");
        FlowEdgeView route = edge(graph, EdgeKind.ROUTE);
        assertThat(route.delivery()).isEqualTo(Delivery.SHARED);
        assertThat(route.rate()).isEqualTo(42.0);
        assertThat(route.averagedOverSeconds()).isNull();
        assertThat(edge(graph, EdgeKind.CONSUME).members()).isEqualTo(3);
        assertThat(edge(graph, EdgeKind.CONSUME).source()).isEqualTo("subscription:orders");
        assertThat(edge(graph, EdgeKind.PRODUCE).target()).isEqualTo("destination:orders");
        assertThat(graph.measuring()).isFalse();
    }

    @Test
    void anUnmeasuredRateIsNullNotZeroAndASlowTierRateStatesItsAverage() {
        queue("orders", "orders", "MULTICAST", 0, 1);
        queue("audit", "orders", "MULTICAST", 0, 1);
        rate(added, "audit", 2.0, Duration.ofMinutes(5));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        FlowEdgeView orders = graph.edges().stream()
                .filter(e -> e.target().equals("subscription:orders"))
                .findFirst()
                .orElseThrow();
        FlowEdgeView audit = graph.edges().stream()
                .filter(e -> e.target().equals("subscription:audit"))
                .findFirst()
                .orElseThrow();
        assertThat(orders.rate()).isNull();
        assertThat(orders.delivery()).isEqualTo(Delivery.COPY);
        assertThat(audit.averagedOverSeconds()).isEqualTo(300);
        assertThat(graph.kpis().inRate()).isEqualTo(2.0);
        assertThat(graph.measuring()).isTrue();
    }

    @Test
    void theBusiestPathsAreShownAndTheBoundIsStated() {
        for (int i = 0; i < 50; i++) {
            queue("q" + i, "a" + i, "ANYCAST", 0, 1);
            // Spread across decades: rates inside one half-decade bucket tie and fall back to name order.
            rate(added, "q" + i, Math.pow(10, i / 5.0), Duration.ofSeconds(15));
        }

        FlowGraphView graph = service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 500, GroupBy.CLIENT_ID, null));
        FlowGraphView top = service.graph(clusterId, query(null, 3));

        assertThat(graph.totals().clamped()).isTrue();
        assertThat(graph.totals().limit()).isEqualTo(FlowQuery.MAX_LIMIT);
        assertThat(top.totals().paths()).isEqualTo(50);
        assertThat(top.totals().shown()).isEqualTo(3);
        assertThat(top.nodes()).extracting(FlowNodeView::label).contains("q49", "q48");
    }

    @Test
    void rankingIsStableForSmallRateChanges() {
        assertThat(FlowGraphService.bucket(101.0)).isEqualTo(FlowGraphService.bucket(120.0));
        assertThat(FlowGraphService.bucket(0.0)).isGreaterThan(FlowGraphService.bucket(null));
        assertThat(FlowGraphService.bucket(1000.0)).isGreaterThan(FlowGraphService.bucket(100.0));
    }

    @Test
    void aQueueWithBacklogAndNoConsumerIsAFaultCountedInTheTotals() {
        queue("dead", "dead", "ANYCAST", 1200, 0);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes())
                .filteredOn(n -> n.kind() == NodeKind.SUBSCRIPTION)
                .singleElement()
                .satisfies(n -> assertThat(n.faults()).containsExactly(Fault.NO_CONSUMER));
        assertThat(graph.kpis().faults()).isEqualTo(1);
        assertThat(graph.kpis().backlog()).isEqualTo(1200);
    }

    @Test
    void aStalledConsumerIsMarkedOnItsEdgeAndNode() {
        queue("orders", "orders", "ANYCAST", 10, 1);
        edge(Kind.CONSUME, "billing", "orders", "orders", 0.0, 1, true);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(edge(graph, EdgeKind.CONSUME).faults()).containsExactly(Fault.STALLED);
    }

    @Test
    void focusingAQueueShowsItsNeighbourhoodAndAFocusOnNothingSaysSo() {
        queue("orders", "orders", "ANYCAST", 0, 1);
        queue("refunds", "refunds", "ANYCAST", 0, 1);
        edge(Kind.CONSUME, "billing", "orders", "orders", 1.0, 1, false);
        edge(Kind.CONSUME, "billing", "refunds", "refunds", 1.0, 1, false);

        FlowGraphView one = service.graph(clusterId, query("subscription:orders", 40));
        FlowGraphView two =
                service.graph(clusterId, FlowQuery.of("subscription:orders", 2, Rank.IN, 40, GroupBy.CLIENT_ID, null));
        FlowGraphView none = service.graph(clusterId, query("subscription:gone", 40));

        assertThat(one.nodes()).isNotEmpty().extracting(FlowNodeView::id).doesNotContain("subscription:refunds");
        assertThat(two.nodes()).extracting(FlowNodeView::id).contains("subscription:refunds");
        assertThat(none.focus().matched()).isFalse();
        assertThat(none.nodes()).isEmpty();
    }

    @Test
    void groupingByHostMergesClientsFromOneHost() {
        queue("orders", "orders", "ANYCAST", 0, 2);
        edges.add(new StoredEdge(
                nodeA,
                now,
                new Edge(Kind.CONSUME, "a", "u1", "10.0.0.5", "CORE", "orders", "orders", 1.0, 0, 1, false)));
        edges.add(new StoredEdge(
                nodeA,
                now,
                new Edge(Kind.CONSUME, "b", "u2", "10.0.0.5", "AMQP", "orders", "orders", 2.0, 0, 1, false)));

        FlowGraphView byHost = service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.HOST, null));
        FlowGraphView byClient = service.graph(clusterId, query(null, 40));

        assertThat(byHost.nodes())
                .filteredOn(n -> n.kind() == NodeKind.CONSUMER)
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.label()).isEqualTo("10.0.0.5");
                    assertThat(n.members()).isEqualTo(2);
                });
        assertThat(edge(byHost, EdgeKind.CONSUME).rate()).isEqualTo(3.0);
        assertThat(byClient.nodes())
                .filteredOn(n -> n.kind() == NodeKind.CONSUMER)
                .hasSize(2);
    }

    @Test
    void aNodeThatRefusedTheListingIsNamedWithTheAccessThatGrantsIt() {
        samples.add(sample("PERMISSION_DENIED", 0, 0));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.brokerNodes()).singleElement().satisfies(n -> {
            assertThat(n.name()).isEqualTo("node-a");
            assertThat(n.state()).isEqualTo(NodeSampleState.PERMISSION_DENIED);
            assertThat(n.brokerXmlSnippet()).contains("role-access");
        });
    }

    @Test
    void aTruncatedSampleIsStated() {
        samples.add(new NodeSample(nodeA, clusterId, now, 2, 2, 5000, 12430, null, null));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.brokerNodes().getFirst().truncated()).isTrue();
    }

    @Test
    void aQueueConsumedBeforeTheSlowSweepSawItIsStillDrawn() {
        edge(Kind.CONSUME, "billing", "fresh", "fresh", null, 1, false);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes()).extracting(FlowNodeView::id).contains("subscription:fresh", "consumer:billing");
        assertThat(graph.nodes())
                .filteredOn(n -> n.id().equals("subscription:fresh"))
                .singleElement()
                .satisfies(n -> assertThat(n.messageCount()).isNull());
    }

    @Test
    void brokerInternalQueuesAreNotDrawn() {
        queue("$.artemis.internal.sf.cluster.abc", "$.artemis.internal.sf.cluster.abc", "MULTICAST", 0, 1);
        queue("activemq.notifications.rahdar", "activemq.notifications", "MULTICAST", 0, 1);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.totals().paths()).isZero();
    }

    @Test
    void anExclusiveDivertReroutesMarksTheAddressesQueuesBypassedAndStatesPartialPresence() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        samples.add(sample(null, 1, 1));
        samples.add(new NodeSample(nodeB, clusterId, now, 1, 1, 1, 1, null, null));
        route(nodeA, FlowStore.RouteKind.DIVERT, "orders-audit", "ORDERS", "AUDIT", null, true, true, null);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        FlowEdgeView divert = edge(graph, EdgeKind.FORWARD);
        assertThat(divert.source()).isEqualTo("destination:ORDERS");
        assertThat(divert.target()).isEqualTo("destination:AUDIT");
        assertThat(divert.exclusive()).isTrue();
        assertThat(divert.rateSource()).isEqualTo(RateSource.NONE);
        assertThat(divert.rate()).isNull();
        assertThat(divert.presentOn()).isEqualTo(1);
        assertThat(divert.presentOf()).isEqualTo(2);
        assertThat(divert.faults()).containsExactly(Fault.PARTIAL_PRESENCE);
        assertThat(edge(graph, EdgeKind.ROUTE).bypassed()).isTrue();
        assertThat(graph.kpis().faults()).isEqualTo(1);
    }

    @Test
    void aBridgeToAnAddressOutsideTheClusterIsRemoteAndItsOutageIsAFault() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        samples.add(sample(null, 1, 1));
        route(nodeA, FlowStore.RouteKind.BRIDGE, "to-dc2", "ORDERS.in", "dc2.orders", null, false, false, 5.0);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        FlowEdgeView bridge = edge(graph, EdgeKind.LINK);
        assertThat(bridge.target()).isEqualTo("remote:link:dc2.orders");
        assertThat(bridge.rate()).isEqualTo(5.0);
        assertThat(bridge.faults()).containsExactly(Fault.LINK_DOWN);
        assertThat(graph.nodes())
                .filteredOn(n -> n.kind() == NodeKind.REMOTE)
                .singleElement()
                .satisfies(n -> assertThat(n.role()).isEqualTo(NodeRole.LINK_TARGET));
    }

    @Test
    void aStoreAndForwardQueueHopsToTheNodeItIsNamedForOnlyWithTheClusterLayer() {
        ClusterNode a = mock(ClusterNode.class);
        when(a.getId()).thenReturn(nodeA);
        when(a.getName()).thenReturn("node-a");
        ClusterNode b = mock(ClusterNode.class);
        when(b.getId()).thenReturn(nodeB);
        when(b.getName()).thenReturn("node-b");
        when(b.getNativeNodeId()).thenReturn("abc-123");
        when(directory.nodes(clusterId)).thenReturn(List.of(a, b));
        route(
                nodeA,
                FlowStore.RouteKind.STORE_AND_FORWARD,
                "$.artemis.internal.sf.demo.abc-123",
                "$.artemis.internal.sf.demo.abc-123",
                "abc-123",
                null,
                false,
                true,
                7.0);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));
        FlowGraphView without =
                service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "ROUTES"));

        FlowEdgeView hop = edge(graph, EdgeKind.CLUSTER_HOP);
        assertThat(hop.rate()).isEqualTo(7.0);
        assertThat(graph.nodes())
                .filteredOn(n -> n.id().equals(hop.target()))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.label()).isEqualTo("node-b");
                    assertThat(n.role()).isEqualTo(NodeRole.CLUSTER_NODE);
                });
        assertThat(graph.nodes())
                .filteredOn(n -> n.id().equals(hop.source()))
                .singleElement()
                .satisfies(n -> assertThat(n.role()).isEqualTo(NodeRole.STORE_AND_FORWARD));
        assertThat(without.edges()).noneMatch(e -> e.kind() == EdgeKind.CLUSTER_HOP);
    }

    @Test
    void aClusterHopNamesThePairsServingEndpointNotItsStandby() {
        ClusterNode serving = mock(ClusterNode.class);
        when(serving.getId()).thenReturn(nodeA);
        when(serving.getName()).thenReturn("artemis-secondary");
        when(serving.getNativeNodeId()).thenReturn("pair-2");
        when(serving.getActive()).thenReturn(true);
        ClusterNode standby = mock(ClusterNode.class);
        when(standby.getId()).thenReturn(nodeB);
        when(standby.getName()).thenReturn("artemis-secondary-backup");
        when(standby.getNativeNodeId()).thenReturn("pair-2");
        when(standby.getActive()).thenReturn(false);
        // The standby sorts after its serving endpoint, which is exactly how the directory lists them.
        when(directory.nodes(clusterId)).thenReturn(List.of(serving, standby));
        route(
                nodeA,
                FlowStore.RouteKind.STORE_AND_FORWARD,
                "$.artemis.internal.sf.demo.pair-2",
                "$.artemis.internal.sf.demo.pair-2",
                "pair-2",
                null,
                false,
                true,
                3.0);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes())
                .filteredOn(n -> n.kind() == NodeKind.REMOTE)
                .singleElement()
                .satisfies(n -> assertThat(n.label()).isEqualTo("artemis-secondary"));
    }

    @Test
    void temporaryQueuesCollapseIntoOneNodePerClientOnlyWhenAsked() {
        route(nodeA, FlowStore.RouteKind.TEMPORARY_QUEUE, "tmp.1", "tmp.1", "tmp.1", null, false, true, null);
        route(nodeA, FlowStore.RouteKind.TEMPORARY_QUEUE, "tmp.2", "tmp.2", "tmp.2", null, false, true, null);
        edge(Kind.CONSUME, "rpc-client", "tmp.1", "tmp.1", 1.0, 1, false);
        edge(Kind.CONSUME, "rpc-client", "tmp.2", "tmp.2", 2.0, 1, false);

        FlowGraphView hidden = service.graph(clusterId, query(null, 40));
        FlowGraphView shown =
                service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "TEMPORARY"));

        assertThat(hidden.nodes()).isEmpty();
        assertThat(shown.nodes())
                .filteredOn(n -> n.kind() == NodeKind.SUBSCRIPTION)
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.label()).isEqualTo("temporary queues ×2");
                    assertThat(n.role()).isEqualTo(NodeRole.TEMPORARY);
                });
        assertThat(edge(shown, EdgeKind.CONSUME).rate()).isEqualTo(3.0);
    }

    @Test
    void aWildcardSubscriptionIsJoinedToTheAddressesItMatchesAndSaysWhatItAssumes() {
        queue("orders.all", "orders.#", "MULTICAST", 0, 1);
        edge(Kind.PRODUCE, "shop", "orders.eu", "", 3.0, 1, false);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        FlowEdgeView wildcard = edge(graph, EdgeKind.WILDCARD);
        assertThat(wildcard.source()).isEqualTo("destination:orders.eu");
        assertThat(wildcard.target()).isEqualTo("destination:orders.#");
        assertThat(graph.assumptions()).containsExactly(FlowGraphService.WILDCARD_ASSUMPTION);
    }

    @Test
    void anAnonymousProducerIsDrawnToItsOwnNodeRatherThanOmitted() {
        edge(Kind.PRODUCE, "legacy-app", "", "", 4.0, 1, false);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes())
                .filteredOn(n -> n.kind() == NodeKind.DESTINATION)
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.role()).isEqualTo(NodeRole.ANONYMOUS);
                    assertThat(n.label()).contains("chosen per message");
                });
        assertThat(edge(graph, EdgeKind.PRODUCE).rate()).isEqualTo(4.0);
    }

    @Test
    void rahdarCaptureTapsAreHiddenUnlessTheirLayerIsOn() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        queue("rahdar.capture.x.q", "rahdar.capture.x.q", "ANYCAST", 0, 1);
        route(
                nodeA,
                FlowStore.RouteKind.DIVERT,
                "rahdar.capture.x",
                "ORDERS",
                "rahdar.capture.x.q",
                null,
                false,
                true,
                null);

        FlowGraphView hidden = service.graph(clusterId, query(null, 40));
        FlowGraphView shown =
                service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "ROUTES,CAPTURE"));

        assertThat(hidden.nodes()).noneMatch(n -> n.label().startsWith("rahdar.capture"));
        assertThat(hidden.edges()).noneMatch(e -> e.kind() == EdgeKind.FORWARD);
        assertThat(edge(shown, EdgeKind.FORWARD).rahdar()).isTrue();
    }

    @Test
    void aFilteredQueueCarriesItsFilterOnItsRoute() {
        queue("ORDERS.red", "ORDERS", "MULTICAST", 0, 1);
        route(
                nodeA,
                FlowStore.RouteKind.QUEUE_FILTER,
                "ORDERS.red",
                "ORDERS",
                "ORDERS.red",
                "color='red'",
                false,
                true,
                null);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(edge(graph, EdgeKind.ROUTE).filter()).isEqualTo("color='red'");
    }

    @Test
    void deadLetterRoutesAreDrawnOnlyWithTheirLayer() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        route(nodeA, FlowStore.RouteKind.DEAD_LETTER, "#", "#", "DLQ", null, false, true, null);

        FlowGraphView hidden = service.graph(clusterId, query(null, 40));
        FlowGraphView shown =
                service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "DEAD_LETTER"));

        assertThat(hidden.edges()).noneMatch(e -> e.kind() == EdgeKind.DEAD_LETTER);
        FlowEdgeView dla = edge(shown, EdgeKind.DEAD_LETTER);
        assertThat(dla.target()).isEqualTo("destination:DLQ");
        assertThat(shown.nodes())
                .filteredOn(n -> n.id().equals("destination:DLQ"))
                .singleElement()
                .satisfies(n -> assertThat(n.role()).isEqualTo(NodeRole.DEAD_LETTER));
    }

    @Test
    void anUnknownLayerIsRefusedAndBlankMeansTheDefaults() {
        assertThatThrownBy(() -> FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "routes,bogus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
        assertThat(FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, " ").layers())
                .isEqualTo(FlowQuery.DEFAULT_LAYERS);
    }

    @Test
    void aNodeWhoseRoutingCouldNotBeReadSaysSoWhileItsClientsStayShown() {
        samples.add(new NodeSample(nodeA, clusterId, now, 1, 1, 1, 1, "divert read refused", "ROUTING_UNAVAILABLE"));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.brokerNodes()).singleElement().satisfies(n -> {
            assertThat(n.state()).isEqualTo(NodeSampleState.ROUTING_UNAVAILABLE);
            assertThat(n.message()).contains("divert read refused");
        });
    }

    private void route(
            UUID node,
            FlowStore.RouteKind kind,
            String name,
            String source,
            String target,
            String filter,
            boolean exclusive,
            boolean connected,
            Double rate) {
        routes.add(new FlowStore.StoredRoute(
                node,
                now.minusSeconds(5),
                new FlowStore.Route(kind, name, source, target, filter, null, exclusive, connected, 0, rate)));
    }

    private FlowQuery query(String focus, int limit) {
        return FlowQuery.of(focus, 1, Rank.IN, limit, GroupBy.CLIENT_ID, null);
    }

    @Test
    void aBreakdownIsOnlyGivenWhenAskedFor() {
        queue("orders", "orders", "ANYCAST", 3, 1);
        edge(Kind.CONSUME, "billing", "orders", "orders", 4.0, 1, false);
        samples.add(sample(null, 0, 1));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes()).allSatisfy(n -> assertThat(n.byNode()).isNull());
        assertThat(graph.edges()).allSatisfy(e -> assertThat(e.byNode()).isNull());
        assertThat(graph.brokerNodes()).allSatisfy(b -> assertThat(b.backlog()).isNull());
    }

    @Test
    void aQueueOnTwoNodesStatesEachNodesBacklogConsumersAndRatesWhichAddUpToItsTotals() {
        ClusterNode a = mock(ClusterNode.class);
        when(a.getId()).thenReturn(nodeA);
        when(a.getName()).thenReturn("node-a");
        ClusterNode b = mock(ClusterNode.class);
        when(b.getId()).thenReturn(nodeB);
        when(b.getName()).thenReturn("node-b");
        List<ClusterNode> both = List.of(a, b);
        when(directory.nodes(clusterId)).thenReturn(both);
        // Stranded: node-b holds the backlog and has no consumer.
        queues.add(new QueueSnapshot(
                clusterId,
                nodeA,
                "orders",
                "orders",
                "ANYCAST",
                true,
                false,
                now,
                10,
                2,
                0,
                0,
                0,
                0,
                0,
                "artemis.queue",
                Map.of()));
        queues.add(new QueueSnapshot(
                clusterId,
                nodeB,
                "orders",
                "orders",
                "ANYCAST",
                true,
                false,
                now,
                9_000,
                0,
                0,
                0,
                0,
                0,
                0,
                "artemis.queue",
                Map.of()));
        Map<String, Map<UUID, SubjectRate>> addedByNode = Map.of(
                "orders",
                Map.of(
                        nodeA, new SubjectRate(3.0, now.minusSeconds(5), Duration.ofSeconds(15)),
                        nodeB, new SubjectRate(27.0, now.minusSeconds(5), Duration.ofSeconds(15))));
        when(metrics.latestRateWithTimeBySubjectAndNode(eq(clusterId), eq("openbroker.enqueued"), any(), any()))
                .thenReturn(addedByNode);
        when(metrics.latestRateWithTimeBySubjectAndNode(eq(clusterId), eq("openbroker.acknowledged"), any(), any()))
                .thenReturn(Map.of(
                        "orders", Map.of(nodeA, new SubjectRate(3.0, now.minusSeconds(5), Duration.ofSeconds(15)))));
        samples.add(sample(null, 0, 2));
        samples.add(new NodeSample(nodeB, clusterId, now, 0, 0, 0, 0, null, null));

        FlowGraphView graph = service.graph(clusterId, query(null, 40).withByNode(true));

        FlowNodeView queue = graph.nodes().stream()
                .filter(n -> n.id().equals("subscription:orders"))
                .findFirst()
                .orElseThrow();
        assertThat(queue.byNode())
                .extracting(sh -> sh.node() + " " + sh.messageCount() + "/" + sh.consumerCount() + " in " + sh.inRate()
                        + " out " + sh.outRate())
                .containsExactly("node-a 10/2 in 3.0 out 3.0", "node-b 9000/0 in 27.0 out null");
        // The totals are the sum of the parts, derived from the same per-node read.
        assertThat(edge(graph, EdgeKind.ROUTE).rate()).isEqualTo(30.0);
        assertThat(graph.nodes().stream()
                        .filter(n -> n.id().equals("destination:orders"))
                        .findFirst()
                        .orElseThrow()
                        .byNode())
                .hasSize(2);
        assertThat(graph.brokerNodes())
                .extracting(bn -> bn.name() + " " + bn.backlog() + " " + bn.consumers())
                .containsExactly("node-a 10 2", "node-b 9000 0");
    }

    @Test
    void aClientRateOnANodeThatDidNotAnswerIsStaleInTheBreakdownNeverZero() {
        queue("orders", "orders", "ANYCAST", 3, 1);
        edge(Kind.CONSUME, "billing", "orders", "orders", 4.0, 1, false);
        samples.add(sample("UNREACHABLE", 0, 1));

        FlowGraphView graph = service.graph(clusterId, query(null, 40).withByNode(true));

        FlowEdgeView consume = edge(graph, EdgeKind.CONSUME);
        assertThat(consume.byNode()).singleElement().satisfies(r -> {
            assertThat(r.node()).isEqualTo("node-a");
            assertThat(r.rate()).isEqualTo(4.0);
            assertThat(r.stale()).isTrue();
        });
    }

    private void queue(String name, String address, String routingType, long messages, long consumers) {
        queues.add(new QueueSnapshot(
                clusterId,
                nodeA,
                name,
                address,
                routingType,
                true,
                false,
                now,
                messages,
                consumers,
                0,
                0,
                0,
                0,
                0,
                "artemis.queue",
                Map.of()));
    }

    private void rate(Map<String, SubjectRate> map, String queue, double rate, Duration span) {
        map.put(queue, new SubjectRate(rate, now.minusSeconds(5), span));
    }

    private void edge(
            Kind kind, String client, String address, String queue, Double rate, int members, boolean stalled) {
        edges.add(new StoredEdge(
                nodeA,
                now.minusSeconds(5),
                new Edge(kind, client, "artemis", "10.0.0.5", "CORE", address, queue, rate, 0, members, stalled)));
    }

    private NodeSample sample(String errorKind, int producers, int consumers) {
        return new NodeSample(nodeA, clusterId, now, producers, producers, consumers, consumers, null, errorKind);
    }

    private static FlowEdgeView edge(FlowGraphView graph, EdgeKind kind) {
        return graph.edges().stream().filter(e -> e.kind() == kind).findFirst().orElseThrow();
    }

    // ---- ranking and focus ------------------------------------------------------------------------

    private FlowQuery ranked(Rank rank, int limit) {
        return FlowQuery.of(null, 1, rank, limit, GroupBy.CLIENT_ID, null);
    }

    private static List<String> queueIds(FlowGraphView graph) {
        return graph.nodes().stream()
                .filter(n -> n.kind() == NodeKind.SUBSCRIPTION)
                .map(FlowNodeView::id)
                .toList();
    }

    @Test
    void theTopPathsAreChosenByTheRankTheOperatorAsked() {
        queue("busy-in", "a1", "ANYCAST", 1, 1);
        queue("busy-out", "a2", "ANYCAST", 1, 1);
        queue("deep", "a3", "ANYCAST", 90_000, 1);
        rate(added, "busy-in", 1000.0, Duration.ofSeconds(15));
        rate(acked, "busy-out", 1000.0, Duration.ofSeconds(15));

        assertThat(queueIds(service.graph(clusterId, ranked(Rank.IN, 1)))).containsExactly("subscription:busy-in");
        assertThat(queueIds(service.graph(clusterId, ranked(Rank.OUT, 1)))).containsExactly("subscription:busy-out");
        assertThat(queueIds(service.graph(clusterId, ranked(Rank.BACKLOG, 1)))).containsExactly("subscription:deep");
    }

    @Test
    void aQueueSeenOnlyByItsConsumerHasNoBacklogToRankBy() {
        queue("known", "a1", "ANYCAST", 5, 1);
        edge(Kind.CONSUME, "billing", "fresh", "fresh", 1.0, 1, false);

        assertThat(queueIds(service.graph(clusterId, ranked(Rank.BACKLOG, 1)))).containsExactly("subscription:known");
    }

    @Test
    void anAddressOnlyProducersReachHasNoQueueAndRanksByWhatProducersSend() {
        edge(Kind.PRODUCE, "shop", "lonely", "", 50.0, 1, false);
        queue("orders", "orders", "ANYCAST", 1, 1);
        rate(added, "orders", 5.0, Duration.ofSeconds(15));

        FlowGraphView byIn = service.graph(clusterId, ranked(Rank.IN, 1));
        assertThat(byIn.nodes())
                .extracting(FlowNodeView::id)
                .contains("destination:lonely")
                .doesNotContain("subscription:orders");

        FlowGraphView byBacklog = service.graph(clusterId, ranked(Rank.BACKLOG, 1));
        assertThat(byBacklog.nodes()).extracting(FlowNodeView::id).contains("subscription:orders");
    }

    @Test
    void storeAndForwardAndTemporaryQueuesRankByTheirOwnRateAndNeverByBacklog() {
        route(nodeA, FlowStore.RouteKind.TEMPORARY_QUEUE, "tmp.1", "tmp.1", "tmp.1", null, false, true, null);
        edge(Kind.CONSUME, "rpc", "tmp.1", "tmp.1", 9.0, 1, false);
        route(
                nodeA,
                FlowStore.RouteKind.STORE_AND_FORWARD,
                "$.artemis.internal.sf.demo.abc",
                "$.artemis.internal.sf.demo.abc",
                "abc",
                null,
                false,
                true,
                4.0);
        FlowQuery temporary = FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "TEMPORARY,CLUSTER");
        FlowQuery temporaryOut = FlowQuery.of(null, 1, Rank.OUT, 40, GroupBy.CLIENT_ID, "TEMPORARY,CLUSTER");
        FlowQuery temporaryBacklog = FlowQuery.of(null, 1, Rank.BACKLOG, 40, GroupBy.CLIENT_ID, "TEMPORARY,CLUSTER");

        assertThat(service.graph(clusterId, temporary).totals().paths()).isEqualTo(2);
        assertThat(service.graph(clusterId, temporaryOut).totals().paths()).isEqualTo(2);
        assertThat(service.graph(clusterId, temporaryBacklog).totals().paths()).isEqualTo(2);
    }

    @Test
    void focusingAnAddressOrAClientShowsWhatTouchesItAndWidensByHops() {
        queue("orders", "orders", "ANYCAST", 0, 1);
        queue("refunds", "refunds", "ANYCAST", 0, 1);
        queue("audit", "audit", "ANYCAST", 0, 1);
        edge(Kind.PRODUCE, "shop", "orders", "", 1.0, 1, false);
        edge(Kind.PRODUCE, "shop", "refunds", "", 1.0, 1, false);
        edge(Kind.CONSUME, "billing", "refunds", "refunds", 1.0, 1, false);
        edge(Kind.CONSUME, "auditor", "audit", "audit", 1.0, 1, false);

        FlowGraphView address = service.graph(clusterId, query("destination:orders", 40));
        assertThat(queueIds(address)).containsExactly("subscription:orders");

        FlowGraphView client = service.graph(clusterId, query("client:shop", 40));
        assertThat(queueIds(client)).containsExactlyInAnyOrder("subscription:orders", "subscription:refunds");

        FlowGraphView widened =
                service.graph(clusterId, FlowQuery.of("client:shop", 2, Rank.IN, 40, GroupBy.CLIENT_ID, null));
        assertThat(queueIds(widened)).containsExactlyInAnyOrder("subscription:orders", "subscription:refunds");
        assertThat(widened.nodes())
                .extracting(FlowNodeView::id)
                .contains("consumer:billing")
                .doesNotContain("consumer:auditor");

        FlowGraphView unknown = service.graph(clusterId, query("client:nobody", 40));
        assertThat(unknown.focus().matched()).isFalse();
    }

    @Test
    void aFocusWidenedFromAConsumerReachesTheProducersOfItsQueuesAddresses() {
        queue("orders", "orders", "ANYCAST", 0, 1);
        queue("other", "other", "ANYCAST", 0, 1);
        edge(Kind.CONSUME, "billing", "orders", "orders", 1.0, 1, false);
        edge(Kind.CONSUME, "billing", "other", "other", 1.0, 1, false);
        edge(Kind.PRODUCE, "shop", "other", "", 1.0, 1, false);

        FlowGraphView narrow = service.graph(clusterId, query("subscription:orders", 40));
        FlowGraphView wide =
                service.graph(clusterId, FlowQuery.of("subscription:orders", 2, Rank.IN, 40, GroupBy.CLIENT_ID, null));

        assertThat(queueIds(narrow)).containsExactly("subscription:orders");
        assertThat(queueIds(wide)).containsExactlyInAnyOrder("subscription:orders", "subscription:other");
    }

    // ---- client grouping and hiding -----------------------------------------------------------------

    @Test
    void clientsAreLabelledByTheFirstIdentityPartTheGroupingAllows() {
        Edge anonymous = new Edge(Kind.CONSUME, "", "", "", "CORE", "a", "q", null, 0, 1, false);
        Edge userOnly = new Edge(Kind.CONSUME, " ", "alice", "10.0.0.1", "CORE", "a", "q", null, 0, 1, false);
        Edge hostOnly = new Edge(Kind.CONSUME, null, null, "10.0.0.1", "CORE", "a", "q", null, 0, 1, false);

        assertThat(FlowGraphService.label(userOnly, GroupBy.CLIENT_ID)).isEqualTo("alice");
        assertThat(FlowGraphService.label(hostOnly, GroupBy.CLIENT_ID)).isEqualTo("10.0.0.1");
        assertThat(FlowGraphService.label(userOnly, GroupBy.USER)).isEqualTo("alice");
        assertThat(FlowGraphService.label(hostOnly, GroupBy.USER)).isEqualTo("10.0.0.1");
        assertThat(FlowGraphService.label(userOnly, GroupBy.HOST)).isEqualTo("10.0.0.1");
        assertThat(FlowGraphService.label(anonymous, GroupBy.CLIENT_ID))
                .isEqualTo(FlowGraphService.label(anonymous, GroupBy.USER))
                .isEqualTo(FlowGraphService.label(anonymous, GroupBy.HOST));
    }

    @Test
    void internalAndCaptureOwnedNamesAreRecognisedAndOrdinaryOnesAreNot() {
        assertThat(FlowGraphService.internal("$.artemis.internal.sf.x")).isTrue();
        assertThat(FlowGraphService.internal("activemq.notifications")).isTrue();
        assertThat(FlowGraphService.internal("activemq.management")).isFalse();
        assertThat(FlowGraphService.internal("orders")).isFalse();
        assertThat(FlowGraphService.internal(null)).isFalse();
        assertThat(FlowGraphService.captureOwned("rahdar.capture.x")).isTrue();
        assertThat(FlowGraphService.captureOwned("orders")).isFalse();
        assertThat(FlowGraphService.captureOwned(null)).isFalse();
    }

    @Test
    void clientsOfInternalOrCaptureQueuesAreHiddenUnlessCaptureIsOn() {
        queue("orders", "orders", "ANYCAST", 0, 1);
        edge(Kind.CONSUME, "tap", "rahdar.capture.x", "rahdar.capture.x.q", 1.0, 1, false);
        edge(Kind.PRODUCE, "tapper", "rahdar.capture.y", "", 1.0, 1, false);
        edge(Kind.CONSUME, "cluster", "$.artemis.internal.sf.x", "$.artemis.internal.sf.x", 1.0, 1, false);

        FlowGraphView hidden = service.graph(clusterId, query(null, 40));
        FlowGraphView shown =
                service.graph(clusterId, FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "CAPTURE"));

        assertThat(hidden.nodes())
                .isNotEmpty()
                .extracting(FlowNodeView::id)
                .doesNotContain("consumer:tap", "producer:tapper", "consumer:cluster");
        assertThat(shown.nodes()).extracting(FlowNodeView::id).doesNotContain("consumer:cluster");
        assertThat(shown.kpis().clients()).isGreaterThanOrEqualTo(2);
    }

    // ---- routing: diverts, bridges, hops, failure addresses --------------------------------------------

    @Test
    void aDivertFromAnAddressNotShownIsNotDrawnAndAFilteredExclusiveDivertBypassesNothing() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        route(nodeA, FlowStore.RouteKind.DIVERT, "elsewhere", "OTHER", "AUDIT", null, false, true, null);
        route(nodeA, FlowStore.RouteKind.DIVERT, "picky", "ORDERS", "AUDIT", "color='red'", true, true, null);
        route(nodeA, FlowStore.RouteKind.DIVERT, "copy", "ORDERS", "COPY", null, false, true, null);
        samples.add(sample(null, 1, 1));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.edges())
                .filteredOn(e -> e.kind() == EdgeKind.FORWARD)
                .extracting(FlowEdgeView::target)
                .containsExactlyInAnyOrder("destination:AUDIT", "destination:COPY");
        assertThat(edge(graph, EdgeKind.ROUTE).bypassed()).isFalse();
        assertThat(graph.edges())
                .filteredOn(e -> e.kind() == EdgeKind.FORWARD)
                .allSatisfy(e -> assertThat(e.faults()).isEmpty());
    }

    @Test
    void aBridgeWhoseSourceQueueIsNotShownIsNotDrawn() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        route(nodeA, FlowStore.RouteKind.BRIDGE, "hidden", "not.shown", "dc2", null, false, true, 1.0);
        samples.add(sample(null, 1, 1));

        assertThat(service.graph(clusterId, query(null, 40)).edges()).noneMatch(e -> e.kind() == EdgeKind.LINK);
    }

    @Test
    void aBridgeIntoAnAddressOfThisClusterDrawsThatAddressAndAHealthyOneHasNoFault() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        queue("MIRROR.in", "MIRROR", "ANYCAST", 0, 1);
        route(nodeA, FlowStore.RouteKind.BRIDGE, "mirror", "ORDERS.in", "MIRROR", null, false, true, 2.0);
        samples.add(sample(null, 1, 1));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        FlowEdgeView bridge = edge(graph, EdgeKind.LINK);
        assertThat(bridge.target()).isEqualTo("destination:MIRROR");
        assertThat(bridge.faults()).isEmpty();
        assertThat(graph.nodes()).noneMatch(n -> n.kind() == NodeKind.REMOTE);
    }

    @Test
    void aBridgeOnlyOneOfTwoSampledNodesHasIsPartiallyPresent() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        route(nodeA, FlowStore.RouteKind.BRIDGE, "to-dc2", "ORDERS.in", "dc2", null, false, true, 1.0);
        samples.add(sample(null, 1, 1));
        samples.add(new NodeSample(nodeB, clusterId, now, 1, 1, 1, 1, null, null));

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(edge(graph, EdgeKind.LINK).faults()).containsExactly(Fault.PARTIAL_PRESENCE);
        assertThat(graph.kpis().faults()).isEqualTo(1);
    }

    @Test
    void aClusterHopWhoseQueueIsNotShownIsSkippedAndAnUnknownNodeIsNamedByItsShortId() {
        route(
                nodeA,
                FlowStore.RouteKind.STORE_AND_FORWARD,
                "$.artemis.internal.sf.demo.0123456789abcdef",
                "$.artemis.internal.sf.demo.0123456789abcdef",
                "0123456789abcdef",
                null,
                false,
                true,
                1.0);
        route(
                nodeA,
                FlowStore.RouteKind.STORE_AND_FORWARD,
                "$.artemis.internal.sf.demo.short",
                "$.artemis.internal.sf.demo.short",
                "short",
                null,
                false,
                true,
                1.0);

        FlowGraphView graph = service.graph(clusterId, query(null, 40));

        assertThat(graph.nodes())
                .filteredOn(n -> n.kind() == NodeKind.REMOTE)
                .extracting(FlowNodeView::label)
                .contains("node 01234567", "node short");
    }

    @Test
    void anExpiryAddressIsDrawnWithTheDeadLetterLayerAndNotWhenNoQueueFeedsIt() {
        queue("ORDERS.in", "ORDERS", "ANYCAST", 0, 1);
        route(nodeA, FlowStore.RouteKind.EXPIRY, "#", "#", "Expired", null, false, true, null);
        FlowQuery expiry = FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "DEAD_LETTER");

        FlowGraphView graph = service.graph(clusterId, expiry);
        assertThat(graph.edges())
                .filteredOn(e -> e.target().equals("destination:Expired"))
                .hasSize(1);

        FlowGraphView blank = service.graph(clusterId, query(null, 40));
        assertThat(blank.edges()).noneMatch(e -> e.target().equals("destination:Expired"));

        queues.clear();
        assertThat(service.graph(clusterId, expiry).edges()).isEmpty();
    }

    // ---- broker node states ------------------------------------------------------------------------------

    @Test
    void everyKindOfSweepFailureIsMappedToTheStateAndMessageTheOperatorNeeds() {
        Map<String, NodeSampleState> expected = new LinkedHashMap<>();
        expected.put("TLS_FAILED", NodeSampleState.UNREACHABLE);
        expected.put("WRONG_PATH", NodeSampleState.UNREACHABLE);
        expected.put("NOT_ARTEMIS", NodeSampleState.UNREACHABLE);
        expected.put("CREDENTIALS_REJECTED", NodeSampleState.PERMISSION_DENIED);
        expected.put("COUNTER_UNAVAILABLE", NodeSampleState.COUNTER_UNAVAILABLE);
        expected.put("BAD_RESPONSE", NodeSampleState.FAILED);

        expected.forEach((kind, state) -> {
            samples.clear();
            samples.add(sample(kind, 0, 0));
            FlowGraphView graph = service.graph(clusterId, query(null, 40));
            assertThat(graph.brokerNodes()).as(kind).singleElement().satisfies(n -> {
                assertThat(n.state()).isEqualTo(state);
                assertThat(n.message()).isNotBlank();
            });
        });

        samples.clear();
        samples.add(sample("COUNTER_UNAVAILABLE", 0, 0));
        assertThat(service.graph(clusterId, query(null, 40))
                        .brokerNodes()
                        .getFirst()
                        .message())
                .contains("unavailable, not zero");
    }

    @Test
    void aFailedSweepUsesTheBrokersOwnErrorWhenItGaveOneAndAGenericLineWhenNot() {
        samples.add(new NodeSample(nodeA, clusterId, now, 0, 0, 0, 0, "disk on fire", "BAD_RESPONSE"));
        assertThat(service.graph(clusterId, query(null, 40))
                        .brokerNodes()
                        .getFirst()
                        .message())
                .isEqualTo("disk on fire");

        samples.clear();
        samples.add(new NodeSample(nodeA, clusterId, now, 0, 0, 0, 0, null, "BAD_RESPONSE"));
        assertThat(service.graph(clusterId, query(null, 40))
                        .brokerNodes()
                        .getFirst()
                        .message())
                .isEqualTo("The latest sweep of this node failed.");
    }

    // ---- per-node breakdown ---------------------------------------------------------------------------------

    @Test
    void anAddressWithTwoQueuesOnOneNodeSumsThemIntoOneShareAndANodeNoLongerListedIsNamedAsRemoved() {
        UUID gone = UUID.randomUUID();
        queue("q1", "orders", "MULTICAST", 10, 1);
        queue("q2", "orders", "MULTICAST", 5, 2);
        queues.add(new QueueSnapshot(
                clusterId,
                gone,
                "q1",
                "orders",
                "MULTICAST",
                true,
                false,
                now,
                1,
                1,
                0,
                0,
                0,
                0,
                0,
                "artemis.queue",
                Map.of()));
        when(metrics.latestRateWithTimeBySubjectAndNode(eq(clusterId), eq("openbroker.enqueued"), any(), any()))
                .thenReturn(Map.of(
                        "q1", Map.of(nodeA, new SubjectRate(1.0, now.minusSeconds(5), Duration.ofSeconds(15))),
                        "q2", Map.of(nodeA, new SubjectRate(2.0, now.minusSeconds(5), Duration.ofSeconds(15)))));
        when(metrics.latestRateWithTimeBySubjectAndNode(eq(clusterId), eq("openbroker.acknowledged"), any(), any()))
                .thenReturn(
                        Map.of("q1", Map.of(nodeA, new SubjectRate(4.0, now.minusSeconds(5), Duration.ofSeconds(15)))));
        samples.add(sample(null, 0, 3));

        FlowGraphView graph = service.graph(clusterId, query(null, 40).withByNode(true));

        FlowNodeView address = graph.nodes().stream()
                .filter(n -> n.id().equals("destination:orders"))
                .findFirst()
                .orElseThrow();
        assertThat(address.byNode())
                .filteredOn(sh -> sh.node().equals("node-a"))
                .singleElement()
                .satisfies(sh -> {
                    assertThat(sh.messageCount()).isEqualTo(15);
                    assertThat(sh.consumerCount()).isEqualTo(3);
                    assertThat(sh.inRate()).isEqualTo(3.0);
                    assertThat(sh.outRate()).isEqualTo(4.0);
                });
        assertThat(address.byNode()).anyMatch(sh -> sh.node().startsWith("removed node "));
    }

    @Test
    void aTemporaryQueueAndAQueueWithNoPerNodeReadingCarryNoBreakdown() {
        route(nodeA, FlowStore.RouteKind.TEMPORARY_QUEUE, "tmp.1", "tmp.1", "tmp.1", null, false, true, null);
        edge(Kind.CONSUME, "rpc", "tmp.1", "tmp.1", 1.0, 1, false);
        edge(Kind.CONSUME, "billing", "fresh", "fresh", 1.0, 1, false);
        FlowQuery query = FlowQuery.of(null, 1, Rank.IN, 40, GroupBy.CLIENT_ID, "TEMPORARY")
                .withByNode(true);

        FlowGraphView graph = service.graph(clusterId, query);

        assertThat(graph.nodes())
                .filteredOn(n -> n.role() == NodeRole.TEMPORARY || n.id().equals("subscription:fresh"))
                .isNotEmpty()
                .allSatisfy(n -> assertThat(n.byNode()).isNullOrEmpty());
    }

    @Test
    void aStaleClientRateInTheBreakdownIsMarkedByAgeAsWellAsByFailure() {
        queue("orders", "orders", "ANYCAST", 3, 1);
        edges.add(new StoredEdge(
                nodeA,
                now.minus(Duration.ofMinutes(10)),
                new Edge(
                        Kind.CONSUME, "billing", "artemis", "10.0.0.5", "CORE", "orders", "orders", 4.0, 0, 1, false)));
        samples.add(sample(null, 0, 1));

        FlowGraphView graph = service.graph(clusterId, query(null, 40).withByNode(true));

        FlowEdgeView consume = edge(graph, EdgeKind.CONSUME);
        assertThat(consume.stale()).isTrue();
        assertThat(consume.byNode())
                .singleElement()
                .satisfies(r -> assertThat(r.stale()).isTrue());
    }
}
