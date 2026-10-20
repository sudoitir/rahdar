package io.github.sudoitir.rahdar.platform.scrape.internal.persistence;

import io.github.sudoitir.rahdar.platform.broker.QueueRow;
import io.github.sudoitir.rahdar.platform.scrape.QueueSnapshotUpsert;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Read-only JPA view of {@code subscription_snapshot} (changeset 005). Writes go through
 * {@link QueueSnapshotUpsert} (JDBC {@code INSERT … ON CONFLICT}, ADR-0016) —
 * this is a disposable cache with no stable row identity, so the estate-table
 * dirty-checking rule (ADR-0011) does not apply. Never {@code save()} this entity.
 */
@Entity
@Table(name = "subscription_snapshot")
@IdClass(QueueSnapshotEntity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QueueSnapshotEntity {

    @Id
    @Column(name = "node_id", nullable = false, updatable = false)
    private UUID nodeId;

    @Id
    @Column(name = "subscription", nullable = false, updatable = false)
    private String queueName;

    @Column(name = "cluster_id", nullable = false)
    private UUID clusterId;

    @Column(name = "destination")
    private String address;

    @Column(name = "kind", nullable = false)
    private String kind;

    /** What the system says about the subscription beyond the standard attributes, by its native names. */
    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "native_attributes", nullable = false)
    private Map<String, String> nativeAttributes;

    @Column(name = "durable", nullable = false)
    private boolean durable;

    /** The broker's own pause flag. A paused queue is correctly slow, and expected. */
    @Column(name = "paused", nullable = false)
    private boolean paused;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    @Column(name = "backlog", nullable = false)
    private long messageCount;

    @Column(name = "consumer_count", nullable = false)
    private long consumerCount;

    @Column(name = "in_flight", nullable = false)
    private long deliveringCount;

    @Column(name = "scheduled", nullable = false)
    private long scheduledCount;

    @Column(name = "enqueued", nullable = false)
    private long messagesAdded;

    @Column(name = "acknowledged", nullable = false)
    private long messagesAcked;

    @Column(name = "expired", nullable = false)
    private long messagesExpired;

    /** An Artemis queue's routing type, {@code ANYCAST} or {@code MULTICAST}; {@code null} on another system. */
    public String getRoutingType() {
        return nativeAttributes.get(QueueRow.ROUTING_TYPE);
    }

    /** Composite PK {@code (node_id, subscription)}. */
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID nodeId;
        private String queueName;

        public Key() {}

        public Key(UUID nodeId, String queueName) {
            this.nodeId = nodeId;
            this.queueName = queueName;
        }
    }
}
