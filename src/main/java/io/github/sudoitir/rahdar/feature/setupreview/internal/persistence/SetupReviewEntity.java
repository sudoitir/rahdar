package io.github.sudoitir.rahdar.feature.setupreview.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Maps {@code artemis_setup_review} (changeset feature-setupreview 0001): a cluster's last review run. */
@Entity
@Table(name = "artemis_setup_review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SetupReviewEntity {

    @Id
    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "nodes_total", nullable = false)
    private int nodesTotal;

    @Column(name = "nodes_reviewed", nullable = false)
    private int nodesReviewed;

    /** Per node: id, name, reviewed, reason. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nodes", nullable = false)
    private String nodes = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "not_assessed", nullable = false)
    private String notAssessed = "[]";

    @Column(name = "cluster_evaluated", nullable = false)
    private boolean clusterEvaluated;

    public SetupReviewEntity(UUID clusterId) {
        this.clusterId = clusterId;
    }

    public void recordReview(
            Instant reviewedAt,
            long durationMs,
            int nodesTotal,
            int nodesReviewed,
            String nodes,
            String notAssessed,
            boolean clusterEvaluated) {
        this.reviewedAt = reviewedAt;
        this.durationMs = durationMs;
        this.nodesTotal = nodesTotal;
        this.nodesReviewed = nodesReviewed;
        this.nodes = nodes;
        this.notAssessed = notAssessed;
        this.clusterEvaluated = clusterEvaluated;
    }
}
