package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateDestinationRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateSubscriptionRequest;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.UpdateResourceRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lifecycle requests as the tests of Artemis queues and addresses spell them. */
final class LifecycleFixtures {

    private LifecycleFixtures() {}

    @SuppressWarnings("java:S107")
    static CreateSubscriptionRequest createQueue(
            String address,
            String name,
            String routingType,
            boolean durable,
            String filter,
            Integer maxConsumers,
            Boolean purgeOnNoConsumers,
            Boolean exclusive,
            Boolean nonDestructive,
            Long ringSize,
            Boolean autoCreateAddress) {
        Map<String, Object> attributes =
                tuning(filter, maxConsumers, purgeOnNoConsumers, exclusive, nonDestructive, ringSize);
        attributes.put("routingType", routingType);
        attributes.put("durable", durable);
        if (autoCreateAddress != null) {
            attributes.put("autoCreateAddress", autoCreateAddress);
        }
        return new CreateSubscriptionRequest(
                "artemis.queue", name, ResourceId.of("artemis.address", address).format(), attributes);
    }

    static UpdateResourceRequest updateQueue(
            String filter,
            Integer maxConsumers,
            Boolean purgeOnNoConsumers,
            Boolean exclusive,
            Boolean nonDestructive,
            Long ringSize) {
        return new UpdateResourceRequest(
                tuning(filter, maxConsumers, purgeOnNoConsumers, exclusive, nonDestructive, ringSize));
    }

    static CreateDestinationRequest createAddress(String name, String routingTypes) {
        return new CreateDestinationRequest("artemis.address", name, Map.of("routingTypes", routingTypes));
    }

    private static Map<String, Object> tuning(
            String filter,
            Integer maxConsumers,
            Boolean purgeOnNoConsumers,
            Boolean exclusive,
            Boolean nonDestructive,
            Long ringSize) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        put(attributes, "filter", filter);
        put(attributes, "maxConsumers", maxConsumers);
        put(attributes, "purgeOnNoConsumers", purgeOnNoConsumers);
        put(attributes, "exclusive", exclusive);
        put(attributes, "nonDestructive", nonDestructive);
        put(attributes, "ringSize", ringSize);
        return attributes;
    }

    private static void put(Map<String, Object> attributes, String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }
}
