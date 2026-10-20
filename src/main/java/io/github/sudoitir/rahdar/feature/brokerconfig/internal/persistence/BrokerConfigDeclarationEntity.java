package io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A cluster's declaration header (changeset 024): which revision is current, how the
 * cluster's configuration is applied (ADR-0067 D2), and whether undeclared
 * resources are reported. The document itself lives in the revision.
 */
@Entity
@Table(name = "artemis_config_declaration")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrokerConfigDeclarationEntity {

    public enum ApplyMode {
        RAHDAR_MANAGED,
        CONFIG_MANAGED
    }

    @Id
    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "current_revision_id", nullable = false)
    private long currentRevisionId;

    @Column(name = "apply_mode", nullable = false)
    private String applyMode = ApplyMode.RAHDAR_MANAGED.name();

    @Column(name = "report_undeclared", nullable = false)
    private boolean reportUndeclared;

    /** A JSON array of match patterns. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "undeclared_exclusions", nullable = false)
    private String undeclaredExclusions = "[]";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public BrokerConfigDeclarationEntity(UUID clusterId, long currentRevisionId) {
        this.clusterId = clusterId;
        this.currentRevisionId = currentRevisionId;
    }

    public void pointAt(long revisionId) {
        this.currentRevisionId = revisionId;
        this.updatedAt = Instant.now();
    }

    public void configure(ApplyMode mode, boolean reportUndeclared, String undeclaredExclusionsJson) {
        this.applyMode = mode.name();
        this.reportUndeclared = reportUndeclared;
        this.undeclaredExclusions = undeclaredExclusionsJson;
        this.updatedAt = Instant.now();
    }

    public ApplyMode mode() {
        return ApplyMode.valueOf(applyMode);
    }
}
