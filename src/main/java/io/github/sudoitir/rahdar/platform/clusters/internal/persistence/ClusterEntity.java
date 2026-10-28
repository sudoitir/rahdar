package io.github.sudoitir.rahdar.platform.clusters.internal.persistence;

import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/** Maps the {@code cluster} table (changeset 002). */
@Entity
@Table(name = "cluster")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ClusterEntity implements RegisteredCluster {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "environment_id")
    private UUID environmentId;

    @Column(name = "read_only", nullable = false)
    private boolean readOnly;

    /** {@code scheme://{host}:port/path}: how a node's management URL is built from its connector host (ADR-0175). */
    @Column(name = "management_url_pattern")
    private String managementUrlPattern;

    /** The broker system the cluster runs, as the broker SDK names it. */
    @Column(name = "system", nullable = false, updatable = false)
    private String system;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Evidence of an attempted management write on this cluster's connection
     * (ADR-0049 D5). Null until one has been attempted — which is what makes the
     * capability report UNKNOWN rather than claiming an authority nothing has
     * observed. {@code AVAILABLE} once a write has succeeded, {@code UNAVAILABLE}
     * once one has been refused for an authorization reason.
     */
    @Column(name = "management_write_status")
    private String managementWriteStatus;

    @Column(name = "management_write_reason")
    private String managementWriteReason;

    @Column(name = "management_write_observed_at")
    private Instant managementWriteObservedAt;

    public ClusterEntity(String system, String name, String description, UUID environmentId) {
        this.system = system;
        this.name = name;
        this.description = description;
        this.environmentId = environmentId;
    }

    /** What an operator sets about the cluster itself; the connection's other parts live in their own rows. */
    public void edit(String name, String description, String managementUrlPattern) {
        this.name = name;
        this.description = description;
        this.managementUrlPattern = managementUrlPattern;
        touch();
    }

    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void touch() {
        updatedAt = Instant.now();
    }

    public void setEnvironmentId(UUID environmentId) {
        this.environmentId = environmentId;
        touch();
    }

    /**
     * Record that a management write succeeded. Always overwrites a previous
     * verdict: a connection whose permissions were fixed must be able to recover
     * from {@code UNAVAILABLE} without being re-registered.
     */
    public void recordManagementWriteSucceeded(String reason) {
        this.managementWriteStatus = "AVAILABLE";
        this.managementWriteReason = reason;
        this.managementWriteObservedAt = Instant.now();
    }

    /**
     * Record that a management write was refused for an authorization reason. Only
     * an authorization refusal may call this — conflating "the broker said no to
     * this argument" with "this connection cannot write" would let one bad request
     * permanently disable a button.
     */
    public void recordManagementWriteRefused(String reason) {
        this.managementWriteStatus = "UNAVAILABLE";
        this.managementWriteReason = reason;
        this.managementWriteObservedAt = Instant.now();
    }
}
