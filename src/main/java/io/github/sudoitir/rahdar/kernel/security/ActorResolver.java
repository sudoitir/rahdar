package io.github.sudoitir.rahdar.kernel.security;

import io.github.sudoitir.rahdar.kernel.core.RequestIds;
import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the {@link Actor} for a mutating action (ADR-0041, superseding
 * ADR-0023). The username and user id come from the authenticated
 * {@link RahdarPrincipal} when one is present; otherwise the actor is
 * {@code "anonymous"} with no user id. The source IP comes from
 * {@code getRemoteAddr()}, and the request id is the inbound
 * {@code X-Request-Id} header when it is a plain token, or a fresh UUID ({@link RequestIds}).
 */
@Component
@PluginApi
public class ActorResolver {

    /** The operator a handed-off task acts for, bound by {@link OperatorHandoff#runAs}; there is no request there. */
    static final ScopedValue<Actor> ON_BEHALF_OF = ScopedValue.newInstance();

    public Actor resolve() {
        if (ON_BEHALF_OF.isBound()) {
            return ON_BEHALF_OF.get();
        }
        HttpServletRequest request = currentRequest();
        RahdarPrincipal principal = currentPrincipal();
        String username = principal != null ? principal.getUsername() : Actor.ANONYMOUS;
        UUID userId = principal != null ? principal.userId() : null;
        String tokenName = principal != null ? principal.tokenName() : null;
        return new Actor(username, sourceIp(request), RequestIds.of(request), userId, tokenName);
    }

    /** For scheduler-originated audit rows. */
    public Actor system() {
        return Actor.ofSystem();
    }

    private static RahdarPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof RahdarPrincipal p) {
            return p;
        }
        return null;
    }

    private static String sourceIp(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
