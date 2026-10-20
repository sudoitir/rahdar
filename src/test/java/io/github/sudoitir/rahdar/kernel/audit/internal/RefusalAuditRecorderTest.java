package io.github.sudoitir.rahdar.kernel.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditScope;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.security.AccessRefused;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.clusters.ClusterPermissions;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * Refused requests become one audit event each, counted when repeated within a minute (audit-log spec).
 */
class RefusalAuditRecorderTest {

    private final AuditService audit = mock(AuditService.class);
    private final ActorResolver actors = mock(ActorResolver.class);
    private final AtomicLong nanos = new AtomicLong();
    private final RefusalAuditRecorder recorder = new RefusalAuditRecorder(audit, actors, nanos::get);
    private final UUID cluster = UUID.randomUUID();
    private final AuditEvent event = mock(AuditEvent.class);
    private final Actor ada = new Actor("ada", "10.0.0.1", "req-1", UUID.randomUUID());

    RefusalAuditRecorderTest() {
        when(actors.resolve()).thenReturn(ada);
        when(audit.refused(any(), any(), any(), any(), any(), any(), any())).thenReturn(event);
    }

    private void refused(ResourceRef resource, String permission, boolean hidden) {
        recorder.onRefused(new AccessRefused(cluster, permission, resource, hidden));
    }

    @Test
    void aRefusalIsAnEventNamingActorPermissionClusterAndResource() {
        refused(ResourceRef.subscription("orders.in"), "subscription:purge", false);

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.captor();
        verify(audit)
                .refused(
                        eq(ada),
                        eq("ACCESS_REFUSED"),
                        eq("SUBSCRIPTION"),
                        eq("orders.in"),
                        eq(cluster),
                        params.capture(),
                        any());
        assertThat(params.getValue())
                .containsEntry("permission", "subscription:purge")
                .containsEntry("visibility", "forbidden");
    }

    @Test
    void aResourceTheCallerWasToldDoesNotExistIsRecordedAsHidden() {
        refused(ResourceRef.subscription("billing.in"), "subscription:read", true);

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.captor();
        verify(audit).refused(any(), any(), eq("SUBSCRIPTION"), eq("billing.in"), eq(cluster), params.capture(), any());
        assertThat(params.getValue()).containsEntry("visibility", "hidden");
    }

    @Test
    void aRefusalOfTheClusterNamesNoResource() {
        refused(null, "cluster:write", true);

        verify(audit).refused(any(), any(), eq("CLUSTER"), isNull(), eq(cluster), any(), any());
    }

    @Test
    void repeatedRefusalsWithinAMinuteAreOneEventWithACount() {
        for (int i = 0; i < 50; i++) {
            refused(ResourceRef.subscription("orders.in"), "subscription:purge", false);
        }

        verify(audit, times(1)).refused(any(), any(), any(), any(), any(), any(), any());
        verify(audit).recount(event, 50);
    }

    @Test
    void aRefusalAfterTheMinuteIsANewEvent() {
        refused(ResourceRef.subscription("orders.in"), "subscription:purge", false);
        nanos.addAndGet(Duration.ofSeconds(61).toNanos());
        refused(ResourceRef.subscription("orders.in"), "subscription:purge", false);

        verify(audit, times(2)).refused(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void refusalsThatDifferInActorPermissionOrResourceAreNotMerged() {
        refused(ResourceRef.subscription("orders.in"), "subscription:purge", false);
        refused(ResourceRef.subscription("orders.in"), "message:send", false);
        refused(ResourceRef.subscription("orders.out"), "subscription:purge", false);
        refused(ResourceRef.subscription("orders.in"), "subscription:purge", true);
        when(actors.resolve()).thenReturn(new Actor("bob", "10.0.0.2", "req-2", UUID.randomUUID()));
        refused(ResourceRef.subscription("orders.in"), "subscription:purge", false);

        verify(audit, times(5)).refused(any(), any(), any(), any(), any(), any(), any());
        verify(audit, never()).recount(any(), anyLong());
    }

    @Test
    void aCallerThatAuditsItsOwnRefusalIsNotRecordedTwice() {
        ScopedValue.where(AuditScope.OWN_REFUSAL, true)
                .run(() -> refused(ResourceRef.subscription("orders.in"), "subscription:purge", false));

        verify(audit, never()).refused(any(), any(), any(), any(), any(), any(), any());
    }

    // ---- method security -----------------------------------------------------------------------------

    @SuppressWarnings("unused")
    static class Guarded {
        @PreAuthorize("@perm.can(T(io.github.sudoitir.rahdar.platform.clusters.ClusterPermissions).CLUSTER_WRITE)")
        public void register() {
            // a guarded method: only the annotation matters
        }

        @PreAuthorize("@perm.can(@settingsService.writePermission(#key))")
        public void put(String key) {
            // a guarded method: only the annotation matters
        }
    }

    private AuthorizationDeniedEvent<MethodInvocation> denied(Method method, Authentication who) {
        MethodInvocation call = mock(MethodInvocation.class);
        when(call.getMethod()).thenReturn(method);
        return new AuthorizationDeniedEvent<>(() -> who, call, new AuthorizationDecision(false));
    }

    private static Authentication signedIn() {
        return UsernamePasswordAuthenticationToken.authenticated("ada", null, AuthorityUtils.NO_AUTHORITIES);
    }

    @Test
    void aMethodDeniedByItsPreAuthorizeIsRecordedWithThePermissionItNames() throws Exception {
        recorder.onDenied(denied(Guarded.class.getMethod("register"), signedIn()));

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.captor();
        verify(audit)
                .refused(
                        eq(ada),
                        eq("ACCESS_REFUSED"),
                        eq("METHOD"),
                        eq("Guarded.register"),
                        isNull(),
                        params.capture(),
                        any());
        assertThat(params.getValue()).containsEntry("permission", ClusterPermissions.CLUSTER_WRITE);
    }

    @Test
    void aMethodWhoseExpressionNamesNoConstantIsRecordedByItsName() throws Exception {
        recorder.onDenied(denied(Guarded.class.getMethod("put", String.class), signedIn()));

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.captor();
        verify(audit).refused(any(), any(), eq("METHOD"), eq("Guarded.put"), isNull(), params.capture(), any());
        assertThat(params.getValue()).doesNotContainKey("permission");
    }

    @Test
    void notBeingSignedInIsNotARefusedPermission() throws Exception {
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        recorder.onDenied(denied(Guarded.class.getMethod("register"), anonymous));

        verify(audit, never()).refused(any(), any(), any(), any(), any(), any(), any());
    }
}
