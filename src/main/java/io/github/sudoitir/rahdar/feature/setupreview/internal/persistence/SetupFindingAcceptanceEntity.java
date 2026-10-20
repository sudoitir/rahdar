package io.github.sudoitir.rahdar.feature.setupreview.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Maps {@code artemis_setup_finding_acceptance}: an operator's audited decision that a finding is a known risk. */
@Entity
@Table(name = "artemis_setup_finding_acceptance")
@IdClass(FindingKey.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SetupFindingAcceptanceEntity {

    @Id
    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Id
    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Id
    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "accepted_by", nullable = false)
    private String acceptedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null: until revoked. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    public SetupFindingAcceptanceEntity(
            UUID clusterId,
            String code,
            String subject,
            String reason,
            String acceptedBy,
            Instant createdAt,
            Instant expiresAt) {
        this.clusterId = clusterId;
        this.code = code;
        this.subject = subject;
        this.reason = reason;
        this.acceptedBy = acceptedBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean activeAt(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }
}
