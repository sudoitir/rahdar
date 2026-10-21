package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance;
import io.github.sudoitir.rahdar.platform.broker.TransferStaging.TransferSource;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Artemis' answer to whether a target queue can accept a selection: what the node says about the queue
 * ({@link AcceptanceProbe}) and the rules over it ({@link TargetAcceptance}), which are Artemis knowledge
 * about address settings, paging, ring queues and the duplicate-detection cache.
 */
@Component
@RequiredArgsConstructor
class ArtemisTransferAcceptance implements TransferAcceptance {

    private final ArtemisNodes nodes;
    private final AcceptanceProbe probe;

    @Override
    public String system() {
        return BrokerGateway.SYSTEM;
    }

    @Override
    public AcceptanceFacts read(UUID clusterId, UUID nodeId, TransferSource queue) {
        return probe.read(nodes.on(clusterId, nodeId), queue.destination(), queue.name(), queue.routingType());
    }

    @Override
    public List<AcceptanceFinding> evaluate(
            AcceptanceFacts target, AcceptanceInput input, String destination, String queue) {
        return TargetAcceptance.evaluate((AcceptanceProbe.Facts) target, input, destination, queue);
    }

    @Override
    public BatchVerdict batch(
            AcceptanceFacts target, long batchBytes, int thresholdPercent, String destination, String queue) {
        return TargetAcceptance.batch((AcceptanceProbe.Facts) target, batchBytes, thresholdPercent, destination, queue);
    }
}
