package io.github.sudoitir.rahdar.feature.flow.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.kernel.core.HeavyRequests;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** The flow read's response (flow-visualization spec). Bounded server-side; every absent number is null, never 0. */
public final class FlowViews {

    private FlowViews() {}

    public enum NodeKind {
        PRODUCER,
        DESTINATION,
        SUBSCRIPTION,
        CONSUMER,
        /** Outside what the graph's columns hold: another cluster node, or a bridge's remote target. */
        REMOTE
    }

    /** What a node stands for when it is not an ordinary client, address or queue. */
    public enum NodeRole {
        /** A queue a cluster connection moves messages through to another node. */
        STORE_AND_FORWARD,
        /** Several temporary queues of one client, collapsed into one node. */
        TEMPORARY,
        /** Where producers that name no address send: the address is chosen per message. */
        ANONYMOUS,
        /** A queue or address Rahdar's message capture owns. */
        CAPTURE,
        DEAD_LETTER,
        EXPIRY,
        /** A broker node of this cluster, reached through store-and-forward. */
        CLUSTER_NODE,
        /** A link target that is not a destination of this cluster. */
        LINK_TARGET
    }

    public enum EdgeKind {
        PRODUCE,
        ROUTE,
        CONSUME,
        /** A destination's messages taken (exclusive) or copied to another destination. Not counted by the broker. */
        FORWARD,
        /** A subscription forwarded to a destination, locally or on another broker. */
        LINK,
        /** Messages a node moves to another node of the cluster. */
        CLUSTER_HOP,
        /** A concrete address whose messages also reach a wildcard address's queues. */
        WILDCARD,
        DEAD_LETTER,
        EXPIRY
    }

    /** Where an edge's rate came from. {@code NONE}: the broker keeps no count for it. */
    public enum RateSource {
        SAMPLER,
        SUBSCRIPTION_METRIC,
        NONE
    }

    /** How an address hands messages to its queues. */
    public enum FlowDelivery {
        /** Multicast: every bound queue receives a copy. */
        COPY,
        /** Anycast: the queues share the messages. */
        SHARED
    }

    public enum Fault {
        /** Messages are waiting on a queue that has no consumer. */
        NO_CONSUMER,
        /** Unacknowledged messages outstanding and nothing acknowledged for two sweeps. */
        STALLED,
        /** A link that is not connected to its target on every node it is deployed on. */
        LINK_DOWN,
        /** A forward or link deployed on only some of the sampled nodes. */
        PARTIAL_PRESENCE
    }

    /** A serving node's part in the latest sweep. */
    public enum NodeSampleState {
        OK,
        UNREACHABLE,
        PERMISSION_DENIED,
        COUNTER_UNAVAILABLE,
        ROUTING_UNAVAILABLE,
        FAILED
    }

    /**
     * @param role what the node stands for beyond its kind; null for an ordinary client, address or queue
     * @param members connections or consumers a client node stands for, or temporary queues a collapsed
     *     node stands for; null otherwise
     * @param messageCount waiting messages on a queue; null for other kinds and for a queue not swept yet
     * @param consumerCount consumers attached to a queue; null for other kinds
     * @param routingTypes an address's routing types; empty for other kinds
     * @param brokerNodes names of the nodes this resource or client was seen on
     */
    public record FlowNodeView(
            String id,
            NodeKind kind,
            @Schema(nullable = true) NodeRole role,
            String label,
            @Schema(nullable = true) Integer members,
            @Schema(nullable = true) Long messageCount,
            @Schema(nullable = true) Long consumerCount,
            List<String> routingTypes,
            List<String> protocols,
            List<String> hosts,
            List<String> users,
            List<String> brokerNodes,
            List<Fault> faults,
            @Schema(nullable = true) List<FlowNodeShare> byNode) {

        public FlowNodeView(
                String id,
                NodeKind kind,
                NodeRole role,
                String label,
                Integer members,
                Long messageCount,
                Long consumerCount,
                List<String> routingTypes,
                List<String> protocols,
                List<String> hosts,
                List<String> users,
                List<String> brokerNodes,
                List<Fault> faults) {
            this(
                    id,
                    kind,
                    role,
                    label,
                    members,
                    messageCount,
                    consumerCount,
                    routingTypes,
                    protocols,
                    hosts,
                    users,
                    brokerNodes,
                    faults,
                    null);
        }

        public FlowNodeView withByNode(List<FlowNodeShare> shares) {
            return new FlowNodeView(
                    id,
                    kind,
                    role,
                    label,
                    members,
                    messageCount,
                    consumerCount,
                    routingTypes,
                    protocols,
                    hosts,
                    users,
                    brokerNodes,
                    faults,
                    shares);
        }
    }

    /**
     * One broker node's part of a queue or address (ADR-0110), when the breakdown was asked for.
     *
     * @param messageCount waiting messages on this node; null when not known
     * @param consumerCount consumers attached on this node; null when not known
     * @param inRate messages added per second on this node; null while not measurable, never 0
     * @param outRate messages acknowledged per second on this node; null while not measurable
     * @param stale the node's figures are older than three of their source's intervals
     */
    public record FlowNodeShare(
            @Schema(requiredMode = REQUIRED) String nodeId,
            @Schema(requiredMode = REQUIRED) String node,
            @Schema(nullable = true) Long messageCount,
            @Schema(nullable = true) Long consumerCount,
            @Schema(nullable = true) Double inRate,
            @Schema(nullable = true) Double outRate,
            boolean stale) {}

    /** One broker node's part of an edge's rate (ADR-0110). Null rate: not measurable, never 0. */
    public record FlowNodeRate(
            @Schema(requiredMode = REQUIRED) String nodeId,
            @Schema(requiredMode = REQUIRED) String node,
            @Schema(nullable = true) Double rate,
            @Schema(nullable = true) Instant asOf,
            boolean stale) {}

    /**
     * @param rate messages per second, or null while not measurable
     * @param asOf when the rate was measured; null when it has not been
     * @param averagedOverSeconds set when the rate is an average over a span much longer than a sweep
     * @param stale the rate is older than three of its source's intervals
     * @param delivery for a route edge, whether its queue receives copies or shares
     * @param members producers or consumers the edge aggregates; null otherwise
     * @param exclusive for a divert, whether it takes the message rather than copying it
     * @param filter a divert's, bridge's or filtered queue's selector
     * @param transformer a divert's or bridge's transformer class
     * @param bypassed for a route edge, an exclusive divert without a filter takes this address's
     *     messages before they reach the queue
     * @param presentOn nodes a divert or bridge is deployed on; null for other kinds
     * @param presentOf nodes sampled, against which {@code presentOn} is counted
     * @param rahdar the object is Rahdar's own (a capture tap)
     */
    public record FlowEdgeView(
            String id,
            EdgeKind kind,
            String source,
            String target,
            @Schema(nullable = true) Double rate,
            RateSource rateSource,
            @Schema(nullable = true) Instant asOf,
            @Schema(nullable = true) Long averagedOverSeconds,
            boolean stale,
            @Schema(nullable = true) FlowDelivery delivery,
            @Schema(nullable = true) Integer members,
            @Schema(nullable = true) Boolean exclusive,
            @Schema(nullable = true) String filter,
            @Schema(nullable = true) String transformer,
            boolean bypassed,
            @Schema(nullable = true) Integer presentOn,
            @Schema(nullable = true) Integer presentOf,
            boolean rahdar,
            List<Fault> faults,
            @Schema(nullable = true) List<FlowNodeRate> byNode) {

        public FlowEdgeView(
                String id,
                EdgeKind kind,
                String source,
                String target,
                Double rate,
                RateSource rateSource,
                Instant asOf,
                Long averagedOverSeconds,
                boolean stale,
                FlowDelivery delivery,
                Integer members,
                Boolean exclusive,
                String filter,
                String transformer,
                boolean bypassed,
                Integer presentOn,
                Integer presentOf,
                boolean rahdar,
                List<Fault> faults) {
            this(
                    id,
                    kind,
                    source,
                    target,
                    rate,
                    rateSource,
                    asOf,
                    averagedOverSeconds,
                    stale,
                    delivery,
                    members,
                    exclusive,
                    filter,
                    transformer,
                    bypassed,
                    presentOn,
                    presentOf,
                    rahdar,
                    faults,
                    null);
        }

        public FlowEdgeView withByNode(List<FlowNodeRate> rates) {
            return new FlowEdgeView(
                    id,
                    kind,
                    source,
                    target,
                    rate,
                    rateSource,
                    asOf,
                    averagedOverSeconds,
                    stale,
                    delivery,
                    members,
                    exclusive,
                    filter,
                    transformer,
                    bypassed,
                    presentOn,
                    presentOf,
                    rahdar,
                    faults,
                    rates);
        }
    }

    /** Totals over every path in the cluster, not only the shown ones. Rates are null when none is known. */
    public record FlowKpis(
            @Schema(nullable = true) Double inRate,
            @Schema(nullable = true) Double outRate,
            long backlog,
            int clients,
            int faults) {}

    /**
     * @param paths every address-to-queue path in the cluster, plus addresses produced to without a queue
     * @param shown the paths drawn
     * @param limit the bound in effect
     * @param clamped the request asked for more than the server allows
     */
    public record FlowTotals(
            int paths,
            int shown,
            int limit,
            boolean clamped,

            @Schema(
                    nullable = true,
                    description = "ROWS when more paths matched than the limit, so some are not drawn.")
            HeavyRequests.Bound bound) {}

    /**
     * @param producersTotal, consumersTotal what the node reported having; above the seen counts when truncated
     * @param brokerXmlSnippet for a refused permission, the management access that grants it
     * @param backlog, consumers, inRate, outRate the node's totals over every queue, with a breakdown
     *     only (ADR-0110); null otherwise, and null for a figure not known
     */
    public record FlowBrokerNodeView(
            String nodeId,
            String name,
            NodeSampleState state,
            @Schema(nullable = true) String message,
            @Schema(nullable = true) Instant sampledAt,
            int producersSeen,
            int producersTotal,
            int consumersSeen,
            int consumersTotal,
            boolean truncated,
            @Schema(nullable = true) String brokerXmlSnippet,
            @Schema(nullable = true) Long backlog,
            @Schema(nullable = true) Long consumers,
            @Schema(nullable = true) Double inRate,
            @Schema(nullable = true) Double outRate) {

        public FlowBrokerNodeView(
                String nodeId,
                String name,
                NodeSampleState state,
                String message,
                Instant sampledAt,
                int producersSeen,
                int producersTotal,
                int consumersSeen,
                int consumersTotal,
                boolean truncated,
                String brokerXmlSnippet) {
            this(
                    nodeId,
                    name,
                    state,
                    message,
                    sampledAt,
                    producersSeen,
                    producersTotal,
                    consumersSeen,
                    consumersTotal,
                    truncated,
                    brokerXmlSnippet,
                    null,
                    null,
                    null,
                    null);
        }

        /** This node's totals over every queue (ADR-0110), when the breakdown was asked for. */
        public FlowBrokerNodeView withTotals(Long backlog, Long consumers, Double inRate, Double outRate) {
            return new FlowBrokerNodeView(
                    nodeId,
                    name,
                    state,
                    message,
                    sampledAt,
                    producersSeen,
                    producersTotal,
                    consumersSeen,
                    consumersTotal,
                    truncated,
                    brokerXmlSnippet,
                    backlog,
                    consumers,
                    inRate,
                    outRate);
        }
    }

    /** @param matched false when the focus names nothing currently in the cluster */
    public record FlowFocusView(String kind, String name, int hops, boolean matched) {}

    /**
     * @param sampledAt the newest client sample; null before the first sweep
     * @param measuring no sweep has completed since this cluster became observed
     * @param layers the routing layers drawn
     * @param assumptions what the drawing assumes and cannot read from the broker, in words
     */
    public record FlowGraphView(
            List<FlowNodeView> nodes,
            List<FlowEdgeView> edges,
            FlowKpis kpis,
            FlowTotals totals,
            @Schema(nullable = true) FlowFocusView focus,
            @Schema(nullable = true) Instant sampledAt,
            boolean measuring,
            long sampleIntervalSeconds,
            List<String> layers,
            List<String> assumptions,
            List<FlowBrokerNodeView> brokerNodes) {}
}
