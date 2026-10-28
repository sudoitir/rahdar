package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Acl;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AclChangeRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AclPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.AclPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.EffectiveAccess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** A Kafka cluster's ACLs, and what a change would do to who may do what (ADR-0216). */
@Service
@RequiredArgsConstructor
public class KafkaAcls {

    static final String NO_AUTHORIZER =
            "The cluster has no authorizer configured, so it enforces no ACLs and none can be added.";

    private static final String SECURITY_DISABLED = "SecurityDisabledException";

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;

    public AclPage list(UUID clusterId, String principal, String resource, String cursor, Integer limit) {
        access.requireCluster(clusterId, KafkaModule.ACCESS_RULE_READ);
        Map<String, String> selector = new LinkedHashMap<>();
        if (principal != null && !principal.isBlank()) {
            selector.put("principal", principal);
        }
        if (resource != null && !resource.isBlank()) {
            selector.put("resource_name", resource);
        }
        ResourceQuery query = ResourceQuery.of(KafkaKinds.ACL)
                .where(selector)
                .limit(limit == null ? KafkaGroups.DEFAULT_LIMIT : Math.max(1, Math.min(limit, KafkaGroups.MAX_LIMIT)));
        if (cursor != null && !cursor.isBlank()) {
            query = query.continueFrom(cursor);
        }
        try {
            ResourcePage page = KafkaGroups.read(gateway.cluster(clusterId), query);
            return new AclPage(
                    true,
                    null,
                    page.items().stream().map(KafkaAcls::acl).toList(),
                    page.continueToken().orElse(null));
        } catch (RuntimeException e) {
            if (noAuthorizer(e)) {
                return new AclPage(false, NO_AUTHORIZER, List.of(), null);
            }
            throw e;
        }
    }

    /** Every ACL on the cluster. */
    List<Acl> all(UUID clusterId) {
        try {
            return KafkaGroups.read(gateway.cluster(clusterId), ResourceQuery.of(KafkaKinds.ACL)).items().stream()
                    .map(KafkaAcls::acl)
                    .toList();
        } catch (RuntimeException e) {
            if (noAuthorizer(e)) {
                throw new ConflictException("acls-unavailable", NO_AUTHORIZER);
            }
            throw e;
        }
    }

    public AclPreview preview(UUID clusterId, AclChangeRequest request) {
        access.requireCluster(clusterId, KafkaModule.ACCESS_RULE_WRITE);
        List<Acl> add = normalised(request.add());
        List<Acl> remove = normalised(request.remove());
        List<Acl> before = all(clusterId);
        Set<Acl> state = new LinkedHashSet<>(before);
        state.removeAll(remove);
        state.addAll(add);
        List<Acl> after = List.copyOf(state);
        int added =
                (int) add.stream().filter(a -> !before.contains(a)).distinct().count();
        int removed = (int) remove.stream().filter(before::contains).distinct().count();

        List<EffectiveAccess> effective = new ArrayList<>();
        List<String> losses = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Acl change : concat(add, remove)) {
            String type = change.resourceType();
            String name = change.name();
            for (String principal : principals(change, before, after)) {
                if (!seen.add(principal + "|" + type + "|" + name)) {
                    continue;
                }
                Set<String> was = KafkaAclAccess.allowed(before, principal, type, name);
                Set<String> will = KafkaAclAccess.allowed(after, principal, type, name);
                effective.add(new EffectiveAccess(principal, type, name, upper(was), upper(will)));
                was.stream()
                        .filter(op -> !will.contains(op))
                        .forEach(op -> losses.add(
                                principal + " loses " + op.toUpperCase(Locale.ROOT) + " on " + type + " " + name));
                for (String op : KafkaAclAccess.OPERATIONS) {
                    if (KafkaAclAccess.denied(after, principal, type, name, op)
                            && KafkaAclAccess.allows(after, principal, type, name, op)) {
                        notes.add("A deny rule wins for " + principal + " on " + type + " " + name + " "
                                + op.toUpperCase(Locale.ROOT) + ", although an allow rule also covers it.");
                    }
                }
            }
        }
        if (concat(add, remove).stream().anyMatch(a -> !"*".equals(a.host()))) {
            notes.add("Hosts are not evaluated: access is shown for a client on any host.");
        }
        if (added == 0 && removed == 0) {
            notes.add("Nothing changes: the ACLs to add exist and the ACLs to remove do not.");
        }
        List<String> key = new ArrayList<>(
                new TreeSet<>(before.stream().map(KafkaAcls::key).toList()));
        return new AclPreview(
                effective,
                losses.stream().distinct().toList(),
                notes.stream().distinct().toList(),
                added,
                removed,
                KafkaEtags.of(key));
    }

    static ResourceId id(Acl a) {
        return new ResourceId(
                KafkaKinds.ACL,
                ResourcePath.of(
                        a.principal(),
                        a.host(),
                        a.operation(),
                        a.permission(),
                        a.resourceType(),
                        a.pattern(),
                        a.name()));
    }

    static List<Acl> normalised(List<Acl> acls) {
        return acls == null
                ? List.of()
                : acls.stream().map(KafkaAclAccess::normalised).distinct().toList();
    }

    private static Acl acl(Resource resource) {
        List<String> p = resource.id().path().segments();
        return new Acl(p.get(0), p.get(1), p.get(2), p.get(3), p.get(4), p.get(5), p.get(6));
    }

    private static String key(Acl a) {
        return id(a).format();
    }

    private static List<Acl> concat(List<Acl> a, List<Acl> b) {
        List<Acl> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<String> upper(Set<String> operations) {
        return operations.stream().map(o -> o.toUpperCase(Locale.ROOT)).toList();
    }

    /** The principal of a change; for the wildcard principal, every principal with a rule on that resource type. */
    private static Set<String> principals(Acl change, List<Acl> before, List<Acl> after) {
        Set<String> out = new LinkedHashSet<>();
        out.add(change.principal());
        if (KafkaAclAccess.WILDCARD_PRINCIPAL.equals(change.principal())) {
            for (List<Acl> state : List.of(before, after)) {
                state.stream()
                        .filter(a -> a.resourceType().equals(change.resourceType()))
                        .forEach(a -> out.add(a.principal()));
            }
        }
        return out;
    }

    static boolean noAuthorizer(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof BrokerException b
                    && SECURITY_DISABLED.equals(b.info().metadata().get("kafka.code"))) {
                return true;
            }
            if (t.getClass().getSimpleName().equals(SECURITY_DISABLED)) {
                return true;
            }
        }
        return false;
    }
}
