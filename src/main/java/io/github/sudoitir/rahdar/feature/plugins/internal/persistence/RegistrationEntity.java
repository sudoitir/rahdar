package io.github.sudoitir.rahdar.feature.plugins.internal.persistence;

import io.github.sudoitir.rahdar.feature.plugins.messaging.RegistrationMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps {@code plugin_message_registration} (feature/plugins changesets 0001, 0002). */
@Entity
@Table(name = "plugin_message_registration")
@Getter
@Setter
@NoArgsConstructor
public class RegistrationEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "plugin_id", nullable = false, updatable = false)
    private String pluginId;

    @Column(name = "reg_key", nullable = false, updatable = false)
    private String key;

    @Column(name = "cluster_id", nullable = false)
    private UUID clusterId;

    @Column(name = "source_kind", nullable = false)
    private String sourceKind;

    @Column(name = "source_path", nullable = false)
    private String sourcePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RegistrationMode mode;

    @Column(nullable = false)
    private int concurrency;

    @Column(name = "acting_user_id", nullable = false)
    private UUID actingUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
