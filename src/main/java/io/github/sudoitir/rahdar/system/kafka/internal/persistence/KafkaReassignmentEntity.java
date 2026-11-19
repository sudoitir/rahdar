package io.github.sudoitir.rahdar.system.kafka.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** The throttle baseline of one in-progress replica move (ADR-0230). */
@Entity
@Table(name = "kafka_reassignment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class KafkaReassignmentEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "operation_id", nullable = false)
    private long operationId;

    @Column(name = "partition", nullable = false)
    private int partition;

    @Column(name = "topic", nullable = false)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_throttle", nullable = false)
    private Map<String, String> previousThrottle;

    @Column(name = "added_keys", nullable = false)
    private String addedKeys;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    public KafkaReassignmentEntity(
            UUID clusterId,
            String topic,
            int partition,
            long operationId,
            Map<String, String> previousThrottle,
            String addedKeys,
            Instant createdAt) {
        this.clusterId = clusterId;
        this.topic = topic;
        this.partition = partition;
        this.createdAt = createdAt;
        replace(operationId, previousThrottle, addedKeys);
    }

    /** Records the throttle baseline this move puts back when it finishes. */
    public void replace(long operationId, Map<String, String> previousThrottle, String addedKeys) {
        this.operationId = operationId;
        this.previousThrottle = new LinkedHashMap<>(previousThrottle);
        this.addedKeys = addedKeys == null ? "" : addedKeys;
    }
}
