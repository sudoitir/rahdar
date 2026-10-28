package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.QueueSettings;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** An Artemis queue's configuration, read from the queue's own MBean on the node. */
@Component
@RequiredArgsConstructor
class ArtemisQueueSettings implements QueueSettings {

    private final ArtemisNodes nodes;
    private final QueueLifecycleOperations ops;

    @Override
    public String system() {
        return ArtemisModule.SYSTEM;
    }

    @Override
    public Map<String, Object> read(BrokerTarget node, String queue, String address, String routingType) {
        ArtemisNode client = nodes.on(node);
        return ops.readQueueConfig(
                client, MBeanNames.queue(client.resolveBrokerObjectName(), address, queue, routingType));
    }
}
