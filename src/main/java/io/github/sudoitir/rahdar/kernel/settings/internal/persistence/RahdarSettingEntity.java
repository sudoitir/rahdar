package io.github.sudoitir.rahdar.kernel.settings.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code rahdar_setting} (changeset 009). One row per operator-tunable key;
 * the value is a JSON string (scalar or object) so a key can gain structure
 * without a migration. Low churn — plain JPA, unlike the disposable
 * {@code subscription_snapshot} cache.
 */
@Entity
@Table(name = "rahdar_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RahdarSettingEntity {

    @Id
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "value", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RahdarSettingEntity(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public void setValue(String value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }
}
