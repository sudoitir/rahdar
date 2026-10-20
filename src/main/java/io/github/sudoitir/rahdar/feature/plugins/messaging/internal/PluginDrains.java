package io.github.sudoitir.rahdar.feature.plugins.messaging.internal;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.ReceiveOptions;
import io.github.sudoitir.openbroker.messaging.Receiver;
import io.github.sudoitir.rahdar.feature.plugins.messaging.Disposition;
import io.github.sudoitir.rahdar.feature.plugins.messaging.PluginMessage;
import io.github.sudoitir.rahdar.feature.plugins.messaging.RegistrationMode;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The receivers that deliver registrations' messages to plugins: one drain per registration and
 * node, with one slot per unit of the registration's concurrency (ADR-0111, ADR-0112).
 *
 * <p>Every slot is its own receiver, read on its own virtual thread, so settling one message
 * settles exactly the message just handled, the slots of a drain handle messages in parallel, and a
 * plugin that blocks holds only its own slot's thread. A slot has no prefetch, so it holds at most
 * the one message it is handling; the broker's message grouping keeps a group on one slot, in
 * order. A tap's copy is accepted whatever the handler said — the original never left its queue. A
 * consumed message is accepted only on {@link Disposition#ACCEPT}; a release hands it back
 * uncounted, and a rejection or an exception hands it back counted, so the broker redelivers it
 * within its own {@code max-delivery-attempts}. A drain that stops, or a Rahdar that dies, accepts
 * nothing it had not already settled, so what it held is redelivered.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginDrains {

    /** How long one receive waits; also how soon a slot sees its drain stop. */
    private static final Duration POLL = Duration.ofMillis(500);

    /** How long stopping a drain waits for a handler still running. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(30);

    private final BrokerMessages messages;
    private final PluginHandlers handlers;

    /** One drain's identity and what it reads. {@code source} is the queue the receivers open. */
    record Spec(
            UUID registrationId,
            String pluginId,
            String key,
            RegistrationMode mode,
            int concurrency,
            UUID clusterId,
            UUID nodeId,
            String nodeName,
            String queue,
            String source) {}

    /** Why a drain could not start: the broker let another consumer have it, or anything else. */
    static final class StartFailure extends Exception {
        final boolean servedElsewhere;

        StartFailure(String message, boolean servedElsewhere, Throwable cause) {
            super(message, cause);
            this.servedElsewhere = servedElsewhere;
        }
    }

    private final Map<String, Drain> running = new ConcurrentHashMap<>();

    private static String key(UUID registrationId, UUID nodeId) {
        return registrationId + "|" + nodeId;
    }

    boolean isRunning(UUID registrationId, UUID nodeId) {
        return running.containsKey(key(registrationId, nodeId));
    }

    /** How many slots a running drain has, or 0 when it is not running. */
    int concurrency(UUID registrationId, UUID nodeId) {
        Drain drain = running.get(key(registrationId, nodeId));
        return drain == null ? 0 : drain.slots.size();
    }

    /** The registrations draining on a node. */
    Set<UUID> runningOn(UUID nodeId) {
        return running.values().stream()
                .filter(d -> d.spec.nodeId().equals(nodeId))
                .map(d -> d.spec.registrationId())
                .collect(Collectors.toSet());
    }

    Set<UUID> nodesOf(UUID registrationId) {
        return running.values().stream()
                .filter(d -> d.spec.registrationId().equals(registrationId))
                .map(d -> d.spec.nodeId())
                .collect(Collectors.toSet());
    }

    /** Start draining, or do nothing if already draining. Every slot starts, or none does. */
    void start(Spec spec) throws StartFailure {
        if (running.containsKey(key(spec.registrationId(), spec.nodeId()))) {
            return;
        }
        Drain drain = new Drain(spec);
        try {
            for (int i = 0; i < spec.concurrency(); i++) {
                drain.slots.add(openSlot(drain));
            }
        } catch (StartFailure e) {
            drain.close();
            throw e;
        }
        drain.slots.forEach(slot -> slot.thread.set(Thread.ofVirtual()
                .name("plugin-" + spec.pluginId() + "-" + spec.key())
                .start(slot::run)));
        String key = key(spec.registrationId(), spec.nodeId());
        running.put(key, drain);
        if (drain.closed) {
            // A slot failed as it started; the next pass tries again.
            running.remove(key, drain);
        }
        log.info(
                "Delivering {} of {} on {} to plugin {} ({}, {} at once)",
                spec.mode() == RegistrationMode.TAP ? "a copy" : "messages",
                spec.queue(),
                spec.nodeName(),
                spec.pluginId(),
                spec.key(),
                spec.concurrency());
    }

    private Slot openSlot(Drain drain) throws StartFailure {
        Spec spec = drain.spec;
        try {
            return new Slot(
                    drain,
                    messages.receiver(
                            spec.clusterId(),
                            spec.nodeId(),
                            spec.source(),
                            ReceiveOptions.consume().withPrefetch(0)));
        } catch (BrokerException e) {
            boolean elsewhere = ErrorReasons.MAX_CONSUMERS_REACHED.equals(e.reason());
            throw new StartFailure(
                    elsewhere
                            ? "Another Rahdar instance sharing this database is delivering this on " + spec.nodeName()
                            : "The broker refused a consumer on " + spec.source() + " at " + spec.nodeName() + ": "
                                    + reason(e),
                    elsewhere,
                    e);
        } catch (RuntimeException e) {
            throw new StartFailure(
                    "Rahdar could not open a receiver on " + spec.nodeName() + ": " + reason(e), false, e);
        }
    }

    void stop(UUID registrationId, UUID nodeId) {
        Drain drain = running.remove(key(registrationId, nodeId));
        if (drain != null) {
            drain.close();
        }
    }

    /** Stop every drain of a registration, on every node. */
    public void stopRegistration(UUID registrationId) {
        running.entrySet().removeIf(e -> {
            if (!e.getValue().spec.registrationId().equals(registrationId)) {
                return false;
            }
            e.getValue().close();
            return true;
        });
    }

    /** Stop every drain on a cluster, because this replica no longer owns it. */
    void stopCluster(UUID clusterId) {
        running.entrySet().removeIf(e -> {
            if (!e.getValue().spec.clusterId().equals(clusterId)) {
                return false;
            }
            e.getValue().close();
            return true;
        });
    }

    /** Stop every drain delivering to a plugin, before its context closes. */
    void stopPlugin(String pluginId) {
        running.entrySet().removeIf(e -> {
            if (!e.getValue().spec.pluginId().equals(pluginId)) {
                return false;
            }
            e.getValue().close();
            return true;
        });
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** One registration on one node: its slots, which stop together. */
    private static final class Drain {

        private final Spec spec;
        private final List<Slot> slots = new ArrayList<>();
        private volatile boolean closed;

        Drain(Spec spec) {
            this.spec = spec;
        }

        /**
         * Stop taking messages on every slot, then wait for each to finish the message it is handling
         * and close its receiver, which hands back whatever it holds unsettled.
         */
        void close() {
            closed = true;
            for (Slot slot : slots) {
                slot.awaitStopped();
            }
        }
    }

    /** One receiver, read on its own thread: a message at a time. */
    private final class Slot {

        private final Drain drain;
        private final Receiver receiver;
        private final AtomicReference<Thread> thread = new AtomicReference<>();

        Slot(Drain drain, Receiver receiver) {
            this.drain = drain;
            this.receiver = receiver;
        }

        void run() {
            try {
                while (!drain.closed) {
                    for (Message message : receiver.receive(1, POLL)) {
                        deliver(message);
                    }
                }
            } catch (RuntimeException e) {
                if (!drain.closed) {
                    lost(e);
                }
            } finally {
                receiver.close();
            }
        }

        private void deliver(Message message) {
            Spec spec = drain.spec;
            Optional<Disposition> outcome;
            try {
                PluginMessage delivered =
                        MessageConversion.toPlugin(spec.key(), spec.clusterId(), spec.nodeId(), spec.queue(), message);
                outcome = handlers.deliver(spec.pluginId(), delivered);
            } catch (Exception | LinkageError e) {
                // The message's content is never logged: it is production payload.
                log.warn(
                        "Plugin {} failed on a message from {} ({}): {}",
                        spec.pluginId(),
                        spec.queue(),
                        spec.key(),
                        e.toString());
                outcome = Optional.of(Disposition.REJECT);
            }
            if (outcome.isEmpty()) {
                // The plugin is not receiving (it is being stopped); leave the message unsettled so
                // closing this drain returns it to the broker.
                return;
            }
            receiver.settle(message, settlement(spec.mode(), outcome.get()));
        }

        /** The slot lost its receiver: the drain ends, and the next pass starts it again. */
        private void lost(RuntimeException failure) {
            if (running.remove(key(drain.spec.registrationId(), drain.spec.nodeId()), drain)) {
                log.warn(
                        "Plugin delivery of {} on {} lost its broker connection and restarts on the next pass: {}",
                        drain.spec.queue(),
                        drain.spec.nodeName(),
                        failure.getMessage());
            }
            drain.closed = true;
        }

        /** Wait for the slot's thread to leave its handler and close its receiver. */
        void awaitStopped() {
            Thread owner = thread.get();
            if (owner == null) {
                receiver.close();
                return;
            }
            if (owner == Thread.currentThread()) {
                return;
            }
            try {
                if (!owner.join(STOP_WAIT)) {
                    log.warn("A plugin drain on {} did not stop within {}", drain.spec.nodeName(), STOP_WAIT);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A tap's copy is always accepted; a consumed message is settled as the plugin said. */
    static io.github.sudoitir.openbroker.messaging.Disposition settlement(RegistrationMode mode, Disposition outcome) {
        if (mode == RegistrationMode.TAP || outcome == Disposition.ACCEPT) {
            return io.github.sudoitir.openbroker.messaging.Disposition.accept();
        }
        return outcome == Disposition.RELEASE
                ? new io.github.sudoitir.openbroker.messaging.Disposition.Release(Optional.empty(), false)
                : new io.github.sudoitir.openbroker.messaging.Disposition.Reject(Optional.empty());
    }
}
