package io.github.sudoitir.rahdar.feature.resources;

import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.count;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.createdAt;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.idText;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.label;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.text;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.Backlog;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ChannelView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ConnectionView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ConsumerView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.Delivery;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.DestinationView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ProducerView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.SubscriptionNodeCell;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.SubscriptionView;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** One listed resource → its typed view, tagged with the logical node it was read from. */
@Component
public class ResourceViewMapper {

    /** The logical node a fanned-out row belongs to. */
    public record NodeRef(UUID id, String name) {}

    /** The aggregation key of a subscription across the cluster's nodes. */
    public record SubscriptionKey(String destination, String name, String routingType) {}

    /**
     * {@code subscription_snapshot} rows sharing a {@link SubscriptionKey} → one {@link SubscriptionView}: a
     * per-node cell for each row, cluster totals, and node presence (ADR-0017).
     */
    public SubscriptionView subscription(
            SubscriptionKey key,
            ClusterConnection connection,
            KindId subscriptionKind,
            KindId destinationKind,
            List<QueueSnapshot> rows,
            Map<UUID, String> nodeNames,
            int nodesTotal,
            Instant staleBefore) {
        List<SubscriptionNodeCell> cells = rows.stream()
                .map(r -> new SubscriptionNodeCell(
                        r.nodeId(),
                        nodeNames.getOrDefault(r.nodeId(), r.nodeId().toString()),
                        r.ts() != null && r.ts().isBefore(staleBefore),
                        r.ts(),
                        r.messageCount(),
                        r.consumerCount(),
                        r.deliveringCount(),
                        r.scheduledCount(),
                        r.paused()))
                .sorted((a, b) -> a.nodeName().compareToIgnoreCase(b.nodeName()))
                .toList();
        return new SubscriptionView(
                ResourceId.of(subscriptionKind.value(), key.name()).format(),
                subscriptionKind.value(),
                label(connection, subscriptionKind),
                key.name(),
                ResourceId.of(destinationKind.value(), key.destination()).format(),
                Map.of("routingType", key.routingType()),
                rows.stream().anyMatch(QueueSnapshot::durable),
                Backlog.exact(cells.stream()
                        .mapToLong(SubscriptionNodeCell::messageCount)
                        .sum()),
                cells.stream().mapToLong(SubscriptionNodeCell::consumerCount).sum(),
                cells.stream().mapToLong(SubscriptionNodeCell::deliveringCount).sum(),
                cells.stream().mapToLong(SubscriptionNodeCell::scheduledCount).sum(),
                (int) cells.stream()
                        .map(SubscriptionNodeCell::nodeId)
                        .distinct()
                        .count(),
                nodesTotal,
                cells.stream().anyMatch(SubscriptionNodeCell::paused),
                cells,
                List.of(),
                null);
    }

    public DestinationView destination(Resource r, NodeRef node, ClusterConnection connection) {
        return new DestinationView(
                node.id(),
                node.name(),
                r.id().format(),
                r.id().kind().value(),
                label(connection, r.id().kind()),
                r.id().name(),
                delivery(r),
                count(r, MetricKeys.SUBSCRIPTION_COUNT),
                count(r, MetricKeys.BACKLOG),
                List.of(),
                null);
    }

    public ConsumerView consumer(Resource r, NodeRef node, ClusterConnection connection) {
        return new ConsumerView(
                node.id(),
                node.name(),
                r.id().format(),
                r.id().kind().value(),
                label(connection, r.id().kind()),
                idText(r, StandardAttributes.PARENT),
                idText(r, StandardAttributes.SUBSCRIPTION),
                idText(r, StandardAttributes.DESTINATION),
                text(r, StandardAttributes.PROTOCOL),
                count(r, MetricKeys.DELIVERED),
                count(r, MetricKeys.ACKNOWLEDGED),
                count(r, MetricKeys.IN_FLIGHT),
                List.of());
    }

    public ChannelView channel(Resource r, NodeRef node, ClusterConnection connection) {
        return new ChannelView(
                node.id(),
                node.name(),
                r.id().format(),
                r.id().kind().value(),
                label(connection, r.id().kind()),
                idText(r, StandardAttributes.PARENT),
                text(r, StandardAttributes.PRINCIPAL),
                count(r, MetricKeys.CONSUMER_COUNT),
                count(r, MetricKeys.PRODUCER_COUNT),
                createdAt(r),
                List.of());
    }

    public ConnectionView connection(Resource r, NodeRef node, ClusterConnection connection) {
        return new ConnectionView(
                node.id(),
                node.name(),
                r.id().format(),
                r.id().kind().value(),
                label(connection, r.id().kind()),
                text(r, StandardAttributes.REMOTE_ADDRESS),
                text(r, StandardAttributes.PROTOCOL),
                text(r, StandardAttributes.CLIENT_ID),
                count(r, MetricKeys.CHANNEL_COUNT),
                createdAt(r),
                List.of());
    }

    public ProducerView producer(Resource r, NodeRef node, ClusterConnection connection) {
        return new ProducerView(
                node.id(),
                node.name(),
                r.id().format(),
                r.id().kind().value(),
                label(connection, r.id().kind()),
                text(r, StandardAttributes.CLIENT_ID),
                idText(r, StandardAttributes.PARENT),
                idText(r, StandardAttributes.DESTINATION),
                text(r, StandardAttributes.PROTOCOL),
                count(r, MetricKeys.SENT),
                List.of());
    }

    /** How the destination delivers, in a stable order. */
    private static List<Delivery> delivery(Resource r) {
        return r.attributes()
                .get(StandardAttributes.DELIVERY)
                .map(d -> d.modes().stream()
                        .sorted()
                        .map(mode -> switch (mode) {
                            case POINT_TO_POINT -> Delivery.POINT_TO_POINT;
                            case PUBLISH_SUBSCRIBE -> Delivery.PUBLISH_SUBSCRIBE;
                        })
                        .toList())
                .orElse(List.of());
    }
}
