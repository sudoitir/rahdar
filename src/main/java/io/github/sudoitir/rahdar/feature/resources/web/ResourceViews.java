package io.github.sudoitir.rahdar.feature.resources.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.kernel.security.TeamRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The cross-node resource API (ADR-0017). Every row is tagged with the logical
 * node it came from; queues additionally roll their per-node cells up into
 * cluster totals. Nothing secret appears here.
 *
 * <p>{@code @Schema} on every component so the generated OpenAPI document (and
 * the frontend's {@code schema.d.ts}) declares requiredness and nullability
 * honestly (ADR-0019): required unless marked {@code nullable = true}.
 */
public final class ResourceViews {

    private ResourceViews() {}

    // ---- queues (aggregated from queue_snapshot) --------------------------

    public record QueueView(
            @Schema(requiredMode = REQUIRED) String address,
            @Schema(requiredMode = REQUIRED) String queueName,
            @Schema(requiredMode = REQUIRED) String routingType,
            @Schema(requiredMode = REQUIRED) boolean durable,
            @Schema(requiredMode = REQUIRED) long totalMessageCount,
            @Schema(requiredMode = REQUIRED) long totalConsumerCount,
            @Schema(requiredMode = REQUIRED) long totalDeliveringCount,
            @Schema(requiredMode = REQUIRED) long totalScheduledCount,
            @Schema(requiredMode = REQUIRED) int nodesPresent,
            @Schema(requiredMode = REQUIRED) int nodesTotal,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "True when the queue is paused on at least one node. A queue paused on some"
                            + " nodes and not others is a divergence the operator needs to see, so this is"
                            + " deliberately 'any', not 'all' — perNode says which.")
            boolean paused,

            @Schema(requiredMode = REQUIRED) List<QueueNodeCell> perNode,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The actions of the catalogue that apply to this row and that the caller holds"
                            + " on it, such as queue:purge. The console gates its controls from this.")
            List<String> allowedActions,

            @Schema(nullable = true, description = "The team whose patterns own this queue; null when no team does.")
            TeamRef ownerTeam) {

        public QueueView withAccess(List<String> actions, TeamRef owner) {
            return new QueueView(
                    address,
                    queueName,
                    routingType,
                    durable,
                    totalMessageCount,
                    totalConsumerCount,
                    totalDeliveringCount,
                    totalScheduledCount,
                    nodesPresent,
                    nodesTotal,
                    paused,
                    perNode,
                    actions,
                    owner);
        }
    }

    /**
     * One node's contribution to a queue row.
     *
     * @param stale the node's last sweep is older than the freshness window — the
     *     numbers are the last seen, not dropped
     */
    public record QueueNodeCell(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) boolean stale,
            @Schema(nullable = true) Instant lastSeenAt,
            @Schema(requiredMode = REQUIRED) long messageCount,
            @Schema(requiredMode = REQUIRED) long consumerCount,
            @Schema(requiredMode = REQUIRED) long deliveringCount,
            @Schema(requiredMode = REQUIRED) long scheduledCount,
            @Schema(requiredMode = REQUIRED) boolean paused) {}

    // ---- live-through resources (one POST per serving node, merged) -------

    /** How a destination delivers. */
    public enum Delivery {
        /** Each message goes to one consumer. */
        POINT_TO_POINT,
        /** Each message goes to every subscription. */
        PUBLISH_SUBSCRIBE
    }

    public record AddressView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) String name,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "How the address delivers: to one consumer, to every subscription, or both.")
            List<Delivery> delivery,

            @Schema(requiredMode = REQUIRED) long queueCount,
            @Schema(requiredMode = REQUIRED) long messageCount,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The actions of the catalogue that apply to this row and that the caller holds"
                            + " on it, such as queue:purge. The console gates its controls from this.")
            List<String> allowedActions,

            @Schema(nullable = true, description = "The team whose patterns own this address; null when no team does.")
            TeamRef ownerTeam) {

        public AddressView withAccess(List<String> actions, TeamRef owner) {
            return new AddressView(nodeId, nodeName, name, delivery, queueCount, messageCount, actions, owner);
        }
    }

    public record ConsumerView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(nullable = true) String consumerId,
            @Schema(nullable = true) String sessionId,
            @Schema(nullable = true) String queueName,
            @Schema(nullable = true) String address,
            @Schema(nullable = true) String protocol,
            @Schema(requiredMode = REQUIRED) long messagesDelivered,
            @Schema(requiredMode = REQUIRED) long messagesAcknowledged,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Messages delivered to this consumer and not yet acknowledged.")
            long messagesInFlight,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The actions of the catalogue that apply to this row and that the caller holds"
                            + " on it, such as queue:purge. The console gates its controls from this.")
            List<String> allowedActions) {

        public ConsumerView withAllowedActions(List<String> actions) {
            return new ConsumerView(
                    nodeId,
                    nodeName,
                    consumerId,
                    sessionId,
                    queueName,
                    address,
                    protocol,
                    messagesDelivered,
                    messagesAcknowledged,
                    messagesInFlight,
                    actions);
        }
    }

    public record SessionView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(nullable = true) String sessionId,
            @Schema(nullable = true) String user,
            @Schema(nullable = true) String connectionId,
            @Schema(requiredMode = REQUIRED) long consumerCount,
            @Schema(requiredMode = REQUIRED) long producerCount,
            @Schema(nullable = true) String creationTime,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The actions of the catalogue that apply to this row and that the caller holds"
                            + " on it, such as queue:purge. The console gates its controls from this.")
            List<String> allowedActions) {

        public SessionView withAllowedActions(List<String> actions) {
            return new SessionView(
                    nodeId,
                    nodeName,
                    sessionId,
                    user,
                    connectionId,
                    consumerCount,
                    producerCount,
                    creationTime,
                    actions);
        }

        /** The session as a caller who may see only part of it sees it. */
        public SessionView trimmedTo(long consumers, long producers) {
            return new SessionView(
                    nodeId,
                    nodeName,
                    sessionId,
                    user,
                    connectionId,
                    consumers,
                    producers,
                    creationTime,
                    allowedActions);
        }
    }

    public record ConnectionView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(nullable = true) String connectionId,
            @Schema(nullable = true) String remoteAddress,
            @Schema(nullable = true) String protocol,
            @Schema(nullable = true) String clientId,
            @Schema(requiredMode = REQUIRED) long sessionCount,
            @Schema(nullable = true) String creationTime,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The actions of the catalogue that apply to this row and that the caller holds"
                            + " on it, such as queue:purge. The console gates its controls from this.")
            List<String> allowedActions) {

        public ConnectionView withAllowedActions(List<String> actions) {
            return new ConnectionView(
                    nodeId,
                    nodeName,
                    connectionId,
                    remoteAddress,
                    protocol,
                    clientId,
                    sessionCount,
                    creationTime,
                    actions);
        }

        /** The connection as a caller who may see only some of its sessions sees it. */
        public ConnectionView trimmedTo(long sessions) {
            return new ConnectionView(
                    nodeId,
                    nodeName,
                    connectionId,
                    remoteAddress,
                    protocol,
                    clientId,
                    sessions,
                    creationTime,
                    allowedActions);
        }
    }

    public record ProducerView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(nullable = true) String producerId,
            @Schema(nullable = true) String clientId,
            @Schema(nullable = true) String sessionId,
            @Schema(nullable = true) String address,
            @Schema(nullable = true) String protocol,
            @Schema(requiredMode = REQUIRED) long messagesSent,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The actions of the catalogue that apply to this row and that the caller holds"
                            + " on it, such as queue:purge. The console gates its controls from this.")
            List<String> allowedActions) {

        public ProducerView withAllowedActions(List<String> actions) {
            return new ProducerView(
                    nodeId, nodeName, producerId, clientId, sessionId, address, protocol, messagesSent, actions);
        }
    }
}
