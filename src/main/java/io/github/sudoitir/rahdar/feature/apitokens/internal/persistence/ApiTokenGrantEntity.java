package io.github.sudoitir.rahdar.feature.apitokens.internal.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Maps {@code api_token_grant}: the scope and permission a token was narrowed to, and when the grant is
 * limited to names matching a pattern, the kind of resource and the pattern. Both are empty for a grant on
 * the whole scope.
 */
@Entity
@Table(name = "api_token_grant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiTokenGrantEntity {

    @EmbeddedId
    private Key id;

    public ApiTokenGrantEntity(
            UUID tokenId, String action, String scopeType, UUID scopeId, String resourceType, String resourcePattern) {
        this.id = new Key(tokenId, action, scopeType, scopeId, resourceType, resourcePattern);
    }

    public UUID getTokenId() {
        return id.tokenId;
    }

    public String getAction() {
        return id.action;
    }

    public String getScopeType() {
        return id.scopeType;
    }

    public UUID getScopeId() {
        return id.scopeId;
    }

    public String getResourceType() {
        return id.resourceType;
    }

    public String getResourcePattern() {
        return id.resourcePattern;
    }

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {
        @Column(name = "token_id")
        private UUID tokenId;

        @Column(name = "action")
        private String action;

        @Column(name = "scope_type")
        private String scopeType;

        @Column(name = "scope_id")
        private UUID scopeId;

        @Column(name = "resource_type")
        private String resourceType;

        @Column(name = "resource_pattern")
        private String resourcePattern;

        Key(UUID tokenId, String action, String scopeType, UUID scopeId, String resourceType, String resourcePattern) {
            this.tokenId = tokenId;
            this.action = action;
            this.scopeType = scopeType;
            this.scopeId = scopeId;
            this.resourceType = resourceType;
            this.resourcePattern = resourcePattern;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(tokenId, key.tokenId)
                    && Objects.equals(action, key.action)
                    && Objects.equals(scopeType, key.scopeType)
                    && Objects.equals(scopeId, key.scopeId)
                    && Objects.equals(resourceType, key.resourceType)
                    && Objects.equals(resourcePattern, key.resourcePattern);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tokenId, action, scopeType, scopeId, resourceType, resourcePattern);
        }
    }
}
