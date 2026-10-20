package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionException;
import io.github.sudoitir.rahdar.platform.broker.BulkResult;
import io.github.sudoitir.rahdar.platform.broker.QueueDepth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The Phase 3 mutating message operations over Jolokia (ADR-0021). Each method is
 * exactly one {@code exec} — never a dry-run and an act in the same POST
 * (non-negotiable #1). Results are the broker's own affected counts, not
 * estimates. Invalid selectors ({@code AMQ229020}) surface as
 * {@link IllegalArgumentException} (→ 400).
 */
@Component
public class MessageOperations {

    private static final String ORIGINAL_ADDRESS = "_AMQ_ORIG_ADDRESS";

    /**
     * The overload with {@code createMessageId} (Artemis 2.33 and later): without it a message sent over
     * Jolokia carries no message id, unlike one sent over Core, so a consumer could not tell a redelivery
     * from a new message.
     */
    private static final String SEND_SIG =
            "sendMessage(java.util.Map,int,java.lang.String,boolean,java.lang.String,java.lang.String,boolean)";

    /** A message to enqueue. {@code headers} are string properties. */
    public record Outgoing(Map<String, Object> headers, int type, String body, boolean durable) {}

    /**
     * The broker account a message is sent as, empty when the broker has no security. The password
     * is never logged, so it is not in {@link #toString()}.
     */
    public record Sender(String user, String password) {
        @Override
        public String toString() {
            return "Sender[user=" + user + "]";
        }
    }

    /** Enqueue one message on the address MBean. Returns the broker-assigned id, or {@code null}. */
    public String send(ArtemisNode client, String addressMbean, Outgoing message, Sender sender) {
        ManagementResponse res = client.single(ManagementRequest.exec(
                addressMbean,
                SEND_SIG,
                message.headers() == null ? Map.of() : message.headers(),
                message.type(),
                message.body() == null ? "" : message.body(),
                message.durable(),
                sender.user(),
                sender.password(),
                true));
        requireOk(res, "sendMessage");
        JsonNode v = res.value();
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * {@code countMessages(filter)} on the queue MBean — the by-filter dry-run estimate, and with a
     * {@link FrozenFilter} the size of a frozen selection.
     */
    public long countMessages(ArtemisNode client, String queueMbean, String filter) {
        ManagementResponse res = client.single(
                ManagementRequest.exec(queueMbean, "countMessages(java.lang.String)", filter == null ? "" : filter));
        rejectBadFilter(res);
        requireOk(res, "countMessages");
        return res.value() == null ? 0L : res.value().asLong();
    }

    /**
     * The addresses the messages on the queue were first sent to, from the {@code _AMQ_ORIG_ADDRESS} the
     * broker sets on a message it dead-letters or expires: one {@code countMessages} grouped by that
     * property. A retry sends each message back to its address, so this is where a retry would write.
     */
    public Set<String> originalAddresses(ArtemisNode client, String queueMbean) {
        ManagementResponse res = client.single(ManagementRequest.exec(
                queueMbean, "countMessages(java.lang.String,java.lang.String)", "", ORIGINAL_ADDRESS));
        requireOk(res, "countMessages");
        Set<String> addresses = new TreeSet<>();
        JsonNode grouped = client.parsed(res);
        if (grouped != null) {
            grouped.propertyNames().forEach(name -> {
                if (!name.isBlank() && !"null".equals(name)) {
                    addresses.add(name);
                }
            });
        }
        return addresses;
    }

    /**
     * The ids of every message on the queue, in queue order, from one {@code listMessages("")}. For a
     * queue known to be small, such as a transfer's staging queue: it lists the whole queue at once.
     */
    public List<Long> listIds(ArtemisNode client, String queueMbean) {
        return listIds(client, queueMbean, "");
    }

    /** The ids of the messages on the queue that match the filter, in queue order. */
    public List<Long> listIds(ArtemisNode client, String queueMbean, String filter) {
        ManagementResponse res =
                client.single(ManagementRequest.exec(queueMbean, "listMessages(java.lang.String)", filter));
        requireOk(res, "listMessages");
        List<Long> ids = new ArrayList<>();
        JsonNode listed = client.parsed(res);
        if (listed != null) {
            listed.forEach(m -> ids.add(m.path("messageID").asLong()));
        }
        return ids;
    }

    /** The filter that selects the messages the broker dead-lettered or expired from this address. */
    public String originalAddressFilter(String address) {
        return ORIGINAL_ADDRESS + " = '" + address.replace("'", "''") + "'";
    }

    /** Current {@code MessageCount} of the queue — the purge / retry-all dry-run estimate. */
    public long messageCount(ArtemisNode client, String queueMbean) {
        ManagementResponse res = client.single(ManagementRequest.read(queueMbean, MESSAGE_COUNT));
        requireOk(res, MESSAGE_COUNT);
        JsonNode count = res.attribute(MESSAGE_COUNT);
        return count == null ? 0L : count.asLong();
    }

    public QueueDepth depth(ArtemisNode client, String queueMbean) {
        ManagementResponse res =
                client.single(ManagementRequest.read(queueMbean, MESSAGE_COUNT, "DeliveringCount", "ScheduledCount"));
        requireOk(res, MESSAGE_COUNT);
        return new QueueDepth(
                res.value().path(MESSAGE_COUNT).asLong(),
                res.value().path("DeliveringCount").asLong(),
                res.value().path("ScheduledCount").asLong());
    }

    // ---- by explicit ids (one exec per id; the broker has no id-batch op) ----

    /** Ids sent to the broker in one batch request, and so charged one permit (ADR-0076). */
    static final int BY_ID_BATCH = 50;

    private static final String MESSAGE_COUNT = "MessageCount";

    public BulkResult moveByIds(ArtemisNode client, String queueMbean, List<Long> ids, String targetQueue) {
        return eachId(
                client,
                ids,
                "moveMessage",
                id -> ManagementRequest.exec(queueMbean, "moveMessage(long,java.lang.String)", id, targetQueue));
    }

    public BulkResult retryByIds(ArtemisNode client, String queueMbean, List<Long> ids) {
        return countTrue(client, queueMbean, "retryMessage(long)", ids);
    }

    public BulkResult deleteByIds(ArtemisNode client, String queueMbean, List<Long> ids) {
        return countTrue(client, queueMbean, "removeMessage(long)", ids);
    }

    public BulkResult expireByIds(ArtemisNode client, String queueMbean, List<Long> ids) {
        return countTrue(client, queueMbean, "expireMessage(long)", ids);
    }

    // ---- by selector (one exec; the broker returns the affected count) ----

    public long moveByFilter(ArtemisNode client, String queueMbean, String filter, String targetQueue) {
        return filterExec(client, queueMbean, "moveMessages(java.lang.String,java.lang.String)", filter, targetQueue);
    }

    public long deleteByFilter(ArtemisNode client, String queueMbean, String filter) {
        return filterExec(client, queueMbean, "removeMessages(java.lang.String)", filter);
    }

    public long expireByFilter(ArtemisNode client, String queueMbean, String filter) {
        return filterExec(client, queueMbean, "expireMessages(java.lang.String)", filter);
    }

    /**
     * {@code moveMessages(flushLimit, filter, otherQueue, rejectDuplicates, messageCount)}: moves at
     * most {@code messageCount} matching messages, so one call stays short however deep the queue is.
     * Returns how many the broker moved; fewer than {@code messageCount} means none are left to match.
     */
    public long moveMessages(
            ArtemisNode client,
            String queueMbean,
            int flushLimit,
            String filter,
            String targetQueue,
            boolean rejectDuplicates,
            int messageCount) {
        return filterExec(
                client,
                queueMbean,
                "moveMessages(int,java.lang.String,java.lang.String,boolean,int)",
                flushLimit,
                filter == null ? "" : filter,
                targetQueue,
                rejectDuplicates,
                messageCount);
    }

    /** {@code copyMessage(id, otherQueue)}: false when the broker found no message with this id. */
    public boolean copyMessage(ArtemisNode client, String queueMbean, long messageId, String targetQueue) {
        ManagementResponse res = client.single(
                ManagementRequest.exec(queueMbean, "copyMessage(long,java.lang.String)", messageId, targetQueue));
        requireOk(res, "copyMessage");
        return res.value() != null && res.value().asBoolean();
    }

    /** Retry every message on the queue — Artemis has no by-filter retry. Returns the count retried. */
    public long retryAll(ArtemisNode client, String queueMbean) {
        ManagementResponse res = client.single(ManagementRequest.exec(queueMbean, "retryMessages()"));
        requireOk(res, "retryMessages");
        return res.value() == null ? 0L : res.value().asLong();
    }

    /** {@code removeAllMessages()} — the purge. Returns the count removed. */
    public long purge(ArtemisNode client, String queueMbean) {
        ManagementResponse res = client.single(ManagementRequest.exec(queueMbean, "removeAllMessages()"));
        requireOk(res, "removeAllMessages");
        return res.value() == null ? 0L : res.value().asLong();
    }

    // ---- helpers -------------------------------------------------------

    private BulkResult countTrue(ArtemisNode client, String queueMbean, String op, List<Long> ids) {
        return eachId(client, ids, op, id -> ManagementRequest.exec(queueMbean, op, id));
    }

    /**
     * Acts on the ids in batch requests of {@value #BY_ID_BATCH} and counts the ones the broker
     * says it acted on. A batch runs every operation in it even after one fails, so every result
     * of that batch is counted before the run stops; later batches are not sent. A failure that
     * acted on nothing at all is thrown as it is, because nothing changed.
     */
    private BulkResult eachId(
            ArtemisNode client, List<Long> ids, String op, Function<Long, ManagementRequest> request) {
        long affected = 0;
        for (int from = 0; from < ids.size(); from += BY_ID_BATCH) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + BY_ID_BATCH));
            List<Long> later = ids.subList(from + chunk.size(), ids.size());
            List<ManagementResponse> responses;
            try {
                responses = client.batch(chunk.stream().map(request).toList());
            } catch (RuntimeException e) {
                if (affected == 0) {
                    throw e;
                }
                return new BulkResult(affected, List.copyOf(ids.subList(from, ids.size())), e.getMessage());
            }
            ChunkOutcome outcome = countChunk(chunk, responses, op);
            affected += outcome.affected();
            if (outcome.firstError() != null) {
                if (affected == 0) {
                    throw outcome.firstError();
                }
                List<Long> notDone = new ArrayList<>(outcome.refused());
                notDone.addAll(later);
                return new BulkResult(
                        affected, List.copyOf(notDone), outcome.firstError().getMessage());
            }
        }
        return new BulkResult(affected, List.of(), null);
    }

    /** What one batch did: how many ids the broker acted on, the ones it refused, and the first refusal. */
    private record ChunkOutcome(long affected, List<Long> refused, RuntimeException firstError) {}

    private static ChunkOutcome countChunk(List<Long> chunk, List<ManagementResponse> responses, String op) {
        long affected = 0;
        List<Long> refused = new ArrayList<>();
        RuntimeException firstError = null;
        for (int i = 0; i < chunk.size(); i++) {
            try {
                if (i >= responses.size()) {
                    throw new BrokerConnectionException(
                            BrokerConnectionException.Kind.BAD_RESPONSE,
                            "The broker returned fewer results than operations.");
                }
                ManagementResponse res = responses.get(i);
                requireOk(res, op);
                if (res.value() != null && res.value().asBoolean()) {
                    affected++;
                }
            } catch (RuntimeException e) {
                refused.add(chunk.get(i));
                if (firstError == null) {
                    firstError = e;
                }
            }
        }
        return new ChunkOutcome(affected, refused, firstError);
    }

    private long filterExec(ArtemisNode client, String queueMbean, String op, Object... args) {
        ManagementResponse res = client.single(ManagementRequest.exec(queueMbean, op, args));
        rejectBadFilter(res);
        requireOk(res, op);
        return res.value() == null ? 0L : res.value().asLong();
    }

    private static void rejectBadFilter(ManagementResponse res) {
        if (!res.ok() && res.error() != null) {
            String e = res.error();
            if (e.contains("AMQ229020") || e.toLowerCase().contains("invalid filter")) {
                throw new IllegalArgumentException("Invalid message filter.");
            }
        }
    }

    private static void requireOk(ManagementResponse res, String op) {
        if (!res.ok()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE,
                    op + " failed: " + (res.error() != null ? res.error() : "status " + res.status()));
        }
    }
}
