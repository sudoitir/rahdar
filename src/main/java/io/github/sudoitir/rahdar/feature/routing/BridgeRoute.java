package io.github.sudoitir.rahdar.feature.routing;

import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.KindId;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.RouteEndpoint;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import io.github.sudoitir.rahdar.platform.broker.ResourceIds;
import java.util.List;
import java.util.stream.Stream;

/** One bridge as a node reports it: a route that forwards a queue's messages to another broker. */
record BridgeRoute(
        String name,
        String queueName,
        String forwardingAddress,
        String filterString,
        String discoveryGroupName,
        String transformerClassName,
        List<String> staticConnectors,
        long messagesAcknowledged,
        long messagesPendingAcknowledgement,
        boolean started,
        boolean connected,
        boolean useDuplicateDetection,
        boolean highlyAvailable) {

    private static final KindId KIND = KindId.of("artemis.bridge");
    private static final String DISCOVERY_GROUP = "artemis.discovery_group";
    private static final String TRANSFORMER_CLASS = "artemis.transformer_class";
    private static final String STATIC_CONNECTORS = "artemis.static_connectors";
    private static final String DUPLICATE_DETECTION = "artemis.duplicate_detection";
    private static final String HA = "artemis.ha";

    /** Every bridge on the node. */
    static List<BridgeRoute> list(BrokerTarget node) {
        return BrokerFailures.translated(() ->
                node.connection().resources().read(ResourceQuery.of(KIND).in(node.scope())).orThrow().items().stream()
                        .map(BridgeRoute::of)
                        .toList());
    }

    private static BridgeRoute of(Resource r) {
        String forwardTo = r.attributes()
                .get(StandardAttributes.ROUTE_TARGET)
                .filter(RouteEndpoint.Remote.class::isInstance)
                .map(e -> ((RouteEndpoint.Remote) e).name())
                .filter(n -> !n.isEmpty())
                .orElse(null);
        String queue = r.attributes()
                .get(StandardAttributes.ROUTE_SOURCE)
                .filter(RouteEndpoint.Local.class::isInstance)
                .map(e -> ((RouteEndpoint.Local) e).resource().name())
                .orElse(null);
        return new BridgeRoute(
                r.id().name(),
                queue,
                forwardTo,
                r.attributes().get(StandardAttributes.FILTER).orElse(null),
                text(r, DISCOVERY_GROUP),
                text(r, TRANSFORMER_CLASS),
                connectors(r),
                ResourceIds.count(r, MetricKeys.ACKNOWLEDGED),
                ResourceIds.count(r, MetricKeys.IN_FLIGHT),
                holds(r, ConditionTypes.STARTED),
                holds(r, ConditionTypes.CONNECTED),
                flag(r, DUPLICATE_DETECTION),
                flag(r, HA));
    }

    private static String text(Resource r, String attribute) {
        return r.attributes().raw(attribute).map(String::valueOf).orElse(null);
    }

    private static boolean flag(Resource r, String attribute) {
        return r.attributes()
                .raw(attribute)
                .map(v -> Boolean.parseBoolean(String.valueOf(v)))
                .orElse(false);
    }

    private static List<String> connectors(Resource r) {
        return r.attributes().raw(STATIC_CONNECTORS).stream()
                .flatMap(v -> v instanceof List<?> list ? list.stream() : Stream.empty())
                .map(String::valueOf)
                .toList();
    }

    private static boolean holds(Resource r, String type) {
        return r.conditions().stream().anyMatch(c -> c.type().equals(type) && c.status() == ConditionStatus.TRUE);
    }
}
