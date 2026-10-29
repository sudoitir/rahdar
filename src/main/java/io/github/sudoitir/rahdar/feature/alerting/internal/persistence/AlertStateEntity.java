package io.github.sudoitir.rahdar.feature.alerting.internal.persistence;

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
 * Maps {@code alert_state} (changeset 006): the current OK/PENDING/FIRING
 * position of one {@code (rule, subject)} pair — the {@code for_seconds} debounce
 * clock lives in {@code since} so it survives a restart (ADR-0035, design.md
 * decision 4). While firing, {@code since} is when the condition first read false, null while it holds. History of past firings is {@link AlertFiringEntity}, not this row.
 */
@Entity
@Table(name = "alert_state")
@IdClass(AlertStateEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertStateEntity {

    @Id
    @Column(name = "rule_id", updatable = false)
    private UUID ruleId;

    @Id
    @Column(name = "subject_key", updatable = false)
    private String subjectKey;

    @Column(name = "state", nullable = false)
    private String state = "OK";

    @Column(name = "since")
    private Instant since;

    @Column(name = "last_notified_at")
    private Instant lastNotifiedAt;

    @Column(name = "last_value")
    private Double lastValue;

    public AlertStateEntity(UUID ruleId, String subjectKey) {
        this.ruleId = ruleId;
        this.subjectKey = subjectKey;
        this.state = "OK";
    }

    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID ruleId;
        private String subjectKey;

        public Key() {}

        public Key(UUID ruleId, String subjectKey) {
            this.ruleId = ruleId;
            this.subjectKey = subjectKey;
        }
    }
}
