package io.github.sudoitir.rahdar.platform.broker;

/**
 * Setting a queue's message counters back to zero on one node. Only some systems keep counters an
 * operator may reset, so their system module provides this; where none does, the action is
 * unavailable.
 */
public interface QueueCounters extends SystemExtension {

    /** Resets the queue's counters on the node. */
    void reset(BrokerTarget node, String queue);
}
