package io.github.sudoitir.rahdar.feature.transfer.internal.persistence;

import io.github.sudoitir.rahdar.feature.transfer.TransferMode;
import io.github.sudoitir.rahdar.feature.transfer.TransferState;
import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One transfer run (ADR-0097, transfer design D5): its source and target, its frozen selection, and
 * the counts it has reached. The messages themselves are on the brokers.
 */
@Entity
@Table(name = "transfer_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TransferRunEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, updatable = false)
    private TransferMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private TransferState state = TransferState.PREVIEWED;

    @Column(name = "source_cluster_id", nullable = false, updatable = false)
    private UUID sourceClusterId;

    @Column(name = "source_node_id", nullable = false, updatable = false)
    private UUID sourceNodeId;

    @Column(name = "source_node_name", nullable = false, updatable = false)
    private String sourceNodeName;

    @Column(name = "source_native_node_id", nullable = false, updatable = false)
    private String sourceNativeNodeId;

    @Column(name = "source_subscription", nullable = false, updatable = false)
    private String sourceQueue;

    @Column(name = "source_destination", updatable = false)
    private String sourceAddress;

    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_native_attributes", nullable = false, updatable = false)
    private Map<String, String> sourceNativeAttributes;

    @Column(name = "target_cluster_id", nullable = false, updatable = false)
    private UUID targetClusterId;

    @Column(name = "target_node_id", nullable = false, updatable = false)
    private UUID targetNodeId;

    @Column(name = "target_node_name", nullable = false, updatable = false)
    private String targetNodeName;

    @Column(name = "target_native_node_id", nullable = false, updatable = false)
    private String targetNativeNodeId;

    @Column(name = "target_subscription", nullable = false, updatable = false)
    private String targetQueue;

    @Column(name = "target_destination", updatable = false)
    private String targetAddress;

    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_native_attributes", nullable = false, updatable = false)
    private Map<String, String> targetNativeAttributes;

    /** Source and target are the same broker node: the broker moves the messages itself. */
    @Column(name = "same_node", nullable = false, updatable = false)
    private boolean sameNode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selection", nullable = false, updatable = false)
    private String selection;

    /** The preview's acceptance findings, as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings", nullable = false, updatable = false)
    private String findings;

    /** The moment the selection is frozen at: a filter or whole-queue run takes nothing produced later. */
    @Column(name = "t0", nullable = false, updatable = false)
    private Instant t0;

    @Column(name = "plan_hash", nullable = false, updatable = false)
    private String planHash;

    @Column(name = "estimate", updatable = false)
    private Long estimate;

    @Column(name = "estimate_bytes", updatable = false)
    private Long estimateBytes;

    @Column(name = "staged", nullable = false)
    private long staged;

    @Column(name = "delivered", nullable = false)
    private long delivered;

    @Column(name = "not_transferred", nullable = false)
    private long notTransferred;

    @Column(name = "expired", nullable = false)
    private long expired;

    @Column(name = "returned", nullable = false)
    private long returned;

    @Column(name = "bytes", nullable = false)
    private long bytes;

    /** How many of an id selection's ids have been taken from the source so far. */
    @Column(name = "id_cursor", nullable = false)
    private int idCursor;

    /** Who previewed it, then who last started it. */
    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "operator_id")
    private UUID operatorId;

    /** The replica executing the current segment: recovery leaves the run alone while it is alive (ADR-0152). */
    @Column(name = "replica_id")
    private UUID replicaId;

    @Column(name = "override_cap", nullable = false)
    private boolean overrideCap;

    /** The current segment's audit event on the source cluster, and its linked event on the target. */
    @Column(name = "audit_event_id")
    private Long auditEventId;

    @Column(name = "target_audit_event_id")
    private Long targetAuditEventId;

    @Column(name = "last_error")
    private String lastError;

    /** The {@code broker.xml} that would have prevented {@link #lastError}, where there is one. */
    @Column(name = "error_snippet")
    private String errorSnippet;

    /**
     * When the operator asked to stop the segment in progress. Set only by
     * {@link TransferRunRepository#requestStop} and cleared by {@link TransferRunRepository#transition}, never
     * by the runner's own saves, which would otherwise overwrite it with what they loaded.
     */
    @Column(name = "stop_requested_at", updatable = false)
    private Instant stopRequestedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Builder
    public TransferRunEntity(
            TransferMode mode,
            UUID sourceClusterId,
            UUID sourceNodeId,
            String sourceNodeName,
            String sourceNativeNodeId,
            String sourceQueue,
            String sourceAddress,
            String sourceRoutingType,
            UUID targetClusterId,
            UUID targetNodeId,
            String targetNodeName,
            String targetNativeNodeId,
            String targetQueue,
            String targetAddress,
            String targetRoutingType,
            boolean sameNode,
            String selection,
            String findings,
            Instant t0,
            String planHash,
            Long estimate,
            Long estimateBytes,
            String username,
            Instant createdAt,
            Instant expiresAt) {
        this.mode = mode;
        this.sourceClusterId = sourceClusterId;
        this.sourceNodeId = sourceNodeId;
        this.sourceNodeName = sourceNodeName;
        this.sourceNativeNodeId = sourceNativeNodeId;
        this.sourceQueue = sourceQueue;
        this.sourceAddress = sourceAddress;
        this.sourceNativeAttributes = nativeAttributes(sourceRoutingType);
        this.targetClusterId = targetClusterId;
        this.targetNodeId = targetNodeId;
        this.targetNodeName = targetNodeName;
        this.targetNativeNodeId = targetNativeNodeId;
        this.targetQueue = targetQueue;
        this.targetAddress = targetAddress;
        this.targetNativeAttributes = nativeAttributes(targetRoutingType);
        this.sameNode = sameNode;
        this.selection = selection;
        this.findings = findings;
        this.t0 = t0;
        this.planHash = planHash;
        this.estimate = estimate;
        this.estimateBytes = estimateBytes;
        this.username = username;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** A segment begins: the first execute, a resume, or a return. */
    public void begin(TransferState state, String username, UUID operatorId, UUID replicaId, Instant now) {
        this.state = state;
        this.replicaId = replicaId;
        this.username = username;
        this.operatorId = operatorId;
        this.lastError = null;
        this.errorSnippet = null;
        this.finishedAt = null;
        this.updatedAt = now;
        if (this.startedAt == null) {
            this.startedAt = now;
        }
    }

    public void overrideCap(boolean overrideCap) {
        this.overrideCap = overrideCap;
    }

    public void attachAudit(Long auditEventId, Long targetAuditEventId) {
        this.auditEventId = auditEventId;
        this.targetAuditEventId = targetAuditEventId;
    }

    /** The target is near full; {@code reason} says how full, until the run continues. */
    public void waiting(String reason, Instant now) {
        this.state = TransferState.WAITING_FOR_CAPACITY;
        this.lastError = reason;
        this.updatedAt = now;
    }

    public void running(Instant now) {
        this.state = TransferState.RUNNING;
        this.lastError = null;
        this.updatedAt = now;
    }

    public void staged(long moved, int idsTaken) {
        this.staged += moved;
        this.idCursor += idsTaken;
    }

    public void delivered(long messages, long bytes, Instant now) {
        this.delivered += messages;
        this.bytes += bytes;
        this.updatedAt = now;
    }

    public void notTransferred(long messages) {
        this.notTransferred += messages;
    }

    public void expired(long messages) {
        this.expired = messages;
    }

    public void returned(long messages) {
        this.returned += messages;
    }

    public void finish(TransferState state, String error, String snippet, Instant now) {
        this.state = state;
        this.lastError = error;
        this.errorSnippet = snippet;
        this.updatedAt = now;
        this.finishedAt = now;
    }

    /** The source queue's Artemis routing type; {@code null} on another system. */
    public String getSourceRoutingType() {
        return sourceNativeAttributes.get(QueueRow.ROUTING_TYPE);
    }

    /** The target queue's Artemis routing type; {@code null} on another system. */
    public String getTargetRoutingType() {
        return targetNativeAttributes.get(QueueRow.ROUTING_TYPE);
    }

    private static Map<String, String> nativeAttributes(String routingType) {
        return routingType == null ? Map.of() : Map.of(QueueRow.ROUTING_TYPE, routingType);
    }
}
