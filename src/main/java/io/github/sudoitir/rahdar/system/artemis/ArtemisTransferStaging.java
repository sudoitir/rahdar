package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BulkResult;
import io.github.sudoitir.rahdar.platform.broker.TransferStaging;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Artemis' side of a cross-broker move: the staging queue managed over Jolokia on the source node, and
 * the {@code moveMessages}, {@code moveMessage} and count calls on the queues around it. A queue is
 * addressed by the MBean name its address, name and routing type give.
 */
@Component
@RequiredArgsConstructor
public class ArtemisTransferStaging implements TransferStaging {

    private static final String ANYCAST = "ANYCAST";

    private final ArtemisNodes nodes;
    private final StagingQueues staging;
    private final MessageOperations messages;

    @Override
    public String system() {
        return BrokerGateway.SYSTEM;
    }

    @Override
    public void create(UUID clusterId, UUID nodeId, UUID runId) {
        staging.create(nodes.on(clusterId, nodeId), runId);
    }

    @Override
    public boolean destroyIfEmpty(UUID clusterId, UUID nodeId, String stagingQueue) {
        return staging.destroyIfEmpty(nodes.on(clusterId, nodeId), stagingQueue);
    }

    @Override
    public List<String> list(UUID clusterId, UUID nodeId) {
        return staging.list(nodes.on(clusterId, nodeId));
    }

    @Override
    public long stagedCount(UUID clusterId, UUID nodeId, String stagingQueue) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        return messages.messageCount(client, stagingMbean(client, stagingQueue));
    }

    @Override
    public List<Long> stagedIds(UUID clusterId, UUID nodeId, String stagingQueue) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        return messages.listIds(client, stagingMbean(client, stagingQueue));
    }

    @Override
    public BulkResult moveStagedIds(UUID clusterId, UUID nodeId, String stagingQueue, List<Long> ids, String toQueue) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        return messages.moveByIds(client, stagingMbean(client, stagingQueue), ids, toQueue);
    }

    @Override
    public long moveStaged(UUID clusterId, UUID nodeId, String stagingQueue, int max, String toQueue) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        return messages.moveMessages(client, stagingMbean(client, stagingQueue), max, "", toQueue, false, max);
    }

    @Override
    public BulkResult moveIds(UUID clusterId, UUID nodeId, TransferSource from, List<Long> ids, String toQueue) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        return messages.moveByIds(client, mbean(client, from), ids, toQueue);
    }

    @Override
    public long moveSelected(
            UUID clusterId, UUID nodeId, TransferSource from, String filter, Instant t0, int max, String toQueue) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        return messages.moveMessages(
                client, mbean(client, from), max, ArtemisSelectors.frozen(filter, t0), toQueue, false, max);
    }

    @Override
    public long countSelected(UUID clusterId, UUID nodeId, TransferSource from, String filter, Instant t0) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        return messages.countMessages(client, mbean(client, from), ArtemisSelectors.frozen(filter, t0));
    }

    @Override
    public boolean isCompleteFilter(String filter) {
        return ArtemisSelectors.isCompleteExpression(filter);
    }

    private static String mbean(ArtemisNode client, TransferSource queue) {
        return MBeanNames.queue(
                client.resolveBrokerObjectName(), queue.destination(), queue.name(), queue.routingType());
    }

    private static String stagingMbean(ArtemisNode client, String stagingQueue) {
        return MBeanNames.queue(client.resolveBrokerObjectName(), stagingQueue, stagingQueue, ANYCAST);
    }
}
