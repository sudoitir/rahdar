package io.github.sudoitir.rahdar.system.artemis;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Expiring messages now, which only Artemis offers: the broker sends them to the queue's expiry
 * address as if their time had run out.
 */
@Component
public class ArtemisExpiry {

    private final ArtemisNodes nodes;
    private final MessageOperations ops;

    public ArtemisExpiry(ArtemisNodes nodes, MessageOperations ops) {
        this.nodes = nodes;
        this.ops = ops;
    }

    /** Expires the messages with these ids; reports which it did not act on, when it stopped part-way. */
    public MessageOperations.BulkResult byIds(
            UUID clusterId, UUID nodeId, String queue, String address, String routingType, List<Long> ids) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        return ops.expireByIds(client, mbean(client, queue, address, routingType), ids);
    }

    /** Expires the messages that match a filter, or every message for a blank one. */
    public long byFilter(UUID clusterId, UUID nodeId, String queue, String address, String routingType, String filter) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        return ops.expireByFilter(client, mbean(client, queue, address, routingType), filter == null ? "" : filter);
    }

    private static String mbean(ArtemisNode client, String queue, String address, String routingType) {
        return MBeanNames.queue(client.resolveBrokerObjectName(), address, queue, routingType);
    }
}
