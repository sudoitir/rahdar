package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The queue and address lifecycle operations over Jolokia (ADR-0049). Each method
 * is exactly one {@code exec} — never a dry-run and an act in the same POST
 * (non-negotiable #1) — except {@link #updateQueue}, which is a read then an exec
 * for the reason given there.
 *
 * <p>These use the <em>non-deprecated</em> JSON {@code QueueConfiguration} API.
 * Every positional {@code createQueue} / {@code updateQueue} overload is
 * {@code @Deprecated} as of Artemis 2.56; the JSON arms are the current surface
 * and {@code createQueue(config, ignoreIfExists)} additionally gives the
 * already-in-state semantics D2 needs for free.
 *
 * <p>A refusal the broker explains — already exists, already gone, bound queues,
 * an immutable field — surfaces as {@link ManagementRefusal} so the service can
 * report it per node without treating it as a connection failure. Anything else
 * is a {@link BrokerConnectionException}.
 */
@Component
public class QueueLifecycleOperations {

    private static final String QUEUE_NAMES = "QueueNames";
    private static final String CONSUMER_COUNT = "ConsumerCount";
    private static final String MESSAGE_COUNT = "MessageCount";
    private static final String PAUSED = "Paused";

    /**
     * Queue MBean attribute -> {@code QueueConfiguration} JSON key. Read back and
     * replayed on every update; see {@link #updateQueue}. Attributes the broker
     * version does not expose are simply absent from the read and skipped.
     */
    private static final Map<String, String> ATTRIBUTE_TO_CONFIG_KEY = Map.ofEntries(
            Map.entry("Name", "name"),
            Map.entry("Address", "address"),
            Map.entry("RoutingType", "routing-type"),
            Map.entry("Filter", "filter-string"),
            Map.entry("Durable", "durable"),
            Map.entry("User", "user"),
            Map.entry("MaxConsumers", "max-consumers"),
            Map.entry("Exclusive", "exclusive"),
            Map.entry("GroupRebalance", "group-rebalance"),
            Map.entry("GroupRebalancePauseDispatch", "group-rebalance-pause-dispatch"),
            Map.entry("GroupBuckets", "group-buckets"),
            Map.entry("GroupFirstKey", "group-first-key"),
            Map.entry("LastValue", "last-value"),
            Map.entry("LastValueKey", "last-value-key"),
            Map.entry("NonDestructive", "non-destructive"),
            Map.entry("PurgeOnNoConsumers", "purge-on-no-consumers"),
            Map.entry("Enabled", "enabled"),
            Map.entry("ConsumersBeforeDispatch", "consumers-before-dispatch"),
            Map.entry("DelayBeforeDispatch", "delay-before-dispatch"),
            Map.entry("ConsumerPriority", "consumer-priority"),
            Map.entry("AutoDelete", "auto-delete"),
            Map.entry("RingSize", "ring-size"),
            Map.entry("ConfigurationManaged", "configuration-managed"),
            Map.entry("Temporary", "temporary"));

    /** Configuration keys the broker will not accept a change to on a live queue (D7). */
    public static final List<String> IMMUTABLE_ON_UPDATE = List.of("name", "address", "routing-type", "durable");

    private final ObjectMapper mapper;

    public QueueLifecycleOperations(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ---- queues ---------------------------------------------------------

    /**
     * Create a queue from a {@code QueueConfiguration} document. Returns the
     * broker's resulting configuration.
     *
     * <p>Sends {@code ignoreIfExists = false} deliberately. The tolerant arm answers
     * 200 with the existing configuration, which cannot be told apart from a
     * successful creation — and this change has to report "created" and "was already
     * there" as different outcomes (D2). So an existing queue comes back as
     * {@code AMQ229019} → {@link ManagementRefusal.Kind#ALREADY}, and the caller
     * reads the existing configuration to decide whether it is the queue that was
     * asked for. That costs a second call only on the path where one is genuinely
     * needed.
     */
    public JsonNode createQueue(ArtemisNode client, String brokerMbean, Map<String, Object> config) {
        ManagementResponse res = client.single(ManagementRequest.exec(
                brokerMbean, "createQueue(java.lang.String,boolean)", mapper.writeValueAsString(config), false));
        require(res, "createQueue");
        return res.valueParsed(mapper);
    }

    /**
     * Apply a sparse patch to a live queue's configuration.
     *
     * <p>This is a read then an exec, and it has to be. Artemis's
     * {@code updateQueue} <em>replaces</em> the configuration rather than merging
     * into it: a document that omits a field clears it, so sending only the
     * operator's changed fields silently destroys the queue's filter. Confirmed
     * against Artemis 2.44 during this change's groundwork. So the current
     * configuration is read back, the patch applied over it, and the whole
     * document sent (D7).
     *
     * <p>The read is not a dry run — it is part of performing the update — so this
     * does not violate the never-preview-and-act-in-one-POST rule.
     */
    public JsonNode updateQueue(ArtemisNode client, String brokerMbean, String queueMbean, Map<String, Object> patch) {
        Map<String, Object> merged = readQueueConfig(client, queueMbean);
        merged.putAll(patch);
        ManagementResponse res = client.single(ManagementRequest.exec(
                brokerMbean, "updateQueue(java.lang.String)", mapper.writeValueAsString(merged)));
        require(res, "updateQueue");
        return res.valueParsed(mapper);
    }

    /**
     * A live queue's configuration as a mutable {@code QueueConfiguration} map,
     * read from its MBean attributes in one call. Also the read half of the
     * create-time comparison that distinguishes {@code ALREADY} from a mismatch.
     */
    public Map<String, Object> readQueueConfig(ArtemisNode client, String queueMbean) {
        ManagementResponse res = client.single(ManagementRequest.readAll(queueMbean));
        require(res, "read queue configuration");
        return toQueueConfig(res.value());
    }

    /**
     * The same attribute-to-configuration mapping for a queue MBean that was read as
     * one entry of a batch, so a caller reading many queues in one POST gets the
     * document {@link #readQueueConfig} would have produced for each.
     */
    public Map<String, Object> toQueueConfig(JsonNode value) {
        Map<String, Object> config = new LinkedHashMap<>();
        if (value == null || !value.isObject()) {
            return config;
        }
        ATTRIBUTE_TO_CONFIG_KEY.forEach((attribute, key) -> {
            JsonNode node = value.get(attribute);
            if (node != null && !node.isNull()) {
                config.put(key, mapper.convertValue(node, Object.class));
            }
        });
        return config;
    }

    /**
     * Destroy a queue. {@code forceAutoDeleteAddress} is false — destroying a queue must not
     * take its address with it, for the same reason address delete is force-free (D8).
     * {@code removeConsumers} closes attached consumers, and is only ever the operator's
     * explicit choice (ADR-0084 D6); without it a queue with consumers raises
     * {@link ManagementRefusal.Kind#HAS_CONSUMERS}. A queue that is already gone raises
     * {@link ManagementRefusal.Kind#ALREADY}.
     */
    public void destroyQueue(ArtemisNode client, String brokerMbean, String queueName, boolean removeConsumers) {
        ManagementResponse res = client.single(ManagementRequest.exec(
                brokerMbean, "destroyQueue(java.lang.String,boolean,boolean)", queueName, removeConsumers, false));
        require(res, "destroyQueue");
    }

    /**
     * What a queue delete checks on one node, read in one POST (ADR-0084).
     *
     * @param present whether the queue is on this node at all
     * @param consumerCount the queue's attached consumers; zero when it is absent
     * @param addressQueues every queue bound to the queue's address on this node
     */
    public record DeleteState(boolean present, long consumerCount, List<String> addressQueues) {}

    public DeleteState deleteState(
            ArtemisNode client, String brokerMbean, String address, String queueName, String routingType) {
        List<ManagementResponse> res = client.batch(List.of(
                ManagementRequest.read(MBeanNames.address(brokerMbean, address), QUEUE_NAMES),
                ManagementRequest.read(
                        MBeanNames.queue(brokerMbean, address, queueName, routingType), CONSUMER_COUNT)));
        // An address that is not there is a fact: the queue is gone. Any other failed read is
        // not, and throwing makes the node "could not be checked" rather than assumed fine.
        if (absent(res.get(0))) {
            return new DeleteState(false, 0L, List.of());
        }
        require(res.get(0), QUEUE_NAMES);
        JsonNode names = res.get(0).attribute(QUEUE_NAMES);
        List<String> addressQueues = names == null || !names.isArray()
                ? List.of()
                : names.valueStream().map(JsonNode::asString).toList();
        if (!addressQueues.contains(queueName)) {
            return new DeleteState(false, 0L, addressQueues);
        }
        require(res.get(1), CONSUMER_COUNT);
        JsonNode consumers = res.get(1).attribute(CONSUMER_COUNT);
        return new DeleteState(true, consumers == null ? 0L : consumers.asLong(), addressQueues);
    }

    private static boolean absent(ManagementResponse res) {
        return !res.ok() && res.errorType() != null && res.errorType().contains("InstanceNotFoundException");
    }

    /** {@code MessageCount} on the queue MBean — the delete estimate the bulk cap is checked against (D6). */
    public long messageCount(ArtemisNode client, String queueMbean) {
        ManagementResponse res = client.single(ManagementRequest.read(queueMbean, MESSAGE_COUNT));
        require(res, MESSAGE_COUNT);
        JsonNode count = res.attribute(MESSAGE_COUNT);
        return count == null ? 0L : count.asLong();
    }

    /** Whether the queue is currently paused — the {@code ALREADY} test for pause / resume. */
    public boolean isPaused(ArtemisNode client, String queueMbean) {
        ManagementResponse res = client.single(ManagementRequest.read(queueMbean, PAUSED));
        require(res, PAUSED);
        JsonNode paused = res.attribute(PAUSED);
        return paused != null && paused.asBoolean();
    }

    /** Pause delivery, persistently so it survives a broker restart. */
    public void pause(ArtemisNode client, String queueMbean) {
        require(client.single(ManagementRequest.exec(queueMbean, "pause(boolean)", true)), "pause");
    }

    public void resume(ArtemisNode client, String queueMbean) {
        require(client.single(ManagementRequest.exec(queueMbean, "resume()")), "resume");
    }

    public void resetMessageCounter(ArtemisNode client, String queueMbean) {
        require(client.single(ManagementRequest.exec(queueMbean, "resetMessageCounter()")), "resetMessageCounter");
    }

    // ---- addresses ------------------------------------------------------

    /** Create an address. One that already exists raises {@link ManagementRefusal.Kind#ALREADY}. */
    public void createAddress(ArtemisNode client, String brokerMbean, String address, String routingTypes) {
        ManagementResponse res = client.single(ManagementRequest.exec(
                brokerMbean, "createAddress(java.lang.String,java.lang.String)", address, routingTypes));
        require(res, "createAddress");
    }

    /**
     * Replace an existing address's routing types with exactly these. The broker refuses
     * to drop a routing type while a queue of that type is bound ({@code AMQ229209},
     * broker-management-notes §15 M8), so this never unbinds a queue.
     */
    public void updateAddress(ArtemisNode client, String brokerMbean, String address, String routingTypes) {
        ManagementResponse res = client.single(ManagementRequest.exec(
                brokerMbean, "updateAddress(java.lang.String,java.lang.String)", address, routingTypes));
        require(res, "updateAddress");
    }

    /**
     * Delete an address, force-free (D8). The broker itself refuses with
     * {@code AMQ229205} while queues are bound, which surfaces as
     * {@link ManagementRefusal.Kind#BOUND_SUBSCRIPTIONS}; the caller names the queues,
     * because the broker's own message does not.
     */
    public void deleteAddress(ArtemisNode client, String brokerMbean, String address) {
        ManagementResponse res =
                client.single(ManagementRequest.exec(brokerMbean, "deleteAddress(java.lang.String)", address));
        require(res, "deleteAddress");
    }

    /** The queues bound to an address — what a {@link ManagementRefusal.Kind#BOUND_SUBSCRIPTIONS} refusal names. */
    public List<String> boundQueues(ArtemisNode client, String addressMbean) {
        ManagementResponse res = client.single(ManagementRequest.read(addressMbean, QUEUE_NAMES));
        if (!res.ok()) {
            return List.of();
        }
        JsonNode names = res.attribute(QUEUE_NAMES);
        if (names == null || !names.isArray()) {
            return List.of();
        }
        return names.valueStream().map(JsonNode::asString).toList();
    }

    // ---- helpers --------------------------------------------------------

    private static void require(ManagementResponse res, String operation) {
        ArtemisNode.require(res, operation);
    }
}
