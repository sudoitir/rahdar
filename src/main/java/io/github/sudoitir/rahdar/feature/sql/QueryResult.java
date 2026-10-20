package io.github.sudoitir.rahdar.feature.sql;

import io.github.sudoitir.rahdar.feature.sql.QueryAst.Source;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a query produced, and everything it could not see. The second half is the
 * part that matters: a result that does not say what it missed is read as complete,
 * and an operator mid-incident acts on that.
 */
public record QueryResult(
        List<Row> rows, List<NodeOutcome> nodes, List<Bound> boundsReached, List<QueryPlan.Notice> notices) {

    /**
     * One message. Identity is {@code (node, queue, messageId)} — a message id is
     * issued by, and meaningful only on, the node holding it (cf. ADR-0057), so the
     * id alone is never a cluster-wide handle.
     *
     * @param source which backend produced this row, so the UI never has to guess
     * @param observedAt when the index saw it; null for a live broker row
     * @param lastSeenAt when the index last still saw it on its queue; null for a live row
     * @param bodyTruncated the management channel cut the body, so a body predicate
     *     evaluated against it may be a false negative
     */
    public record Row(
            UUID nodeId,
            String nodeName,
            String queueName,
            String address,
            long messageId,
            int messageType,
            boolean durable,
            int priority,
            long timestamp,
            long expiration,
            long size,
            String jmsType,
            String correlationId,
            String groupId,
            String userId,
            String replyTo,
            String body,
            boolean bodyTruncated,
            /** The body is binary, carried as base64 (or withheld); a text body is false whatever the message type. */
            boolean bodyBase64,
            /** gzip or deflate when the text body was unwrapped from one; null otherwise. */
            String bodyCompression,
            Map<String, Object> properties,
            Source source,
            Instant observedAt,
            Instant lastSeenAt,
            /**
             * SAMPLED or CAPTURED for an index row, null for a live broker row
             * (ADR-0062). The two are different claims: a sampled row says a poll saw
             * this message, a captured row says the address routed it.
             */
            String origin,
            /**
             * The message's id on its <em>source</em> queue, for a captured row. Null
             * when the broker did not copy {@code _AMQ_ORIG_MESSAGE_ID}, which leaves
             * the row perfectly usable and only makes verifying it against the live
             * broker impossible — stated, never hidden.
             */
            Long sourceMessageId) {}

    /**
     * What one node contributed. A node that failed is reported here rather than
     * being left out of the result — an absent node is not an absence of messages.
     */
    public record NodeOutcome(
            UUID nodeId, String nodeName, String queueName, Status status, long examined, long matched, String detail) {

        public enum Status {
            ANSWERED,
            FAILED,
            /** The query stopped before this target was read, because a bound was reached. */
            NOT_READ
        }
    }

    /** A limit the query ran into. Reaching one makes the result partial, and it says so. */
    public record Bound(Kind kind, long value) {
        public enum Kind {
            SCAN_CAP,
            ROW_LIMIT,
            TIMEOUT,
            TARGET_CAP
        }
    }

    public boolean isPartial() {
        return !boundsReached.isEmpty() || nodes.stream().anyMatch(n -> n.status() != NodeOutcome.Status.ANSWERED);
    }
}
