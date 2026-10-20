package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.messaging.Blob;
import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.MessageBody;
import io.github.sudoitir.openbroker.messaging.MessageHeaders;
import io.github.sudoitir.openbroker.messaging.MessageRef;
import io.github.sudoitir.openbroker.messaging.OutboundMessage;
import io.github.sudoitir.openbroker.messaging.PropertyValue;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.rahdar.platform.broker.RelayedMessages.Provenance;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a relayed message carries besides itself: a duplicate id per run and where it came from. */
class RelayedMessagesTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID CLUSTER = UUID.randomUUID();

    private static Message received(String id, MessageHeaders headers) {
        return new Message(
                new MessageRef(ResourceId.of("artemis.queue", "q"), Shard.node(NodeId.of("n")), id),
                headers,
                Map.of("app", PropertyValue.of("kept")),
                MessageBody.of(Blob.of(new byte[] {1, 2})),
                Set.of(),
                Map.of());
    }

    @Test
    void aBrowsedCopyNamesItsOwnIdAsTheSourceAndKeepsWhatItCarries() {
        OutboundMessage out = RelayedMessages.of(
                received("5", MessageHeaders.builder().correlationId("c-1").build()),
                new Provenance(RUN, CLUSTER, "node-1", "SRC", false));

        assertThat(out.headers().deduplicationId()).contains("rahdar:" + RUN + ":5");
        assertThat(out.headers().correlationId()).contains("c-1");
        assertThat(out.properties())
                .containsEntry("app", PropertyValue.of("kept"))
                .containsEntry(RelayedMessages.RUN, PropertyValue.of(RUN.toString()))
                .containsEntry(RelayedMessages.ORIG_CLUSTER, PropertyValue.of(CLUSTER.toString()))
                .containsEntry(RelayedMessages.ORIG_NODE, PropertyValue.of("node-1"))
                .containsEntry(RelayedMessages.ORIG_QUEUE, PropertyValue.of("SRC"))
                .containsEntry(RelayedMessages.ORIG_MESSAGE_ID, PropertyValue.of(5L));
    }

    @Test
    void aStagedCopyKeepsItsSourceQueueIdAsItsOrigin() {
        OutboundMessage out = RelayedMessages.of(
                received("77", MessageHeaders.builder().originId("12").build()),
                new Provenance(RUN, CLUSTER, "node-1", "SRC", true));

        assertThat(out.headers().deduplicationId()).contains("rahdar:" + RUN + ":77");
        assertThat(out.properties()).containsEntry(RelayedMessages.ORIG_MESSAGE_ID, PropertyValue.of(12L));
    }
}
