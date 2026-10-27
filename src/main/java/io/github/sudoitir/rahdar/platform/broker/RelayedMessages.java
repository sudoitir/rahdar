package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.OutboundMessage;
import io.github.sudoitir.openbroker.messaging.PropertyValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The message a relay sends for the one it received (transfer design D3): the same message to its
 * consumers, with the broker's own bookkeeping left behind by the transport, plus where it came from.
 *
 * <ul>
 *   <li><b>Copied:</b> the body and its type, priority, absolute expiration, timestamp, id, and every
 *       property, which keeps group, correlation, reply-to and last-value keys.
 *   <li><b>Made durable:</b> the target receives it in a transaction, and the broker SDK sends every
 *       transacted message durable (a non-durable one is refused), so a non-durable message arrives
 *       durable (ADR-0211).
 *   <li><b>Added:</b> a duplicate-detection id {@code rahdar:<run>:<source message id>}, so a batch
 *       relayed twice is dropped by the target, and the {@code _rahdar_*} provenance properties.
 * </ul>
 */
public final class RelayedMessages {

    public static final String RUN = "_rahdar_transfer_run";
    public static final String ORIG_CLUSTER = "_rahdar_orig_cluster";
    public static final String ORIG_NODE = "_rahdar_orig_node";
    public static final String ORIG_QUEUE = "_rahdar_orig_queue";
    public static final String ORIG_MESSAGE_ID = "_rahdar_orig_message_id";

    private RelayedMessages() {}

    /**
     * Where a message came from. {@code staged} says the relayed message is a staging copy made by a
     * broker-side move, whose id on the source queue is its origin id; otherwise (a copy browsing the
     * source) its own id is the source id.
     */
    public record Provenance(UUID runId, UUID cluster, String node, String queue, boolean staged) {}

    /** The received message's id on the queue it was read from. */
    public static long sourceId(Message m) {
        return Long.parseLong(m.ref().position());
    }

    public static OutboundMessage of(Message source, Provenance p) {
        long sourceId = sourceId(source);
        long original =
                p.staged() ? source.headers().originId().map(Long::parseLong).orElse(sourceId) : sourceId;
        Map<String, PropertyValue> provenance = new LinkedHashMap<>();
        provenance.put(RUN, PropertyValue.of(p.runId().toString()));
        provenance.put(ORIG_CLUSTER, PropertyValue.of(p.cluster().toString()));
        provenance.put(ORIG_NODE, PropertyValue.of(p.node()));
        provenance.put(ORIG_QUEUE, PropertyValue.of(p.queue()));
        provenance.put(ORIG_MESSAGE_ID, PropertyValue.of(original));
        return OutboundMessage.copyOf(source)
                .withHeaders(source.headers().toBuilder()
                        .deduplicationId("rahdar:" + p.runId() + ":" + sourceId)
                        .durable(null)
                        .build())
                .withProperties(provenance);
    }
}
