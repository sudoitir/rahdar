package io.github.sudoitir.rahdar.platform.governance.internal.persistence;

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
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/** Maps {@code governance_rule} (platform/governance 0001). Enum columns are held as their names. */
@Entity
@Table(name = "governance_rule")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class GovernanceRuleEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "destination_pattern")
    private String destinationPattern;

    @Column(name = "target", nullable = false)
    private String target;

    @Column(name = "selector", nullable = false)
    private String selector;

    @Column(name = "data_class", nullable = false)
    private String dataClass;

    @Column(name = "action")
    private String action;

    @Column(name = "builtin", nullable = false, updatable = false)
    private boolean builtin;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "is_exception", nullable = false, updatable = false)
    private boolean exception;

    public GovernanceRuleEntity(
            String destinationPattern,
            String target,
            String selector,
            String dataClass,
            String action,
            boolean exception) {
        this.destinationPattern = destinationPattern;
        this.target = target;
        this.selector = selector;
        this.dataClass = dataClass;
        this.action = action;
        this.exception = exception;
        this.enabled = true;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
