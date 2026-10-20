package io.github.sudoitir.rahdar.feature.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.artemis.ArtemisLists;
import io.github.sudoitir.openbroker.artemis.JolokiaRequest;
import io.github.sudoitir.openbroker.artemis.JolokiaResponse;
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
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterLock;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import io.github.sudoitir.rahdar.platform.clusters.ClusterOwnership;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNodes;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The sampler against a stubbed Jolokia batch: what one node's POST becomes, and how each part fails alone. */
class ClientSamplerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final FlowStore store = mock(FlowStore.class);
    private final ClusterDirectory directory = mock(ClusterDirectory.class);
    private final BrokerConnections connections = mock(BrokerConnections.class);
    ArtemisNodes artemisNodes = mock(ArtemisNodes.class);
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
        sampler = new ClientSampler(
                store, directory, connections, lock, ownership, settings, hub, meters, clock, artemisNodes);
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

    private static JsonNode json(String json) {
        return MAPPER.readTree(json);
    }

    private static JolokiaResponse ok(String json) {
        return new JolokiaResponse(200, json(json), null, null, null);
    }

    private static JolokiaResponse failed(int status, String error) {
        return new JolokiaResponse(status, null, error, null, null);
    }

    private static JolokiaResponse listing(long count, String... rows) {
        return ok("{\"count\":" + count + ",\"data\":[" + String.join(",", rows) + "]}");
    }

    private static JolokiaResponse pattern(String... attributes) {
        StringBuilder value = new StringBuilder("{");
        for (int i = 0; i < attributes.length; i++) {
            value.append(i == 0 ? "" : ",")
                    .append("\"mbean")
                    .append(i)
                    .append("\":")
                    .append(attributes[i]);
        }
        return ok(value.append('}').toString());
    }

    private static String producer(int id, Integer sent) {
        return "{\"id\":" + id + ",\"session\":\"s" + id + "\",\"clientID\":\"app\",\"user\":\"u\","
                + "\"remoteAddress\":\"/10.0.0.1:5000\",\"protocol\":\"CORE\",\"address\":\"orders\""
                + (sent == null ? "" : ",\"msgSent\":" + sent) + "}";
    }

    private static String consumer(int id, Integer acked) {
        return "{\"id\":" + id + ",\"session\":\"c" + id + "\",\"clientID\":\"app\",\"validatedUser\":\" \","
                + "\"user\":\"u\",\"remoteAddress\":\"/10.0.0.1:5001\",\"protocol\":\"CORE\","
                + "\"address\":\"orders\",\"queue\":\"orders\",\"messagesInTransit\":1"
                + (acked == null ? "" : ",\"messagesAcknowledged\":" + acked) + "}";
    }

    /** The eight entries of one node's POST, in request order, defaulting to "nothing here". */
    private static final class Batch {
        JolokiaResponse producers = listing(0);
        JolokiaResponse consumers = listing(0);
        JolokiaResponse diverts = failed(404, "no diverts");
        JolokiaResponse bridges = failed(404, "no bridges");
        JolokiaResponse storeAndForward = listing(0);
        JolokiaResponse temporary = listing(0);
        JolokiaResponse filtered = listing(0);
        JolokiaResponse addressSettings = ok("{}");

        List<JolokiaResponse> entries() {
            return List.of(
                    producers, consumers, diverts, bridges, storeAndForward, temporary, filtered, addressSettings);
        }
    }

    private ArtemisNode client(List<JolokiaResponse> entries) {
        ArtemisNode client = mock(ArtemisNode.class);
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
        when(client.batch(anyList())).thenReturn(entries);
        when(client.parsed(any())).thenAnswer(inv -> ((JolokiaResponse) inv.getArgument(0)).value());
        return client;
    }

    private static ClusterNode node(UUID id, String name, String jolokiaUrl, String artemisNodeId, Boolean active) {
        ClusterNode node = mock(ClusterNode.class);
        when(node.getId()).thenReturn(id);
        when(node.getName()).thenReturn(name);
        when(node.getJolokiaUrl()).thenReturn(jolokiaUrl);
        when(node.getArtemisNodeId()).thenReturn(artemisNodeId);
        when(node.getActive()).thenReturn(active);
        return node;
    }

    private void serving(ClusterNode... nodes) {
        List<ClusterNode> list = List.of(nodes);
        when(directory.nodes(clusterId)).thenReturn(list);
    }

    private void answers(ArtemisNode client) {
        when(artemisNodes.on(eq(clusterId), any(UUID.class))).thenReturn(client);
    }

    private NodeResult sample(Batch batch) {
        ClusterNode node = node(UUID.randomUUID(), "n1", "http://n1/jolokia", "N1", true);
        answers(client(batch.entries()));
        return sampler.sampleNode(clusterId, node, 100);
    }

    // ---- sampleNode ----------------------------------------------------

    private NodeResult oneHealthyPost() {
        Batch batch = new Batch();
        batch.producers = listing(1, producer(1, 5));
        batch.consumers = listing(1, consumer(2, 3));
        batch.diverts = pattern(
                "{\"UniqueName\":\"d1\",\"Address\":\"a\",\"ForwardingAddress\":\"b\",\"Filter\":\"f\","
                        + "\"TransformerClassName\":\"T\",\"Exclusive\":true}",
                "\"not an object\"");
        batch.bridges = pattern("{\"Name\":\"br\",\"QueueName\":\"q\",\"ForwardingAddress\":\"fa\","
                + "\"FilterString\":\"x\",\"Connected\":true,\"MessagesAcknowledged\":7}");
        batch.storeAndForward = listing(
                2,
                "{\"name\":\"$.artemis.internal.sf.my-cluster.node-b\",\"address\":\"$.artemis.internal.sf.my-cluster.node-b\",\"messagesAdded\":11}",
                "{\"name\":\"other-queue\",\"address\":\"x\"}",
                "{\"address\":\"nameless\"}");
        batch.temporary = listing(1, "{\"name\":\"tmp-1\",\"address\":\"tmp-addr\"}");
        batch.filtered = listing(
                3,
                "{\"name\":\"f1\",\"address\":\"fa\",\"filter\":\"color='red'\"}",
                "{\"name\":\"f2\",\"address\":\"fa\",\"filter\":\" \"}",
                "{\"name\":\"f3\",\"address\":\"fa\"}");
        batch.addressSettings = ok("{\"deadLetterAddress\":\"DLQ\",\"expiryAddress\":\" \"}");

        return sample(batch);
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
        assertThat(result.members().get(0).queue()).isNull();
        assertThat(result.members().get(1).kind()).isEqualTo(Kind.CONSUME);
        assertThat(result.members().get(1).user())
                .as("a blank validated user falls back")
                .isEqualTo("u");
        assertThat(result.members().get(1).queue()).isEqualTo("orders");
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
        Route sf = result.routes().get(2);
        assertThat(sf.target()).as("the receiving node id is the last segment").isEqualTo("node-b");
        assertThat(sf.counter()).isEqualTo(11);
        assertThat(result.routes().get(3).name()).isEqualTo("tmp-1");
        assertThat(result.routes().get(4).filter()).isEqualTo("color='red'");
        assertThat(result.routes().get(5).target()).isEqualTo("DLQ");
    }

    @Test
    void anIncompleteBatchIsABadResponse() {
        ClusterNode node = node(UUID.randomUUID(), "n1", "http://n1/jolokia", "N1", true);
        answers(client(List.of(ok("{}"))));

        NodeResult result = sampler.sampleNode(clusterId, node, 100);

        assertThat(result.sample().errorKind()).isEqualTo("BAD_RESPONSE");
        assertThat(result.sample().error()).isEqualTo("The broker returned an incomplete response.");
        assertThat(result.partial()).isFalse();
        assertThat(result.members()).isEmpty();
    }

    @Test
    void aFailedProducerListingIsClassifiedByStatusOrByWhatTheBrokerSaid() {
        Batch forbidden = new Batch();
        forbidden.producers = failed(403, "nope");
        assertThat(sample(forbidden).sample().errorKind()).isEqualTo("PERMISSION_DENIED");

        Batch security = new Batch();
        security.producers = failed(500, "Security exception: access denied");
        assertThat(sample(security).sample().errorKind()).isEqualTo("PERMISSION_DENIED");

        Batch permission = new Batch();
        permission.producers = failed(500, "no Permission for listProducers");
        assertThat(sample(permission).sample().errorKind()).isEqualTo("PERMISSION_DENIED");

        Batch other = new Batch();
        other.producers = failed(500, null);
        NodeResult result = sample(other);
        assertThat(result.sample().errorKind()).isEqualTo("BAD_RESPONSE");
        assertThat(result.sample().error()).isEqualTo("status 500");
        assertThat(result.partial()).as("nothing was listed, nothing to keep").isFalse();
    }

    @Test
    void aFailedConsumerListingKeepsTheProducersItDidRead() {
        Batch batch = new Batch();
        batch.producers = listing(1, producer(1, 1));
        batch.consumers = failed(500, "consumers down");

        NodeResult result = sample(batch);

        assertThat(result.sample().errorKind()).isEqualTo("BAD_RESPONSE");
        assertThat(result.sample().error()).isEqualTo("consumers down");
        assertThat(result.partial()).isTrue();
        assertThat(result.members()).hasSize(1);
    }

    @Test
    void aFailedConsumerListingWithNoRowsAnywhereIsNotPartial() {
        Batch batch = new Batch();
        batch.consumers = failed(403, "no");

        NodeResult result = sample(batch);

        assertThat(result.sample().errorKind()).isEqualTo("PERMISSION_DENIED");
        assertThat(result.partial()).isFalse();
    }

    @Test
    void aMissingCounterIsReportedButTheRowsAreStillUsable() {
        Batch noSent = new Batch();
        noSent.producers = listing(1, producer(1, null));
        NodeResult producersResult = sample(noSent);
        assertThat(producersResult.sample().errorKind()).isEqualTo("COUNTER_UNAVAILABLE");
        assertThat(producersResult.partial()).isTrue();

        Batch noAcked = new Batch();
        noAcked.consumers = listing(1, consumer(1, null));
        assertThat(sample(noAcked).sample().errorKind()).isEqualTo("COUNTER_UNAVAILABLE");
    }

    @Test
    void oneFailingRoutingEntryLosesOnlyItself() {
        Batch batch = new Batch();
        batch.producers = listing(1, producer(1, 1));
        batch.diverts = failed(500, "divert boom");
        batch.bridges = failed(500, null);
        batch.filtered = failed(500, "filter boom");
        batch.addressSettings = failed(500, "settings boom");

        NodeResult result = sample(batch);

        assertThat(result.sample().errorKind()).isEqualTo("ROUTING_UNAVAILABLE");
        assertThat(result.sample().error()).isEqualTo("divert boom");
        assertThat(result.partial()).isTrue();
        assertThat(result.members()).hasSize(1);
        assertThat(result.routes()).isEmpty();
    }

    @Test
    void anAddressSettingsFailureAloneIsAlsoARoutingError() {
        Batch batch = new Batch();
        batch.addressSettings = failed(500, null);

        NodeResult result = sample(batch);

        assertThat(result.sample().errorKind()).isEqualTo("ROUTING_UNAVAILABLE");
        assertThat(result.sample().error()).isEqualTo("status 500");
    }

    @Test
    void aPatternReadThatIsNotAnObjectIsNone() {
        Batch batch = new Batch();
        batch.diverts = ok("[]");
        batch.bridges = ok("null");

        NodeResult result = sample(batch);

        assertThat(result.sample().errorKind()).isNull();
        assertThat(result.routes()).isEmpty();
    }

    @Test
    void aCountAboveTheRowsReturnedIsTruncation() {
        Batch batch = new Batch();
        batch.producers = listing(250, producer(1, 1));
        batch.consumers = listing(1, consumer(2, 1));

        NodeResult result = sample(batch);

        assertThat(result.truncated()).isTrue();
        assertThat(result.sample().producersSeen()).isEqualTo(1);
        assertThat(result.sample().producersTotal()).isEqualTo(250);

        Batch consumersOver = new Batch();
        consumersOver.consumers = listing(9, consumer(2, 1));
        assertThat(sample(consumersOver).truncated()).isTrue();
    }

    @Test
    void aListingWithoutACountOrDataFallsBackToWhatItHas() {
        Batch batch = new Batch();
        batch.producers = ok("{\"data\":[" + producer(1, 1) + "]}");
        batch.consumers = ok("{}");

        NodeResult result = sample(batch);

        assertThat(result.sample().producersTotal()).isEqualTo(1);
        assertThat(result.sample().consumersTotal()).isZero();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void aNullParsedListingIsEmptyNotAFailure() {
        Batch batch = new Batch();
        batch.producers = ok("null");
        ClusterNode node = node(UUID.randomUUID(), "n1", "http://n1/jolokia", "N1", true);
        ArtemisNode client = client(batch.entries());
        when(client.parsed(batch.producers)).thenReturn(null);
        answers(client);

        NodeResult result = sampler.sampleNode(clusterId, node, 100);

        assertThat(result.sample().errorKind()).isNull();
        assertThat(result.sample().producersTotal()).isZero();
    }

    @Test
    void aConnectionFailureNamesItsKindAndARuntimeFailureIsABadResponse() {
        ClusterNode node = node(UUID.randomUUID(), "n1", "http://n1/jolokia", "N1", true);
        doThrow(BrokerConnectionException.of(BrokerConnectionException.Kind.UNREACHABLE))
                .when(artemisNodes)
                .on(any(UUID.class), any(UUID.class));
        NodeResult unreachable = sampler.sampleNode(clusterId, node, 100);
        assertThat(unreachable.sample().errorKind()).isEqualTo("UNREACHABLE");
        assertThat(unreachable.sample().error()).isEqualTo(BrokerConnectionException.Kind.UNREACHABLE.defaultMessage());
        assertThat(unreachable.partial()).isFalse();

        doThrow(new IllegalStateException("garbled")).when(artemisNodes).on(any(UUID.class), any(UUID.class));
        NodeResult garbled = sampler.sampleNode(clusterId, node, 100);
        assertThat(garbled.sample().errorKind()).isEqualTo("BAD_RESPONSE");
        assertThat(garbled.sample().error()).isEqualTo("garbled");
    }

    @Test
    void theReceivingNodeIsTheLastSegmentOfAStoreAndForwardQueue() {
        assertThat(ClientSampler.receivingNodeId("$.artemis.internal.sf.my-cluster.abc-123"))
                .isEqualTo("abc-123");
        assertThat(ClientSampler.receivingNodeId("nodots")).isEqualTo("nodots");
    }

    // ---- sweep ---------------------------------------------------------

    private ArtemisNode stableClient() {
        Batch batch = new Batch();
        batch.producers = listing(1, producer(1, 100));
        batch.consumers = listing(1, consumer(2, 50));
        batch.bridges = pattern("{\"Name\":\"br\",\"QueueName\":\"q\",\"Connected\":true,\"MessagesAcknowledged\":7}");
        return client(batch.entries());
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
        when(artemisNodes.on(eq(clusterId), any(UUID.class))).thenAnswer(inv -> stableClient());

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
        when(artemisNodes.on(eq(clusterId), any(UUID.class))).thenAnswer(inv -> stableClient());

        sampler.sweep(clusterId); // first: signals
        clock.advance(Duration.ofSeconds(10));
        sampler.sweep(clusterId); // rates go from unknown to zero: signals
        clock.advance(Duration.ofSeconds(10));
        sampler.sweep(clusterId); // identical: silent

        verify(hub, times(2)).publish(clusterId, FlowModule.TOPIC);
    }

    @Test
    void anUnusableNodeIsPersistedWithItsErrorAndNoEdgesOrRoutes() {
        UUID id = UUID.randomUUID();
        serving(node(id, "n", "http://n/j", "N", true));
        doThrow(BrokerConnectionException.of(BrokerConnectionException.Kind.CREDENTIALS_REJECTED))
                .when(artemisNodes)
                .on(any(UUID.class), any(UUID.class));

        sampler.sweep(clusterId);

        ArgumentCaptor<NodeSample> sample = ArgumentCaptor.forClass(NodeSample.class);
        verify(store).persistNode(sample.capture(), eq(List.of()), eq(List.of()));
        assertThat(sample.getValue().errorKind()).isEqualTo("CREDENTIALS_REJECTED");
    }

    @Test
    void aTruncatedNodeIsCounted() {
        Batch batch = new Batch();
        batch.producers = listing(500, producer(1, 1));
        serving(node(UUID.randomUUID(), "n", "http://n/j", "N", true));
        answers(client(batch.entries()));

        sampler.sweep(clusterId);

        assertThat(meters.get("rahdar.flow.sample.truncated").counter().count()).isEqualTo(1);
    }

    @Test
    void theRowCapIsAtLeastOne() {
        when(settings.intValue(FlowSettings.MAX_ROWS_PER_NODE)).thenReturn(0);
        serving(node(UUID.randomUUID(), "n", "http://n/j", "N", true));
        ArtemisNode client = client(new Batch().entries());
        answers(client);

        sampler.sweep(clusterId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JolokiaRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(client).batch(requests.capture());
        assertThat(requests.getValue().get(0).arguments()).containsExactly(ArtemisLists.ALL, 1, 1);
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
