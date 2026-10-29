package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.messaging.Blob;
import io.github.sudoitir.openbroker.messaging.BrowseRequest;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.messaging.Cursor;
import io.github.sudoitir.openbroker.messaging.Denominator;
import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.MessageBody;
import io.github.sudoitir.openbroker.messaging.MessageHeaders;
import io.github.sudoitir.openbroker.messaging.MessageRef;
import io.github.sudoitir.openbroker.messaging.Messages;
import io.github.sudoitir.openbroker.messaging.Position;
import io.github.sudoitir.openbroker.messaging.Total;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Shard;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** A browse is bounded by bytes, not only by count, and a value or key says how it is written. */
class BrowsedMessagesBoundsTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final ResourceId LOG = ResourceId.of("kafka.topic", "orders");
    private static final byte[] MEGABYTE = new byte[1024 * 1024];

    private static Message record(long offset, MessageBody body, MessageHeaders headers) {
        return new Message(
                new MessageRef(LOG, Shard.node(NodeId.of("n")), String.valueOf(offset)),
                headers,
                Map.of(),
                body,
                Set.of(),
                Map.of());
    }

    private static MessageBody megabyte() {
        return MessageBody.streamed(MEGABYTE.length, () -> new ByteArrayInputStream(MEGABYTE));
    }

    private BrokerMessages serving(List<Message> served, Optional<Cursor> next) {
        Messages sdk = mock(Messages.class);
        when(sdk.browse(any(ResourceId.class), any(Scope.class), any(BrowseRequest.class)))
                .thenReturn(new io.github.sudoitir.openbroker.messaging.BrowsePage(
                        served, next, new Total(Denominator.ALL, new Count.Unavailable("not counted"))));
        ClusterConnection connection = mock(ClusterConnection.class);
        when(connection.messages()).thenReturn(sdk);
        BrokerGateway gateway = mock(BrokerGateway.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        return new BrokerMessages(gateway);
    }

    @Test
    void fiveHundredOneMegabyteRecordsStopAtTheByteBudgetAndNameWhereToContinue() {
        List<Message> served = IntStream.range(0, 500)
                .mapToObj(n -> record(n, megabyte(), MessageHeaders.builder().build()))
                .toList();
        BrokerMessages messages = serving(served, Optional.of(new Cursor("0:500")));

        LogPage page = messages.browseLog(CLUSTER, LOG, 0, Position.earliest(), 500, null);

        assertThat(page.records()).hasSize(8);
        assertThat(page.truncated()).isTrue();
        assertThat(page.next()).isEqualTo(8L);
    }

    @Test
    void aPageUnderTheBudgetIsWholeAndKeepsTheDriversContinuation() {
        List<Message> served = new ArrayList<>(IntStream.range(0, 3)
                .mapToObj(n -> record(
                        n,
                        MessageBody.of(new byte[10]),
                        MessageHeaders.builder().build()))
                .toList());
        BrokerMessages messages = serving(served, Optional.of(new Cursor("0:3")));

        LogPage page = messages.browseLog(CLUSTER, LOG, 0, Position.earliest(), 3, null);

        assertThat(page.records()).hasSize(3);
        assertThat(page.truncated()).isFalse();
        assertThat(page.next()).isEqualTo(3L);
    }

    @Test
    void aStreamedValueLargerThanTheLimitIsReadOnlyToTheLimitAndMarkedTruncated() {
        byte[] huge = new byte[(int) BrokerMessages.MAX_BODY_BYTES + 5];
        Message streamed = record(
                1,
                MessageBody.streamed(huge.length, () -> new ByteArrayInputStream(huge)),
                MessageHeaders.builder().build());

        BrowsedMessage shown = BrowsedMessages.of(streamed);

        assertThat(shown.bodyTruncated()).isTrue();
        assertThat(shown.observedLimitBytes()).isEqualTo((int) BrokerMessages.MAX_BODY_BYTES);
    }

    @Test
    void aKeyIsTextOrBase64AndSaysWhich() {
        Message text = record(
                1,
                MessageBody.of(new byte[0]),
                MessageHeaders.builder().key(Blob.of("order-1".getBytes())).build());
        Message binary = record(
                2,
                MessageBody.of(new byte[0]),
                MessageHeaders.builder()
                        .key(Blob.of(new byte[] {(byte) 0xff, 0x00, (byte) 0xfe}))
                        .build());
        Message none =
                record(3, MessageBody.of(new byte[0]), MessageHeaders.builder().build());

        assertThat(BrowsedMessages.keyOf(text)).isEqualTo(new BrowsedMessages.Key("order-1", BodyEncoding.TEXT));
        assertThat(BrowsedMessages.keyOf(binary).encoding()).isEqualTo(BodyEncoding.BASE64);
        assertThat(BrowsedMessages.keyOf(binary).text()).isEqualTo("/wD+");
        assertThat(BrowsedMessages.keyOf(none)).isNull();
    }
}
