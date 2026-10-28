package io.github.sudoitir.rahdar.feature.plugins.messaging;

import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationEntity;
import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationNodeEntity;
import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationNodeRepository;
import io.github.sudoitir.rahdar.feature.plugins.internal.persistence.RegistrationRepository;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.AccessCheck;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.PluginDrains;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.PluginMessagingReconciler;
import io.github.sudoitir.rahdar.feature.plugins.messaging.internal.Reservations;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.plugin.PluginPurged;
import io.github.sudoitir.rahdar.kernel.plugin.PluginScopedBeans;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.MessageKind;
import io.github.sudoitir.rahdar.platform.broker.SendInDoubtException;
import io.github.sudoitir.rahdar.platform.broker.SendNames;
import io.github.sudoitir.rahdar.platform.broker.SentMessages;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.ClusterNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plugins' message registrations and sends (ADR-0111), behind each plugin's own
 * {@link PluginMessaging}. Every method takes the plugin id from that bound object, never from the
 * plugin.
 */
@Service
@RequiredArgsConstructor
public class PluginMessagingService implements PluginScopedBeans {

    static final String BEAN_NAME = "pluginMessaging";
    private static final String PLUGIN = "plugin";

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,200}");
    private static final int MAX_NAME = 1000;
    private static final int MAX_CONCURRENCY = 32;

    private final RegistrationRepository registrations;
    private final RegistrationNodeRepository nodeStates;
    private final AccessCheck access;
    private final PluginMessagingReconciler reconciler;
    private final PluginDrains drains;
    private final ClusterDirectory clusters;
    private final BrokerMessages messages;
    private final AuditService audit;
    private final ActorResolver actors;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Override
    public Map<String, Object> beansFor(String pluginId) {
        return Map.of(BEAN_NAME, new PluginMessaging(this, pluginId));
    }

    MessageRegistration register(String pluginId, RegistrationSpec spec) {
        validate(spec);
        access.denial(
                        spec.actingUserId(),
                        spec.clusterId(),
                        AccessCheck.needsOf(spec.mode(), spec.source().name()))
                .ifPresent(why -> {
                    throw new RegistrationRefusedException(why);
                });
        AuditEvent event = audit.begin(
                actor(pluginId, spec.actingUserId()),
                "PLUGIN_MESSAGE_REGISTER",
                "PLUGIN_MESSAGE_REGISTRATION",
                pluginId + "/" + spec.key(),
                spec.clusterId(),
                null,
                Map.of(
                        PLUGIN,
                        pluginId,
                        "source",
                        spec.source().name(),
                        "mode",
                        spec.mode().name(),
                        "concurrency",
                        spec.concurrency()),
                false);
        AtomicReference<UUID> replaced = new AtomicReference<>();
        reconciler.change(
                spec.clusterId(),
                () -> replaced.set(tx.execute(status -> {
                    Optional<RegistrationEntity> existing = registrations.findByPluginIdAndKey(pluginId, spec.key());
                    UUID old = null;
                    if (existing.isPresent()) {
                        RegistrationEntity e = existing.get();
                        boolean sameTarget = e.getClusterId().equals(spec.clusterId())
                                && e.getSubscription().equals(spec.source().name())
                                && e.getMode() == spec.mode();
                        if (sameTarget) {
                            // A changed concurrency is converged by the pass below, which restarts the drains.
                            e.setConcurrency(spec.concurrency());
                            e.setActingUserId(spec.actingUserId());
                            e.setUpdatedAt(clock.instant());
                            registrations.save(e);
                            return null;
                        }
                        // A different target is a different registration: the old one's broker objects are
                        // removed as orphans, the new one's installed afresh.
                        old = e.getId();
                        registrations.delete(e);
                        registrations.flush();
                    }
                    RegistrationEntity row = new RegistrationEntity();
                    row.setPluginId(pluginId);
                    row.setKey(spec.key());
                    row.setClusterId(spec.clusterId());
                    row.setSubscription(spec.source().name());
                    row.setMode(spec.mode());
                    row.setConcurrency(spec.concurrency());
                    row.setActingUserId(spec.actingUserId());
                    row.setCreatedAt(clock.instant());
                    row.setUpdatedAt(clock.instant());
                    registrations.save(row);
                    return old;
                })));
        audit.succeed(event, 1);
        if (replaced.get() != null) {
            drains.stopRegistration(replaced.get());
        }
        return registration(pluginId, spec.key()).orElseThrow();
    }

    boolean unregister(String pluginId, String key) {
        Optional<RegistrationEntity> row = registrations.findByPluginIdAndKey(pluginId, key);
        if (row.isEmpty()) {
            return false;
        }
        RegistrationEntity reg = row.get();
        AuditEvent event = audit.begin(
                actor(pluginId, reg.getActingUserId()),
                "PLUGIN_MESSAGE_UNREGISTER",
                "PLUGIN_MESSAGE_REGISTRATION",
                pluginId + "/" + key,
                reg.getClusterId(),
                null,
                Map.of(
                        PLUGIN,
                        pluginId,
                        "source",
                        reg.getSubscription(),
                        "mode",
                        reg.getMode().name()),
                false);
        drains.stopRegistration(reg.getId());
        reconciler.change(reg.getClusterId(), () -> registrations.delete(reg));
        audit.succeed(event, 1);
        return true;
    }

    Optional<MessageRegistration> registration(String pluginId, String key) {
        return registrations.findByPluginIdAndKey(pluginId, key).map(this::view);
    }

    List<MessageRegistration> registrations(String pluginId) {
        return registrations.findByPluginIdOrderByKey(pluginId).stream()
                .map(this::view)
                .toList();
    }

    /** Why a send to {@code target} as {@code actingUserId} would be refused, or empty when it would be allowed. */
    Optional<String> sendDenial(UUID clusterId, ResourceRef target, UUID actingUserId) {
        if (clusterId == null || target == null || target.type() == null || blank(target.name())) {
            return Optional.of("A message needs a cluster and a target.");
        }
        if (target.name().length() > MAX_NAME) {
            return Optional.of("The target's name is longer than " + MAX_NAME + " characters.");
        }
        String reserved = Reservations.reason(target.name());
        if (reserved != null) {
            return Optional.of(reserved);
        }
        if (clusters.cluster(clusterId).isEmpty()) {
            return Optional.of("The cluster " + clusterId + " is not registered.");
        }
        return access.denial(actingUserId, clusterId, AccessCheck.sendNeeds(target));
    }

    void send(String pluginId, OutboundMessage message) {
        if (message == null) {
            throw new RegistrationRefusedException("A message needs a cluster and a target.");
        }
        sendDenial(message.clusterId(), message.target(), message.actingUserId())
                .ifPresent(why -> {
                    throw new RegistrationRefusedException(why);
                });
        ClusterNode node = servingWithCore(message.clusterId());
        // A plugin's headers are plain string properties, never the message's standard headers.
        Map<String, Object> properties = new LinkedHashMap<>();
        if (message.headers() != null) {
            properties.putAll(message.headers());
        }
        if (message.properties() != null) {
            properties.putAll(message.properties());
        }
        try {
            SendNames.validate(Map.of(), properties);
        } catch (IllegalArgumentException e) {
            throw new RegistrationRefusedException(e.getMessage());
        }
        byte[] body = message.body() == null ? new byte[0] : message.body();
        AuditEvent event = audit.begin(
                actor(pluginId, message.actingUserId()),
                "PLUGIN_MESSAGE_SEND",
                message.target().type().name(),
                message.target().name(),
                message.clusterId(),
                node.getId(),
                Map.of(PLUGIN, pluginId, "bytes", body.length),
                false);
        try {
            messages.sendTo(
                    message.clusterId(),
                    node.getId(),
                    message.target(),
                    SentMessages.of(
                            message.text() ? MessageKind.TEXT : MessageKind.BYTES,
                            message.durable(),
                            body,
                            Map.of(),
                            properties));
            audit.succeed(event, 1);
        } catch (SendInDoubtException e) {
            audit.inDoubt(event, e.getMessage());
            throw new MessageInDoubtException(e.getMessage());
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    /** Purging a plugin deletes its registrations; the next pass removes what they left on the broker. */
    @EventListener
    public void onPurged(PluginPurged purged) {
        for (RegistrationEntity reg : registrations.findByPluginIdOrderByKey(purged.pluginId())) {
            drains.stopRegistration(reg.getId());
        }
        registrations.deleteByPluginId(purged.pluginId());
    }

    // ---- helpers -----------------------------------------------------------

    private void validate(RegistrationSpec spec) {
        if (spec == null) {
            throw new RegistrationRefusedException("A registration needs a spec.");
        }
        if (spec.key() == null || !KEY.matcher(spec.key()).matches()) {
            throw new RegistrationRefusedException(
                    "A registration key is 1 to 200 letters, digits, '.', '_', ':' or '-'.");
        }
        if (spec.mode() == null) {
            throw new RegistrationRefusedException("A registration needs a mode: TAP or CONSUME.");
        }
        if (spec.mode() == RegistrationMode.TAP && spec.concurrency() != 1) {
            throw new RegistrationRefusedException(
                    "A tap delivers one copy at a time, so its concurrency is 1, not " + spec.concurrency() + ".");
        }
        if (spec.mode() == RegistrationMode.CONSUME
                && (spec.concurrency() < 1 || spec.concurrency() > MAX_CONCURRENCY)) {
            throw new RegistrationRefusedException("A consumer's concurrency is 1 to " + MAX_CONCURRENCY
                    + " messages at once per node, not " + spec.concurrency() + ".");
        }
        if (spec.clusterId() == null || clusters.cluster(spec.clusterId()).isEmpty()) {
            throw new RegistrationRefusedException("The cluster " + spec.clusterId() + " is not registered.");
        }
        if (spec.source() == null
                || spec.source().type() != ResourceType.SUBSCRIPTION
                || blank(spec.source().name())
                || spec.source().name().length() > MAX_NAME) {
            throw new RegistrationRefusedException(
                    "A registration names one subscription, by its name, in at most " + MAX_NAME + " characters.");
        }
        String reserved = Reservations.reason(spec.source().name());
        if (reserved != null) {
            throw new RegistrationRefusedException(reserved);
        }
    }

    private ClusterNode servingWithCore(UUID clusterId) {
        return reconciler.servingNodes(clusterId).stream()
                .filter(n -> n.getDataUrl() != null)
                .findFirst()
                .orElseThrow(() -> new RegistrationRefusedException(
                        "No serving node of cluster " + clusterId + " has a Core URL, and sends go over Core."));
    }

    /** The operator behind a request, or — outside one — the plugin acting for its user. */
    private Actor actor(String pluginId, UUID actingUserId) {
        Actor current = actors.resolve();
        if (current.userId() != null) {
            return current;
        }
        return new Actor("plugin " + pluginId + " for " + access.nameOf(actingUserId), null, null, actingUserId);
    }

    private MessageRegistration view(RegistrationEntity reg) {
        List<MessageRegistration.NodeState> nodes = new ArrayList<>();
        for (RegistrationNodeEntity row : nodeStates.findByIdRegistrationId(reg.getId())) {
            String name = clusters.node(row.getNodeId())
                    .map(ClusterNode::getName)
                    .orElse(row.getNodeId().toString());
            nodes.add(new MessageRegistration.NodeState(
                    row.getNodeId(), name, row.getState(), row.getDetail(), row.getDroppedCopies()));
        }
        nodes.sort(Comparator.comparing(MessageRegistration.NodeState::nodeName));
        RegistrationState overall = overall(nodes);
        String detail = nodes.stream()
                .filter(n -> n.state() == overall && n.detail() != null)
                .map(n -> nodes.size() > 1 ? n.nodeName() + ": " + n.detail() : n.detail())
                .findFirst()
                .orElse(null);
        Long dropped = nodes.stream()
                .map(MessageRegistration.NodeState::droppedCopies)
                .filter(Objects::nonNull)
                .reduce(Long::sum)
                .orElse(null);
        return new MessageRegistration(
                reg.getKey(),
                reg.getClusterId(),
                ResourceRef.subscription(reg.getSubscription()),
                reg.getMode(),
                reg.getConcurrency(),
                reg.getActingUserId(),
                overall,
                detail,
                dropped,
                List.copyOf(nodes),
                reg.getUpdatedAt());
    }

    /** The state that most needs attention; delivering, or delivered by another instance, is fine. */
    private static RegistrationState overall(List<MessageRegistration.NodeState> nodes) {
        if (nodes.isEmpty()) {
            return RegistrationState.PENDING;
        }
        for (RegistrationState worst : List.of(
                RegistrationState.SUSPENDED,
                RegistrationState.INACTIVE,
                RegistrationState.FAILED,
                RegistrationState.PENDING)) {
            if (nodes.stream().anyMatch(n -> n.state() == worst)) {
                return worst;
            }
        }
        return nodes.stream().allMatch(n -> n.state() == RegistrationState.SERVED_ELSEWHERE)
                ? RegistrationState.SERVED_ELSEWHERE
                : RegistrationState.ACTIVE;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
