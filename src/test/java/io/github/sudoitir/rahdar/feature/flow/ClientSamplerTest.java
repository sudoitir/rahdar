package io.github.sudoitir.rahdar.feature.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Edge;
import io.github.sudoitir.rahdar.feature.flow.ClientEdges.Kind;
import io.github.sudoitir.rahdar.feature.flow.ClientSampler.NodeResult;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.NodeSample;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.Route;
import io.github.sudoitir.rahdar.feature.flow.FlowStore.RouteKind;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.kernel.stream.SseHub;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnections;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowMember;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowRoute;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowSample;
import io.github.sudoitir.rahdar.platform.broker.SystemExtensions;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The sampler against a stubbed {@link FlowSampler}: what one node's sample becomes, and what a sweep
 * persists, rates and signals. How a system's own answer becomes a sample is that system's test.
 */
class ClientSamplerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final FlowStore store = mock(FlowStore.class);
    private final ClusterDirectory directory = mock(ClusterDirectory.class);
    private final BrokerConnections connections = mock(BrokerConnections.class);
    private final FlowSampler flowSampler = mock(FlowSampler.class);
    private final SystemExtensions extensions = mock(SystemExtensions.class);
    private final ClusterLock lock = mock(ClusterLock.class);
    private final ClusterOwnership ownership = mock(ClusterOwnership.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final SseHub hub = mock(SseHub.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(T0);
    private final UUID clusterId = UUID.randomUUID();

    private ClientSampler sampler;

    @BeforeEach
    void wire() {
        when(settings.intValue(FlowSettings.MAX_ROWS_PER_NODE)).thenReturn(100);
        when(settings.duration(FlowSettings.SAMPLE_INTERVAL)).thenReturn(Duration.ofSeconds(30));
        when(lock.runIfHeld(eq(clusterId), eq(ClusterLock.Scope.FLOW_SAMPLE), any(Runnable.class)))
                .thenAnswer(inv -> {
                    ((Runnable) inv.getArgument(2)).run();
                    return true;
                });
        when(ownership.owns(clusterId)).thenReturn(true);
        when(extensions.require(any(), eq(FlowSampler.class))).thenReturn(flowSampler);
        sampler = new ClientSampler(
                store, directory, connections, lock, ownership, settings, hub, meters, clock, extensions);
    }

    // ---- fixtures ------------------------------------------------------

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        void advance(Duration by) {
            now.updateAndGet(i -> i.plus(by));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static ClusterNode node(UUID id, String name, String jolokiaUrl, String nativeNodeId, Boolean active) {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(id);
        when(node.getName()).thenReturn(name);
        when(node.getManagementUrl()).thenReturn(jolokiaUrl);
        when(node.getNativeNodeId()).thenReturn(nativeNodeId);
        when(node.getActive()).thenReturn(active);
        return node;
    }

    private void serving(ClusterNode... nodes) {
        when(directory.nodes(clusterId)).thenReturn(List.of(nodes));
    }

    private static FlowMember producer(int id, long sent) {
        return new FlowMember(id + "@s" + id, "app", "u", "/10.0.0.1:5000", "CORE", "orders", null, sent, 0);
    }

    private static FlowMember consumer(int id, long acked) {
        return new FlowMember(id + "@c" + id, "app", "u", "/10.0.0.1:5001", "CORE", "orders", "orders", acked, 1);
    }

    private static FlowRoute bridge(String name, long acknowledged) {
        return new FlowRoute("BRIDGE", name, "q", "fa", "x", null, false, true, acknowledged);
    }

    /** A sample whose totals are the rows it carries, with the error the broker's answer carried, if any. */
    private static FlowSample sampleOf(
            List<FlowMember> producers, List<FlowMember> consumers, List<FlowRoute> routes, String errorKind) {
        return new FlowSample(
                producers,
                producers.size(),
                consumers,
                consumers.size(),
                routes,
                errorKind == null ? null : "the broker said so",
                errorKind);
    }

    private static FlowSample nothing() {
        return sampleOf(List.of(), List.of(), List.of(), null);
    }

    private NodeResult sampled(FlowSample sample) {
        ClusterNode node = node(UUID.randomUUID(), "n1", "http://n1/jolokia", "N1", true);
        when(flowSampler.sample(eq(clusterId), any(UUID.class), anyInt())).thenReturn(sample);
        return sampler.sampleNode(clusterId, node, 100);
    }

    // ---- sampleNode ----------------------------------------------------

    private NodeResult oneHealthyPost() {
        return sampled(sampleOf(
                List.of(producer(1, 5)),
                List.of(consumer(2, 3)),
                List.of(
                        new FlowRoute("DIVERT", "d1", "a", "b", "f", "T", true, true, 0),
                        bridge("br", 7),
                        new FlowRoute(
                                "STORE_AND_FORWARD",
                                "$.sf.my-cluster.node-b",
                                "sf-addr",
                                "node-b",
                                null,
                                null,
                                false,
                                true,
                                11),
                        new FlowRoute("TEMPORARY_QUEUE", "tmp-1", "tmp-addr", "tmp-1", null, null, false, true, 0),
                        new FlowRoute("QUEUE_FILTER", "f1", "fa", "f1", "color='red'", null, false, true, 0),
                        new FlowRoute("DEAD_LETTER", "#", "#", "DLQ", null, null, false, true, 0)),
                null));
    }

    @Test
    void oneHealthyPostIsCountedWithoutError() {
        NodeResult result = oneHealthyPost();

        NodeSample sample = result.sample();
        assertThat(sample.errorKind()).isNull();
        assertThat(sample.error()).isNull();
        assertThat(sample.producersSeen()).isEqualTo(1);
        assertThat(sample.producersTotal()).isEqualTo(1);
        assertThat(sample.consumersSeen()).isEqualTo(1);
        assertThat(result.partial()).isTrue();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void oneHealthyPostBecomesMembersWithTheirReadings() {
        NodeResult result = oneHealthyPost();

        assertThat(result.members()).hasSize(2);
        assertThat(result.members().get(0).kind()).isEqualTo(Kind.PRODUCE);
        assertThat(result.members().get(0).user()).isEqualTo("u");
        assertThat(result.members().get(0).subscription()).isNull();
        assertThat(result.members().get(1).kind()).isEqualTo(Kind.CONSUME);
        assertThat(result.members().get(1).subscription()).isEqualTo("orders");
        assertThat(result.producerReadings()).singleElement().satisfies(r -> {
            assertThat(r.memberId()).isEqualTo("1@s1");
            assertThat(r.counter()).isEqualTo(5);
        });
        assertThat(result.consumerReadings()).singleElement().satisfies(r -> {
            assertThat(r.counter()).isEqualTo(3);
            assertThat(r.unacked()).isEqualTo(1);
        });
    }

    @Test
    void oneHealthyPostBecomesEveryKindOfRoute() {
        NodeResult result = oneHealthyPost();

        assertThat(result.routes())
                .extracting(Route::kind)
                .containsExactly(
                        RouteKind.DIVERT,
                        RouteKind.BRIDGE,
                        RouteKind.STORE_AND_FORWARD,
                        RouteKind.TEMPORARY_QUEUE,
                        RouteKind.QUEUE_FILTER,
                        RouteKind.DEAD_LETTER);
        Route divert = result.routes().get(0);
        assertThat(divert.name()).isEqualTo("d1");
        assertThat(divert.exclusive()).isTrue();
        assertThat(divert.transformer()).isEqualTo("T");
        Route bridge = result.routes().get(1);
        assertThat(bridge.connected()).isTrue();
        assertThat(bridge.counter()).isEqualTo(7);
        assertThat(result.routes().get(2).target()).isEqualTo("node-b");
        assertThat(result.routes().get(2).counter()).isEqualTo(11);
        assertThat(result.routes().get(3).name()).isEqualTo("tmp-1");
        assertThat(result.routes().get(4).filter()).isEqualTo("color='red'");
        assertThat(result.routes().get(5).target()).isEqualTo("DLQ");
    }

    @Test
    void aSampleThatFailedWithNothingReadIsNotPartial() {
        for (String kind : List.of(FlowSampler.PERMISSION_DENIED, FlowSampler.BAD_RESPONSE)) {
            NodeResult result = sampled(sampleOf(List.of(), List.of(), List.of(), kind));

            assertThat(result.sample().errorKind()).isEqualTo(kind);
            assertThat(result.partial())
                    .as("nothing was listed, nothing to keep")
                    .isFalse();
            assertThat(result.members()).isEmpty();
        }
    }

    @Test
    void aFailureAfterSomeRowsWereReadKeepsThem() {
        NodeResult result = sampled(sampleOf(List.of(producer(1, 1)), List.of(), List.of(), FlowSampler.BAD_RESPONSE));

        assertThat(result.sample().errorKind()).isEqualTo(FlowSampler.BAD_RESPONSE);
        assertThat(result.sample().error()).isEqualTo("the broker said so");
        assertThat(result.partial()).isTrue();
        assertThat(result.members()).hasSize(1);
    }

    @Test
    void aMissingCounterOrRoutingIsReportedButTheRowsAreStillUsable() {
        for (String kind : List.of(FlowSampler.COUNTER_UNAVAILABLE, FlowSampler.ROUTING_UNAVAILABLE)) {
            NodeResult result = sampled(sampleOf(List.of(producer(1, 1)), List.of(), List.of(), kind));

            assertThat(result.sample().errorKind()).isEqualTo(kind);
            assertThat(result.partial()).isTrue();
            assertThat(result.members()).hasSize(1);
            assertThat(result.routes()).isEmpty();
        }
    }

    @Test
    void aTotalAboveTheRowsReturnedIsTruncation() {
        NodeResult producersOver = sampled(
                new FlowSample(List.of(producer(1, 1)), 250, List.of(consumer(2, 1)), 1, List.of(), null, null));

        assertThat(producersOver.truncated()).isTrue();
        assertThat(producersOver.sample().producersSeen()).isEqualTo(1);
        assertThat(producersOver.sample().producersTotal()).isEqualTo(250);

        NodeResult consumersOver =
                sampled(new FlowSample(List.of(), 0, List.of(consumer(2, 1)), 9, List.of(), null, null));
        assertThat(consumersOver.truncated()).isTrue();
    }

    @Test
    void aConnectionFailureNamesItsKindAndARuntimeFailureIsABadResponse() {
        ClusterNode node = node(UUID.randomUUID(), "n1", "http://n1/jolokia", "N1", true);
        when(flowSampler.sample(eq(clusterId), any(UUID.class), anyInt()))
                .thenThrow(BrokerConnectionException.of(BrokerConnectionException.Kind.UNREACHABLE));
        NodeResult unreachable = sampler.sampleNode(clusterId, node, 100);
        assertThat(unreachable.sample().errorKind()).isEqualTo("UNREACHABLE");
        assertThat(unreachable.sample().error()).isEqualTo(BrokerConnectionException.Kind.UNREACHABLE.defaultMessage());
        assertThat(unreachable.partial()).isFalse();

        when(flowSampler.sample(eq(clusterId), any(UUID.class), anyInt()))
                .thenThrow(new IllegalStateException("garbled"));
        NodeResult garbled = sampler.sampleNode(clusterId, node, 100);
        assertThat(garbled.sample().errorKind()).isEqualTo("BAD_RESPONSE");
        assertThat(garbled.sample().error()).isEqualTo("garbled");
    }

    // ---- sweep ---------------------------------------------------------

    private FlowSample stable() {
        return sampleOf(List.of(producer(1, 100)), List.of(consumer(2, 50)), List.of(bridge("br", 7)), null);
    }

    @Test
    void aSweepPersistsEachServingNodeOnceAndRatesAppearOnTheSecondSweep() {
        UUID standby = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        UUID solo = UUID.randomUUID();
        UUID unmanaged = UUID.randomUUID();
        serving(
                node(standby, "standby", "http://standby/jolokia", "SHARED", false),
                node(active, "active", "http://active/jolokia", "SHARED", true),
                node(solo, "solo", "http://solo/jolokia", null, null),
                node(unmanaged, "unmanaged", null, "OTHER", true));
        when(flowSampler.sample(eq(clusterId), any(UUID.class), anyInt())).thenAnswer(inv -> stable());

        sampler.sweep(clusterId);
        clock.advance(Duration.ofSeconds(10));
        sampler.sweep(clusterId);

        ArgumentCaptor<NodeSample> samples = ArgumentCaptor.forClass(NodeSample.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Edge>> edges = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Route>> routes = ArgumentCaptor.forClass(List.class);
        verify(store, times(4)).persistNode(samples.capture(), edges.capture(), routes.capture());
        assertThat(samples.getAllValues())
                .extracting(NodeSample::nodeId)
                .containsOnly(active, solo)
                .doesNotContain(standby, unmanaged);

        List<Edge> firstEdges = edges.getAllValues().get(0);
        assertThat(firstEdges)
                .hasSize(2)
                .allSatisfy(e -> assertThat(e.rate()).as("measuring").isNull());
        List<Edge> secondEdges = edges.getAllValues().get(2);
        assertThat(secondEdges)
                .filteredOn(e -> e.kind() == Kind.PRODUCE)
                .singleElement()
                .satisfies(e -> assertThat(e.rate()).isZero());
        assertThat(routes.getAllValues().get(0))
                .singleElement()
                .satisfies(r -> assertThat(r.rate()).isNull());
        assertThat(routes.getAllValues().get(2))
                .singleElement()
                .satisfies(r -> assertThat(r.rate()).isZero());

        assertThat(meters.get("rahdar.flow.sample.rows").counter().count()).isEqualTo(8);
        assertThat(meters.find("rahdar.flow.sample.truncated").counter()).isNull();
        assertThat(meters.get("rahdar.flow.sample.duration").timer().count()).isEqualTo(2);
    }

    @Test
    void aSweepSignalsOnlyWhenWhatWasPersistedChanged() {
        serving(node(UUID.randomUUID(), "n", "http://n/j", "N", true));
        when(flowSampler.sample(eq(clusterId), any(UUID.class), anyInt())).thenAnswer(inv -> stable());

        sampler.sweep(clusterId); // first: signals
        clock.advance(Duration.ofSeconds(10));
        sampler.sweep(clusterId); // rates go from unknown to zero: signals
        clock.advance(Duration.ofSeconds(10));
        sampler.sweep(clusterId); // identical: silent

        verify(hub, times(2)).publish(clusterId, FlowModule.TOPIC);
    }

    @Test
    void anUnusableNodeIsPersistedWithItsErrorAndNoEdgesOrRoutes() {
        serving(node(UUID.randomUUID(), "n", "http://n/j", "N", true));
        when(flowSampler.sample(eq(clusterId), any(UUID.class), anyInt()))
                .thenThrow(BrokerConnectionException.of(BrokerConnectionException.Kind.CREDENTIALS_REJECTED));

        sampler.sweep(clusterId);

        ArgumentCaptor<NodeSample> sample = ArgumentCaptor.forClass(NodeSample.class);
        verify(store).persistNode(sample.capture(), eq(List.of()), eq(List.of()));
        assertThat(sample.getValue().errorKind()).isEqualTo("CREDENTIALS_REJECTED");
    }

    @Test
    void aTruncatedNodeIsCounted() {
        serving(node(UUID.randomUUID(), "n", "http://n/j", "N", true));
        when(flowSampler.sample(eq(clusterId), any(UUID.class), anyInt()))
                .thenReturn(new FlowSample(List.of(producer(1, 1)), 500, List.of(), 0, List.of(), null, null));

        sampler.sweep(clusterId);

        assertThat(meters.get("rahdar.flow.sample.truncated").counter().count()).isEqualTo(1);
    }

    @Test
    void theRowCapIsAtLeastOne() {
        when(settings.intValue(FlowSettings.MAX_ROWS_PER_NODE)).thenReturn(0);
        ClusterNode node = node(UUID.randomUUID(), "n", "http://n/j", "N", true);
        serving(node);
        when(flowSampler.sample(eq(clusterId), any(UUID.class), anyInt())).thenReturn(nothing());

        sampler.sweep(clusterId);

        verify(flowSampler).sample(eq(clusterId), any(UUID.class), eq(1));
    }

    // ---- sweepObserved -------------------------------------------------

    @Test
    void withNoObservedClusterTheSweepIsSkippedAndStaleStateForgotten() {
        when(store.observedClusters(any())).thenReturn(Set.of());

        sampler.sweepObserved();

        verify(store).forgetUnobserved(T0.minus(Duration.ofSeconds(90)));
        assertThat(meters.get("rahdar.flow.sample.skipped")
                        .tag("reason", "no-lease")
                        .counter()
                        .count())
                .isEqualTo(1);
        verify(lock, never()).runIfHeld(any(), any(), any(Runnable.class));
    }

    @Test
    void anObservedClusterIsSweptUnderItsLock() {
        when(store.observedClusters(any())).thenReturn(Set.of(clusterId));
        when(directory.nodes(clusterId)).thenReturn(List.of());

        sampler.sweepObserved();

        verify(directory).nodes(clusterId);
        verify(lock).runIfHeld(eq(clusterId), eq(ClusterLock.Scope.FLOW_SAMPLE), any(Runnable.class));
    }

    @Test
    void aClusterThisReplicaDoesNotOwnIsNotSwept() {
        UUID other = UUID.randomUUID();
        when(store.observedClusters(any())).thenReturn(Set.of(clusterId, other));

        sampler.sweepObserved();

        verify(lock).runIfHeld(eq(clusterId), eq(ClusterLock.Scope.FLOW_SAMPLE), any(Runnable.class));
        verify(lock, never()).runIfHeld(eq(other), any(), any(Runnable.class));
    }

    @Test
    void aClusterAnotherInstanceHoldsIsSkippedAndItsBaselineDropped() {
        when(store.observedClusters(any())).thenReturn(Set.of(clusterId));
        when(lock.runIfHeld(eq(clusterId), eq(ClusterLock.Scope.FLOW_SAMPLE), any(Runnable.class)))
                .thenReturn(false);

        sampler.sweepObserved();

        assertThat(meters.get("rahdar.flow.sample.skipped")
                        .tag("reason", "lock-held")
                        .counter()
                        .count())
                .isEqualTo(1);
        verify(directory, never()).nodes(any());
    }

    @Test
    void aSweepThatThrowsIsLoggedAndDoesNotEscapeOrBlockTheNextOne() {
        when(store.observedClusters(any())).thenReturn(Set.of(clusterId));
        when(lock.runIfHeld(eq(clusterId), eq(ClusterLock.Scope.FLOW_SAMPLE), any(Runnable.class)))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(false);

        sampler.sweepObserved();
        sampler.sweepObserved();

        assertThat(meters.get("rahdar.flow.sample.skipped")
                        .tag("reason", "lock-held")
                        .counter()
                        .count())
                .as("the running marker was released after the failure")
                .isEqualTo(1);
    }

    @Test
    void aSweepStillRunningSkipsTheNextOneForTheSameCluster() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(store.observedClusters(any())).thenReturn(Set.of(clusterId));
        when(lock.runIfHeld(eq(clusterId), eq(ClusterLock.Scope.FLOW_SAMPLE), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inside.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return false;
                });
        List<Throwable> failures = new ArrayList<>();
        Thread first = Thread.ofVirtual().start(() -> {
            try {
                sampler.sweepObserved();
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        sampler.sweepObserved();
        release.countDown();
        first.join();

        assertThat(failures).isEmpty();
        assertThat(meters.get("rahdar.flow.sample.skipped")
                        .tag("reason", "overlap")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
