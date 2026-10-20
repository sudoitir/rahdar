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
 * One saved revision of a cluster's declaration (changeset 024, ADR-0067 D12).
 * Append-only: a row is never updated, so the history an operator reads is the
 * history that happened.
 */
@Entity
@Table(name = "artemis_config_revision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BrokerConfigRevisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    /** 1-based, per cluster. */
    @Column(name = "revision", nullable = false, updatable = false)
    private int revision;

    /** The whole declaration as JSON, keyed by the catalogue's JSON names. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document", nullable = false, updatable = false)
    private String document;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    @Column(name = "note")
    private String note;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public BrokerConfigRevisionEntity(
            UUID clusterId, int revision, String document, String source, String note, String createdBy) {
        this.clusterId = clusterId;
        this.revision = revision;
        this.document = document;
        this.source = source;
        this.note = note;
        this.createdBy = createdBy;
    }
}
