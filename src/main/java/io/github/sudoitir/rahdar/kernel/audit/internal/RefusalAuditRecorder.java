package io.github.sudoitir.rahdar.kernel.audit.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditScope;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.security.AccessRefused;
import io.github.sudoitir.rahdar.kernel.security.Actor;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Records every request refused for lack of a permission as a {@code REFUSED} audit event (audit-log
 * spec): the guard's refusals, which say whether the resource was hidden or forbidden, and the method
 * security's denials.
 *
 * <p>Identical refusals by the same actor within a minute are one event with a count, so a client that
 * keeps asking cannot fill the trail: the first writes the event, and each repeat raises its count. The
 * minute starts at the first refusal. Replicas count their own, so a client that reaches several is
 * recorded once on each.
 */
@Component
class RefusalAuditRecorder {

    static final String ACTION = "ACCESS_REFUSED";

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** The constants a method security expression names: {@code T(some.Class).NAME}. */
    private static final Pattern PERMISSION_CONSTANT = Pattern.compile("T\\(([\\w.$]+)\\)\\.(\\w+)");

    private record Key(
            String actor, String permission, UUID clusterId, String targetType, String targetName, boolean hidden) {}

    private static final class Recorded {
        private final AuditEvent event;
        private final AtomicLong count = new AtomicLong(1);

        private Recorded(AuditEvent event) {
            this.event = event;
        }
    }

    private final AuditService audit;
    private final ActorResolver actors;
    private final Cache<Key, Recorded> recent;
    private final Map<Method, String> permissionOfMethod = new ConcurrentHashMap<>();

    @Autowired
    RefusalAuditRecorder(AuditService audit, ActorResolver actors) {
        this(audit, actors, Ticker.systemTicker());
    }

    RefusalAuditRecorder(AuditService audit, ActorResolver actors, Ticker ticker) {
        this.audit = audit;
        this.actors = actors;
        this.recent = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(WINDOW)
                .maximumSize(10_000)
                .build();
    }

    @EventListener
    void onRefused(AccessRefused refused) {
        if (AuditScope.OWN_REFUSAL.isBound()) {
            return;
        }
        ResourceRef resource = refused.resource();
        record(
                refused.permission(),
                refused.clusterId(),
                resource == null ? "CLUSTER" : resource.type().name(),
                resource == null ? null : resource.name(),
                refused.hidden());
    }

    @EventListener
    void onDenied(AuthorizationDeniedEvent<?> denied) {
        Authentication authentication = denied.getAuthentication().get();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            // Not being signed in is the sign-in's to answer, not a permission refused.
            return;
        }
        switch (denied.getObject()) {
            case MethodInvocation call -> {
                Method method = call.getMethod();
                String permission = permissionOf(method);
                record(
                        permission.isEmpty() ? null : permission,
                        null,
                        "METHOD",
                        method.getDeclaringClass().getSimpleName() + "." + method.getName(),
                        false);
            }
            case RequestAuthorizationContext context ->
                record(
                        null,
                        null,
                        "REQUEST",
                        context.getRequest().getMethod() + " "
                                + context.getRequest().getRequestURI(),
                        false);
            default -> record(null, null, "REQUEST", null, false);
        }
    }

    private void record(String permission, UUID clusterId, String targetType, String targetName, boolean hidden) {
        Actor actor = actors.resolve();
        Key key = new Key(
                actor.userId() == null ? actor.displayName() : actor.userId() + "/" + actor.displayName(),
                permission,
                clusterId,
                targetType,
                targetName,
                hidden);
        boolean[] first = {false};
        Recorded recorded = recent.get(key, k -> {
            first[0] = true;
            return new Recorded(audit.refused(
                    actor, ACTION, targetType, targetName, clusterId, params(permission, hidden), reason(key)));
        });
        if (!first[0]) {
            audit.recount(recorded.event, recorded.count.incrementAndGet());
        }
    }

    private static Map<String, Object> params(String permission, boolean hidden) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (permission != null) {
            params.put("permission", permission);
        }
        params.put("visibility", hidden ? "hidden" : "forbidden");
        return params;
    }

    private static String reason(Key key) {
        String what = key.permission() == null ? "access" : key.permission();
        return key.hidden()
                ? "Refused " + what + ": the resource was not shown to the caller."
                : "Refused " + what + ": the caller does not hold it.";
    }

    /** The permission a {@code @PreAuthorize} expression names through a constant, or empty when it names none. */
    private String permissionOf(Method method) {
        return permissionOfMethod.computeIfAbsent(method, m -> {
            PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
            if (annotation == null) {
                annotation = AnnotatedElementUtils.findMergedAnnotation(m.getDeclaringClass(), PreAuthorize.class);
            }
            if (annotation == null) {
                return "";
            }
            Matcher matcher = PERMISSION_CONSTANT.matcher(annotation.value());
            if (!matcher.find()) {
                return "";
            }
            try {
                Object value = Class.forName(matcher.group(1))
                        .getField(matcher.group(2))
                        .get(null);
                return value instanceof String permission ? permission : "";
            } catch (ReflectiveOperationException | LinkageError _) {
                return "";
            }
        });
    }
}
