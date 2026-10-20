package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.RahdarNames;
import java.util.List;
import java.util.UUID;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import org.springframework.stereotype.Component;

/**
 * The Rahdar-owned staging queue a cross-broker move parks its selection in (ADR-0097), managed over
 * Jolokia on the source node. Every operation is safe to repeat, so a resumed run can call it again
 * whatever the previous attempt got through.
 *
 * <p>The queue is durable, anycast and never auto-deleted. Its exact-match address settings turn off
 * dead-lettering ({@code maxDeliveryAttempts=-1}) and redistribution ({@code redistributionDelay=-1}),
 * so a rolled-back relay batch is never sent to a dead-letter address and a clustered source never
 * moves the parked messages to another node.
 */
@Component
public class StagingQueues {

    /** Every staging queue's name starts with this; nothing else Rahdar touches here does. */
    public static final String PREFIX = RahdarNames.TRANSFER_PREFIX;

    static final String SETTINGS = "{\"maxDeliveryAttempts\":-1,\"redistributionDelay\":-1}";

    private static final String QUEUE_MISSING = "AMQ229017";
    private static final String ADDRESS_MISSING = "AMQ229203";

    /** The staging queue (and address) name of a run. */
    public static String queueName(UUID runId) {
        return RahdarNames.stagingQueue(runId);
    }

    /** Create the staging queue and its address settings, or leave them as they are when they exist. */
    public void create(ArtemisNode client, UUID runId) {
        String queue = queueName(runId);
        String broker = client.resolveBrokerObjectName();
        String config = "{\"name\":\"%s\",\"address\":\"%s\",\"routing-type\":\"ANYCAST\",\"durable\":true,"
                        .formatted(queue, queue)
                + "\"auto-delete\":false,\"auto-create-address\":true}";
        List<ManagementResponse> responses = client.batch(List.of(
                ManagementRequest.exec(
                        broker, "addAddressSettings(java.lang.String,java.lang.String)", queue, SETTINGS),
                ManagementRequest.exec(broker, "createQueue(java.lang.String,boolean)", config, true)));
        requireAll(responses, 2, "create staging queue " + queue);
    }

    /**
     * Destroy an empty staging queue, its address and its address settings. A queue that still holds
     * messages is left alone and {@code false} returned: destroying it would delete them.
     *
     * @throws IllegalArgumentException for a queue that is not a staging queue
     */
    public boolean destroyIfEmpty(ArtemisNode client, String queue) {
        requireStaging(queue);
        String broker = client.resolveBrokerObjectName();
        ManagementResponse depth = client.single(
                ManagementRequest.read(MBeanNames.queue(broker, queue, queue, "ANYCAST"), "MessageCount"));
        if (depth.ok()) {
            if (depth.attribute("MessageCount").asLong() > 0) {
                return false;
            }
        } else if (!"javax.management.InstanceNotFoundException".equals(depth.errorType())) {
            throw failed("read the depth of " + queue, depth);
        }
        List<ManagementResponse> responses = client.batch(List.of(
                ManagementRequest.exec(broker, "destroyQueue(java.lang.String,boolean,boolean)", queue, true, true),
                ManagementRequest.exec(broker, "deleteAddress(java.lang.String,boolean)", queue, false),
                ManagementRequest.exec(broker, "removeAddressSettings(java.lang.String)", queue)));
        requireAll(responses, 3, "destroy staging queue " + queue);
        return true;
    }

    /** The staging queues on this node, whether or not a run still knows them. */
    public List<String> list(ArtemisNode client) {
        String pattern = MBeanNames.queuePattern(client.resolveBrokerObjectName(), PREFIX + "*");
        return client.search(pattern).stream()
                .map(StagingQueues::queueOf)
                .filter(q -> q != null && q.startsWith(PREFIX))
                .distinct()
                .sorted()
                .toList();
    }

    private static String queueOf(String objectName) {
        try {
            String quoted = new ObjectName(objectName).getKeyProperty("queue");
            return quoted == null ? null : ObjectName.unquote(quoted);
        } catch (MalformedObjectNameException | IllegalArgumentException _) {
            return null;
        }
    }

    private static void requireStaging(String queue) {
        if (queue == null || !queue.startsWith(PREFIX)) {
            throw new IllegalArgumentException(queue + " is not a Rahdar staging queue.");
        }
    }

    /** Every response succeeded, or failed only because what it removes is already gone. */
    private static void requireAll(List<ManagementResponse> responses, int expected, String what) {
        if (responses.size() < expected) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE,
                    "Could not " + what + ": the broker returned fewer results than operations.");
        }
        for (ManagementResponse res : responses) {
            if (!res.ok() && !alreadyGone(res)) {
                throw failed(what, res);
            }
        }
    }

    private static boolean alreadyGone(ManagementResponse res) {
        String error = res.error();
        return error != null && (error.contains(QUEUE_MISSING) || error.contains(ADDRESS_MISSING));
    }

    private static BrokerConnectionException failed(String what, ManagementResponse res) {
        return new BrokerConnectionException(
                BrokerConnectionException.Kind.BAD_RESPONSE,
                "Could not " + what + ": " + (res.error() != null ? res.error() : "status " + res.status()));
    }
}
