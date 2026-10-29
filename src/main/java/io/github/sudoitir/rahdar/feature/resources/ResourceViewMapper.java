package io.github.sudoitir.rahdar.feature.resources;

import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.count;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.createdAt;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.idText;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.label;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.text;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.Precision;
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
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** One listed resource → its typed view, tagged with the logical node it was read from. */
@Component
public class ResourceViewMapper {

    /** The logical node a fanned-out row belongs to. */
    public record NodeRef(UUID id, String name) {}

    /** The aggregation key of a subscription across the cluster's nodes. */
    public record SubscriptionKey(String destination, String name, String routingType) {}

    /** The kinds a rolled-up subscription and its destination are read as. */
    record SubscriptionKinds(KindId subscription, KindId destination) {}

    /**
     * {@code subscription_snapshot} rows sharing a {@link SubscriptionKey} → one {@link SubscriptionView}: a
     * per-node cell for each row, cluster totals, and node presence (ADR-0017).
     */
    public SubscriptionView subscription(
            SubscriptionKey key,
            ClusterConnection connection,
            SubscriptionKinds kinds,
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
        // A subscription is of the kind its rows were scraped as, and its id has as many segments as the system gives
        // it.
        QueueSnapshot first = rows.getFirst();
        KindId kind = first.kind() == null || first.kind().isBlank() ? kinds.subscription() : KindId.of(first.kind());
        String path = first.attributes().get(QueueRow.PATH);
        ResourceId id =
                path == null ? ResourceId.of(kind.value(), key.name()) : ResourceId.parse(kind.value() + "/" + path);
        long total = cells.stream()
                .map(SubscriptionNodeCell::messageCount)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
        return new SubscriptionView(
                id.format(),
                kind.value(),
                label(connection, kind),
                // The group or queue is what a team owns, so it is the name; the destination says which topic.
                path == null ? key.name() : id.path().segments().getFirst(),
                ResourceId.of(kinds.destination().value(), key.destination()).format(),
                key.routingType() == null ? Map.of() : Map.of("routingType", key.routingType()),
                rows.stream().anyMatch(QueueSnapshot::durable),
                backlogOf(rows, total),
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

    /**
     * Exact unless the scrape marked the figure an estimate, which it says with its reason; unavailable when no node
     * reported one.
     */
    private static Backlog backlogOf(List<QueueSnapshot> rows, long total) {
        Map<String, String> attributes = rows.getFirst().attributes();
        long unknown = rows.stream().filter(r -> !r.backlogKnown()).count();
        if (unknown == rows.size()) {
            return new Backlog(null, Backlog.Accuracy.UNAVAILABLE, attributes.get(QueueRow.BACKLOG_REASON), false);
        }
        if (unknown > 0) {
            return new Backlog(
                    total,
                    Backlog.Accuracy.ESTIMATE,
                    "Excludes " + unknown + " of " + rows.size() + " nodes that reported no backlog.",
                    false);
        }
        if (!Precision.ESTIMATE.name().equals(attributes.get(QueueRow.BACKLOG_PRECISION))) {
            return Backlog.exact(total);
        }
        return new Backlog(
                total,
                Backlog.Accuracy.ESTIMATE,
                attributes.get(QueueRow.BACKLOG_REASON),
                Boolean.parseBoolean(attributes.get(QueueRow.BACKLOG_BREAKDOWN)));
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
