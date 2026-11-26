package io.github.sudoitir.rahdar.system.kafka.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Maps {@code kafka_schema_binding}: which serde decodes a topic's key or value. */
@Entity
@Table(name = "kafka_schema_binding")
@IdClass(KafkaSchemaBindingEntity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KafkaSchemaBindingEntity {

    @Id
    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Id
    @Column(name = "topic", nullable = false, updatable = false)
    private String topic;

    @Id
    @Column(name = "side", nullable = false, updatable = false)
    private String side;

    @Column(name = "serde", nullable = false)
    private String serde;

    public KafkaSchemaBindingEntity(UUID clusterId, String topic, String side, String serde) {
        this.clusterId = clusterId;
        this.topic = topic;
        this.side = side;
        this.serde = serde;
    }

    public void changeSerde(String serde) {
        this.serde = serde;
    }

    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID clusterId;
        private String topic;
        private String side;

        public Key() {}

        public Key(UUID clusterId, String topic, String side) {
            this.clusterId = clusterId;
            this.topic = topic;
            this.side = side;
        }
    }
}
