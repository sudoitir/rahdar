package io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * One apply command — dry runs included, flagged — with the plan that was shown and
 * the outcome it produced (changeset 024), so what an operator confirmed and what
 * happened are readable side by side.
 */
@Entity
@Table(name = "artemis_config_apply")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BrokerConfigApplyEntity {

    public enum Outcome {
        DRY_RUN,
        APPLIED,
        HALTED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private long revisionId;

    @Column(name = "audit_event_id")
    private Long auditEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan", nullable = false, updatable = false)
    private String plan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outcome_detail")
    private String outcomeDetail;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Column(name = "summary")
    private String summary;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @Column(name = "canary_node_id")
    private UUID canaryNodeId;

    @Column(name = "dry_run", nullable = false, updatable = false)
    private boolean dryRun;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    public BrokerConfigApplyEntity(
            UUID clusterId, long revisionId, String plan, String actor, UUID canaryNodeId, boolean dryRun) {
        this.clusterId = clusterId;
        this.revisionId = revisionId;
        this.plan = plan;
        this.actor = actor;
        this.canaryNodeId = canaryNodeId;
        this.dryRun = dryRun;
        this.outcome = dryRun ? Outcome.DRY_RUN.name() : Outcome.FAILED.name();
    }

    public void attachAudit(Long auditEventId) {
        this.auditEventId = auditEventId;
    }

    public void finish(Outcome outcome, String summary, String outcomeDetail) {
        this.outcome = outcome.name();
        this.summary = summary;
        this.outcomeDetail = outcomeDetail;
        this.finishedAt = Instant.now();
    }

    public Outcome outcome() {
        return Outcome.valueOf(outcome);
    }
}
