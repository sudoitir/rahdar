package io.github.sudoitir.rahdar.feature.rr.internal.persistence;

import io.github.sudoitir.rahdar.feature.rr.RrState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * Maps {@code rr_flow} (changesets 007, 011): one reconstructed request-reply
 * flow, in exactly one of the six states the CHECK constraint enumerates
 * (request-reply-tracing spec).
 */
@Entity
@Table(name = "rr_flow")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RrFlowEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "node_id")
    private UUID nodeId;

    /** Null only for a flow created directly in {@code ORPHANED_REPLY} — the request side was never observed. */
    @Column(name = "request_destination", updatable = false)
    private String requestAddress;

    @Column(name = "reply_destination")
    private String replyDestination;

    @Column(name = "reply_kind", nullable = false, updatable = false)
    private String replyKind;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "request_message_id", updatable = false)
    private String requestMessageId;

    @Column(name = "reply_message_id")
    private String replyMessageId;

    @Column(name = "responder_consumer")
    private String responderConsumer;

    @Column(name = "requester_session", updatable = false)
    private String requesterSession;

    @Column(name = "responder_session")
    private String responderSession;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "observed_at")
    private Instant observedAt;

    @Column(name = "latency_ms")
    private Long latencyMs;

    /**
     * How {@link #latencyMs} was arrived at (ADR-0053). Never null: a flow measured
     * before this existed was measured the observed way, and a third spelling of
     * "unknown" would force every reader to handle it.
     */
    @Column(name = "latency_source", nullable = false)
    private String latencySource = "OBSERVED";

    /** The error bar on an {@code OBSERVED} latency — one sample interval. Null when exact. */
    @Column(name = "latency_bound_ms")
    private Integer latencyBoundMs;

    /** When the request message says it was produced, on Rahdar's clock. Null on the notification path. */
    @Column(name = "request_enqueued_at")
    private Instant requestEnqueuedAt;

    @Column(name = "reply_enqueued_at")
    private Instant replyEnqueuedAt;

    /**
     * How far into the future the request claimed to have been produced. Only
     * forward skew is evidence of a wrong clock: a message that says it was
     * produced before Rahdar saw it was simply sitting on the queue, which is the
     * normal case and is never recorded here.
     */
    @Column(name = "request_skew_ms")
    private Long requestSkewMs;

    @Column(name = "reply_skew_ms")
    private Long replySkewMs;

    /** A request seen and now awaiting its reply, by the deadline it must be answered by. */
    public record Awaiting(
            UUID clusterId,
            UUID nodeId,
            String requestAddress,
            String replyDestination,
            String replyKind,
            String correlationId,
            String requestMessageId,
            Instant requestedAt,
            Instant deadlineAt) {}

    public RrFlowEntity(Awaiting awaiting) {
        this.clusterId = awaiting.clusterId();
        this.nodeId = awaiting.nodeId();
        this.requestAddress = awaiting.requestAddress();
        this.replyDestination = awaiting.replyDestination();
        this.replyKind = awaiting.replyKind();
        this.state = RrState.AWAITING_REPLY.name();
        this.correlationId = awaiting.correlationId();
        this.requestMessageId = awaiting.requestMessageId();
        this.requestedAt = awaiting.requestedAt();
        this.deadlineAt = awaiting.deadlineAt();
        this.observedAt = awaiting.requestedAt();
    }

    /** A reply observed with no matching awaiting-reply flow (request-reply-tracing spec, ORPHANED_REPLY). */
    public static RrFlowEntity orphanedReply(
            UUID clusterId,
            UUID nodeId,
            String replyDestination,
            String replyKind,
            String correlationId,
            Instant repliedAt) {
        RrFlowEntity e = new RrFlowEntity();
        e.clusterId = clusterId;
        e.nodeId = nodeId;
        e.requestAddress = null;
        e.replyDestination = replyDestination;
        e.replyKind = replyKind;
        e.state = RrState.ORPHANED_REPLY.name();
        e.correlationId = correlationId;
        e.requestedAt = repliedAt;
        e.repliedAt = repliedAt;
        e.observedAt = repliedAt;
        return e;
    }
}
