package io.github.sudoitir.rahdar.feature.apitokens.web;

import io.github.sudoitir.rahdar.feature.apitokens.ApiTokenService;
import io.github.sudoitir.rahdar.feature.apitokens.ApiTokensSettings;
import io.github.sudoitir.rahdar.feature.apitokens.web.TokenViews.CreateTokenRequest;
import io.github.sudoitir.rahdar.feature.apitokens.web.TokenViews.CreatedTokenView;
import io.github.sudoitir.rahdar.feature.apitokens.web.TokenViews.TokenGrantRequest;
import io.github.sudoitir.rahdar.feature.apitokens.web.TokenViews.TokenPolicyView;
import io.github.sudoitir.rahdar.feature.apitokens.web.TokenViews.TokenView;
import io.github.sudoitir.rahdar.feature.apitokens.web.TokenViews.UsageView;
import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.gate.HeldResponse;
import io.github.sudoitir.rahdar.kernel.plugin.ResourceType;
import io.github.sudoitir.rahdar.kernel.security.Grant;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.ResourcePattern;
import io.github.sudoitir.rahdar.kernel.security.ScopeIds;
import io.github.sudoitir.rahdar.kernel.security.SessionAuthentication;
import io.github.sudoitir.rahdar.kernel.security.SessionFacts;
import io.github.sudoitir.rahdar.kernel.security.SessionRequiredException;
import io.github.sudoitir.rahdar.kernel.security.TokenGrant;
import io.github.sudoitir.rahdar.kernel.security.TokenPrincipal;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own API tokens (api-tokens spec). Minting and rotation happen only here; a token
 * is narrowable only to a subset of its own owner's grants. Administrators see and revoke every
 * user's tokens through {@link AdminTokensController}.
 *
 * <p>Only a signed-in session manages keys. A key that could mint or rotate keys would escape its
 * own narrowed grants and tool allow-list, and a leaked one could take over its owner's other keys.
 * A minted key also records whether its session had verified a second factor (ADR-0143), which only a
 * session can say.
 */
@RestController
@RequestMapping("/tokens")
@RequiredArgsConstructor
public class TokensController {

    private final ApiTokenService tokens;
    private final TokenViewAssembler views;
    private final SettingsService settings;
    private final SessionAuthentication sessions;

    @GetMapping
    public PagedView<TokenView> list(
            @AuthenticationPrincipal RahdarPrincipal principal,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        requireSession(principal);
        return ResourceQuery.ofPage(page, size).paginate(views.views(tokens.listFor(principal.userId())), null);
    }

    @GetMapping("/policy")
    public TokenPolicyView policy() {
        return new TokenPolicyView(
                tokens.maxLifetime().toString(),
                Instant.now().plus(tokens.maxLifetime()),
                settings.duration(ApiTokensSettings.ROTATION_OVERLAP).toString());
    }

    @HeldResponse
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedTokenView create(
            @AuthenticationPrincipal RahdarPrincipal principal,
            @Valid @RequestBody CreateTokenRequest request,
            HttpServletRequest req) {
        SessionFacts facts = requireSession(principal, req);
        List<TokenGrant> requested =
                request.grants().stream().map(TokensController::toGrant).toList();
        var minted = tokens.mint(
                principal.userId(),
                request.name(),
                request.expiresAt(),
                requested,
                request.mcpTools() == null ? List.of() : request.mcpTools(),
                facts.mfaVerifiedAt() != null);
        return new CreatedTokenView(views.view(minted.entity()), minted.plaintext());
    }

    @PostMapping("/{tokenId}/rotate")
    public CreatedTokenView rotate(@AuthenticationPrincipal RahdarPrincipal principal, @PathVariable UUID tokenId) {
        requireSession(principal);
        var rotated = tokens.rotate(principal.userId(), tokenId);
        return new CreatedTokenView(views.view(rotated.entity()), rotated.plaintext());
    }

    @GetMapping("/{tokenId}/usage")
    public UsageView usage(
            @AuthenticationPrincipal RahdarPrincipal principal,
            @PathVariable UUID tokenId,
            @RequestParam(defaultValue = "7") int days) {
        requireSession(principal);
        return TokenViewAssembler.usage(tokens.usage(principal.userId(), tokenId, TokenViewAssembler.period(days)));
    }

    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal RahdarPrincipal principal, @PathVariable UUID tokenId) {
        requireSession(principal);
        tokens.revoke(principal.userId(), tokenId);
    }

    /** A grant as asked for: a kind and a pattern come together, and the pattern must parse. */
    static TokenGrant toGrant(TokenGrantRequest g) {
        Grant.ScopeType scope = Grant.ScopeType.valueOf(g.scopeType());
        UUID scopeId = g.scopeId() != null ? g.scopeId() : ScopeIds.GLOBAL;
        boolean hasKind = g.resourceType() != null && !g.resourceType().isBlank();
        boolean hasPattern = g.resourcePattern() != null && !g.resourcePattern().isBlank();
        if (hasKind != hasPattern) {
            throw new IllegalArgumentException("Give both the kind and the pattern a grant is limited to, or neither.");
        }
        if (!hasKind) {
            return TokenGrant.of(scope, scopeId, g.action());
        }
        return new TokenGrant(
                scope,
                scopeId,
                g.action(),
                ResourceType.valueOf(g.resourceType().toUpperCase(Locale.ROOT)),
                ResourcePattern.parse(g.resourcePattern()));
    }

    private static void requireSession(RahdarPrincipal principal) {
        if (principal instanceof TokenPrincipal) {
            throw new SessionRequiredException(
                    "Sign in to Rahdar in your browser to manage API keys; a key cannot manage keys.");
        }
    }

    /** The session's facts: what a minted key records about how its owner signed in. */
    private SessionFacts requireSession(RahdarPrincipal principal, HttpServletRequest req) {
        requireSession(principal);
        return sessions.facts(req)
                .orElseThrow(() -> new SessionRequiredException(
                        "Sign in to Rahdar in your browser to create a key; a key cannot create keys."));
    }
}
