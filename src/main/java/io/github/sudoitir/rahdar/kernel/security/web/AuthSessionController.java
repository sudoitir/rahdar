package io.github.sudoitir.rahdar.kernel.security.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.rahdar.kernel.core.PagedView;
import io.github.sudoitir.rahdar.kernel.core.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.core.SecretRedactor;
import io.github.sudoitir.rahdar.kernel.plugin.IdentityProviderListing;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.RahdarPrincipal;
import io.github.sudoitir.rahdar.kernel.security.SessionAuthentication;
import io.github.sudoitir.rahdar.kernel.security.SessionFacts;
import io.github.sudoitir.rahdar.kernel.security.SessionRequiredException;
import io.github.sudoitir.rahdar.kernel.security.TokenPrincipal;
import io.github.sudoitir.rahdar.kernel.security.UserAccounts;
import io.github.sudoitir.rahdar.kernel.security.internal.LoginService;
import io.github.sudoitir.rahdar.kernel.security.internal.SessionService;
import io.github.sudoitir.rahdar.kernel.security.web.SessionViews.AccountSessionView;
import io.github.sudoitir.rahdar.kernel.security.web.SessionViews.EndedSessionsView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sign-in, sign-out, the current identity and the providers to sign in with
 * (identity-and-sessions spec). {@code /login}, {@code /second-factor} and {@code /providers} are
 * the only endpoints reachable with no session — see {@code SecurityConfig}'s allow-list.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthSessionController {

    private final LoginService logins;
    private final IdentityProviderListing providers;
    private final UserAccounts accounts;
    private final SessionAuthentication sessions;
    private final PermissionResolver perm;
    private final SessionService sessionService;
    private final JsonMapper json;

    public record LoginRequest(
            @Schema(nullable = true, description = "The credential provider to sign in with. Omit for local.")
            String provider,

            @NotBlank String username,
            @NotBlank String password) {}

    public record GrantView(
            @Schema(requiredMode = REQUIRED) String scopeType,
            @Schema(nullable = true) UUID scopeId,
            @Schema(requiredMode = REQUIRED) List<String> permissions) {}

    public record MeView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String username,
            @Schema(requiredMode = REQUIRED) boolean mustChangePassword,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The provider the account signs in with: local, or the id of another one. "
                            + "Only a local account's password is changed in Rahdar.")
            String providerId,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "The user's role requires two-step verification and they have none: "
                            + "the session may only enrol one until they do.")
            boolean secondFactorEnrolmentRequired,

            @Schema(requiredMode = REQUIRED) List<GrantView> grants,
            @Schema(requiredMode = REQUIRED) ReauthenticationView reauthentication) {}

    /**
     * How this user confirms it is them for an action that needs a recent sign-in (ADR-0103).
     *
     * @param authenticatedAt when this session last signed in or stepped up
     * @param startPath for {@code REDIRECT}: where the browser begins it; append {@code returnTo}
     */
    public record ReauthenticationView(
            @Schema(
                    requiredMode = REQUIRED,
                    description = "PASSWORD to re-enter it here; REDIRECT to sign in with the provider again.",
                    allowableValues = {"PASSWORD", "REDIRECT"})
            String method,

            @Schema(nullable = true) String startPath,
            @Schema(nullable = true) Instant authenticatedAt,
            @Schema(requiredMode = REQUIRED) long windowSeconds) {}

    public record ReauthenticateRequest(@NotBlank String password) {

        /** The password is never printed. */
        @Override
        public String toString() {
            return "ReauthenticateRequest[password=" + SecretRedactor.MASK + "]";
        }
    }

    public enum AuthStatus {
        AUTHENTICATED,
        SECOND_FACTOR_REQUIRED
    }

    /**
     * Where a sign-in or step-up stands. {@code AUTHENTICATED} carries the signed-in user;
     * {@code SECOND_FACTOR_REQUIRED} means the password was right and the user must now give a
     * second factor to {@code POST /auth/second-factor}, with one of {@code methods}.
     */
    public record AuthResult(
            @Schema(requiredMode = REQUIRED) AuthStatus status,

            @Schema(nullable = true, description = "The signed-in user; set when the status is AUTHENTICATED.")
            MeView me,

            @Schema(
                    nullable = true,
                    description = "How the user can prove a second factor; set when the status is "
                            + "SECOND_FACTOR_REQUIRED. TOTP is a code from an authenticator app, WEBAUTHN a passkey, "
                            + "RECOVERY_CODE one of the single-use codes.")
            List<SessionFacts.Method> methods,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "For a sign-in that needs a second factor: for how many days the user may trust "
                            + "this browser after giving it, so the next sign-in asks for the password only. 0 when "
                            + "trusted devices are off, and always 0 in any other case. Send trustDevice with the "
                            + "second factor to trust it.")
            int trustDeviceDays) {}

    /** Exactly one of {@code totpCode}, {@code recoveryCode} and {@code webauthn} is set. */
    public record SecondFactorRequest(
            @Schema(nullable = true, description = "A code from an authenticator app.")
            String totpCode,

            @Schema(nullable = true, description = "A single-use recovery code; dashes and case are ignored.")
            String recoveryCode,

            @Schema(
                    nullable = true,
                    description = "The credential a passkey returned for the options from "
                            + "POST /auth/second-factor/options, as PublicKeyCredential.toJSON() gives it.")
            Map<String, Object> webauthn,

            @Schema(
                    nullable = true,
                    description = "Trust this browser, so the next sign-in needs only the password. Honoured at "
                            + "sign-in when trusted devices are on (see trustDeviceDays); ignored for a step-up.")
            Boolean trustDevice) {}

    public record IdentityProviderView(
            @Schema(requiredMode = REQUIRED) String id,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "CREDENTIAL for a username and password form; REDIRECT for sign-in elsewhere.",
                    allowableValues = {"CREDENTIAL", "REDIRECT"})
            String kind,

            @Schema(requiredMode = REQUIRED) String label,

            @Schema(nullable = true, description = "Where a redirect provider's sign-in begins.")
            String startPath) {}

    /** Public: the login screen is built from this before any session exists. */
    @GetMapping("/providers")
    public PagedView<IdentityProviderView> providers(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size)
                .paginate(
                        providers.providers().stream()
                                .map(p -> new IdentityProviderView(p.id(), p.kind(), p.label(), p.startPath()))
                                .toList(),
                        null);
    }

    @PostMapping("/login")
    public AuthResult login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest req, HttpServletResponse resp) {
        return result(logins.login(request.provider(), request.username(), request.password(), req, resp), req);
    }

    /**
     * Step-up with a password (ADR-0103); a single-sign-on user steps up at {@code reauthentication.startPath}.
     * A user with a second factor is not fresh until they also give it to {@code /second-factor}.
     */
    @PostMapping("/reauthenticate")
    public AuthResult reauthenticate(
            @AuthenticationPrincipal RahdarPrincipal principal,
            @Valid @RequestBody ReauthenticateRequest request,
            HttpServletRequest req,
            HttpServletResponse resp) {
        return result(logins.reauthenticate(principal, request.password(), req, resp), req);
    }

    /**
     * The second factor that completes a sign-in whose password was right, or a step-up whose password
     * was right. Reachable without a session, because the first is; it still needs the CSRF token.
     */
    @PostMapping("/second-factor")
    public AuthResult secondFactor(
            @RequestBody SecondFactorRequest request, HttpServletRequest req, HttpServletResponse resp) {
        String passkey = request.webauthn() == null ? null : json.writeValueAsString(request.webauthn());
        return result(
                logins.secondFactor(
                        new LoginService.Submission(
                                request.totpCode(),
                                request.recoveryCode(),
                                passkey,
                                Boolean.TRUE.equals(request.trustDevice())),
                        req,
                        resp),
                req);
    }

    /**
     * The options a browser needs to ask for a passkey, for a sign-in or a step-up whose password was
     * right: the object {@code PublicKeyCredential.parseRequestOptionsFromJSON} takes. Reachable
     * without a session, because a sign-in has none yet; it still needs the CSRF token, and answers
     * only while a password is waiting for its second factor.
     */
    @PostMapping("/second-factor/options")
    public Map<String, Object> secondFactorOptions(HttpServletRequest req) {
        return json.readValue(logins.passkeyOptions(req), new TypeReference<>() {});
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest req, HttpServletResponse resp) {
        logins.logout(req, resp);
    }

    @GetMapping("/sessions")
    public PagedView<AccountSessionView> ownSessions(
            @AuthenticationPrincipal RahdarPrincipal principal,
            HttpServletRequest req,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        requireSession(principal);
        return ResourceQuery.ofPage(page, size).paginate(sessionService.listOwn(principal.getUsername(), req), null);
    }

    /** Ends every other session of the caller. */
    @DeleteMapping("/sessions")
    public EndedSessionsView endOtherOwnSessions(
            @AuthenticationPrincipal RahdarPrincipal principal, HttpServletRequest req) {
        requireSession(principal);
        return sessionService.endOtherOwn(principal.getUsername(), req);
    }

    /** Ends one of the caller's sessions; ending the current one signs out. */
    @DeleteMapping("/sessions/{handle}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endOwnSession(
            @AuthenticationPrincipal RahdarPrincipal principal,
            @PathVariable String handle,
            HttpServletRequest req,
            HttpServletResponse resp) {
        requireSession(principal);
        sessionService.endOwn(principal.getUsername(), handle, req, resp);
    }

    /** An API key acts within its narrowed grants, so it cannot see or end its owner's sessions. */
    private static void requireSession(RahdarPrincipal principal) {
        if (principal instanceof TokenPrincipal) {
            throw new SessionRequiredException(
                    "Sign in to Rahdar in your browser to manage sessions; a key cannot manage sessions.");
        }
    }

    @GetMapping("/me")
    public MeView me(@AuthenticationPrincipal RahdarPrincipal principal, HttpServletRequest req) {
        return view(principal, req);
    }

    private AuthResult result(LoginService.Outcome outcome, HttpServletRequest req) {
        return switch (outcome) {
            case LoginService.Outcome.Authenticated(var principal) ->
                new AuthResult(AuthStatus.AUTHENTICATED, view(principal, req), null, 0);
            case LoginService.Outcome.SecondFactorRequired(var methods, var trustDeviceDays) ->
                new AuthResult(AuthStatus.SECOND_FACTOR_REQUIRED, null, methods, trustDeviceDays);
        };
    }

    private MeView view(RahdarPrincipal principal, HttpServletRequest req) {
        var grants = perm.grantsOf(principal).stream()
                .map(g -> new GrantView(
                        g.scopeType().name(),
                        g.scopeId(),
                        g.permissions().stream().sorted().toList()))
                .toList();
        String providerId = providerOf(principal);
        return new MeView(
                principal.userId(),
                principal.getUsername(),
                principal.mustChangePassword(),
                providerId,
                principal.secondFactorEnrolmentRequired(),
                grants,
                reauthentication(providerId, req));
    }

    private String providerOf(RahdarPrincipal principal) {
        return accounts.byId(principal.userId())
                .map(UserAccounts.Account::providerId)
                .orElse(LoginService.DEFAULT_PROVIDER);
    }

    private ReauthenticationView reauthentication(String providerId, HttpServletRequest req) {
        var redirect = providers.providers().stream()
                .filter(p -> p.id().equals(providerId) && "REDIRECT".equals(p.kind()))
                .findFirst();
        return new ReauthenticationView(
                redirect.isPresent() ? "REDIRECT" : "PASSWORD",
                redirect.map(p -> p.startPath() + "?stepup").orElse(null),
                sessions.authenticatedAt(req).orElse(null),
                SessionAuthentication.REAUTHENTICATION_WINDOW.toSeconds());
    }
}
