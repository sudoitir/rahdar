package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.TransferAcceptance.AcceptanceFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads what decides whether a node's queue can accept a transfer (ADR-0097, transfer design D4):
 * one batched Jolokia POST per node, charged to that node's limiter like every other call.
 *
 * <p>Each fact is its own operation in the batch, so one the broker cannot answer (an attribute an
 * older broker lacks, a management permission Rahdar does not hold) comes back {@code null} without
 * taking the others with it. {@code null} always means unknown, never zero or false: the verdict
 * states it as unknown (ADR-0049 D5).
 */
@Component
public class AcceptanceProbe {

    /**
     * What a node said about one queue, its address and the broker. {@code queueExists} is false only
     * when the broker said the queue is not there; {@code filter} is empty for a queue without one.
     * {@code diskStoreUsage} is a fraction (0–1); {@code maxDiskUsage} and the percentages are 0–100.
     * {@code addressSettings} is the broker's merged settings for the address, as JSON.
     */
    public record Facts(
            Boolean queueExists,
            String filter,
            String routingType,
            Long ringSize,
            Boolean lastValue,
            Integer consumerCount,
            Long messageCount,
            Long persistentSize,
            Long addressSize,
            Boolean paging,
            Integer addressLimitPercent,
            JsonNode addressSettings,
            Double diskStoreUsage,
            Integer maxDiskUsage,
            Integer addressMemoryUsagePercentage,
            Integer idCacheSize,
            Boolean persistIdCache)
            implements AcceptanceFacts {

        /** How many messages the broker looks at to count a filter; Artemis defaults it to 200. */
        @Override
        public int filterCountLimit() {
            return addressSettings == null
                    ? 200
                    : addressSettings.path("managementBrowsePageSize").asInt(200);
        }

        static Facts unknown() {
            return new Facts(
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null);
        }
    }

    private static final String NOT_FOUND = "javax.management.InstanceNotFoundException";

    public Facts read(ArtemisNode client, String address, String queue, String routingType) {
        List<ManagementResponse> r;
        String broker;
        try {
            broker = client.resolveBrokerObjectName();
            String q = MBeanNames.queue(broker, address, queue, routingType);
            String a = MBeanNames.address(broker, address);
            List<ManagementRequest> requests = new ArrayList<>(reads(q, QUEUE));
            requests.addAll(reads(a, ADDRESS));
            requests.addAll(reads(broker, BROKER));
            requests.add(ManagementRequest.exec(broker, "getAddressSettingsAsJSON(java.lang.String)", address));
            r = client.batch(requests);
            if (r.size() < requests.size()) {
                return Facts.unknown();
            }
        } catch (BrokerConnectionException _) {
            return Facts.unknown();
        }
        int i = 0;
        ManagementResponse filterRead = r.get(i++);
        Boolean exists = exists(filterRead);
        String filter = filterRead.ok() ? text(filterRead, "Filter", "") : null;
        String type = value(r.get(i++), "RoutingType", JsonNode::asText);
        Long ring = value(r.get(i++), "RingSize", JsonNode::asLong);
        Boolean lastValue = value(r.get(i++), "LastValue", JsonNode::asBoolean);
        Integer consumers = value(r.get(i++), "ConsumerCount", JsonNode::asInt);
        Long count = value(r.get(i++), "MessageCount", JsonNode::asLong);
        Long persistent = value(r.get(i++), "PersistentSize", JsonNode::asLong);
        Long addressSize = value(r.get(i++), "AddressSize", JsonNode::asLong);
        Boolean paging = value(r.get(i++), "Paging", JsonNode::asBoolean);
        Integer limit = value(r.get(i++), "AddressLimitPercent", JsonNode::asInt);
        Double disk = value(r.get(i++), "DiskStoreUsage", JsonNode::asDouble);
        Integer maxDisk = value(r.get(i++), "MaxDiskUsage", JsonNode::asInt);
        Integer memory = value(r.get(i++), "AddressMemoryUsagePercentage", JsonNode::asInt);
        Integer idCache = value(r.get(i++), "IDCacheSize", JsonNode::asInt);
        Boolean persistIdCache = value(r.get(i++), "PersistIDCache", JsonNode::asBoolean);
        JsonNode settings = settings(client, r.get(i));
        return new Facts(
                exists,
                filter,
                type,
                ring,
                lastValue,
                consumers,
                count,
                persistent,
                addressSize,
                paging,
                limit,
                settings,
                disk,
                maxDisk,
                memory,
                idCache,
                persistIdCache);
    }

    /** Filter first: whether it answered is also whether the queue exists. */
    private static final List<String> QUEUE = List.of(
            "Filter", "RoutingType", "RingSize", "LastValue", "ConsumerCount", "MessageCount", "PersistentSize");

    private static final List<String> ADDRESS = List.of("AddressSize", "Paging", "AddressLimitPercent");
    private static final List<String> BROKER =
            List.of("DiskStoreUsage", "MaxDiskUsage", "AddressMemoryUsagePercentage", "IDCacheSize", "PersistIDCache");

    private static List<ManagementRequest> reads(String mbean, List<String> attributes) {
        return attributes.stream()
                .map(attribute -> ManagementRequest.read(mbean, attribute))
                .toList();
    }

    /** The filter read answering means the queue exists; a not-found error means it does not; else unknown. */
    private static Boolean exists(ManagementResponse filterRead) {
        if (filterRead.ok()) {
            return Boolean.TRUE;
        }
        return NOT_FOUND.equals(filterRead.errorType()) ? Boolean.FALSE : null;
    }

    private static <T> T value(ManagementResponse res, String attribute, Function<JsonNode, T> as) {
        if (!res.ok()) {
            return null;
        }
        JsonNode v = res.attribute(attribute);
        return v == null || v.isNull() ? null : as.apply(v);
    }

    private static String text(ManagementResponse res, String attribute, String absent) {
        JsonNode v = res.attribute(attribute);
        return v == null || v.isNull() ? absent : v.asText();
    }

    private static JsonNode settings(ArtemisNode client, ManagementResponse res) {
        if (!res.ok()) {
            return null;
        }
        try {
            return client.parsed(res);
        } catch (RuntimeException _) {
            return null;
        }
    }
}
