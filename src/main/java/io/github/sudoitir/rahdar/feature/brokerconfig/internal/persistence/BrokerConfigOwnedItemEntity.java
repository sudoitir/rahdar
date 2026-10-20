package io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A setting or divert Rahdar itself applied to a cluster (changeset 024, ADR-0067
 * D6) — the record that lets an apply remove what it created and nothing else.
 */
@Entity
@Table(name = "artemis_config_owned_item")
@IdClass(BrokerConfigOwnedItemEntity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrokerConfigOwnedItemEntity {

    @Id
    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    /** {@code ADDRESS_SETTING}, {@code SECURITY_SETTING} or {@code DIVERT}. */
    @Id
    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    /** The match pattern or the divert name. */
    @Id
    @Column(name = "item_key", nullable = false, updatable = false)
    private String itemKey;

    @Column(name = "revision_id", nullable = false)
    private long revisionId;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt = Instant.now();

    public BrokerConfigOwnedItemEntity(UUID clusterId, String kind, String itemKey, long revisionId) {
        this.clusterId = clusterId;
        this.kind = kind;
        this.itemKey = itemKey;
        this.revisionId = revisionId;
    }

    public void touch(long revisionId) {
        this.revisionId = revisionId;
        this.appliedAt = Instant.now();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID clusterId;
        private String kind;
        private String itemKey;
    }
}
