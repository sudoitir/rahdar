package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.messaging.Disposition;
import io.github.sudoitir.openbroker.messaging.Message;
import io.github.sudoitir.openbroker.messaging.Receiver;
import io.github.sudoitir.openbroker.messaging.Sender;
import io.github.sudoitir.rahdar.platform.broker.RelayedMessages.Provenance;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One run's relay from a queue on one node to a queue on another (ADR-0097, transfer design D1 and
 * D2), one batch at a time, through the broker SDK's transport: a receiver on the source and a
 * transacted sender on the target.
 *
 * <p>A batch is received, each message sent with its provenance ({@link RelayedMessages}) and
 * committed on the target; only then is it acknowledged on the source. A <em>staged</em> link
 * receives in a transaction from a staging queue; a <em>browse</em> link reads the source queue and
 * leaves it as it is, and the caller keeps its own record of what it copied.
 *
 * <p>When the target refuses a commit because one of the batch's duplicate ids arrived before (a
 * batch repeated after Rahdar stopped between the two commits), it delivers none of the batch. The
 * link rolls both sides back and relays the next batch's worth of messages one per transaction, where
 * a refusal means only that one message had already arrived.
 *
 * <p>Not thread-safe: one run drives it.
 */
public final class RelayLink implements AutoCloseable {

    private static final Duration FIRST_WAIT = Duration.ofSeconds(1);
    private static final Duration NEXT_WAIT = Duration.ofMillis(200);

    /** What the caller decides around a batch: which messages it wants, and what happens once the target has them. */
    public interface Hooks {

        /** Whether this source message is part of the selection. */
        default boolean selected(long sourceId) {
            return true;
        }

        /** The ids among {@code sourceIds} already relayed by an earlier attempt, to skip. */
        default Set<Long> alreadyRelayed(List<Long> sourceIds) {
            return Set.of();
        }

        /**
         * These source messages are about to be committed on the target. Once the commit is sent,
         * Rahdar may not learn whether it succeeded, so a caller that must know records them here.
         */
        default void beforeTargetCommit(List<Long> sourceIds) {}

        /**
         * The target has committed these source messages and the source has not yet acknowledged
         * them. Throwing leaves them unacknowledged on the source.
         */
        default void afterTargetCommit(List<Long> sourceIds) {}
    }

    public enum Outcome {
        /** Messages were relayed, or skipped as already relayed. */
        RELAYED,
        /** The source gave no message within the wait. */
        EMPTY,
        /** The target refused the batch for want of room; nothing of it was delivered or acknowledged. */
        ADDRESS_FULL
    }

    /**
     * @param delivered messages the target committed in this batch
     * @param duplicates messages the target refused as already delivered, now acknowledged
     * @param bytes body bytes of the delivered messages
     * @param sourceIds the source ids of the delivered and duplicate messages
     */
    public record Batch(Outcome outcome, int delivered, int duplicates, long bytes, List<Long> sourceIds) {}

    /**
     * What to relay: from {@code sourceQueue} (a staging queue when {@code staged}, else the source
     * queue browsed with {@code browseFilter}, which may be null, among the messages produced at or
     * before {@code producedAtOrBefore}) to {@code targetQueue}, with {@code provenance} naming where
     * the messages came from.
     */
    public record Route(
            String sourceQueue,
            boolean staged,
            String browseFilter,
            Optional<Instant> producedAtOrBefore,
            String targetQueue,
            Provenance provenance) {}

    private final Supplier<Receiver> receivers;
    private final Sender target;
    private final Route route;
    private Receiver source;
    /** Messages still to be relayed one per transaction after a duplicate refusal. */
    private int single;

    RelayLink(Supplier<Receiver> receivers, Sender target, Route route) {
        this.receivers = receivers;
        this.target = target;
        this.route = route;
    }

    /**
     * Relay at most {@code max} selected messages.
     *
     * @throws BrokerConnectionException when either node fails mid-batch: nothing of the batch was
     *     acknowledged on the source, and the caller closes this link
     * @throws IOException when a large message cannot be spooled, for want of disk
     */
    public Batch relay(int max, Hooks hooks) throws IOException {
        List<Message> received = receive(max, hooks);
        try {
            if (received.isEmpty()) {
                return new Batch(Outcome.EMPTY, 0, 0, 0, List.of());
            }
            List<Long> ids = received.stream().map(RelayedMessages::sourceId).toList();
            Set<Long> skip = hooks.alreadyRelayed(ids);
            List<Message> fresh = new ArrayList<>();
            for (Message m : received) {
                if (skip.contains(RelayedMessages.sourceId(m))) {
                    acknowledge(m);
                } else {
                    fresh.add(m);
                }
            }
            if (fresh.isEmpty()) {
                commitSource();
                return new Batch(Outcome.RELAYED, 0, 0, 0, List.of());
            }
            return single > 0 ? oneByOne(fresh, hooks) : together(fresh, hooks);
        } catch (BrokerException e) {
            rollbackQuietly();
            throw failed(e);
        } catch (UncheckedIOException e) {
            rollbackQuietly();
            throw e.getCause();
        }
    }

    /** The whole batch in one target transaction. */
    private Batch together(List<Message> messages, Hooks hooks) {
        long bytes = 0;
        List<Long> ids = messages.stream().map(RelayedMessages::sourceId).toList();
        try {
            for (Message m : messages) {
                bytes += m.body().size();
                target.send(RelayedMessages.of(m, route.provenance()));
            }
            hooks.beforeTargetCommit(ids);
            target.commit();
        } catch (BrokerException e) {
            rollbackQuietly();
            if (duplicate(e)) {
                // Some of these arrived before: find which, one message per transaction.
                single = Math.max(messages.size(), 1);
                return new Batch(Outcome.RELAYED, 0, 0, 0, List.of());
            }
            if (full(e)) {
                return new Batch(Outcome.ADDRESS_FULL, 0, 0, 0, List.of());
            }
            throw e;
        }
        hooks.afterTargetCommit(ids);
        for (Message m : messages) {
            acknowledge(m);
        }
        commitSource();
        return new Batch(Outcome.RELAYED, messages.size(), 0, bytes, ids);
    }

    /** Each message in its own target transaction, after a duplicate refusal. */
    private Batch oneByOne(List<Message> messages, Hooks hooks) {
        int delivered = 0;
        int duplicates = 0;
        long bytes = 0;
        boolean full = false;
        List<Message> arrived = new ArrayList<>();
        for (Message m : messages) {
            long id = RelayedMessages.sourceId(m);
            try {
                target.send(RelayedMessages.of(m, route.provenance()));
                hooks.beforeTargetCommit(List.of(id));
                target.commit();
                delivered++;
                bytes += m.body().size();
            } catch (BrokerException e) {
                target.rollback();
                if (full(e)) {
                    full = true;
                    break;
                }
                if (!duplicate(e)) {
                    throw e;
                }
                duplicates++;
            }
            arrived.add(m);
            single--;
        }
        List<Long> ids = arrived.stream().map(RelayedMessages::sourceId).toList();
        if (!ids.isEmpty()) {
            hooks.afterTargetCommit(ids);
        }
        // Acknowledge exactly the messages the target holds; the rest go back to the source.
        for (Message m : arrived) {
            acknowledge(m);
        }
        commitSource();
        if (full) {
            giveBack();
        }
        return new Batch(
                full && ids.isEmpty() ? Outcome.ADDRESS_FULL : Outcome.RELAYED, delivered, duplicates, bytes, ids);
    }

    /** Receive up to {@code max} selected messages; a large one's body is spooled by the transport. */
    private List<Message> receive(int max, Hooks hooks) throws IOException {
        List<Message> out = new ArrayList<>();
        try {
            if (source == null) {
                source = receivers.get();
            }
            while (out.size() < max) {
                List<Message> got = source.receive(max - out.size(), out.isEmpty() ? FIRST_WAIT : NEXT_WAIT);
                if (got.isEmpty()) {
                    break;
                }
                for (Message m : got) {
                    if (hooks.selected(RelayedMessages.sourceId(m))) {
                        out.add(m);
                    } else if (!route.staged()) {
                        // A browse holds what it read until it is settled; one not wanted is let go now.
                        source.settle(m, Disposition.accept());
                    }
                }
            }
            return out;
        } catch (BrokerException e) {
            rollbackQuietly();
            throw failed(e);
        } catch (UncheckedIOException e) {
            rollbackQuietly();
            throw e.getCause();
        }
    }

    /** Settle one received message: within the transaction for a staging queue; a browse only lets it go. */
    private void acknowledge(Message m) {
        source.settle(m, Disposition.accept());
    }

    private void commitSource() {
        if (route.staged()) {
            source.commit();
        }
    }

    /** Return the received but unacknowledged messages: to the staging queue, or to a new browse. */
    private void giveBack() {
        if (source == null) {
            return;
        }
        if (route.staged()) {
            source.rollback();
        } else {
            // A browse starts again from the head of the queue.
            source.close();
            source = null;
        }
    }

    /** Roll both sides back; a browse starts again from the head, so nothing it read is lost. */
    private void rollbackQuietly() {
        try {
            target.rollback();
        } catch (RuntimeException _) {
            // The session is going away; the broker rolls it back when it closes.
        }
        try {
            giveBack();
        } catch (RuntimeException _) {
            // As above.
        }
    }

    private static boolean duplicate(BrokerException e) {
        return ErrorReasons.DUPLICATE_REJECTED.equals(e.reason());
    }

    private static boolean full(BrokerException e) {
        return ErrorReasons.DESTINATION_FULL.equals(e.reason());
    }

    private static BrokerConnectionException failed(BrokerException e) {
        return new BrokerConnectionException(
                BrokerConnectionException.Kind.UNREACHABLE, "The relay failed: " + e.getMessage(), e);
    }

    @Override
    public void close() {
        if (source != null) {
            source.close();
        }
        target.close();
    }
}
