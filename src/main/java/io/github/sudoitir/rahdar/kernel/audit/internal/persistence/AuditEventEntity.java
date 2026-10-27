package io.github.sudoitir.rahdar.kernel.audit.internal.persistence;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code audit_event} table (changeset 004). One row per mutating
 * action: inserted {@code PENDING} before the broker call, updated to
 * {@code SUCCESS} / {@code FAILURE} after — in the command's own transaction
 * (non-negotiable #3).
 */
@Entity
@Table(name = "audit_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AuditEventEntity implements AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "ts", nullable = false, updatable = false)
    private Instant ts;

    @Column(name = "affected_count")
    private Long affectedCount;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "target_type", updatable = false)
    private String targetType;

    @Column(name = "target_name", updatable = false)
    private String targetName;

    /** The cluster's name when the event was written, kept after the cluster is removed (ADR-0072). */
    @Column(name = "cluster_name", updatable = false)
    private String clusterName;

    @Column(name = "username", updatable = false)
    private String username;

    @Column(name = "request_id", updatable = false)
    private String requestId;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "source_ip", updatable = false)
    private String sourceIp;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "outcome", nullable = false)
    private String outcome = "PENDING";

    @Column(name = "error")
    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", updatable = false)
    private String params;

    /**
     * The per-node outcome of a cluster-wide fan-out command (ADR-0049 D2/D4), as
     * a JSON array. Null for a single-node action, which has nothing to spread
     * across nodes.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outcome_detail")
    private String outcomeDetail;

    @Column(name = "cluster_id", updatable = false)
    private UUID clusterId;

    @Column(name = "node_id", updatable = false)
    private UUID nodeId;

    @Column(name = "dry_run", nullable = false, updatable = false)
    private boolean dryRun;

    /** The event this one is a part of, such as a bulk run's (ADR-0093); null for a standalone action. */
    @Column(name = "parent_id", updatable = false)
    private Long parentId;

    /** What an action is aimed at: the target, and the cluster and node it sits on. */
    public record Target(String type, String name, UUID clusterId, String clusterName, UUID nodeId) {}

    public AuditEventEntity(String action, Actor actor, Target target, String params, boolean dryRun) {
        this.action = action;
        this.targetType = target.type();
        this.targetName = target.name();
        this.username = actor.displayName();
        this.requestId = actor.requestId();
        this.sourceIp = actor.sourceIp();
        this.userId = actor.userId();
        this.clusterId = target.clusterId();
        this.clusterName = target.clusterName();
        this.nodeId = target.nodeId();
        this.params = params;
        this.dryRun = dryRun;
    }

    public void attachParent(Long parentId) {
        this.parentId = parentId;
    }

    @PrePersist
    void onInsert() {
        ts = Instant.now();
    }

    public void markSuccess(long affectedCount) {
        this.outcome = "SUCCESS";
        this.error = null;
        this.affectedCount = affectedCount;
    }

    /** The request was refused for lack of a permission, {@code count} times so far. */
    public void markRefused(String reason, long count) {
        this.outcome = "REFUSED";
        this.error = reason;
        this.affectedCount = count;
    }

    /** The outcome is unknown: the broker's confirmation never came back, so it may or may not have happened. */
    public void markInDoubt(String reason) {
        this.outcome = "IN_DOUBT";
        this.error = reason;
    }

    public void markFailure(String error) {
        this.outcome = "FAILURE";
        this.error = error;
    }

    /** A failure after part of the action was done: the count is what was affected before it. */
    public void markFailure(String error, long affectedCount) {
        markFailure(error);
        this.affectedCount = affectedCount;
    }

    /**
     * Attach the per-node detail of a fan-out. Set alongside the outcome, so a
     * partially applied command is reconstructable from this row alone (D4).
     */
    public void attachOutcomeDetail(String outcomeDetail) {
        this.outcomeDetail = outcomeDetail;
    }
}
