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
 * Maps {@code team_share} (changeset kernel-security 0010): a pattern inside what the owner team owns,
 * shared with the target team at a team role. {@code kind} is {@code SUBSCRIPTION}, {@code DESTINATION} or
 * {@code ANY}.
 */
@Entity
@Table(name = "team_share")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TeamShareEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "owner_team_id", nullable = false, updatable = false)
    private UUID ownerTeamId;

    @Column(name = "target_team_id", nullable = false, updatable = false)
    private UUID targetTeamId;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "pattern", nullable = false, updatable = false)
    private String pattern;

    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    public TeamShareEntity(
            UUID ownerTeamId, UUID targetTeamId, UUID clusterId, String kind, String pattern, UUID roleId) {
        this.ownerTeamId = ownerTeamId;
        this.targetTeamId = targetTeamId;
        this.clusterId = clusterId;
        this.kind = kind;
        this.pattern = pattern;
        this.roleId = roleId;
    }
}
