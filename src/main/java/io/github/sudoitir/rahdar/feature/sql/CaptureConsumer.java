package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.openbroker.messaging.Disposition;
import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.PropertyValue;
import io.github.sudoitir.openbroker.messaging.ReceiveOptions;
import io.github.sudoitir.openbroker.messaging.Receiver;
import io.github.sudoitir.rahdar.feature.sql.QueryAst.Source;
import io.github.sudoitir.rahdar.feature.sql.QueryResult.Row;
import io.github.sudoitir.rahdar.platform.broker.BodyEncoding;
import io.github.sudoitir.rahdar.platform.broker.BrokerMessages;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessage;
import io.github.sudoitir.rahdar.platform.broker.BrowsedMessages;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Drains one capture queue on one node (ADR-0062 D1), stores what it drains in the
 * message index, and offers it to {@link CaptureBus} listeners.
 *
 * <p>Consuming is destructive, which is correct: Rahdar owns the capture queue, and
 * nothing else is entitled to what is on it. The original message is untouched — the
 * divert is non-exclusive and made a copy.
 *
 * <p>Store first, acknowledge second (ADR-0077). Each drain reads through its own transacted
 * receiver on its own virtual thread, and commits a batch only after that batch's rows are stored,
 * so a commit never runs ahead of the store and never covers another drain's rows. When the store
 * fails, nothing is committed; the drain waits with backoff and rolls back, and the bounded capture
 * queue holds the backlog meanwhile. A crash mid-batch redelivers, which the writer's redelivery
 * guard absorbs.
 *
 * <p>Identity comes from the queue being drained, not from a header (D2): this
 * consumer was started for a known source address, so every message it receives is
 * attributed to that address whatever the broker did or did not copy into
 * {@code _AMQ_ORIG_ADDRESS}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CaptureConsumer {

    /** The broker's own name for the source message's id, which a divert copies onto the copy. */
    private static final String ORIG_MESSAGE_ID = "_AMQ_ORIG_MESSAGE_ID";

    /** Messages acknowledged in one go. Larger trades redelivery risk for fewer round trips. */
    static final int ACK_BATCH = 50;

    /** Consecutive failures to read one message before it is counted as lost rather than stall the tap. */
    private static final int POISON_ATTEMPTS = 3;

    private static final Duration RETRY_INITIAL = Duration.ofSeconds(1);
    private static final Duration RETRY_MAX = Duration.ofMinutes(5);

    /** How long one receive waits; also how soon a drain sees a flush or a stop. */
    private static final Duration POLL = Duration.ofMillis(500);

    /** How long stopping waits for a drain to store, commit and close. */
    private static final Duration STOP_WAIT = Duration.ofSeconds(30);

    private final BrokerMessages messages;
    private final CaptureBus bus;
    private final MessageIndexWriter writer;

    /**
     * Running drains, by node and tap name.
     *
     * <p>The node is part of the key and has to be. A tap's name identifies the
     * subscription and the source address, not the broker — the same tap exists on
     * every live node — so keying on the name alone made one node's drain look like
     * every node's, and a promoted backup was reported as already draining while the
     * only live drain was still pointed at the node that had just died.
     */
    private final Map<String, Drain> running = new ConcurrentHashMap<>();

    private static String key(UUID nodeId, String name) {
        return nodeId + "|" + name;
    }

    /** What a drain needs to know to attribute and bound what it reads. */
    public record Spec(
            UUID clusterId,
            UUID nodeId,
            String nodeName,
            UUID subscriptionId,
            /** The tap's name — the divert's name, and this drain's key. */
            String name,
            /** The queue actually drained, which is {@link CaptureNames#queueOf} of the name. */
            String captureQueue,
            String sourceAddress,
            int bodyCapBytes,
            int maxRate) {}

    /**
     * Why a node's capture recorded less than it drained since this was last taken: the
     * cause half of the loss figure, which {@link CaptureLoss} measures from counters.
     *
     * @param storeFailure the last store error, or null when every store since succeeded
     */
    public record Shortfall(long unreadable, String storeFailure) {
        static final Shortfall NONE = new Shortfall(0, null);

        Shortfall plus(Shortfall other) {
            return new Shortfall(
                    unreadable + other.unreadable, other.storeFailure != null ? other.storeFailure : storeFailure);
        }
    }

    /** Whether a drain is running for this tap on this node. */
    public boolean isDraining(UUID nodeId, String name) {
        return running.containsKey(key(nodeId, name));
    }

    /** The tap names currently being drained on this node. */
    public Set<String> drainingOn(UUID nodeId) {
        String prefix = nodeId + "|";
        return running.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length()))
                .collect(Collectors.toSet());
    }

    /** The causes recorded by this subscription's drains on this node since the last call, reset by taking them. */
    public Shortfall takeShortfall(UUID nodeId, UUID subscriptionId) {
        Shortfall total = Shortfall.NONE;
        for (Drain drain : running.values()) {
            if (drain.spec.nodeId().equals(nodeId)
                    && drain.spec.subscriptionId().equals(subscriptionId)) {
                total = total.plus(drain.takeShortfall());
            }
        }
        return total;
    }

    /**
     * Start draining, or do nothing if already draining. Throws when the broker will
     * not let Rahdar consume — which is a per-node fact the caller records, not a
     * reason to stop reconciling the other nodes.
     */
    public void start(Spec spec) {
        String key = key(spec.nodeId(), spec.name());
        if (running.containsKey(key)) {
            return;
        }
        Receiver receiver = messages.receiver(
                spec.clusterId(),
                spec.nodeId(),
                spec.captureQueue(),
                ReceiveOptions.consume().asTransacted());
        Drain drain = new Drain(spec, receiver);
        running.put(key, drain);
        drain.thread = Thread.ofVirtual().name("capture-" + spec.name()).start(drain::run);
        log.info("Draining capture queue {} for {} on {}", spec.captureQueue(), spec.sourceAddress(), spec.nodeName());
    }

    /** Stop draining a tap on one node. Safe to call for one that is not running. */
    public void stop(UUID nodeId, String name) {
        Drain drain = running.remove(key(nodeId, name));
        if (drain != null) {
            drain.close();
        }
    }

    /**
     * Stop every drain of one subscription, on every node — before its rows are deleted or its
     * capture is turned off, so nothing is written after the operator's decision.
     */
    public void stopSubscription(UUID subscriptionId) {
        running.entrySet().removeIf(entry -> {
            if (!entry.getValue().spec.subscriptionId().equals(subscriptionId)) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
    }

    /**
     * Stop every drain on a cluster, storing and acknowledging what each holds: this replica no
     * longer owns the cluster, and the capture queue admits one consumer, so the new owner's drain
     * can only start once these are closed.
     */
    public void stopCluster(UUID clusterId) {
        running.entrySet().removeIf(entry -> {
            if (!entry.getValue().spec.clusterId().equals(clusterId)) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
    }

    /** Close everything this holds open. Called at its shutdown phase. */
    public void closeAll() {
        running.values().forEach(Drain::close);
        running.clear();
    }

    /**
     * Store and acknowledge what each drain is holding below a full acknowledge batch.
     *
     * <p>Without it a drain only commits at {@link #ACK_BATCH}, which on an address that
     * carries a handful of messages an hour is never: the copies sit unstored in the drain
     * and uncommitted on the capture queue, so the index answers nothing about them and
     * the queue grows towards its ring bound while its drain looks healthy.
     *
     * <p>It only asks: each drain commits on its own thread, the one its receiver belongs to, at
     * its next turn. A drain whose store is failing is waiting out its backoff and asked once,
     * however many ticks pass.
     */
    public void flushAll() {
        running.values().forEach(drain -> drain.flushRequested.set(true));
    }

    /** Exponential, capped, with jitter — so many drains failing together do not retry together. */
    static long backoffMillis(int failures) {
        long base = Math.min(RETRY_MAX.toMillis(), RETRY_INITIAL.toMillis() * (1L << Math.min(failures - 1, 20)));
        return base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1);
    }

    /**
     * One queue's drain: its receiver, batch and counters, used only by its own thread. The
     * counters a caller takes, and the monitor a waiting drain is woken on, are the only shared
     * state.
     */
    final class Drain {

        private final Spec spec;
        private final Receiver receiver;
        private volatile Thread thread;

        /** Rows received and accepted since the last commit. */
        private final List<MessageIndexWriter.Captured> batch = new ArrayList<>(ACK_BATCH);

        /** Set by {@link #flushAll}, taken by the drain at its next turn. */
        private final AtomicBoolean flushRequested = new AtomicBoolean();

        private String failingMessageId;
        private int readFailures;
        private int storeFailures;

        private final AtomicLong unreadable = new AtomicLong();
        private volatile String storeFailure;

        private volatile boolean closed;

        Drain(Spec spec, Receiver receiver) {
            this.spec = spec;
            this.receiver = receiver;
        }

        /**
         * Receive, store and commit until stopped; then store and commit what is held. A receiver
         * that fails has lost its connection: the drain is forgotten, so the next reconcile pass
         * installs it again and records the gap (message-capture spec), and what it held
         * uncommitted is redelivered to the drain that replaces it.
         */
        void run() {
            try {
                while (!closed) {
                    List<Message> received = receiver.receive(ACK_BATCH - batch.size(), POLL);
                    for (Message message : received) {
                        if (closed) {
                            break;
                        }
                        take(message);
                    }
                    if (batch.size() >= ACK_BATCH || (flushRequested.getAndSet(false) && !batch.isEmpty())) {
                        commit();
                    }
                }
                finish();
            } catch (RuntimeException e) {
                running.remove(key(spec.nodeId(), spec.name()), this);
                log.warn(
                        "Capture of {} on {} lost its broker connection and will be re-created on the next pass: {}",
                        spec.sourceAddress(),
                        spec.nodeName(),
                        e.getMessage());
            } finally {
                receiver.close();
            }
        }

        void take(Message message) {
            MessageIndexWriter.Captured captured;
            try {
                captured = read(message);
            } catch (RuntimeException e) {
                unreadable(message, e);
                return;
            }
            failingMessageId = null;
            readFailures = 0;
            // Over the rate cap the drain waits, and the message stays in the bounded capture queue
            // (ADR-0077). A drain stopped while waiting has neither recorded nor accepted it, so the
            // broker redelivers it.
            long wait;
            while ((wait = bus.admit(spec.subscriptionId(), spec.maxRate())) > 0) {
                waitUnlessClosed(wait);
                if (closed) {
                    return;
                }
            }
            receiver.settle(message, Disposition.accept());
            batch.add(captured);
            bus.publish(new CaptureBus.Captured(
                    spec.clusterId(),
                    spec.subscriptionId(),
                    captured.row(),
                    captured.origAddress(),
                    captured.sourceMessageId(),
                    captured.at()));
        }

        private MessageIndexWriter.Captured read(Message message) {
            BrowsedMessage browsed = BrowsedMessages.of(message);
            return new MessageIndexWriter.Captured(
                    spec.clusterId(),
                    toRow(browsed),
                    message.headers().origin().orElse(null),
                    longProperty(message, ORIG_MESSAGE_ID),
                    Instant.now());
        }

        /** Store, then commit. Returns false when the store failed and the batch was rolled back. */
        private boolean commit() {
            try {
                writer.capturedBatch(batch);
            } catch (RuntimeException e) {
                storeFailed(e);
                return false;
            }
            // Stored but not committed, should the commit fail: the broker redelivers, and the
            // writer's redelivery guard stops the batch being stored twice.
            receiver.commit();
            batch.clear();
            storeFailures = 0;
            return true;
        }

        private void storeFailed(RuntimeException e) {
            storeFailures++;
            storeFailure =
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            long delay = backoffMillis(storeFailures);
            log.warn(
                    "Capture of {} on {} could not store {} message(s): {}. Nothing was acknowledged; retrying in {} ms,"
                            + " and the capture queue holds the backlog up to its bound.",
                    spec.sourceAddress(),
                    spec.nodeName(),
                    batch.size(),
                    storeFailure,
                    delay);
            waitUnlessClosed(delay);
            rollback();
        }

        /**
         * A message that cannot be read is retried like a failed store. One that keeps
         * failing is counted as lost and acknowledged — after everything received before
         * it has been stored — so a single poison message cannot stall the tap forever.
         */
        private void unreadable(Message message, Exception e) {
            String id = message.ref().position();
            if (id.equals(failingMessageId)) {
                readFailures++;
            } else {
                failingMessageId = id;
                readFailures = 1;
            }
            if (readFailures < POISON_ATTEMPTS) {
                log.debug("Capture drain of {} could not read message {}: {}", spec.captureQueue(), id, e.getMessage());
                waitUnlessClosed(backoffMillis(readFailures));
                rollback();
                return;
            }
            log.warn(
                    "Capture of {} on {} could not read message {} after {} attempts; counting it as lost: {}",
                    spec.sourceAddress(),
                    spec.nodeName(),
                    id,
                    readFailures,
                    e.getMessage());
            receiver.settle(message, Disposition.accept());
            if (commit()) {
                unreadable.incrementAndGet();
                failingMessageId = null;
                readFailures = 0;
            }
        }

        /** Hand everything uncommitted back to the broker, and forget the rows built from it. */
        private void rollback() {
            batch.clear();
            receiver.rollback();
        }

        /** Stopped: store and commit what is held, or leave it uncommitted for redelivery. */
        private void finish() {
            if (batch.isEmpty()) {
                return;
            }
            try {
                writer.capturedBatch(batch);
                receiver.commit();
            } catch (RuntimeException e) {
                log.warn(
                        "Capture drain of {} stopped with {} message(s) not stored; left unacknowledged for"
                                + " redelivery: {}",
                        spec.captureQueue(),
                        batch.size(),
                        e.getMessage());
            }
            batch.clear();
        }

        private synchronized void waitUnlessClosed(long millis) {
            long deadline = System.nanoTime() + millis * 1_000_000L;
            try {
                long remaining;
                while (!closed && (remaining = (deadline - System.nanoTime()) / 1_000_000L) > 0) {
                    wait(remaining);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized Shortfall takeShortfall() {
            Shortfall taken = new Shortfall(unreadable.getAndSet(0), storeFailure);
            storeFailure = null;
            return taken;
        }

        /**
         * The body cap is applied here, at the edge, and not in the writer. A large message
         * is already in memory by the time it is a {@code BrowsedMessage}, so this bounds what
         * reaches Postgres rather than what reaches Rahdar — the transfer itself is bounded by
         * the divert filter and by not capturing addresses that carry large messages.
         */
        private Row toRow(BrowsedMessage message) {
            String body = message.body();
            boolean truncated = message.bodyTruncated();
            if (body != null && body.length() > spec.bodyCapBytes()) {
                body = body.substring(0, spec.bodyCapBytes());
                truncated = true;
            }
            Map<String, Object> properties = new HashMap<>();
            properties.putAll(message.stringProperties());
            properties.putAll(message.intProperties());
            properties.putAll(message.longProperties());
            properties.putAll(message.doubleProperties());
            properties.putAll(message.booleanProperties());
            return new Row(
                    spec.nodeId(),
                    spec.nodeName(),
                    spec.sourceAddress(),
                    spec.sourceAddress(),
                    message.messageId(),
                    message.type(),
                    message.durable(),
                    message.priority(),
                    message.timestamp(),
                    message.expiration(),
                    message.size(),
                    message.contentType(),
                    message.correlationId(),
                    message.groupId(),
                    message.userId(),
                    message.replyTo(),
                    body,
                    truncated,
                    message.bodyEncoding() == BodyEncoding.BASE64,
                    message.bodyCompression().apiName(),
                    Map.copyOf(properties),
                    Source.BROKER,
                    null,
                    null,
                    "CAPTURED",
                    null);
        }

        /**
         * Stop the drain and wait for it: a waiting drain is woken, stores and commits what it
         * holds, and closes its receiver, so the capture queue's one consumer is free again.
         */
        void close() {
            synchronized (this) {
                closed = true;
                notifyAll();
            }
            Thread running = thread;
            if (running == null || running == Thread.currentThread()) {
                return;
            }
            try {
                if (!running.join(STOP_WAIT)) {
                    log.warn("Capture drain of {} did not stop within {}", spec.captureQueue(), STOP_WAIT);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Long longProperty(Message message, String name) {
        return switch (message.properties().get(name)) {
            case PropertyValue.OfLong l -> l.value();
            case PropertyValue.OfInt i -> (long) i.value();
            case PropertyValue.OfString t -> parseLong(t.value());
            case null, default -> null;
        };
    }

    private static Long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
