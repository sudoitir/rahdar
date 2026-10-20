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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code artemis_setup_finding}: the latest rendering of one finding. {@code lastSeenAt} older than
 * the cluster's last review means the review could not re-check it — its node did not answer — and
 * it is kept rather than resolved on no evidence.
 */
@Entity
@Table(name = "artemis_setup_finding")
@IdClass(FindingKey.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SetupFindingEntity {

    @Id
    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Id
    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Id
    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "category", nullable = false)
    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "finding", nullable = false)
    private String finding;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    public SetupFindingEntity(UUID clusterId, String code, String subject, Instant now) {
        this.clusterId = clusterId;
        this.code = code;
        this.subject = subject;
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    public void seen(String severity, String category, String findingJson, Instant now) {
        this.severity = severity;
        this.category = category;
        this.finding = findingJson;
        this.lastSeenAt = now;
    }
}
