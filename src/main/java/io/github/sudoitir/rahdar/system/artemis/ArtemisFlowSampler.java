package io.github.sudoitir.rahdar.system.artemis;

import static io.github.sudoitir.rahdar.system.artemis.ListRows.num;
import static io.github.sudoitir.rahdar.system.artemis.ListRows.str;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.FlowSampler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Everything flow reads from one Artemis node, in one batched POST: the producers and consumers, the
 * diverts, the bridges, the store-and-forward, temporary and filtered queues, and the default dead-letter
 * and expiry addresses. A failing entry loses only itself.
 */
@Component
@RequiredArgsConstructor
public class ArtemisFlowSampler implements FlowSampler {

    static final String LIST_PRODUCERS = "listProducers(java.lang.String,int,int)";
    static final String LIST_CONSUMERS = "listConsumers(java.lang.String,int,int)";
    static final String LIST_QUEUES = "listQueues(java.lang.String,int,int)";
    static final String ADDRESS_SETTINGS = "getAddressSettingsAsJSON(java.lang.String)";

    /** Queues a cluster connection creates to move messages to another node, named for that node. */
    static final String STORE_AND_FORWARD_PREFIX = "$.artemis.internal.sf.";

    private static final String STORE_AND_FORWARD_QUEUES =
            "{\"field\":\"name\",\"operation\":\"CONTAINS\",\"value\":\"" + STORE_AND_FORWARD_PREFIX + "\"}";
    private static final String TEMPORARY_QUEUES =
            "{\"field\":\"temporary\",\"operation\":\"EQUALS\",\"value\":\"true\"}";
    private static final String FILTERED_QUEUES = "{\"field\":\"filter\",\"operation\":\"NOT_EQUALS\",\"value\":\"\"}";

    private static final String ADDRESS = "address";

    private final ArtemisNodes nodes;

    @Override
    public String system() {
        return BrokerGateway.SYSTEM;
    }

    @Override
    public FlowSample sample(UUID clusterId, UUID nodeId, int rowCap) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        String mbean = client.resolveBrokerObjectName();
        List<ManagementResponse> entries = client.batch(List.of(
                ManagementRequest.exec(mbean, LIST_PRODUCERS, ListRows.ALL, 1, rowCap),
                ManagementRequest.exec(mbean, LIST_CONSUMERS, ListRows.ALL, 1, rowCap),
                ManagementRequest.readAll(MBeanNames.divertsPattern(mbean)),
                ManagementRequest.readAll(MBeanNames.bridgesPattern(mbean)),
                ManagementRequest.exec(mbean, LIST_QUEUES, STORE_AND_FORWARD_QUEUES, 1, rowCap),
                ManagementRequest.exec(mbean, LIST_QUEUES, TEMPORARY_QUEUES, 1, rowCap),
                ManagementRequest.exec(mbean, LIST_QUEUES, FILTERED_QUEUES, 1, rowCap),
                ManagementRequest.exec(mbean, ADDRESS_SETTINGS, "#")));
        if (entries.size() != 8) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE, "The broker returned an incomplete response.");
        }
        if (lacksNotEquals(entries.get(6))) {
            // An older broker has no NOT_EQUALS filter (ADR-0142): read the queues unfiltered and keep
            // the filtered ones here, as the route parse below already does.
            // ponytail: the cap now counts every queue, so a node with more queues than the cap can
            // miss filtered ones; page through them if a broker that old runs that many.
            entries = new ArrayList<>(entries);
            entries.set(6, client.single(ManagementRequest.exec(mbean, LIST_QUEUES, ListRows.ALL, 1, rowCap)));
        }

        Listing producers = listing(client, entries.get(0));
        Listing consumers = listing(client, entries.get(1));
        List<FlowMember> producerMembers = new ArrayList<>();
        List<FlowMember> consumerMembers = new ArrayList<>();
        boolean counterMissing = false;
        for (JsonNode row : producers.rows()) {
            counterMissing |= !row.has("msgSent");
            producerMembers.add(member(row, str(row, ADDRESS), null, num(row, "msgSent"), 0));
        }
        for (JsonNode row : consumers.rows()) {
            counterMissing |= !row.has("messagesAcknowledged");
            consumerMembers.add(member(
                    row,
                    str(row, ADDRESS),
                    str(row, "queue"),
                    num(row, "messagesAcknowledged"),
                    num(row, "messagesInTransit")));
        }

        List<String> routingErrors = new ArrayList<>();
        List<FlowRoute> routes = routes(client, entries, routingErrors);
        return new FlowSample(
                producerMembers,
                producers.total(),
                consumerMembers,
                consumers.total(),
                routes,
                error(producers, consumers, routingErrors),
                errorKind(producers, consumers, counterMissing, routingErrors));
    }

    /** Artemis before NOT_EQUALS rejects the filter by name; nothing else answers this way. */
    static boolean lacksNotEquals(ManagementResponse res) {
        return !res.ok()
                && res.error() != null
                && res.error().contains("No enum constant")
                && res.error().contains("NOT_EQUALS");
    }

    private static String errorKind(
            Listing producers, Listing consumers, boolean counterMissing, List<String> routingErrors) {
        if (producers.error() != null) {
            return producers.errorKind();
        }
        if (consumers.error() != null) {
            return consumers.errorKind();
        }
        if (counterMissing) {
            return COUNTER_UNAVAILABLE;
        }
        return routingErrors.isEmpty() ? null : ROUTING_UNAVAILABLE;
    }

    private static String error(Listing producers, Listing consumers, List<String> routingErrors) {
        if (producers.error() != null) {
            return producers.error();
        }
        if (consumers.error() != null) {
            return consumers.error();
        }
        return routingErrors.isEmpty() ? null : routingErrors.getFirst();
    }

    /** The routing entries of a node's POST, each read on its own: one failing entry loses only itself. */
    private static List<FlowRoute> routes(ArtemisNode client, List<ManagementResponse> entries, List<String> errors) {
        List<FlowRoute> routes = new ArrayList<>();
        for (JsonNode attrs : patternRead(entries.get(2), errors)) {
            DivertRow d = DivertRow.parse(attrs, null, null);
            routes.add(new FlowRoute(
                    "DIVERT",
                    d.uniqueName(),
                    d.address(),
                    d.forwardingAddress(),
                    d.filter(),
                    d.transformerClassName(),
                    d.exclusive(),
                    true,
                    0));
        }
        for (JsonNode attrs : patternRead(entries.get(3), errors)) {
            BridgeRow b = BridgeRow.parse(attrs, null, null);
            routes.add(new FlowRoute(
                    "BRIDGE",
                    b.name(),
                    b.queueName(),
                    b.forwardingAddress(),
                    b.filterString(),
                    b.transformerClassName(),
                    false,
                    b.connected(),
                    b.messagesAcknowledged()));
        }
        for (JsonNode row : queueRows(client, entries.get(4), errors)) {
            String name = str(row, "name");
            if (name != null && name.startsWith(STORE_AND_FORWARD_PREFIX)) {
                routes.add(new FlowRoute(
                        "STORE_AND_FORWARD",
                        name,
                        str(row, ADDRESS),
                        receivingNodeId(name),
                        null,
                        null,
                        false,
                        true,
                        num(row, "messagesAdded")));
            }
        }
        queueRows(client, entries.get(5), errors).stream()
                .map(row -> new FlowRoute(
                        "TEMPORARY_QUEUE",
                        str(row, "name"),
                        str(row, ADDRESS),
                        str(row, "name"),
                        null,
                        null,
                        false,
                        true,
                        0))
                .forEach(routes::add);
        for (JsonNode row : queueRows(client, entries.get(6), errors)) {
            String filter = str(row, "filter");
            if (filter != null && !filter.isBlank()) {
                routes.add(new FlowRoute(
                        "QUEUE_FILTER",
                        str(row, "name"),
                        str(row, ADDRESS),
                        str(row, "name"),
                        filter,
                        null,
                        false,
                        true,
                        0));
            }
        }
        ManagementResponse settings = entries.get(7);
        if (settings.ok()) {
            JsonNode parsed = client.parsed(settings);
            addressRoute(routes, "DEAD_LETTER", str(parsed, "deadLetterAddress"));
            addressRoute(routes, "EXPIRY", str(parsed, "expiryAddress"));
        } else {
            errors.add(entryError(settings));
        }
        return routes;
    }

    private static void addressRoute(List<FlowRoute> routes, String kind, String address) {
        if (address != null && !address.isBlank()) {
            routes.add(new FlowRoute(kind, "#", "#", address, null, null, false, true, 0));
        }
    }

    /**
     * The attribute maps of a Jolokia pattern read. A pattern that matches nothing answers 404 for that
     * entry alone, which means "none of these on this node", not a failure.
     */
    private static List<JsonNode> patternRead(ManagementResponse entry, List<String> errors) {
        if (entry.status() == 404) {
            return List.of();
        }
        if (!entry.ok()) {
            errors.add(entryError(entry));
            return List.of();
        }
        JsonNode value = entry.value();
        List<JsonNode> out = new ArrayList<>();
        if (value != null && value.isObject()) {
            for (String objectName : value.propertyNames()) {
                JsonNode attrs = value.get(objectName);
                if (attrs != null && attrs.isObject()) {
                    out.add(attrs);
                }
            }
        }
        return out;
    }

    private static List<JsonNode> queueRows(ArtemisNode client, ManagementResponse entry, List<String> errors) {
        Listing listing = listing(client, entry);
        if (listing.error() != null) {
            errors.add(listing.error());
        }
        return listing.rows();
    }

    /** {@code $.artemis.internal.sf.<cluster-connection>.<nodeID>}: the node id is the last segment. */
    static String receivingNodeId(String storeAndForwardQueue) {
        int dot = storeAndForwardQueue.lastIndexOf('.');
        return dot < 0 ? storeAndForwardQueue : storeAndForwardQueue.substring(dot + 1);
    }

    private static FlowMember member(JsonNode row, String address, String queue, long counter, long unacked) {
        return new FlowMember(
                memberId(row),
                str(row, "clientID"),
                firstNonBlank(str(row, "validatedUser"), str(row, "user")),
                str(row, "remoteAddress"),
                str(row, "protocol"),
                address,
                queue,
                counter,
                unacked);
    }

    /** Broker ids are unique per node; the session is added so a reused id in a new session is a new member. */
    private static String memberId(JsonNode row) {
        return str(row, "id") + "@" + str(row, "session");
    }

    private static Listing listing(ArtemisNode client, ManagementResponse entry) {
        if (!entry.ok()) {
            String error = entryError(entry);
            String lower = error.toLowerCase(Locale.ROOT);
            String kind = entry.status() == 403 || lower.contains("permission") || lower.contains("security")
                    ? PERMISSION_DENIED
                    : BAD_RESPONSE;
            return new Listing(List.of(), 0, error, kind);
        }
        JsonNode env = client.parsed(entry);
        JsonNode data = env == null ? null : env.get("data");
        List<JsonNode> rows = new ArrayList<>();
        if (data != null && data.isArray()) {
            data.forEach(rows::add);
        }
        long total = env == null ? 0L : env.path("count").asLong(rows.size());
        return new Listing(rows, total, null, null);
    }

    private static String entryError(ManagementResponse entry) {
        return entry.error() != null ? entry.error() : "status " + entry.status();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private record Listing(List<JsonNode> rows, long total, String error, String errorKind) {}
}
