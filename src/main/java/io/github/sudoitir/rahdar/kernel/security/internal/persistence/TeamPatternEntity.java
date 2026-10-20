package io.github.sudoitir.rahdar.kernel.security.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/**
 * Maps {@code team_pattern} (changeset kernel-security 0010): a queue or address name pattern a team
 * owns on one cluster. {@code kind} is {@code SUBSCRIPTION}, {@code DESTINATION} or {@code ANY}.
 */
@Entity
@Table(name = "team_pattern")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TeamPatternEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "pattern", nullable = false, updatable = false)
    private String pattern;

    public TeamPatternEntity(UUID teamId, UUID clusterId, String kind, String pattern) {
        this.teamId = teamId;
        this.clusterId = clusterId;
        this.kind = kind;
        this.pattern = pattern;
    }
}
