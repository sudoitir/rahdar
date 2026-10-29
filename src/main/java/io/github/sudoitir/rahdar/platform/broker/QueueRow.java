package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.model.AttributeKey;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.Precision;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import java.util.LinkedHashMap;
import java.util.List;
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
        Long messageCount,
        long consumerCount,
        long deliveringCount,
        long scheduledCount,
        long messagesAdded,
        long messagesAcked,
        long messagesExpired,
        boolean paused,
        String kind,
        Map<String, String> attributes) {

    /**
     * The path of a subscription whose id has more than one segment (a group on a topic), encoded as the id shows
     * it, so the snapshot's one name column can hold it and the id can be built back.
     */
    public static final String PATH = "rahdar.path";

    /** How far the backlog can be trusted when it is only an estimate, and why. */
    public static final String BACKLOG_PRECISION = "rahdar.backlog_precision";

    public static final String BACKLOG_REASON = "rahdar.backlog_reason";

    /** Whether the subscription has a per-partition breakdown of its backlog to read. */
    public static final String BACKLOG_BREAKDOWN = "rahdar.backlog_breakdown";

    /** The native attribute that holds a queue's routing type. */
    public static final String ROUTING_TYPE = "artemis.routing_type";

    /** A queue as the broker SDK lists it. */
    public static QueueRow of(UUID clusterId, UUID nodeId, Resource queue) {
        Attributes a = queue.attributes();
        List<String> segments = queue.id().path().segments();
        String path = queue.id().format().substring(queue.id().kind().value().length() + 1);
        Map<String, String> extra = new LinkedHashMap<>();
        if (segments.size() > 1) {
            extra.put(PATH, path);
        }
        if (a.get(MetricKeys.BACKLOG).isEmpty()) {
            extra.put(
                    BACKLOG_REASON,
                    "The system reported no backlog for it, such as a group with no committed offsets.");
        }
        a.get(MetricKeys.BACKLOG).ifPresent(backlog -> {
            if (backlog.precision() == Precision.ESTIMATE) {
                extra.put(BACKLOG_PRECISION, Precision.ESTIMATE.name());
                queue.conditions().stream()
                        .filter(c -> c.type().endsWith(".backlog_estimate") && c.status() == ConditionStatus.TRUE)
                        .findFirst()
                        .ifPresent(c -> extra.put(BACKLOG_REASON, c.message()));
            }
            if (!backlog.breakdown().isEmpty()) {
                extra.put(BACKLOG_BREAKDOWN, "true");
            }
        });
        return new QueueRow(
                clusterId,
                nodeId,
                a.get(StandardAttributes.DESTINATION).map(ResourceId::name).orElse(null),
                segments.size() > 1 ? path : queue.id().name(),
                a.raw(ROUTING_TYPE).map(Object::toString).orElse(null),
                a.get(StandardAttributes.DURABLE).orElse(false),
                a.get(MetricKeys.BACKLOG).map(MetricValue::asLong).orElse(null),
                metric(a, MetricKeys.CONSUMER_COUNT),
                metric(a, MetricKeys.IN_FLIGHT),
                metric(a, MetricKeys.SCHEDULED),
                metric(a, MetricKeys.ENQUEUED),
                metric(a, MetricKeys.ACKNOWLEDGED),
                metric(a, MetricKeys.EXPIRED),
                a.get(StandardAttributes.PAUSED).orElse(false),
                queue.id().kind().value(),
                extra);
    }

    private static long metric(Attributes a, AttributeKey<MetricValue> key) {
        return a.get(key).map(MetricValue::asLong).orElse(0L);
    }

    /** False when the system gave no backlog: {@link #messageCount} is then null, never zero. */
    public boolean backlogKnown() {
        return messageCount != null;
    }

    /** What the system says about the queue beyond the standard attributes, by native name, as the snapshot keeps it. */
    public Map<String, String> nativeAttributes() {
        Map<String, String> all = new LinkedHashMap<>(attributes);
        if (routingType != null) {
            all.put(ROUTING_TYPE, routingType);
        }
        return all;
    }

    /** True when this queue had traffic worth re-reading on the fast tier. */
    public boolean busy() {
        return consumerCount > 0
                || (messageCount != null && messageCount > 0)
                || deliveringCount > 0
                || scheduledCount > 0;
    }
}
