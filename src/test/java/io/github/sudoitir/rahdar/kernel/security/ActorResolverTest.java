package io.github.sudoitir.rahdar.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ActorResolverTest {

    private final ActorResolver resolver = new ActorResolver();

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private void bind(HttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void anonymousWhenNoPrincipal() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.1.2.3");
        bind(req);

        Actor actor = resolver.resolve();

        assertThat(actor.username()).isEqualTo("anonymous");
        assertThat(actor.sourceIp()).isEqualTo("10.1.2.3");
        assertThat(actor.userId()).isNull();
    }

    @Test
    void preservesInboundRequestIdElseGeneratesOne() {
        MockHttpServletRequest with = new MockHttpServletRequest();
        with.addHeader("X-Request-Id", "req-abc-123");
        bind(with);
        assertThat(resolver.resolve().requestId()).isEqualTo("req-abc-123");

        RequestContextHolder.resetRequestAttributes();
        bind(new MockHttpServletRequest());
        String generated = resolver.resolve().requestId();
        assertThat(generated).isNotBlank().hasSize(36); // a UUID
    }

    @Test
    void systemActorForTheScheduler() {
        assertThat(resolver.system().username()).isEqualTo("system");
        assertThat(Actor.ofSystem().username()).isEqualTo("system");
    }

    @Test
    void resolvesRealUsernameAndUserIdForAnAuthenticatedSessionPrincipal() {
        bind(new MockHttpServletRequest());
        UUID userId = UUID.randomUUID();
        authenticate(new RahdarPrincipal(userId, "alice", Set.of(), false));

        Actor actor = resolver.resolve();

        assertThat(actor.username()).isEqualTo("alice");
        assertThat(actor.userId()).isEqualTo(userId);
        assertThat(actor.tokenName()).isNull();
        assertThat(actor.displayName()).isEqualTo("alice");
    }

    @Test
    void foldsTheTokenNameIntoDisplayNameForAnApiTokenPrincipal() {
        bind(new MockHttpServletRequest());
        UUID userId = UUID.randomUUID();
        authenticate(new RahdarPrincipal(userId, "alice", Set.of(), false, "ci-token"));

        Actor actor = resolver.resolve();

        assertThat(actor.username()).isEqualTo("alice");
        assertThat(actor.userId()).isEqualTo(userId);
        assertThat(actor.tokenName()).isEqualTo("ci-token");
        assertThat(actor.displayName()).isEqualTo("alice [token: ci-token]");
    }

    private static void authenticate(RahdarPrincipal principal) {
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
