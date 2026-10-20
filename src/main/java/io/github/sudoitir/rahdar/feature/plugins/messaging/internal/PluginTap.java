package io.github.sudoitir.rahdar.feature.plugins.messaging.internal;

import io.github.sudoitir.openbroker.artemis.ArtemisMBeans;
import io.github.sudoitir.openbroker.artemis.JolokiaRequest;
import io.github.sudoitir.openbroker.artemis.JolokiaResponse;
import io.github.sudoitir.rahdar.feature.queues.DivertOperations;
import io.github.sudoitir.rahdar.feature.queues.DivertRow;
import io.github.sudoitir.rahdar.feature.queues.QueueLifecycleOperations;
import io.github.sudoitir.rahdar.feature.sql.CaptureProperties;
import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.system.artemis.ArtemisNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Installs and removes one plugin tap on one node (ADR-0111): capture's tap (ADR-0062, ADR-0079)
 * under its own prefix.
 *
 * <p>The same four objects in the same order, for the same reasons — address settings that make the
 * tap queue drop rather than page or block and expire what nobody reads; security settings that
 * restrict it to Rahdar's broker role; a non-durable, ring- and byte-bounded queue; and a
 * non-exclusive divert, so production routing is untouched. Rahdar's broker role, the expiry and the
 * byte bound are capture's settings, so an estate configures one role for both.
 *
 * <p>The divert carries the tapped queue's own filter, so the copy is what that queue receives, and
 * the tap queue takes {@code max-consumers = 1}: a second instance of the same Rahdar drains the
 * same tap only after the first lets it go.
 */
@Component
@RequiredArgsConstructor
class PluginTap {

    private static final String ANYCAST = "ANYCAST";
    private static final String FILTER = "Filter";
    private static final String ADD_ADDRESS_SETTINGS = "addAddressSettings(java.lang.String,java.lang.String)";

    /** The 13-string arm the broker accepts; its order is documented and measured in capture's {@code CaptureTap}. */
    private static final String ADD_SECURITY_SETTINGS = "addSecuritySettings(java.lang.String,java.lang.String,"
            + "java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,"
            + "java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String)";

    /** Absent while the queues feature is disabled; taps are then refused with that reason. */
    private final ObjectProvider<DivertOperations> divertOperations;

    private final ObjectProvider<QueueLifecycleOperations> queueOperations;
    private final ObjectProvider<CaptureProperties> captureProperties;
    private final ObjectMapper mapper;

    private DivertOperations divertOps() {
        DivertOperations ops = divertOperations.getIfAvailable();
        if (ops == null) {
            throw new TapRefusedException("Taps are installed through the queues feature, which is disabled"
                    + " (rahdar.features.queues.enabled=false). Enable it, or register in consume mode.");
        }
        return ops;
    }

    private QueueLifecycleOperations queueOps() {
        divertOps();
        return queueOperations.getObject();
    }

    private CaptureProperties capture() {
        CaptureProperties props = captureProperties.getIfAvailable();
        if (props == null) {
            throw new TapRefusedException("Taps use capture's broker role and bounds, and the SQL console feature that"
                    + " holds them is disabled. Enable it, or register in consume mode.");
        }
        return props;
    }

    /** The queue a tap copies, as the broker reports it on this node. */
    record Source(String queue, String address, String routingType, String filter) {}

    /** The tapped queue on this node, or {@code null} when the node does not have it. */
    Source source(ArtemisNode client, String queue) {
        JolokiaResponse read = client.single(JolokiaRequest.read(
                ArtemisMBeans.queuePattern(client.resolveBrokerObjectName(), queue), "Address", "RoutingType", FILTER));
        if (!read.ok() || read.value() == null || !read.value().isObject()) {
            return null;
        }
        var entries = read.value().properties().iterator();
        if (!entries.hasNext()) {
            return null;
        }
        JsonNode attrs = entries.next().getValue();
        String filter = attrs.path(FILTER).isNull() ? null : attrs.path(FILTER).asString(null);
        return new Source(
                queue,
                attrs.path("Address").asString(queue),
                attrs.path("RoutingType").asString(ANYCAST),
                filter == null || filter.isBlank() ? null : filter);
    }

    /** Refuse, or install (idempotently) and verify. Returns the tap's name. */
    String install(ArtemisNode client, String instanceId, UUID registrationId, Source source, long ringSize) {
        String broker = client.resolveBrokerObjectName();
        refuseIfShadowed(client, source.address());
        String role = capture().brokerRole();
        if (role == null || role.isBlank()) {
            throw new TapRefusedException(
                    "Taps restrict their queues to the broker role Rahdar's own user holds, and none is configured."
                            + " Set rahdar.capture.broker-role (RAHDAR_CAPTURE_BROKER_ROLE) to that"
                            + " role, then the tap is installed on the next pass.");
        }
        String name = TapNames.of(instanceId, registrationId);
        applyAddressSettings(client, broker, instanceId, ringSize);
        applySecuritySettings(client, broker, instanceId, role);
        String queue = TapNames.queueOf(name);
        tolerateAlready(() -> queueOps().createAddress(client, broker, queue, ANYCAST));
        tolerateAlready(() -> queueOps().createQueue(client, broker, queueConfig(queue, ringSize)));
        tolerateAlready(() -> divertOps()
                .createDivert(
                        client,
                        broker,
                        DivertOperations.divertConfig(
                                name,
                                TapNames.routingOf(name),
                                source.address(),
                                queue,
                                false,
                                source.filter(),
                                ANYCAST)));
        // Artemis answers 200 and only logs when it declines to deploy a divert (see CaptureTap).
        if (!installedNames(client, instanceId).contains(name)) {
            throw new TapRefusedException("The broker accepted the tap but did not deploy its divert on "
                    + source.address() + ". The broker's own log says why. Nothing is tapped on this node.");
        }
        return name;
    }

    /** Remove a tap, and this instance's settings once it has no tap left on the node. Idempotent. */
    void remove(ArtemisNode client, String instanceId, String name) {
        String broker = client.resolveBrokerObjectName();
        String queue = TapNames.queueOf(name);
        tolerateAlready(() -> divertOps().destroyDivert(client, broker, name));
        tolerateAlready(() -> queueOps().destroyQueue(client, broker, queue, true));
        tolerateAlready(() -> queueOps().deleteAddress(client, broker, queue));
        if (installedNames(client, instanceId).isEmpty()) {
            client.single(JolokiaRequest.exec(
                    broker, "removeAddressSettings(java.lang.String)", TapNames.matchFor(instanceId)));
            client.single(JolokiaRequest.exec(
                    broker, "removeSecuritySettings(java.lang.String)", TapNames.matchFor(instanceId)));
        }
    }

    /** This instance's tap diverts on the node: the reconciler's view of what exists. */
    List<String> installedNames(ArtemisNode client, String instanceId) {
        if (divertOperations.getIfAvailable() == null) {
            return List.of();
        }
        return divertOps().listDiverts(client, null, null).stream()
                .map(DivertRow::uniqueName)
                .filter(n -> TapNames.ownedBy(n, instanceId))
                .toList();
    }

    /**
     * Copies the broker dropped because the tap queue was full or they expired unread: its
     * {@code MessagesKilled} and {@code MessagesExpired} plus ring removals, which Artemis counts in
     * {@code MessagesAdded} minus what is still there and what was acknowledged. {@code null} when
     * the node did not answer.
     */
    Long dropped(ArtemisNode client, String name) {
        String queue = TapNames.queueOf(name);
        JolokiaResponse read = client.single(JolokiaRequest.read(
                ArtemisMBeans.queuePattern(client.resolveBrokerObjectName(), queue),
                "MessagesAdded",
                "MessagesAcknowledged",
                "MessageCount",
                "DeliveringCount"));
        if (!read.ok() || read.value() == null || !read.value().isObject()) {
            return null;
        }
        var entries = read.value().properties().iterator();
        if (!entries.hasNext()) {
            return null;
        }
        JsonNode a = entries.next().getValue();
        long added = a.path("MessagesAdded").asLong(0);
        long acked = a.path("MessagesAcknowledged").asLong(0);
        long held = a.path("MessageCount").asLong(0);
        // Everything added that was neither acknowledged by the drain nor is still waiting was
        // dropped: ring overflow, the DROP policy at the byte bound, or expiry.
        return Math.max(0, added - acked - held);
    }

    /** An exclusive divert runs before every other one, so a tap behind it would copy nothing. */
    private void refuseIfShadowed(ArtemisNode client, String address) {
        divertOps().listDiverts(client, null, null).stream()
                .filter(DivertRow::exclusive)
                .filter(d -> address.equals(d.address()))
                .findFirst()
                .ifPresent(d -> {
                    throw new TapRefusedException("The divert '" + d.uniqueName() + "' on " + address
                            + " is exclusive, and Artemis applies exclusive diverts before every other one, so a tap"
                            + " here would never see a message. Register on " + d.forwardingAddress()
                            + " instead, which is where this address's traffic goes.");
                });
    }

    private void applyAddressSettings(ArtemisNode client, String broker, String instanceId, long ringSize) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("addressFullMessagePolicy", "DROP");
        settings.put("defaultRingSize", ringSize);
        settings.put("expiryDelay", capture().expiry().toMillis());
        settings.put("autoCreateExpiryResources", false);
        settings.put("maxDeliveryAttempts", -1);
        settings.put("maxSizeBytes", capture().maxRingBytes().toBytes());
        // As capture's: a promoted backup may hold the divert before the non-durable queue exists,
        // and the address must then exist or the producer's send is rejected (see CaptureTap).
        settings.put("autoCreateAddresses", true);
        settings.put("autoDeleteAddresses", true);
        settings.put("autoCreateQueues", false);
        settings.put("autoDeleteQueues", false);
        JolokiaResponse res = client.single(JolokiaRequest.exec(
                broker, ADD_ADDRESS_SETTINGS, TapNames.matchFor(instanceId), mapper.writeValueAsString(settings)));
        if (!res.ok()) {
            throw new TapRefusedException(
                    "This broker would not accept the address settings that bound a tap queue: " + res.error()
                            + ". Without them a tap could page production payload to disk, so it is not installed.");
        }
    }

    private static void applySecuritySettings(ArtemisNode client, String broker, String instanceId, String role) {
        JolokiaResponse res = client.single(JolokiaRequest.exec(
                broker,
                ADD_SECURITY_SETTINGS,
                TapNames.matchFor(instanceId),
                "", // send
                role, // consume
                "", // createDurableQueue
                "", // deleteDurableQueue
                role, // createNonDurableQueue
                role, // deleteNonDurableQueue
                "", // manage
                role, // browse
                "", // createAddress
                "", // deleteAddress
                "", // view
                "")); // edit
        if (!res.ok()) {
            throw new TapRefusedException("This broker would not let Rahdar restrict a tap queue: " + res.error()
                    + ". A tap queue is a full copy of the queue's payload, so it is not created unrestricted.");
        }
    }

    private static Map<String, Object> queueConfig(String name, long ringSize) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", name);
        config.put("address", name);
        config.put("routing-type", ANYCAST);
        config.put("durable", false);
        config.put("ring-size", ringSize);
        config.put("max-consumers", 1);
        config.put("purge-on-no-consumers", false);
        config.put("auto-delete", false);
        return config;
    }

    private static void tolerateAlready(Runnable operation) {
        try {
            operation.run();
        } catch (ManagementRefusal e) {
            if (e.kind() != ManagementRefusal.Kind.ALREADY) {
                throw e;
            }
        }
    }
}
