package io.github.sudoitir.rahdar.feature.apitokens;

import io.github.sudoitir.rahdar.feature.apitokens.internal.persistence.ApiTokenEntity;
import io.github.sudoitir.rahdar.feature.apitokens.internal.persistence.ApiTokenRepository;
import io.github.sudoitir.rahdar.kernel.gate.DisplayRow;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.ExecutionMode;
import io.github.sudoitir.rahdar.kernel.gate.GatedOperation;
import io.github.sudoitir.rahdar.kernel.gate.OperationScope;
import io.github.sudoitir.rahdar.kernel.gate.Trait;
import io.github.sudoitir.rahdar.kernel.security.UserAccounts;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gated operations of the API tokens module (ADR-0179). Creating a token is completed by the requester, who
 * submits the same request again once it is approved, because only that response may carry the secret.
 */
@Configuration(proxyBeanMethods = false)
class TokenOperations {

    static final String CREATE = "token.create";
    static final String REVOKE_ANY = "token.revoke-any";

    @Bean
    GatedOperation<CreateToken> createTokenOperation() {
        return new GatedOperation<>() {
            @Override
            public String type() {
                return CREATE;
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<CreateToken> paramsType() {
                return CreateToken.class;
            }

            @Override
            public Set<Trait> traits(CreateToken params) {
                return Set.of(Trait.ACCESS_CONTROL);
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.BY_REQUESTER;
            }

            @Override
            public OperationScope scope(CreateToken params) {
                return OperationScope.GLOBAL;
            }

            @Override
            public String summary(CreateToken params) {
                return "Create API token " + params.name();
            }

            @Override
            public List<DisplayRow> display(CreateToken params) {
                List<DisplayRow> rows = new ArrayList<>();
                rows.add(DisplayRow.of("Name", params.name()));
                rows.add(DisplayRow.of("Expires", params.expiresAt().toString()));
                for (CreateToken.Grant grant : params.grants()) {
                    rows.add(DisplayRow.of("Permission", describe(grant)));
                }
                if (!params.mcpTools().isEmpty()) {
                    rows.add(DisplayRow.of("MCP tools", String.join(", ", params.mcpTools())));
                }
                return rows;
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of();
            }

            @Override
            public Effect estimate(CreateToken params) {
                return new Effect(1, "tokens", "token:" + params.userId() + ":" + params.name(), null);
            }

            @Override
            public void replay(CreateToken params) {
                throw new UnsupportedOperationException("A new token is completed by its requester");
            }
        };
    }

    @Bean
    GatedOperation<RevokeAnyToken> revokeAnyTokenOperation(
            ApiTokenService tokens, ApiTokenRepository repository, UserAccounts accounts) {
        return new GatedOperation<>() {
            @Override
            public String type() {
                return REVOKE_ANY;
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public Class<RevokeAnyToken> paramsType() {
                return RevokeAnyToken.class;
            }

            @Override
            public Set<Trait> traits(RevokeAnyToken params) {
                return Set.of(Trait.ACCESS_CONTROL);
            }

            @Override
            public ExecutionMode mode() {
                return ExecutionMode.ON_APPROVAL;
            }

            @Override
            public OperationScope scope(RevokeAnyToken params) {
                return OperationScope.GLOBAL;
            }

            @Override
            public String summary(RevokeAnyToken params) {
                ApiTokenEntity token = token(params);
                return "Revoke API token " + token.getName() + " of " + owner(token);
            }

            @Override
            public List<DisplayRow> display(RevokeAnyToken params) {
                ApiTokenEntity token = token(params);
                return List.of(DisplayRow.of("Token", token.getName()), DisplayRow.of("Owner", owner(token)));
            }

            @Override
            public Set<String> redactedPaths() {
                return Set.of();
            }

            @Override
            public Effect estimate(RevokeAnyToken params) {
                return new Effect(1, "tokens", "token:" + params.tokenId(), null);
            }

            @Override
            public void replay(RevokeAnyToken params) {
                tokens.revokeAny(params.tokenId());
            }

            private ApiTokenEntity token(RevokeAnyToken params) {
                return repository
                        .findById(params.tokenId())
                        .orElseThrow(() -> new IllegalStateException("The token " + params.tokenId() + " is gone"));
            }

            private String owner(ApiTokenEntity token) {
                return accounts.byId(token.getUserId())
                        .map(UserAccounts.Account::username)
                        .orElse(token.getUserId().toString());
            }
        };
    }

    private static String describe(CreateToken.Grant grant) {
        String scope = "GLOBAL".equals(grant.scopeType())
                ? "everywhere"
                : grant.scopeType().toLowerCase() + " " + grant.scopeId();
        String limit = grant.resourceType() == null
                ? ""
                : ", only " + grant.resourceType().toLowerCase() + "s matching " + grant.resourcePattern();
        return grant.action() + " (" + scope + limit + ")";
    }
}
