package io.github.sudoitir.rahdar.feature.plugins.internal.persistence;

import io.github.sudoitir.rahdar.feature.plugins.messaging.RegistrationState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps {@code plugin_message_registration_node}: what the last pass found on one node, or for the whole
 * cluster when {@code nodeId} is null (a partitioned log is read once for the cluster).
 */
@Entity
@Table(name = "plugin_message_registration_node")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegistrationNodeEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "registration_id", nullable = false, updatable = false)
    private UUID registrationId;

    @Column(name = "node_id", updatable = false)
    private UUID nodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RegistrationState state;

    private String detail;

    @Column(name = "dropped_copies")
    private Long droppedCopies;

    @Column(name = "releases_counted", nullable = false)
    private boolean releasesCounted;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RegistrationNodeEntity(UUID registrationId, UUID nodeId) {
        this.registrationId = registrationId;
        this.nodeId = nodeId;
    }
}
