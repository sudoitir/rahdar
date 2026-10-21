package io.github.sudoitir.rahdar.platform.governance.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps {@code classification_finding}. Rows are inserted by the flush job's batched upsert, never here. */
@Entity
@Table(name = "classification_finding")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ClassificationFindingEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "hit_count", nullable = false)
    private long hitCount;

    @Column(name = "destination", nullable = false, updatable = false)
    private String destination;

    @Column(name = "location", nullable = false, updatable = false)
    private String location;

    @Column(name = "field_path", nullable = false, updatable = false)
    private String fieldPath;

    @Column(name = "data_class", nullable = false, updatable = false)
    private String dataClass;

    @Column(name = "status", nullable = false)
    private String status;
}
