package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.model.AttributeKey;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import java.util.Map;
import java.util.UUID;

/**
 * One subscription as a listing reports it, tagged with the cluster and node it came from: what the scrape
 * keeps in {@code subscription_snapshot}. The broker's internal queues are not listed.
 */
public record QueueRow(
        UUID clusterId,
        UUID nodeId,
        String address,
        String queueName,
        String routingType,
        boolean durable,
        long messageCount,
        long consumerCount,
        long deliveringCount,
        long scheduledCount,
        long messagesAdded,
        long messagesAcked,
        long messagesExpired,
        boolean paused,
        String kind) {

    /** The native attribute that holds a queue's routing type. */
    public static final String ROUTING_TYPE = "artemis.routing_type";

    /** A queue as the broker SDK lists it. */
    public static QueueRow of(UUID clusterId, UUID nodeId, Resource queue) {
        Attributes a = queue.attributes();
        return new QueueRow(
                clusterId,
                nodeId,
                a.get(StandardAttributes.DESTINATION).map(ResourceId::name).orElse(null),
                queue.id().name(),
                a.raw(ROUTING_TYPE).map(Object::toString).orElse("ANYCAST"),
                a.get(StandardAttributes.DURABLE).orElse(false),
                metric(a, MetricKeys.BACKLOG),
                metric(a, MetricKeys.CONSUMER_COUNT),
                metric(a, MetricKeys.IN_FLIGHT),
                metric(a, MetricKeys.SCHEDULED),
                metric(a, MetricKeys.ENQUEUED),
                metric(a, MetricKeys.ACKNOWLEDGED),
                metric(a, MetricKeys.EXPIRED),
                a.get(StandardAttributes.PAUSED).orElse(false),
                queue.id().kind().value());
    }

    private static long metric(Attributes a, AttributeKey<MetricValue> key) {
        return a.get(key).map(MetricValue::asLong).orElse(0L);
    }

    /** What the system says about the queue beyond the standard attributes, by native name, as the snapshot keeps it. */
    public Map<String, String> nativeAttributes() {
        return routingType == null ? Map.of() : Map.of(ROUTING_TYPE, routingType);
    }

    /** True when this queue had traffic worth re-reading on the fast tier. */
    public boolean busy() {
        return consumerCount > 0 || messageCount > 0 || deliveringCount > 0 || scheduledCount > 0;
    }
}
