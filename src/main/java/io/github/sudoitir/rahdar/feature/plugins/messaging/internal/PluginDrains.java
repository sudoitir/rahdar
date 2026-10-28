package io.github.sudoitir.rahdar.feature.plugins.messaging.internal;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.messaging.FlowControl;
import io.github.sudoitir.openbroker.messaging.LinkState;
import io.github.sudoitir.openbroker.messaging.MessageContext;
import io.github.sudoitir.openbroker.messaging.OrderingScope;
import io.github.sudoitir.openbroker.messaging.Processor;
import io.github.sudoitir.openbroker.messaging.ProcessorOptions;
import io.github.sudoitir.openbroker.messaging.ReceiverOptions;
import io.github.sudoitir.openbroker.messaging.RetryPolicy;
import io.github.sudoitir.rahdar.feature.plugins.messaging.Disposition;
import io.github.sudoitir.rahdar.feature.plugins.messaging.PluginMessage;
import io.github.sudoitir.rahdar.feature.plugins.messaging.RegistrationMode;
import io.github.sudoitir.rahdar.feature.plugins.messaging.SubscriptionId;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The broker SDK processors that deliver registrations' messages to plugins: one per registration and
 * node, with one lane per unit of the registration's concurrency (ADR-0111, ADR-0112, ADR-0211).
 *
 * <p>Each lane holds at most the one message it is handling and reads nothing ahead, so a slow plugin
 * is handed fewer messages and the rest wait on the subscription for other consumers. The lanes keep
 * a message group in order, one message at a time. Retries are the broker's: a rejection or an
 * exception hands the message back counted, so it is redelivered within the broker's own {@code
 * max-delivery-attempts} and then dead-lettered by the broker; a release hands it back uncounted where the
 * subscription's system leaves it so. On a Kafka consumer group the SDK retries in place and then writes the
 * record to its dead-letter topic, so the offset passes a rejected record only once it is dead-lettered. A
 * tap's copy is accepted whatever the handler said, since the original never left its queue. The SDK
 * reopens a lost link on its own; a processor it gives up on is dropped here, and the next pass starts
 * it again. A processor that stops, or a Rahdar that dies, accepts nothing it had not already settled,
 * so what it held is redelivered.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginDrains {

    /** How long stopping a drain waits for a handler still running; the rest goes back uncounted. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(30);

    /** Set while a lane runs a plugin's handler, so a stop asked from inside one never waits on itself. */
    private static final ThreadLocal<Boolean> IN_HANDLER = new ThreadLocal<>();

    private final BrokerMessages messages;
    private final PluginHandlers handlers;

    /**
     * One drain's identity and what it reads. {@code subscription} is what the plugin registered and is
     * handed; {@code source} is what the processor opens (a tap's holding queue, otherwise the same). A
     * null {@code nodeId} is a drain for the whole cluster.
     */
    record Spec(
            UUID registrationId,
            String pluginId,
            String key,
            RegistrationMode mode,
            int concurrency,
            UUID clusterId,
            UUID nodeId,
            String nodeName,
            SubscriptionId subscription,
            SubscriptionId source,
            OrderingScope ordering,
            FlowControl flow) {}

    /**
     * Why a drain could not start: the broker let another consumer have it, it refused for a reason a retry will
     * not change ({@code permanent}), or anything else.
     */
    static final class StartFailure extends Exception {
        final boolean servedElsewhere;
        final boolean permanent;

        StartFailure(String message, boolean servedElsewhere, boolean permanent, Throwable cause) {
            super(message, cause);
            this.servedElsewhere = servedElsewhere;
            this.permanent = permanent;
        }
    }

    /** A running drain: its spec and the processor delivering it. */
    private record Drain(Spec spec, Processor processor) {}

    private final Map<String, Drain> running = new ConcurrentHashMap<>();

    private static String key(UUID registrationId, UUID nodeId) {
        return registrationId + "|" + (nodeId == null ? "cluster" : nodeId);
    }

    /** Whether the drain runs; one whose link the SDK gave up on is dropped, so the next pass restarts it. */
    boolean isRunning(UUID registrationId, UUID nodeId) {
        String key = key(registrationId, nodeId);
        Drain drain = running.get(key);
        if (drain == null) {
            return false;
        }
        if (drain.processor().state() == LinkState.FAILED && running.remove(key, drain)) {
            log.warn(
                    "Plugin delivery of {} on {} lost its broker link for good and restarts on this pass",
                    drain.spec().subscription().id(),
                    drain.spec().nodeName());
            close(drain);
            return false;
        }
        return true;
    }

    /** How many lanes a running drain has, or 0 when it is not running. */
    int concurrency(UUID registrationId, UUID nodeId) {
        Drain drain = running.get(key(registrationId, nodeId));
        return drain == null ? 0 : drain.spec().concurrency();
    }

    /** The registrations draining on a node. */
    Set<UUID> runningOn(UUID nodeId) {
        return running.values().stream()
                .filter(d -> Objects.equals(d.spec().nodeId(), nodeId))
                .map(d -> d.spec().registrationId())
                .collect(Collectors.toSet());
    }

    /** The registrations draining for the whole of a cluster (a partitioned log), not per node. */
    Set<UUID> wholeClusterOf(UUID clusterId) {
        return running.values().stream()
                .filter(d -> d.spec().nodeId() == null && d.spec().clusterId().equals(clusterId))
                .map(d -> d.spec().registrationId())
                .collect(Collectors.toSet());
    }

    Set<UUID> nodesOf(UUID registrationId) {
        return running.values().stream()
                .filter(d -> d.spec().registrationId().equals(registrationId))
                .map(d -> d.spec().nodeId())
                .collect(Collectors.toSet());
    }

    /**
     * Start draining, or do nothing if already draining. Every lane opens, or none does. Starts are serial, so
     * two passes never both open a processor for one drain, which a queue's consumer limit would report as
     * another replica delivering it.
     */
    synchronized void start(Spec spec) throws StartFailure {
        String key = key(spec.registrationId(), spec.nodeId());
        if (running.containsKey(key)) {
            return;
        }
        Processor processor = open(spec);
        if (running.putIfAbsent(key, new Drain(spec, processor)) != null) {
            processor.close();
            return;
        }
        log.info(
                "Delivering {} of {} on {} to plugin {} ({}, {} at once)",
                spec.mode() == RegistrationMode.TAP ? "a copy" : "messages",
                spec.subscription().id(),
                spec.nodeName(),
                spec.pluginId(),
                spec.key(),
                spec.concurrency());
    }

    private Processor open(Spec spec) throws StartFailure {
        ProcessorOptions options = new ProcessorOptions(
                ReceiverOptions.consume().withFlow(spec.flow()),
                spec.concurrency(),
                spec.ordering(),
                RetryPolicy.defaults(),
                STOP_WAIT);
        try {
            return messages.process(
                    spec.clusterId(),
                    spec.nodeId(),
                    spec.source().kind(),
                    spec.source().path(),
                    options,
                    context -> deliver(spec, context),
                    "plugin " + spec.pluginId() + "/" + spec.key());
        } catch (BrokerException e) {
            boolean elsewhere = ErrorReasons.MAX_CONSUMERS_REACHED.equals(e.reason());
            boolean permanent = e.status() == Status.FAILED_PRECONDITION
                    || e.status() == Status.UNIMPLEMENTED
                    || e.status() == Status.INVALID_ARGUMENT;
            throw new StartFailure(
                    elsewhere
                            ? "Another Rahdar instance sharing this database is delivering this on " + spec.nodeName()
                            : "The broker refused a consumer on "
                                    + spec.source().id() + " at " + spec.nodeName() + ": " + reason(e),
                    elsewhere,
                    permanent,
                    e);
        } catch (RuntimeException e) {
            throw new StartFailure(
                    "Rahdar could not open a receiver on " + spec.nodeName() + ": " + reason(e), false, false, e);
        }
    }

    private io.github.sudoitir.openbroker.messaging.Disposition deliver(Spec spec, MessageContext context) {
        Optional<Disposition> outcome;
        IN_HANDLER.set(Boolean.TRUE);
        try {
            PluginMessage delivered = MessageConversion.toPlugin(
                    spec.key(), spec.clusterId(), spec.nodeId(), spec.subscription(), context.message());
            outcome = handlers.deliver(spec.pluginId(), delivered);
        } catch (Exception | LinkageError e) {
            // The message's content is never logged: it is production payload.
            log.warn(
                    "Plugin {} failed on a message from {} ({}): {}",
                    spec.pluginId(),
                    spec.subscription().id(),
                    spec.key(),
                    e.toString());
            outcome = Optional.of(Disposition.REJECT);
        } finally {
            IN_HANDLER.remove();
        }
        // An empty outcome: the plugin is not receiving (it is being stopped), so the message goes back
        // uncounted.
        return settlement(spec.mode(), outcome.orElse(Disposition.RELEASE));
    }

    void stop(UUID registrationId, UUID nodeId) {
        Drain drain = running.remove(key(registrationId, nodeId));
        if (drain != null) {
            close(drain);
        }
    }

    /** Stop every drain of a registration, on every node. */
    public void stopRegistration(UUID registrationId) {
        stopWhere(d -> d.spec().registrationId().equals(registrationId));
    }

    /** Stop every drain on a cluster, because this replica no longer owns it. */
    void stopCluster(UUID clusterId) {
        stopWhere(d -> d.spec().clusterId().equals(clusterId));
    }

    /** Stop every drain delivering to a plugin, before its context closes. */
    void stopPlugin(String pluginId) {
        stopWhere(d -> d.spec().pluginId().equals(pluginId));
    }

    private void stopWhere(Predicate<Drain> which) {
        running.entrySet().removeIf(e -> {
            if (!which.test(e.getValue())) {
                return false;
            }
            close(e.getValue());
            return true;
        });
    }

    /**
     * Stop taking messages, wait for running handlers, and hand back what is left uncounted. Asked from
     * inside a handler (a plugin stopping its own registration), it closes on another thread, since the
     * processor would otherwise wait for the very handler asking.
     */
    private static void close(Drain drain) {
        if (Boolean.TRUE.equals(IN_HANDLER.get())) {
            Thread.ofVirtual().name("plugin-stop-" + drain.spec().key()).start(() -> closeQuietly(drain));
            return;
        }
        closeQuietly(drain);
    }

    private static void closeQuietly(Drain drain) {
        try {
            drain.processor().close();
        } catch (RuntimeException e) {
            log.warn(
                    "Stopping plugin delivery of {} on {} failed: {}",
                    drain.spec().subscription().id(),
                    drain.spec().nodeName(),
                    e.getMessage());
        }
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * A tap's copy is always accepted. A consumed message is accepted, handed back uncounted on a
     * release, or handed back counted on a rejection, so the broker redelivers it within its own
     * delivery-attempt limit before it dead-letters it.
     */
    static io.github.sudoitir.openbroker.messaging.Disposition settlement(RegistrationMode mode, Disposition outcome) {
        if (mode == RegistrationMode.TAP || outcome == Disposition.ACCEPT) {
            return io.github.sudoitir.openbroker.messaging.Disposition.accept();
        }
        return new io.github.sudoitir.openbroker.messaging.Disposition.Release(
                Optional.empty(), outcome == Disposition.REJECT);
    }
}
