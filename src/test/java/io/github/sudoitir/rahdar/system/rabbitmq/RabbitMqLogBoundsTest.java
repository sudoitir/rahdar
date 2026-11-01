package io.github.sudoitir.rahdar.system.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.messaging.BrowsePage;
import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.MessageRef;
import io.github.sudoitir.openbroker.messaging.Messages;
import io.github.sudoitir.openbroker.messaging.Total;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Resources;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.LogBounds.Bounds;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A stream is one log: partition 0, from the first message it still keeps to after its committed offset. */
class RabbitMqLogBoundsTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final ResourceId STREAM = ResourceId.of("rabbitmq.stream", "/", "events");

    private final BrokerGateway gateway = mock(BrokerGateway.class);
    private final Resources resources = mock(Resources.class);
    private final Messages messages = mock(Messages.class);
    private final RabbitMqLogBounds bounds = new RabbitMqLogBounds(gateway);

    RabbitMqLogBoundsTest() {
        ClusterConnection connection = mock(ClusterConnection.class);
        when(gateway.cluster(CLUSTER)).thenReturn(connection);
        when(connection.resources()).thenReturn(resources);
        when(connection.messages()).thenReturn(messages);
    }

    private void streamReports(Object committed) {
        Attributes.Builder attributes = Attributes.builder();
        if (committed != null) {
            attributes.putRaw("rabbitmq.committed_offset", committed);
        }
        Resource found = new Resource(STREAM, attributes.build(), List.of(), List.of(), Instant.now());
        Resource other = new Resource(
                ResourceId.of("rabbitmq.stream", "/", "events2"),
                Attributes.empty(),
                List.of(),
                List.of(),
                Instant.now());
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(List.of(other, found), Optional.empty()), List.of()));
    }

    private void firstMessageIs(String position) {
        Message message = mock(Message.class);
        when(message.ref()).thenReturn(new MessageRef(STREAM, Shard.partition(PartitionId.of("0")), position));
        when(messages.browse(any(), any(Scope.class), any()))
                .thenReturn(new BrowsePage(List.of(message), Optional.empty(), mock(Total.class)));
    }

    @Test
    void aStreamIsTheKindThatIsItselfALogForAnyClusterOfTheSystem() {
        assertThat(bounds.system()).isEqualTo("rabbitmq");
        assertThat(bounds.logs(CLUSTER)).containsExactly(KindId.of("rabbitmq.stream"));
    }

    @Test
    void itStartsAtTheFirstMessageKeptAndEndsAfterTheCommittedOffset() {
        streamReports(MetricValue.exact(41));
        firstMessageIs("10");

        assertThat(bounds.of(CLUSTER, STREAM, 0)).isEqualTo(new Bounds(10, 42));
    }

    @Test
    void anEmptyStreamStartsWhereItEnds() {
        streamReports(null);
        when(messages.browse(any(), any(Scope.class), any()))
                .thenReturn(new BrowsePage(List.of(), Optional.empty(), mock(Total.class)));

        assertThat(bounds.of(CLUSTER, STREAM, 0)).isEqualTo(new Bounds(0, 0));
    }

    @Test
    void withoutACommittedOffsetItEndsAfterTheMessagesTheStreamHolds() {
        Attributes.Builder attributes = Attributes.builder();
        attributes.put(MetricKeys.BACKLOG, MetricValue.estimate(200));
        Resource found = new Resource(STREAM, attributes.build(), List.of(), List.of(), Instant.now());
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(List.of(found), Optional.empty()), List.of()));
        firstMessageIs("40");

        assertThat(bounds.of(CLUSTER, STREAM, 0)).isEqualTo(new Bounds(40, 240));
    }

    @Test
    void aCommittedOffsetGivenAsTextIsRead() {
        streamReports("7");
        firstMessageIs("3");

        assertThat(bounds.of(CLUSTER, STREAM, 0)).isEqualTo(new Bounds(3, 8));
    }

    @Test
    void aStreamTheClusterDoesNotHaveIsNotFoundAndAnotherPartitionIsRefused() {
        when(resources.read(any(ResourceQuery.class)))
                .thenReturn(Partial.complete(new ResourcePage(List.of(), Optional.empty()), List.of()));

        assertThatThrownBy(() -> bounds.of(CLUSTER, STREAM, 0)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> bounds.of(CLUSTER, STREAM, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partition 0");
    }
}
