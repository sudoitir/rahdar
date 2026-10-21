package io.github.sudoitir.rahdar.system.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowRoute;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler.FlowSample;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** What one Artemis node's batched Jolokia POST becomes, and how each part of it fails alone. */
class ArtemisFlowSamplerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final ArtemisNodes nodes = mock(ArtemisNodes.class);
    private final ArtemisFlowSampler sampler = new ArtemisFlowSampler(nodes);
    private final UUID clusterId = UUID.randomUUID();
    private final UUID nodeId = UUID.randomUUID();

    // ---- fixtures ------------------------------------------------------

    private static JsonNode json(String json) {
        return MAPPER.readTree(json);
    }

    private static ManagementResponse ok(String json) {
        return new ManagementResponse(200, json(json), null, null, null);
    }

    private static ManagementResponse failed(int status, String error) {
        return new ManagementResponse(status, null, error, null, null);
    }

    private static ManagementResponse listing(long count, String... rows) {
        return ok("{\"count\":" + count + ",\"data\":[" + String.join(",", rows) + "]}");
    }

    private static ManagementResponse pattern(String... attributes) {
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
        ManagementResponse producers = listing(0);
        ManagementResponse consumers = listing(0);
        ManagementResponse diverts = failed(404, "no diverts");
        ManagementResponse bridges = failed(404, "no bridges");
        ManagementResponse storeAndForward = listing(0);
        ManagementResponse temporary = listing(0);
        ManagementResponse filtered = listing(0);
        ManagementResponse addressSettings = ok("{}");

        List<ManagementResponse> entries() {
            return List.of(
                    producers, consumers, diverts, bridges, storeAndForward, temporary, filtered, addressSettings);
        }
    }

    private ArtemisNode client(List<ManagementResponse> entries) {
        ArtemisNode client = mock(ArtemisNode.class);
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");
        when(client.batch(anyList())).thenReturn(entries);
        when(client.parsed(any())).thenAnswer(inv -> ((ManagementResponse) inv.getArgument(0)).value());
        when(nodes.on(clusterId, nodeId)).thenReturn(client);
        return client;
    }

    private FlowSample sample(Batch batch) {
        client(batch.entries());
        return sampler.sample(clusterId, nodeId, 100);
    }

    private FlowSample oneHealthyPost() {
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

    // ---- what the POST becomes -----------------------------------------

    @Test
    void theReceivingNodeIsTheLastSegmentOfAStoreAndForwardQueue() {
        assertThat(ArtemisFlowSampler.receivingNodeId("$.artemis.internal.sf.my-cluster.abc-123"))
                .isEqualTo("abc-123");
        assertThat(ArtemisFlowSampler.receivingNodeId("nodots")).isEqualTo("nodots");
    }

    @Test
    void oneHealthyPostIsCountedWithoutError() {
        FlowSample sample = oneHealthyPost();

        assertThat(sample.errorKind()).isNull();
        assertThat(sample.error()).isNull();
        assertThat(sample.producers()).hasSize(1);
        assertThat(sample.producersTotal()).isEqualTo(1);
        assertThat(sample.consumers()).hasSize(1);
        assertThat(sample.consumersTotal()).isEqualTo(1);
    }

    @Test
    void oneHealthyPostBecomesMembersWithTheirReadings() {
        FlowSample sample = oneHealthyPost();

        assertThat(sample.producers()).singleElement().satisfies(p -> {
            assertThat(p.memberId()).isEqualTo("1@s1");
            assertThat(p.user()).isEqualTo("u");
            assertThat(p.destination()).isEqualTo("orders");
            assertThat(p.subscription()).isNull();
            assertThat(p.counter()).isEqualTo(5);
        });
        assertThat(sample.consumers()).singleElement().satisfies(c -> {
            assertThat(c.user()).as("a blank validated user falls back").isEqualTo("u");
            assertThat(c.subscription()).isEqualTo("orders");
            assertThat(c.counter()).isEqualTo(3);
            assertThat(c.unacked()).isEqualTo(1);
        });
    }

    @Test
    void oneHealthyPostBecomesEveryKindOfRoute() {
        FlowSample sample = oneHealthyPost();

        assertThat(sample.routes())
                .extracting(FlowRoute::kind)
                .containsExactly(
                        "DIVERT", "BRIDGE", "STORE_AND_FORWARD", "TEMPORARY_QUEUE", "QUEUE_FILTER", "DEAD_LETTER");
        FlowRoute divert = sample.routes().get(0);
        assertThat(divert.name()).isEqualTo("d1");
        assertThat(divert.exclusive()).isTrue();
        assertThat(divert.transformer()).isEqualTo("T");
        FlowRoute bridge = sample.routes().get(1);
        assertThat(bridge.connected()).isTrue();
        assertThat(bridge.counter()).isEqualTo(7);
        FlowRoute sf = sample.routes().get(2);
        assertThat(sf.target()).as("the receiving node id is the last segment").isEqualTo("node-b");
        assertThat(sf.counter()).isEqualTo(11);
        assertThat(sample.routes().get(3).name()).isEqualTo("tmp-1");
        assertThat(sample.routes().get(4).filter()).isEqualTo("color='red'");
        assertThat(sample.routes().get(5).target()).isEqualTo("DLQ");
    }

    // ---- how each part fails alone -------------------------------------

    @Test
    void anIncompleteBatchIsABadResponse() {
        client(List.of(ok("{}")));

        assertThatThrownBy(() -> sampler.sample(clusterId, nodeId, 100))
                .isInstanceOfSatisfying(BrokerConnectionException.class, e -> {
                    assertThat(e.kind()).isEqualTo(BrokerConnectionException.Kind.BAD_RESPONSE);
                    assertThat(e.getMessage()).isEqualTo("The broker returned an incomplete response.");
                });
    }

    @Test
    void aFailedProducerListingIsClassifiedByStatusOrByWhatTheBrokerSaid() {
        Batch forbidden = new Batch();
        forbidden.producers = failed(403, "nope");
        assertThat(sample(forbidden).errorKind()).isEqualTo("PERMISSION_DENIED");

        Batch security = new Batch();
        security.producers = failed(500, "Security exception: access denied");
        assertThat(sample(security).errorKind()).isEqualTo("PERMISSION_DENIED");

        Batch permission = new Batch();
        permission.producers = failed(500, "no Permission for listProducers");
        assertThat(sample(permission).errorKind()).isEqualTo("PERMISSION_DENIED");

        Batch other = new Batch();
        other.producers = failed(500, null);
        FlowSample result = sample(other);
        assertThat(result.errorKind()).isEqualTo("BAD_RESPONSE");
        assertThat(result.error()).isEqualTo("status 500");
        assertThat(result.producers()).as("nothing was listed, nothing to keep").isEmpty();
    }

    @Test
    void aFailedConsumerListingKeepsTheProducersItDidRead() {
        Batch batch = new Batch();
        batch.producers = listing(1, producer(1, 1));
        batch.consumers = failed(500, "consumers down");

        FlowSample sample = sample(batch);

        assertThat(sample.errorKind()).isEqualTo("BAD_RESPONSE");
        assertThat(sample.error()).isEqualTo("consumers down");
        assertThat(sample.producers()).hasSize(1);
    }

    @Test
    void aFailedConsumerListingWithNoRowsAnywhereHasNoRows() {
        Batch batch = new Batch();
        batch.consumers = failed(403, "no");

        FlowSample sample = sample(batch);

        assertThat(sample.errorKind()).isEqualTo("PERMISSION_DENIED");
        assertThat(sample.producers()).isEmpty();
        assertThat(sample.consumers()).isEmpty();
    }

    @Test
    void aMissingCounterIsReportedButTheRowsAreStillUsable() {
        Batch noSent = new Batch();
        noSent.producers = listing(1, producer(1, null));
        FlowSample producersResult = sample(noSent);
        assertThat(producersResult.errorKind()).isEqualTo("COUNTER_UNAVAILABLE");
        assertThat(producersResult.producers()).hasSize(1);

        Batch noAcked = new Batch();
        noAcked.consumers = listing(1, consumer(1, null));
        assertThat(sample(noAcked).errorKind()).isEqualTo("COUNTER_UNAVAILABLE");
    }

    @Test
    void oneFailingRoutingEntryLosesOnlyItself() {
        Batch batch = new Batch();
        batch.producers = listing(1, producer(1, 1));
        batch.diverts = failed(500, "divert boom");
        batch.bridges = failed(500, null);
        batch.filtered = failed(500, "filter boom");
        batch.addressSettings = failed(500, "settings boom");

        FlowSample sample = sample(batch);

        assertThat(sample.errorKind()).isEqualTo("ROUTING_UNAVAILABLE");
        assertThat(sample.error()).isEqualTo("divert boom");
        assertThat(sample.producers()).hasSize(1);
        assertThat(sample.routes()).isEmpty();
    }

    @Test
    void anAddressSettingsFailureAloneIsAlsoARoutingError() {
        Batch batch = new Batch();
        batch.addressSettings = failed(500, null);

        FlowSample sample = sample(batch);

        assertThat(sample.errorKind()).isEqualTo("ROUTING_UNAVAILABLE");
        assertThat(sample.error()).isEqualTo("status 500");
    }

    @Test
    void aPatternReadThatIsNotAnObjectIsNone() {
        Batch batch = new Batch();
        batch.diverts = ok("[]");
        batch.bridges = ok("null");

        FlowSample sample = sample(batch);

        assertThat(sample.errorKind()).isNull();
        assertThat(sample.routes()).isEmpty();
    }

    @Test
    void aCountAboveTheRowsReturnedIsReportedAsTheTotal() {
        Batch batch = new Batch();
        batch.producers = listing(250, producer(1, 1));
        batch.consumers = listing(1, consumer(2, 1));

        FlowSample sample = sample(batch);

        assertThat(sample.producers()).hasSize(1);
        assertThat(sample.producersTotal()).isEqualTo(250);
        assertThat(sample.consumersTotal()).isEqualTo(1);
    }

    @Test
    void aListingWithoutACountOrDataFallsBackToWhatItHas() {
        Batch batch = new Batch();
        batch.producers = ok("{\"data\":[" + producer(1, 1) + "]}");
        batch.consumers = ok("{}");

        FlowSample sample = sample(batch);

        assertThat(sample.producersTotal()).isEqualTo(1);
        assertThat(sample.consumersTotal()).isZero();
    }

    @Test
    void aNullParsedListingIsEmptyNotAFailure() {
        Batch batch = new Batch();
        batch.producers = ok("null");
        ArtemisNode client = client(batch.entries());
        when(client.parsed(batch.producers)).thenReturn(null);

        FlowSample sample = sampler.sample(clusterId, nodeId, 100);

        assertThat(sample.errorKind()).isNull();
        assertThat(sample.producersTotal()).isZero();
    }

    @Test
    void theRowCapIsAskedOfTheBroker() {
        ArtemisNode client = client(new Batch().entries());

        sampler.sample(clusterId, nodeId, 7);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ManagementRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(client).batch(requests.capture());
        assertThat(requests.getValue().get(0).arguments()).containsExactly(ListRows.ALL, 1, 7);
    }
}
