package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.ManagementRefusal;
import io.github.sudoitir.rahdar.platform.broker.MessageTaps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Installs and removes one tap on one Artemis node (ADR-0062 D1, ADR-0111).
 *
 * <p>A tap is four broker objects, created in this order because each depends on the one before it:
 *
 * <ol>
 *   <li><b>address settings</b> on the tap's match —
 *       {@code addressFullMessagePolicy=DROP} so the broker never blocks or pages on
 *       Rahdar's account, and {@code expiryDelay} with
 *       {@code autoCreateExpiryResources=false} so an abandoned tap is bounded in age
 *       as well as in size. Artemis' own documentation warns against paging an address
 *       that holds ring queues, which is why this is part of the tap rather than an
 *       optional extra;
 *   <li><b>security settings</b> on the same match, restricting the tap queue to
 *       Rahdar's own broker role. The tap queue is a complete second copy of
 *       production payload, and creating it unguarded would hand a tap on production
 *       traffic to every client the broker authorises;
 *   <li><b>the tap queue</b> — non-durable, anycast, {@code ring-size} bounded, so
 *       the worst case with Rahdar dead forever is a fixed number of messages held in
 *       memory;
 *   <li><b>the divert</b> — non-exclusive, so production routing is untouched. It carries the tapped
 *       queue's own filter, so the copy is what that queue receives, and the tap queue takes
 *       {@code max-consumers = 1}: a second instance of the same Rahdar drains the same tap only after
 *       the first lets it go.
 * </ol>
 *
 * <p>Removal is the reverse, and it is always an explicit act: none of these objects
 * disappears on a broker restart (ADR-0065). Both directions are idempotent, because
 * the reconcilers are the only callers and they run on a schedule.
 *
 * <p><b>Measured, not assumed.</b> Every management signature used here was checked
 * against the running broker rather than the classpath: {@code addSecuritySettings}'
 * single-JSON overload exists on the {@code ActiveMQServerControl} interface but is
 * <em>not</em> registered on the MBean, which answers with {@code IllegalArgumentException}
 * and lists the positional arms. The 13-string arm below is the one the broker accepts,
 * and its argument order — send, consume, createDurableQueue, deleteDurableQueue,
 * createNonDurableQueue, deleteNonDurableQueue, manage, browse, createAddress,
 * deleteAddress, view, edit — was confirmed by writing it and reading it back through
 * {@code getRolesAsJSON}.
 */
@Component
@RequiredArgsConstructor
class ArtemisTaps implements MessageTaps {

    private static final String ANYCAST = "ANYCAST";
    private static final String FILTER = "Filter";
    private static final String ADD_ADDRESS_SETTINGS = "addAddressSettings(java.lang.String,java.lang.String)";

    private static final String ADD_SECURITY_SETTINGS = "addSecuritySettings(java.lang.String,java.lang.String,"
            + "java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,"
            + "java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String)";

    private final ArtemisNodes nodes;
    private final DivertOperations divertOps;
    private final QueueLifecycleOperations queueOps;
    private final ObjectMapper mapper;

    @Override
    public String system() {
        return ArtemisModule.SYSTEM;
    }

    @Override
    public Optional<TapSource> source(UUID clusterId, UUID nodeId, String queue) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        ManagementResponse read = client.single(ManagementRequest.read(
                MBeanNames.queuePattern(client.resolveBrokerObjectName(), queue), "Address", "RoutingType", FILTER));
        if (!read.ok() || read.value() == null || !read.value().isObject()) {
            return Optional.empty();
        }
        var entries = read.value().properties().iterator();
        if (!entries.hasNext()) {
            return Optional.empty();
        }
        JsonNode attrs = entries.next().getValue();
        String filter = attrs.path(FILTER).isNull() ? null : attrs.path(FILTER).asString(null);
        return Optional.of(new TapSource(
                queue, attrs.path("Address").asString(queue), filter == null || filter.isBlank() ? null : filter));
    }

    @Override
    public void install(UUID clusterId, UUID nodeId, TapSpec spec) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        String broker = client.resolveBrokerObjectName();
        refuseIfShadowedByExclusiveDivert(client, spec.destination());

        applyAddressSettings(client, broker, spec);
        applySecuritySettings(client, broker, spec);

        tolerateAlready(() -> queueOps.createAddress(client, broker, spec.queue(), ANYCAST));
        tolerateAlready(() -> queueOps.createQueue(client, broker, queueConfig(spec.queue(), spec.ringSize())));
        tolerateAlready(() -> divertOps.createDivert(
                client,
                broker,
                DivertOperations.divertConfig(
                        spec.name(),
                        routingNameFor(spec.name()),
                        spec.destination(),
                        spec.queue(),
                        false,
                        spec.filter(),
                        ANYCAST)));

        // Verified, not assumed. Artemis answers 200 and logs a WARN when it declines
        // to deploy a divert — a routing-name that collides with an existing binding is
        // the way to get there, and it was exactly how this went wrong the first time.
        // An install that reports success without a divert would copy nothing while
        // the console said it was copying, which is the one outcome D6 exists to stop.
        if (!installedNames(client).contains(spec.name())) {
            throw new TapRefusal(
                    "The broker accepted the request but did not deploy the divert for " + spec.destination()
                            + ". Its own log says why — the usual cause is a binding that already exists under the"
                            + " same routing name. Nothing is tapped on this node.",
                    null);
        }
    }

    @Override
    public void remove(UUID clusterId, UUID nodeId, String name, String queue, boolean disconnectConsumers) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        String broker = client.resolveBrokerObjectName();
        tolerateAlready(() -> divertOps.destroyDivert(client, broker, name));
        tolerateAlready(() -> queueOps.destroyQueue(client, broker, queue, disconnectConsumers));
        tolerateAlready(() -> queueOps.deleteAddress(client, broker, queue));
    }

    @Override
    public void removeSettings(UUID clusterId, UUID nodeId, String match) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        String broker = client.resolveBrokerObjectName();
        client.single(ManagementRequest.exec(broker, "removeAddressSettings(java.lang.String)", match));
        client.single(ManagementRequest.exec(broker, "removeSecuritySettings(java.lang.String)", match));
    }

    @Override
    public List<String> installed(UUID clusterId, UUID nodeId, String prefix) {
        return installedNames(nodes.on(clusterId, nodeId)).stream()
                .filter(n -> n.startsWith(prefix))
                .toList();
    }

    /**
     * Copies the broker dropped because the tap queue was full or they expired unread: its
     * {@code MessagesKilled} and {@code MessagesExpired} plus ring removals, which Artemis counts in
     * {@code MessagesAdded} minus what is still there and what was acknowledged. Empty when the node
     * did not answer.
     */
    @Override
    public OptionalLong dropped(UUID clusterId, UUID nodeId, String queue) {
        ArtemisNode client = nodes.on(clusterId, nodeId);
        ManagementResponse read = client.single(ManagementRequest.read(
                MBeanNames.queuePattern(client.resolveBrokerObjectName(), queue),
                "MessagesAdded",
                "MessagesAcknowledged",
                "MessageCount",
                "DeliveringCount"));
        if (!read.ok() || read.value() == null || !read.value().isObject()) {
            return OptionalLong.empty();
        }
        var entries = read.value().properties().iterator();
        if (!entries.hasNext()) {
            return OptionalLong.empty();
        }
        JsonNode a = entries.next().getValue();
        long added = a.path("MessagesAdded").asLong(0);
        long acked = a.path("MessagesAcknowledged").asLong(0);
        long held = a.path("MessageCount").asLong(0);
        // Everything added that was neither acknowledged by the drain nor is still waiting was
        // dropped: ring overflow, the DROP policy at the byte bound, or expiry.
        return OptionalLong.of(Math.max(0, added - acked - held));
    }

    /** The configuration an operator would add to make a refusal go away. */
    @Override
    public String settingsSnippet(TapSpec spec) {
        return """
                <security-settings>
                  <security-setting match="%1$s">
                    <permission type="consume" roles="%2$s"/>
                    <permission type="browse" roles="%2$s"/>
                    <permission type="createNonDurableQueue" roles="%2$s"/>
                    <permission type="deleteNonDurableQueue" roles="%2$s"/>
                  </security-setting>
                </security-settings>

                <address-settings>
                  <address-setting match="%1$s">
                    <address-full-policy>DROP</address-full-policy>
                    <default-ring-size>%3$d</default-ring-size>
                    <max-size-bytes>%4$d</max-size-bytes>
                    <expiry-delay>%5$d</expiry-delay>
                    <auto-create-expiry-resources>false</auto-create-expiry-resources>
                    <max-delivery-attempts>-1</max-delivery-attempts>
                  </address-setting>
                </address-settings>
                """.formatted(
                        spec.match(),
                        spec.brokerRole(),
                        spec.ringSize(),
                        spec.maxBytes(),
                        spec.expiry().toMillis());
    }

    /**
     * The divert's routing name, which must not collide with any existing binding.
     *
     * <p>Kept distinct from both the divert's name and the tap queue's, because a
     * routing name is itself a binding and a collision is refused the same silent way
     * a name collision is.
     */
    private static String routingNameFor(String name) {
        return name + ".routing";
    }

    private List<String> installedNames(ArtemisNode client) {
        return divertOps.listDiverts(client, null, null).stream()
                .map(DivertRow::uniqueName)
                .filter(n -> n != null)
                .toList();
    }

    /**
     * Artemis evaluates exclusive diverts before non-exclusive ones, so a tap
     * divert behind one never sees the traffic. It would install cleanly and record
     * nothing — the worst available outcome, because an empty index reads as a quiet
     * queue rather than as a defeated tap.
     */
    private void refuseIfShadowedByExclusiveDivert(ArtemisNode client, String address) {
        List<DivertRow> shadowing = divertOps.listDiverts(client, null, null).stream()
                .filter(DivertRow::exclusive)
                .filter(d -> address.equals(d.address()))
                .toList();
        if (shadowing.isEmpty()) {
            return;
        }
        DivertRow first = shadowing.get(0);
        throw new TapRefusal(
                "The divert '" + first.uniqueName() + "' on " + address + " is exclusive, and Artemis applies "
                        + "exclusive diverts before every other one, so a tap divert here would never see a "
                        + "message. Tap " + first.forwardingAddress() + " instead — that is where this address's "
                        + "traffic actually goes.",
                null);
    }

    private void applyAddressSettings(ArtemisNode client, String broker, TapSpec spec) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("addressFullMessagePolicy", "DROP");
        settings.put("defaultRingSize", spec.ringSize());
        settings.put("expiryDelay", spec.expiry().toMillis());
        settings.put("autoCreateExpiryResources", false);
        // Unlimited redelivery. A drain that cannot store recovers its session and retries
        // (ADR-0077); with the broker's default of 10 attempts and no dead-letter address, a
        // long database outage would silently discard what the bounded queue was holding.
        settings.put("maxDeliveryAttempts", -1);
        // Bounded in bytes as well as messages: a ring of large messages is otherwise bounded
        // only by the broker's global size, which is not a bound on this tap (ADR-0079).
        settings.put("maxSizeBytes", spec.maxBytes());
        // Auto-create the tap *address*, and nothing else. This is a safety
        // property, not a convenience: the divert lives in the bindings journal and
        // therefore replicates to a backup, while the non-durable tap queue does
        // not — so a promoted backup can hold a divert whose forwarding address does
        // not exist yet, and Artemis rejects the *producer's* send with
        // AMQ229203 when that happens. Measured on 2.56.0, by failing a pair over with
        // this set to false. With it true the copy is routed to an address with no
        // queue and dropped, the tap reports the loss, and production is untouched —
        // which is the only acceptable ordering (non-negotiable #1).
        settings.put("autoCreateAddresses", true);
        settings.put("autoDeleteAddresses", true);
        settings.put("autoCreateQueues", false);
        settings.put("autoDeleteQueues", false);
        ManagementResponse res = client.single(ManagementRequest.exec(
                broker, ADD_ADDRESS_SETTINGS, spec.match(), mapper.writeValueAsString(settings)));
        if (!res.ok()) {
            throw new TapRefusal(
                    "This broker would not accept the address settings that bound the tap queue: "
                            + res.error()
                            + ". Without them an abandoned tap queue could page production payload to disk, so "
                            + "the tap is refused rather than installed unbounded.",
                    settingsSnippet(spec));
        }
    }

    /**
     * Consume, browse and the non-durable queue lifecycle, granted to Rahdar's own
     * role and to nothing else. Send is deliberately not granted: a divert routes
     * inside the server and is not a client send, so granting it would only widen who
     * can write into the tap address.
     */
    private void applySecuritySettings(ArtemisNode client, String broker, TapSpec spec) {
        String role = spec.brokerRole();
        ManagementResponse res = client.single(ManagementRequest.exec(
                broker,
                ADD_SECURITY_SETTINGS,
                spec.match(),
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
            throw new TapRefusal(
                    "This broker would not let Rahdar restrict the tap queue: " + res.error()
                            + ". The tap queue is a complete second copy of this address's payload, so it is not "
                            + "created at all rather than created readable by every client the broker authorises.",
                    settingsSnippet(spec));
        }
    }

    /**
     * Non-durable so the queue is never written to the journal, anycast because
     * address and queue coincide for a tap queue, and ring-bounded so the broker
     * drops the oldest message rather than growing.
     */
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

    /** An object that is already in the requested state is a success, in both directions. */
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
