package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.model.AttributeKey;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.RouteEndpoint;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import java.util.List;
import java.util.Optional;

/**
 * One divert as a node reports it: a route that copies what reaches one address into another. Diverts
 * are named by kind id, a string, so no driver class is involved.
 *
 * @param routingName the name the diverted copy is routed under
 */
public record DivertRoute(
        String name,
        String routingName,
        String address,
        String forwardingAddress,
        String filter,
        String routingType,
        String transformerClassName,
        boolean exclusive) {

    /** The kind of a divert. */
    public static final KindId KIND = KindId.of("artemis.divert");

    /** Attribute names of a divert beyond the standard ones. */
    public static final String ROUTING_NAME = "artemis.routing_name";

    public static final String EXCLUSIVE_ATTRIBUTE = "artemis.exclusive";
    public static final String ROUTING_TYPE = "artemis.routing_type";
    public static final String TRANSFORMER_CLASS = "artemis.transformer_class";

    /** Every divert on the node. */
    public static List<DivertRoute> list(BrokerTarget node) {
        return BrokerFailures.translated(() ->
                node.connection().resources().read(ResourceQuery.of(KIND).in(node.scope())).orThrow().items().stream()
                        .map(DivertRoute::of)
                        .toList());
    }

    /** The divert by name on the node, or empty when it is not deployed there. */
    public static Optional<DivertRoute> find(BrokerTarget node, String name) {
        return list(node).stream().filter(d -> name.equals(d.name())).findFirst();
    }

    /** The id of a divert of this name. */
    public static ResourceId id(String name) {
        return new ResourceId(KIND, ResourcePath.of(name));
    }

    static DivertRoute of(Resource r) {
        return new DivertRoute(
                r.id().name(),
                text(r, ROUTING_NAME),
                end(r, StandardAttributes.ROUTE_SOURCE),
                end(r, StandardAttributes.ROUTE_TARGET),
                r.attributes().get(StandardAttributes.FILTER).orElse(null),
                text(r, ROUTING_TYPE),
                text(r, TRANSFORMER_CLASS),
                r.attributes()
                        .raw(EXCLUSIVE_ATTRIBUTE)
                        .map(v -> Boolean.parseBoolean(String.valueOf(v)))
                        .orElse(false));
    }

    private static String text(Resource r, String attribute) {
        return r.attributes().raw(attribute).map(String::valueOf).orElse(null);
    }

    private static String end(Resource r, AttributeKey<RouteEndpoint> key) {
        return r.attributes()
                .get(key)
                .filter(RouteEndpoint.Local.class::isInstance)
                .map(e -> ((RouteEndpoint.Local) e).resource().name())
                .orElse(null);
    }
}
