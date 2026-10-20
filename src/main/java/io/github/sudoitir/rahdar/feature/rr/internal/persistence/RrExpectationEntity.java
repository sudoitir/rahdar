package io.github.sudoitir.rahdar.feature.rr.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code rr_expectation} (changesets 007, 011, 017): which request addresses
 * an operator has declared for request-reply tracing, and how (proposal.md,
 * request-reply-tracing spec).
 *
 * <p>{@code replyAddresses} is a set of literal addresses or globs, never null. An
 * empty list is meaningful and is not the same as unset: it says replies arrive on
 * a temporary queue named by the request's {@code replyTo} (design.md, D3).
 */
@Entity
@Table(name = "rr_expectation")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RrExpectationEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "request_destination", nullable = false)
    private String requestAddress;

    /**
     * Literal addresses or {@code *} globs. Mapped as the Postgres {@code TEXT[]}
     * declared by changeset 017 rather than a child table: the set is small, always
     * read whole with its parent and never queried on its own, so a join would only
     * add a query to the correlator's hot path (design.md, D2).
     */
    @Column(name = "reply_destinations", nullable = false, columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> replyAddresses = new ArrayList<>();

    @Column(name = "correlation_property")
    private String correlationProperty;

    @Column(name = "deadline_ms")
    private Integer deadlineMs;

    @Column(name = "sample_per_min", nullable = false)
    private int samplePerMin = 10;

    @Column(name = "capture_payload", nullable = false)
    private boolean capturePayload;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Never stores null — the column is {@code NOT NULL} and an empty list is the meaningful "temp queue" value. */
    public void setReplyAddresses(List<String> replyAddresses) {
        this.replyAddresses = replyAddresses == null ? new ArrayList<>() : new ArrayList<>(replyAddresses);
    }

    public RrExpectationEntity(
            UUID clusterId,
            String requestAddress,
            List<String> replyAddresses,
            String correlationProperty,
            Integer deadlineMs,
            int samplePerMin,
            boolean capturePayload) {
        this.clusterId = clusterId;
        this.requestAddress = requestAddress;
        setReplyAddresses(replyAddresses);
        this.correlationProperty = correlationProperty;
        this.deadlineMs = deadlineMs;
        this.samplePerMin = samplePerMin;
        this.capturePayload = capturePayload;
        this.enabled = true;
    }
}
