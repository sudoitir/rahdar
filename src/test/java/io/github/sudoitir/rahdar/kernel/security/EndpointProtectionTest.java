package io.github.sudoitir.rahdar.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Reflects over every endpoint the app actually registers (via
 * {@link RequestMappingHandlerMapping}, not a hand-maintained list — a new
 * controller is covered automatically) and asserts an unauthenticated request
 * to it is rejected with {@code 401}, unless the path is on the small,
 * explicit allow-list this test itself defines. A controller shipped without
 * authentication fails this test, not a security review months later.
 *
 * <p>Unlike the sibling controller tests, this one runs the real
 * {@code SecurityFilterChain} ({@code .apply(springSecurity())}) — the thing
 * under test here is the filter chain itself, not method security.
 */
class EndpointProtectionTest extends PostgresIntegrationTest {

    /**
     * Paths reachable with no session or token, by design (identity-and-sessions
     * spec: "Login endpoint is reachable unauthenticated"; the second factor completes a sign-in that has
     * no principal yet, and is checked against the session's pending sign-in). Anything else under
     * {@code /api/**} must require authentication.
     */
    private static final Set<String> ALLOWED_UNAUTHENTICATED = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/second-factor",
            "/api/v1/auth/second-factor/options",
            "/api/v1/auth/providers",
            "/actuator/health",
            "/actuator/health/**");

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyApiEndpointRequiresAuthenticationUnlessAllowlisted() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();

        List<String> unprotected = new ArrayList<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            var patterns = info.getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/")) {
                    continue; // the SPA shell and its static assets stay public by design
                }
                if (ALLOWED_UNAUTHENTICATED.contains(pattern)) {
                    continue;
                }
                HttpMethod method = methods.isEmpty()
                        ? HttpMethod.GET
                        : HttpMethod.valueOf(methods.iterator().next().name());
                String concretePath = pattern.replaceAll("\\{[^}]+}", "00000000-0000-0000-0000-000000000000");
                // A valid CSRF token is attached so CsrfFilter's 403 never masks what
                // this test actually checks: whether the endpoint requires authentication.
                int status = mvc.perform(MockMvcRequestBuilders.request(method, concretePath)
                                .with(csrf()))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                if (status != 401) {
                    unprotected.add(method + " " + pattern + " -> " + status);
                }
            }
        }

        assertThat(unprotected)
                .as("every /api/** endpoint must reject an unauthenticated request with 401 "
                        + "unless it is on EndpointProtectionTest.ALLOWED_UNAUTHENTICATED")
                .isEmpty();
    }

    /**
     * Actuator endpoints are not controllers, so the sweep above cannot see them.
     * {@code /actuator/refresh} re-reads the Environment — a configuration change —
     * and the surrounding {@code /actuator/**} rule is {@code permitAll}, so without
     * its own explicit rule it would be callable by anyone who can reach the port.
     */
    /**
     * The metrics are installation-wide: a series names every cluster by id and every destination by name, so an
     * anonymous read shows a scope-limited caller what the scope walk hides (ADR-0228).
     */
    @Test
    void readingTheMetricsRequiresAuthentication() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();

        for (String path : List.of("/actuator/prometheus", "/actuator/metrics")) {
            int status = mvc.perform(MockMvcRequestBuilders.get(path))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).as(path).isEqualTo(401);
        }
    }

    @Test
    void refreshingTheEnvironmentRequiresAuthentication() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();

        int status = mvc.perform(
                        MockMvcRequestBuilders.post("/actuator/refresh").with(csrf()))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isEqualTo(401);
    }

    /**
     * The metrics are tagged with every cluster's id and every destination's name, so an anonymous read would show a
     * caller what the scope walk says they may not see (ADR-0228).
     */
    @Test
    void theMetricsRequireAuthentication() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();

        for (String path : List.of("/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/jvm.threads.live")) {
            int status = mvc.perform(MockMvcRequestBuilders.get(path))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            assertThat(status).as(path).isEqualTo(401);
        }
    }
}
