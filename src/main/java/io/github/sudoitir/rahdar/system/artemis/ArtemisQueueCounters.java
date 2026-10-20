package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.QueueCounters;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** An Artemis queue's message counters, reset through the queue's own control on each node. */
@Component
@RequiredArgsConstructor
class ArtemisQueueCounters implements QueueCounters {

    private final ArtemisNodes nodes;
    private final QueueLifecycleOperations ops;

    @Override
    public void reset(BrokerTarget node, String queue) {
        ArtemisNode client = nodes.on(node);
        List<String> mbeans = client.search(MBeanNames.queuePattern(client.resolveBrokerObjectName(), queue));
        for (String mbean : mbeans) {
            ops.resetMessageCounter(client, mbean);
        }
    }
}
