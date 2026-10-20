package io.github.sudoitir.rahdar.feature.bulk.internal.persistence;

import io.github.sudoitir.rahdar.feature.bulk.BulkItemStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** One queue in a bulk run: its figures at preview, and what acting on it did. */
@Entity
@Table(name = "bulk_run_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BulkRunItemEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    @Column(name = "subscription", nullable = false, updatable = false)
    private String queueName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BulkItemStatus status;

    @Column(name = "error")
    private String error;

    /** The per-node figures and any warning, as the preview stated them. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "estimate", nullable = false, updatable = false)
    private String estimate;

    /** The per-node outcome once acted on, in the single-queue command's shape. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outcome")
    private String outcome;

    @Column(name = "affected")
    private Long affected;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public BulkRunItemEntity(UUID runId, int ordinal, String queueName, String refusal, String estimate) {
        this.runId = runId;
        this.ordinal = ordinal;
        this.queueName = queueName;
        this.status = refusal == null ? BulkItemStatus.PENDING : BulkItemStatus.REFUSED;
        this.error = refusal;
        this.estimate = estimate;
    }

    public void begin(Instant now) {
        this.status = BulkItemStatus.RUNNING;
        this.startedAt = now;
    }

    public void finish(BulkItemStatus status, String error, Long affected, String outcome, Instant now) {
        this.status = status;
        this.error = error;
        this.affected = affected;
        this.outcome = outcome;
        this.finishedAt = now;
    }
}
