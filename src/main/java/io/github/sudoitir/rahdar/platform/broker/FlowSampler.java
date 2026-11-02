package io.github.sudoitir.rahdar.platform.broker;

import java.util.List;
import java.util.UUID;

/**
 * What flow reads from one node in one sweep: who produces to and consumes from it, and the objects that
 * route messages beyond clients. Systems list these in their own ways, so each system's module provides it.
 */
public interface FlowSampler extends SystemExtension {

    /** The node could not be read in full for this reason. Neutral names; flow persists them as they are. */
    String BAD_RESPONSE = "BAD_RESPONSE";

    String PERMISSION_DENIED = "PERMISSION_DENIED";
    String ROUTING_UNAVAILABLE = "ROUTING_UNAVAILABLE";
    String COUNTER_UNAVAILABLE = "COUNTER_UNAVAILABLE";

    /**
     * One producer or consumer.
     *
     * @param memberId unique on the node; a reused id in a new session is a new member
     * @param counter its lifetime count of messages sent, or acknowledged for a consumer
     * @param unacked for a consumer, the messages in transit
     */
    record FlowMember(
            String memberId,
            String clientId,
            String user,
            String remoteAddress,
            String protocol,
            String destination,
            String subscription,
            long counter,
            long unacked) {}

    /**
     * One routing object. {@code kind} names the flow route kind; {@code counter} is its lifetime count of
     * messages when it keeps one.
     */
    record FlowRoute(
            String kind,
            String name,
            String source,
            String target,
            String filter,
            String transformer,
            boolean exclusive,
            boolean connected,
            long counter) {}

    /**
     * One node's sweep. The producers and consumers are the rows read, at most the cap; the totals are what
     * the broker has. {@code error} and {@code errorKind} (one of the constants above) are null when the node
     * answered in full.
     */
    record FlowSample(
            List<FlowMember> producers,
            long producersTotal,
            List<FlowMember> consumers,
            long consumersTotal,
            List<FlowRoute> routes,
            String error,
            String errorKind) {}

    /** Everything flow reads from one node. Broker I/O only. */
    FlowSample sample(UUID clusterId, UUID nodeId, int rowCap);

    /**
     * Whether one read answers for the whole cluster, whichever node it is asked through (Kafka, RabbitMQ). Flow
     * then samples the cluster once, through its first node, instead of once per node, which would count every
     * client as many times as there are nodes.
     */
    default boolean clusterWide() {
        return false;
    }

    /**
     * Whether a member carries a lifetime counter its rate can be taken from. A system without one has its
     * members' rates shown as unavailable, never as zero.
     */
    default boolean memberCounters() {
        return true;
    }

    /** Why producers are not shown, in words, or null when the system lists them. */
    default String producersNote() {
        return null;
    }

    /** What an operator changes so the user Rahdar connects with can list clients, or null when there is no snippet. */
    default String accessSnippet() {
        return null;
    }

    /** Whether addresses can be wildcards that match other addresses (Artemis), which the graph then joins. */
    default boolean wildcardAddresses() {
        return false;
    }
}
