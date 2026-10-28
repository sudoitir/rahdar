package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.BulkResult;
import io.github.sudoitir.rahdar.platform.broker.MessageExpiry;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator;
import io.github.sudoitir.rahdar.platform.scrape.QueueLocator.QueueLocation;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Expiring messages now, which only Artemis offers: the broker sends them to the queue's expiry
 * address as if their time had run out.
 */
@Component
public class ArtemisExpiry implements MessageExpiry {

    @Override
    public String system() {
        return ArtemisModule.SYSTEM;
    }

    private final ArtemisNodes nodes;
    private final MessageOperations ops;
    private final QueueLocator locator;

    public ArtemisExpiry(ArtemisNodes nodes, MessageOperations ops, QueueLocator locator) {
        this.nodes = nodes;
        this.ops = ops;
        this.locator = locator;
    }

    @Override
    public BulkResult byIds(UUID clusterId, UUID nodeId, String queue, List<Long> ids) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        return ops.expireByIds(client, mbean(client, clusterId, nodeId, queue), ids);
    }

    @Override
    public long byFilter(UUID clusterId, UUID nodeId, String queue, String filter) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        return ops.expireByFilter(client, mbean(client, clusterId, nodeId, queue), filter == null ? "" : filter);
    }

    private String mbean(ArtemisNode client, UUID clusterId, UUID nodeId, String queue) {
        QueueLocation here = locator.locate(clusterId, queue).stream()
                .filter(l -> l.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("queue", queue));
        return MBeanNames.queue(client.resolveBrokerObjectName(), here.address(), queue, here.routingType());
    }
}
