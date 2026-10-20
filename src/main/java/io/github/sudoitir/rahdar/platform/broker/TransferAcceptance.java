package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.rahdar.platform.broker.TransferStaging.TransferSource;
import java.util.List;
import java.util.UUID;

/**
 * Whether a target queue can accept a selection (transfer design D4). What decides it is the system's
 * own knowledge of how its queues behave when full, filtered or low on disk, so its module provides it.
 *
 * <p>A refusal is anything that would lose messages silently or that the target cannot hold; a warning is
 * a risk the operator acknowledges; an unknown is a check that could not be made, and never counts as
 * passing (ADR-0049 D5).
 */
public interface TransferAcceptance extends SystemExtension {

    /** What a node said about a queue, its destination and itself. {@code null} always means unknown. */
    interface AcceptanceFacts {

        /** The queue's message count. */
        Long messageCount();

        /** The queue's persistent size in bytes. */
        Long persistentSize();

        /** How many messages the broker looks at to count a filter. */
        int filterCountLimit();
    }

    /**
     * What an evaluation is over.
     *
     * @param count messages selected; null when unknown
     * @param bytes their projected size; null when unknown
     * @param sameQueueSameNode the target is the source queue on the source node
     * @param sameCluster source and target are in one cluster, so the broker may redistribute
     * @param targetLive the target node is live now
     * @param targetBackup the target node is a backup
     * @param splitBrain the target node's pair is in split-brain
     * @param thresholdPercent how full the target may get before a run waits
     */
    record AcceptanceInput(
            Long count,
            Long bytes,
            boolean sameQueueSameNode,
            boolean sameCluster,
            boolean targetLive,
            boolean targetBackup,
            boolean splitBrain,
            int thresholdPercent) {}

    enum FindingKind {
        /** The transfer cannot run: the target would drop, or could not take, the messages. */
        REFUSE,
        /** A risk the operator must acknowledge before running. */
        WARN,
        /** A check that could not be made. Never counts as passing. */
        UNKNOWN
    }

    /**
     * One finding, in words.
     *
     * @param code stable, for acknowledging a warning
     * @param snippet the configuration that changes the verdict, where there is one
     */
    record AcceptanceFinding(FindingKind kind, String code, String words, String snippet) {}

    /**
     * Before a batch: a refusal that would lose messages fails the run, a target near full makes it wait.
     *
     * @param refusal set when the run must not continue
     * @param waitReason why the run must wait, when it must
     */
    record BatchVerdict(AcceptanceFinding refusal, String waitReason) {
        public boolean proceed() {
            return refusal == null && waitReason == null;
        }
    }

    /** What the node says about the queue; every fact unknown when it does not answer. */
    AcceptanceFacts read(UUID clusterId, UUID nodeId, TransferSource queue);

    /** The verdict over the whole selection, for the preview. {@code target} is null when its node is not live. */
    List<AcceptanceFinding> evaluate(AcceptanceFacts target, AcceptanceInput input, String destination, String queue);

    /** The verdict before one batch. What cannot be read now is not held against the run. */
    BatchVerdict batch(AcceptanceFacts target, long batchBytes, int thresholdPercent, String destination, String queue);
}
