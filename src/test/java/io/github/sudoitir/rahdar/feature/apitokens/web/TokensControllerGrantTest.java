package io.github.sudoitir.rahdar.feature.apitokens.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.feature.apitokens.web.TokenViews.TokenGrantRequest;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.TokenGrant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A grant asked for when minting a key: whole-scope, or limited to the names a pattern matches. */
class TokensControllerGrantTest {

    private final UUID cluster = UUID.randomUUID();

    @Test
    void aGrantWithNoKindOrPatternCoversItsWholeScope() {
        TokenGrant grant =
                TokensController.toGrant(new TokenGrantRequest("subscription:read", "GLOBAL", null, null, null));

        assertThat(grant.limited()).isFalse();
        assertThat(grant.scopeId()).isEqualTo(ScopeIds.GLOBAL);
    }

    @Test
    void aGrantMayBeLimitedToTheNamesAPatternMatches() {
        TokenGrant grant = TokensController.toGrant(
                new TokenGrantRequest("message:read", "CLUSTER", cluster, "subscription", "orders.#"));

        assertThat(grant.scopeType()).isEqualTo(Grant.ScopeType.CLUSTER);
        assertThat(grant.kind()).isEqualTo(ResourceType.SUBSCRIPTION);
        assertThat(grant.pattern().matches("orders.in")).isTrue();
        assertThat(grant.pattern().matches("orders2.in")).isFalse();
    }

    @Test
    void aKindWithoutAPatternIsRefused() {
        TokenGrantRequest request = new TokenGrantRequest("message:read", "CLUSTER", cluster, "SUBSCRIPTION", " ");
        assertThatThrownBy(() -> TokensController.toGrant(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPatternWithoutAKindIsRefused() {
        TokenGrantRequest request = new TokenGrantRequest("message:read", "CLUSTER", cluster, null, "orders.#");
        assertThatThrownBy(() -> TokensController.toGrant(request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPatternThatDoesNotParseIsRefused() {
        TokenGrantRequest request =
                new TokenGrantRequest("message:read", "CLUSTER", cluster, "SUBSCRIPTION", "orders..in");
        assertThatThrownBy(() -> TokensController.toGrant(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty word");
    }
}
