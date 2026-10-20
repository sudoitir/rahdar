package io.github.sudoitir.rahdar.feature.resources;

import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.count;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.createdAt;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.name;
import static io.github.sudoitir.rahdar.platform.broker.ResourceIds.text;

import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.AddressView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ConnectionView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ConsumerView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.Delivery;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.ProducerView;
import io.github.sudoitir.rahdar.feature.resources.web.ResourceViews.SessionView;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** One listed resource → its typed view, tagged with the logical node it was read from. */
@Component
public class ResourceViewMapper {

    /** The logical node a fanned-out row belongs to. */
    public record NodeRef(UUID id, String name) {}

    public AddressView address(Resource r, NodeRef node) {
        return new AddressView(
                node.id(),
                node.name(),
                r.id().name(),
                delivery(r),
                count(r, MetricKeys.SUBSCRIPTION_COUNT),
                count(r, MetricKeys.BACKLOG),
                List.of(),
                null);
    }

    public ConsumerView consumer(Resource r, NodeRef node) {
        return new ConsumerView(
                node.id(),
                node.name(),
                r.id().name(),
                name(r, StandardAttributes.PARENT),
                name(r, StandardAttributes.SUBSCRIPTION),
                name(r, StandardAttributes.DESTINATION),
                text(r, StandardAttributes.PROTOCOL),
                count(r, MetricKeys.DELIVERED),
                count(r, MetricKeys.ACKNOWLEDGED),
                count(r, MetricKeys.IN_FLIGHT),
                List.of());
    }

    public SessionView session(Resource r, NodeRef node) {
        return new SessionView(
                node.id(),
                node.name(),
                r.id().name(),
                text(r, StandardAttributes.PRINCIPAL),
                name(r, StandardAttributes.PARENT),
                count(r, MetricKeys.CONSUMER_COUNT),
                count(r, MetricKeys.PRODUCER_COUNT),
                createdAt(r),
                List.of());
    }

    public ConnectionView connection(Resource r, NodeRef node) {
        return new ConnectionView(
                node.id(),
                node.name(),
                r.id().name(),
                text(r, StandardAttributes.REMOTE_ADDRESS),
                text(r, StandardAttributes.PROTOCOL),
                text(r, StandardAttributes.CLIENT_ID),
                count(r, MetricKeys.CHANNEL_COUNT),
                createdAt(r),
                List.of());
    }

    public ProducerView producer(Resource r, NodeRef node) {
        return new ProducerView(
                node.id(),
                node.name(),
                r.id().name(),
                text(r, StandardAttributes.CLIENT_ID),
                name(r, StandardAttributes.PARENT),
                name(r, StandardAttributes.DESTINATION),
                text(r, StandardAttributes.PROTOCOL),
                count(r, MetricKeys.SENT),
                List.of());
    }

    /** How the address delivers, in a stable order. */
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
