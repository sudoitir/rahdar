package io.github.sudoitir.rahdar.feature.brokerconfig.internal.persistence;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The latest drift evaluation of one node against the cluster's current revision
 * (changeset 024, ADR-0067 D8). Rewritten every interval; the findings are the
 * planner's, serialised, so the drift screen and the alert condition read one truth.
 */
@Entity
@Table(name = "artemis_config_node_state")
@IdClass(BrokerConfigNodeStateEntity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class BrokerConfigNodeStateEntity {

    public enum State {
        IN_SYNC,
        DRIFTED,
        NOT_EVALUATED,
        UNREACHABLE
    }

    /**
     * Why a node agrees with the declaration (changeset 025). The three are not equal
     * evidence, and a screen that shows only the state cannot tell them apart: an
     * adoption closes every finding on a broker nobody wrote to.
     */
    public enum Basis {
        /** Rahdar wrote the values and its read-back matched. {@code basisRef} is the apply id. */
        VERIFIED_APPLY,
        /** The declaration was taken from what the broker already ran. {@code basisRef} is the revision. */
        ADOPTED,
        /** An evaluation found them equal; Rahdar wrote nothing. The honest answer for CONFIG_MANAGED. */
        OBSERVED_MATCH
    }

    @Id
    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Id
    @Column(name = "node_id", nullable = false, updatable = false)
    private UUID nodeId;

    @Column(name = "state", nullable = false)
    private String state = State.NOT_EVALUATED.name();

    /** Why it is in that state when it could not be evaluated; null otherwise. */
    @Column(name = "detail")
    private String detail;

    /** The revision number this evaluation compared against; null when never evaluated. */
    @Column(name = "verified_revision")
    private Integer verifiedRevision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings", nullable = false)
    private String findings = "[]";

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt = Instant.now();

    /** Why the node is in this state; null for everything but {@code IN_SYNC}. */
    @Column(name = "basis")
    private String basis;

    /** The apply id or revision number the basis points at; null for {@code OBSERVED_MATCH}. */
    @Column(name = "basis_ref")
    private Long basisRef;

    public BrokerConfigNodeStateEntity(UUID clusterId, UUID nodeId) {
        this.clusterId = clusterId;
        this.nodeId = nodeId;
    }

    public void recordState(
            State state, String detail, Integer verifiedRevision, String findingsJson, Basis basis, Long basisRef) {
        this.state = state.name();
        this.detail = detail;
        this.verifiedRevision = verifiedRevision;
        this.findings = findingsJson;
        this.evaluatedAt = Instant.now();
        this.basis = basis == null ? null : basis.name();
        this.basisRef = basisRef;
    }

    public State state() {
        return State.valueOf(state);
    }

    public Basis basis() {
        return basis == null ? null : Basis.valueOf(basis);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID clusterId;
        private UUID nodeId;
    }
}
