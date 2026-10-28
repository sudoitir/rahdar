package io.github.sudoitir.rahdar.feature.resources.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.kernel.core.KindName;
import io.github.sudoitir.rahdar.kernel.security.TeamRef;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The cross-node resource API (ADR-0017). Every row is tagged with the logical
 * node it came from; subscriptions additionally roll their per-node cells up into
 * cluster totals. Nothing secret appears here.
 *
 * <p>Every resource carries its canonical {@code id}, its {@code kind} and the kind's {@code kindLabel}
 * for people. A field that names another resource holds that resource's canonical id and is named by its
 * role ({@code subscription}, {@code destination}, {@code channel}, {@code connection}).
 *
 * <p>{@code @Schema} on every component so the generated OpenAPI document (and
 * the frontend's {@code schema.d.ts}) declares requiredness and nullability
 * honestly (ADR-0019): required unless marked {@code nullable = true}.
 */
public final class ResourceViews {

    private ResourceViews() {}

    private static final String ALLOWED_ACTIONS = "The actions of the catalogue that apply to this row and that the"
            + " caller holds on it, such as subscription:purge. The console gates its controls from this.";
    private static final String ID = "The resource's canonical id, such as artemis.queue/orders.";
    private static final String KIND = "The id of the resource's kind, such as artemis.queue.";
    private static final String KIND_LABEL = "The kind's name for people, singular and plural.";

    // ---- subscriptions (aggregated from subscription_snapshot) --------------------

    public record SubscriptionView(
            @Schema(requiredMode = REQUIRED, description = ID)
            String id,

            @Schema(requiredMode = REQUIRED, description = KIND)
            String kind,

            @Schema(requiredMode = REQUIRED, description = KIND_LABEL)
            KindName kindLabel,

            @Schema(requiredMode = REQUIRED) String name,

            @Schema(requiredMode = REQUIRED, description = "The id of the destination this subscription is bound to.")
            String destination,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "What the system calls the subscription's own properties, such as its routing type.")
            Map<String, String> nativeAttributes,

            @Schema(requiredMode = REQUIRED) boolean durable,
            @Schema(requiredMode = REQUIRED) long totalMessageCount,
            @Schema(requiredMode = REQUIRED) long totalConsumerCount,
            @Schema(requiredMode = REQUIRED) long totalDeliveringCount,
            @Schema(requiredMode = REQUIRED) long totalScheduledCount,
            @Schema(requiredMode = REQUIRED) int nodesPresent,
            @Schema(requiredMode = REQUIRED) int nodesTotal,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "True when the subscription is paused on at least one node. One paused on some"
                            + " nodes and not others is a divergence the operator needs to see, so this is"
                            + " deliberately 'any', not 'all' — perNode says which.")
            boolean paused,

            @Schema(requiredMode = REQUIRED) List<SubscriptionNodeCell> perNode,

            @Schema(requiredMode = REQUIRED, description = ALLOWED_ACTIONS)
            List<String> allowedActions,

            @Schema(
                    nullable = true,
                    description = "The team whose patterns own this subscription; null when no team does.")
            TeamRef ownerTeam) {

        public SubscriptionView withAccess(List<String> actions, TeamRef owner) {
            return new SubscriptionView(
                    id,
                    kind,
                    kindLabel,
                    name,
                    destination,
                    nativeAttributes,
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

        /** The name of the destination, which the permission checks and the broker calls are keyed by. */
        public String destinationName() {
            return ResourceIds.nameOf(destination);
        }
    }

    /**
     * One node's contribution to a subscription row.
     *
     * @param stale the node's last sweep is older than the freshness window — the
     *     numbers are the last seen, not dropped
     */
    public record SubscriptionNodeCell(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) boolean stale,
            @Schema(nullable = true) Instant lastSeenAt,
            @Schema(requiredMode = REQUIRED) long messageCount,
            @Schema(requiredMode = REQUIRED) long consumerCount,
            @Schema(requiredMode = REQUIRED) long deliveringCount,
            @Schema(requiredMode = REQUIRED) long scheduledCount,
            @Schema(requiredMode = REQUIRED) boolean paused) {}

    /** The cluster a row of the estate-wide list belongs to. */
    public record ClusterRef(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String system) {}

    /** One row of {@code GET /subscriptions}: a subscription and the cluster it is in. */
    public record EstateSubscriptionView(
            @Schema(requiredMode = REQUIRED) ClusterRef cluster,
            @Schema(requiredMode = REQUIRED) SubscriptionView subscription) {}

    // ---- live-through resources (one POST per serving node, merged) -------

    /** How a destination delivers. */
    public enum Delivery {
        /** Each message goes to one consumer. */
        POINT_TO_POINT,
        /** Each message goes to every subscription. */
        PUBLISH_SUBSCRIBE
    }

    public record DestinationView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,

            @Schema(requiredMode = REQUIRED, description = ID)
            String id,

            @Schema(requiredMode = REQUIRED, description = KIND)
            String kind,

            @Schema(requiredMode = REQUIRED, description = KIND_LABEL)
            KindName kindLabel,

            @Schema(requiredMode = REQUIRED) String name,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "How the destination delivers: to one consumer, to every subscription, or both.")
            List<Delivery> delivery,

            @Schema(requiredMode = REQUIRED) long subscriptionCount,
            @Schema(requiredMode = REQUIRED) long messageCount,

            @Schema(requiredMode = REQUIRED, description = ALLOWED_ACTIONS)
            List<String> allowedActions,

            @Schema(nullable = true, description = "The team whose patterns own this destination; null when none does.")
            TeamRef ownerTeam) {

        public DestinationView withAccess(List<String> actions, TeamRef owner) {
            return new DestinationView(
                    nodeId,
                    nodeName,
                    id,
                    kind,
                    kindLabel,
                    name,
                    delivery,
                    subscriptionCount,
                    messageCount,
                    actions,
                    owner);
        }
    }

    public record ConsumerView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,

            @Schema(requiredMode = REQUIRED, description = ID)
            String id,

            @Schema(requiredMode = REQUIRED, description = KIND)
            String kind,

            @Schema(requiredMode = REQUIRED, description = KIND_LABEL)
            KindName kindLabel,

            @Schema(nullable = true, description = "The id of the channel the consumer runs in.")
            String channel,

            @Schema(nullable = true, description = "The id of the subscription it consumes.")
            String subscription,

            @Schema(nullable = true, description = "The id of the destination of that subscription.")
            String destination,

            @Schema(nullable = true) String protocol,
            @Schema(requiredMode = REQUIRED) long messagesDelivered,
            @Schema(requiredMode = REQUIRED) long messagesAcknowledged,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "Messages delivered to this consumer and not yet acknowledged.")
            long messagesInFlight,

            @Schema(requiredMode = REQUIRED, description = ALLOWED_ACTIONS)
            List<String> allowedActions) {

        public ConsumerView withAllowedActions(List<String> actions) {
            return new ConsumerView(
                    nodeId,
                    nodeName,
                    id,
                    kind,
                    kindLabel,
                    channel,
                    subscription,
                    destination,
                    protocol,
                    messagesDelivered,
                    messagesAcknowledged,
                    messagesInFlight,
                    actions);
        }

        /** The name of the subscription, which the permission checks are keyed by. */
        public String subscriptionName() {
            return ResourceIds.nameOf(subscription);
        }
    }

    public record ChannelView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,

            @Schema(requiredMode = REQUIRED, description = ID)
            String id,

            @Schema(requiredMode = REQUIRED, description = KIND)
            String kind,

            @Schema(requiredMode = REQUIRED, description = KIND_LABEL)
            KindName kindLabel,

            @Schema(nullable = true, description = "The id of the connection the channel belongs to.")
            String connection,

            @Schema(nullable = true) String user,
            @Schema(requiredMode = REQUIRED) long consumerCount,
            @Schema(requiredMode = REQUIRED) long producerCount,
            @Schema(nullable = true) String creationTime,

            @Schema(requiredMode = REQUIRED, description = ALLOWED_ACTIONS)
            List<String> allowedActions) {

        public ChannelView withAllowedActions(List<String> actions) {
            return new ChannelView(
                    nodeId,
                    nodeName,
                    id,
                    kind,
                    kindLabel,
                    connection,
                    user,
                    consumerCount,
                    producerCount,
                    creationTime,
                    actions);
        }

        /** The channel as a caller who may see only part of it sees it. */
        public ChannelView trimmedTo(long consumers, long producers) {
            return new ChannelView(
                    nodeId,
                    nodeName,
                    id,
                    kind,
                    kindLabel,
                    connection,
                    user,
                    consumers,
                    producers,
                    creationTime,
                    allowedActions);
        }
    }

    public record ConnectionView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,

            @Schema(requiredMode = REQUIRED, description = ID)
            String id,

            @Schema(requiredMode = REQUIRED, description = KIND)
            String kind,

            @Schema(requiredMode = REQUIRED, description = KIND_LABEL)
            KindName kindLabel,

            @Schema(nullable = true) String remoteAddress,
            @Schema(nullable = true) String protocol,
            @Schema(nullable = true) String clientId,
            @Schema(requiredMode = REQUIRED) long channelCount,
            @Schema(nullable = true) String creationTime,

            @Schema(requiredMode = REQUIRED, description = ALLOWED_ACTIONS)
            List<String> allowedActions) {

        public ConnectionView withAllowedActions(List<String> actions) {
            return new ConnectionView(
                    nodeId,
                    nodeName,
                    id,
                    kind,
                    kindLabel,
                    remoteAddress,
                    protocol,
                    clientId,
                    channelCount,
                    creationTime,
                    actions);
        }

        /** The connection as a caller who may see only some of its channels sees it. */
        public ConnectionView trimmedTo(long channels) {
            return new ConnectionView(
                    nodeId,
                    nodeName,
                    id,
                    kind,
                    kindLabel,
                    remoteAddress,
                    protocol,
                    clientId,
                    channels,
                    creationTime,
                    allowedActions);
        }
    }

    public record ProducerView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,

            @Schema(requiredMode = REQUIRED, description = ID)
            String id,

            @Schema(requiredMode = REQUIRED, description = KIND)
            String kind,

            @Schema(requiredMode = REQUIRED, description = KIND_LABEL)
            KindName kindLabel,

            @Schema(nullable = true) String clientId,

            @Schema(nullable = true, description = "The id of the channel the producer runs in.")
            String channel,

            @Schema(nullable = true, description = "The id of the destination it sends to.")
            String destination,

            @Schema(nullable = true) String protocol,
            @Schema(requiredMode = REQUIRED) long messagesSent,

            @Schema(requiredMode = REQUIRED, description = ALLOWED_ACTIONS)
            List<String> allowedActions) {

        public ProducerView withAllowedActions(List<String> actions) {
            return new ProducerView(
                    nodeId,
                    nodeName,
                    id,
                    kind,
                    kindLabel,
                    clientId,
                    channel,
                    destination,
                    protocol,
                    messagesSent,
                    actions);
        }

        /** The name of the destination, which the permission checks are keyed by. */
        public String destinationName() {
            return ResourceIds.nameOf(destination);
        }
    }
}
