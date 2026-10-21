package io.github.sudoitir.rahdar.feature.rr;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One normalised fact about request-reply traffic, from either channel: the
 * sampler's browse (correlation identity) or the notification stream
 * (lifecycle). {@link RrCorrelator} does not know or care which channel a fact
 * came from (design.md D2).
 */
public sealed interface Observation {

    UUID clusterId();

    UUID nodeId();

    Instant at();

    /**
     * A request message observed on a traced request address.
     *
     * <p>{@code at} is when Rahdar saw it; {@code enqueuedAt} is when the message
     * says it was produced, already normalised onto Rahdar's clock (ADR-0053).
     * They are different facts: the first is quantised to the sample interval, the
     * second is the real thing but comes from a clock Rahdar does not own. Only the
     * browse path can supply the second — a notification carries no enqueue time —
     * so it is null there, and null means "unknown", never "zero".
     */
    record RequestSeen(
            UUID clusterId,
            UUID nodeId,
            Instant at,
            String requestDestination,
            String messageId,
            String correlationId,
            String replyTo,
            long expiration,
            String bodyPreview,
            Map<String, Object> properties,
            Instant enqueuedAt)
            implements Observation {

        /** The notification path, which observes the message without reading it. */
        public RequestSeen(
                UUID clusterId,
                UUID nodeId,
                Instant at,
                String requestDestination,
                String messageId,
                String correlationId,
                String replyTo,
                long expiration,
                String bodyPreview,
                Map<String, Object> properties) {
            this(
                    clusterId,
                    nodeId,
                    at,
                    requestDestination,
                    messageId,
                    correlationId,
                    replyTo,
                    expiration,
                    bodyPreview,
                    properties,
                    null);
        }
    }

    /** A reply message observed on a traced reply address or a temp reply queue. */
    record ReplySeen(
            UUID clusterId,
            UUID nodeId,
            Instant at,
            String replyDestination,
            String messageId,
            String correlationId,
            String bodyPreview,
            Map<String, Object> properties,
            Instant enqueuedAt)
            implements Observation {

        /** The notification path, which observes the delivery without reading the message. */
        public ReplySeen(
                UUID clusterId,
                UUID nodeId,
                Instant at,
                String replyDestination,
                String messageId,
                String correlationId,
                String bodyPreview,
                Map<String, Object> properties) {
            this(clusterId, nodeId, at, replyDestination, messageId, correlationId, bodyPreview, properties, null);
        }
    }

    /** A consumer attached to a traced request address. */
    record ResponderUp(UUID clusterId, UUID nodeId, Instant at, String requestDestination, String consumerName)
            implements Observation {}

    /** A consumer on a traced request address closed; {@code remainingConsumers} is the count left. */
    record ResponderDown(
            UUID clusterId,
            UUID nodeId,
            Instant at,
            String requestDestination,
            String consumerName,
            int remainingConsumers)
            implements Observation {}

    /** A temporary reply queue's binding was removed (deleted, or its owning connection closed). */
    record TempQueueUnbound(UUID clusterId, UUID nodeId, Instant at, String queueName) implements Observation {}

    /** A message on a traced address expired without being delivered. */
    record MessageExpired(UUID clusterId, UUID nodeId, Instant at, String address, String messageId)
            implements Observation {}
}
