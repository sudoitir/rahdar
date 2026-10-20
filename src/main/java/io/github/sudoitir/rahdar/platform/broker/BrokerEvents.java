package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.model.AttributeKey;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The broker SDK's events as Rahdar keeps them. */
final class BrokerEvents {

    private BrokerEvents() {}

    /**
     * An event, its time moved onto Rahdar's clock: a broker's timestamps are on its own, and a flow
     * that subtracted one from a Rahdar instant would call the difference latency (ADR-0053).
     */
    static BrokerEvent of(
            UUID clusterId,
            UUID nodeId,
            io.github.sudoitir.openbroker.notification.BrokerEvent event,
            BrokerTime time) {
        Attributes data = event.data();
        Map<String, Object> kept = new LinkedHashMap<>();
        data.asMap().forEach((name, value) -> kept.put(name, value instanceof ResourceId id ? id.toString() : value));
        return new BrokerEvent(
                clusterId,
                nodeId,
                event.type(),
                time.toRahdarTime(nodeId, event.time().toEpochMilli()),
                name(data, StandardAttributes.DESTINATION),
                name(data, StandardAttributes.SUBSCRIPTION),
                name(data, StandardAttributes.CONSUMER),
                name(data, StandardAttributes.CHANNEL),
                name(data, StandardAttributes.CONNECTION),
                data.get(StandardAttributes.REMOTE_ADDRESS).orElse(null),
                data.get(StandardAttributes.PRINCIPAL).orElse(null),
                kept);
    }

    private static String name(Attributes data, AttributeKey<ResourceId> key) {
        return data.get(key).map(ResourceId::name).orElse(null);
    }
}
