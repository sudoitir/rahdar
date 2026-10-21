package io.github.sudoitir.rahdar.kernel.security.internal;

import io.github.sudoitir.rahdar.kernel.security.IdentityProviders;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.kernel.security.SessionAuthentication;
import io.github.sudoitir.rahdar.kernel.security.SettingsPermissions;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one security chain: session-cookie authentication and dynamic, scope-walked
 * authorization (ADR-0037, ADR-0038), assembled from the installed identity providers
 * (ADR-0073).
 *
 * <p>Every {@code /api/**} path requires authentication except sign-in and the provider list;
 * the served SPA shell and its static assets stay public so an unauthenticated browser can load
 * the login screen at all.
 *
 * <p>{@code /mcp} is authenticated on the same terms (ADR-0046): bearer providers — personal
 * API tokens (ADR-0039) — authenticate it, with no second credential store.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Scripts, connections, workers and forms stay on Rahdar's own origin; nothing may frame it
     * (ADR-0122). Styles allow {@code 'unsafe-inline'} because Mantine injects them at runtime and
     * style injection runs no script. {@code 'wasm-unsafe-eval'} lets the code highlighter compile its
     * WebAssembly regex engine; it allows no JavaScript eval. Plugin remotes are served from this
     * origin, so they fit.
     *
     * <p>Defence in depth against a script that gets in anyway (ADR-0168): inline event-handler
     * attributes never run, and the DOM's script sinks accept only Trusted Types. Three policies
     * may exist: {@code dompurify} (the sanitiser the code highlighter's markup goes through),
     * {@code rahdar#worker} (the layout worker's script URL) and {@code default} (text without
     * markup into a style element, which Mantine needs).
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; "
            + "script-src-attr 'none'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
            + "font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; object-src 'none'; "
            + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'; "
            + "require-trusted-types-for 'script'; trusted-types default dompurify rahdar#worker";

    /** Nothing a page needs from a camera, a sensor, a payment sheet or a USB port. */
    static final String PERMISSIONS_POLICY = "accelerometer=(), autoplay=(), camera=(), display-capture=(), "
            + "fullscreen=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), midi=(), "
            + "payment=(), usb=(), xr-spatial-tracking=()";

    /**
     * The headers every response carries, API answers, the SPA shell, static assets and refusals
     * alike (ADR-0168). HSTS stays Spring's default: sent over HTTPS only, never on plain HTTP.
     */
    private static void responseHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                .crossOriginOpenerPolicy(coop -> coop.policy(CrossOriginOpenerPolicy.SAME_ORIGIN))
                .crossOriginResourcePolicy(corp -> corp.policy(CrossOriginResourcePolicy.SAME_ORIGIN));
    }

    /**
     * Publishes an {@code AuthorizationDeniedEvent} for every request a signed-in caller is denied, so the
     * audit trail records the refusal (audit-log spec).
     */
    @Bean
    AuthorizationEventPublisher authorizationEventPublisher(ApplicationEventPublisher events) {
        return new SpringAuthorizationEventPublisher(events);
    }

    /**
     * A resource id is one URL segment, so a name that holds a slash or a percent sign arrives as
     * {@code %2F} or {@code %25} (design of the resource routes). The strict firewall refuses both
     * by default; the embedded Tomcat is told to pass them through ({@code EncodedSolidusConfig}).
     */
    @Bean
    StrictHttpFirewall httpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedSlash(true);
        firewall.setAllowUrlEncodedPercent(true);
        return firewall;
    }

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            List<IdentityProviders> identityProviders,
            HandlerExceptionResolver handlerExceptionResolver,
            JsonMapper json,
            CsrfTokenRepository csrfTokenRepository,
            SessionAuthentication sessions,
            PermissionResolver perm)
            throws Exception {
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(json);
        http.securityContext(sc -> sc.securityContextRepository(securityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // A request a bearer token authenticated carries no ambient browser
                        // credential, so there is nothing for a cross-site request to ride on
                        // (design.md decision 1). Any other Authorization header never gets here:
                        // BearerAuthenticationFilter answers it with a 401 problem.
                        .ignoringRequestMatchers(
                                request -> request.getAttribute(BearerAuthenticationFilter.AUTHENTICATED) != null))
                .headers(SecurityConfig::responseHeaders)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new ProblemAccessDeniedHandler(json)))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/second-factor",
                                "/api/v1/auth/second-factor/options",
                                "/api/v1/auth/providers",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/livez",
                                "/readyz")
                        .permitAll()
                        // POST /actuator/refresh re-reads the Environment, so it is a
                        // configuration change and is gated like one. Without this rule it
                        // would fall through to the permitAll() below with the rest of
                        // /actuator/** and be callable by anyone who can reach the port.
                        .requestMatchers("/actuator/refresh")
                        // The resolver is called directly rather than through a SpEL
                        // "@perm.can(...)" expression: WebExpressionAuthorizationManager
                        // evaluates without a bean resolver, so the @perm reference fails
                        // at request time rather than at startup.
                        .access((authentication, context) ->
                                new AuthorizationDecision(perm.can(SettingsPermissions.SETTINGS_WRITE)))
                        .requestMatchers("/api/**", "/mcp", "/mcp/**")
                        .authenticated()
                        // A plugin's UI runs with the full rights of whoever is viewing it
                        // (design.md §7), so its own assets are gated exactly like /api/** rather
                        // than falling into the SPA shell's public catch-all below.
                        .requestMatchers("/plugin-ui/**")
                        .authenticated()
                        // The SPA shell and its static assets (SpaRoutingConfig) must stay
                        // reachable unauthenticated, or the login page itself cannot load.
                        .anyRequest()
                        .permitAll())
                // First of all, so every reader of the client's address sees it in one spelling.
                .addFilterBefore(new ClientAddressFilter(), DisableEncodeUrlFilter.class)
                // A session that should no longer count ends first, so nothing after it sees it signed in.
                .addFilterAfter(new SessionLifetimeFilter(sessions), SecurityContextHolderFilter.class)
                // SecurityContextHolderFilter loads (empty, session-less) context from the
                // repository and would overwrite a bearer authentication set before it runs —
                // this filter must come after, not before.
                .addFilterAfter(
                        new BearerAuthenticationFilter(identityProviders, entryPoint),
                        SecurityContextHolderFilter.class)
                .addFilterAfter(
                        new RestrictedSessionFilter(handlerExceptionResolver), SecurityContextHolderFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

        // Redirect sign-in (ADR-0040) adds what it needs; a module with nothing to add adds nothing.
        for (IdentityProviders contribution : identityProviders) {
            contribution.configure(http);
        }
        return http.build();
    }

    /** Combines session storage (form login) with the per-request attribute cache Spring Security expects. */
    private static SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new HttpSessionSecurityContextRepository(), new RequestAttributeSecurityContextRepository());
    }

    @Bean
    SecurityContextRepository securityContextRepositoryBean() {
        return securityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }
}
