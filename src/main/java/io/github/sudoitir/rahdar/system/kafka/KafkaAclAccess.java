package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Acl;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a principal may do to a resource, given a set of ACLs: Kafka's rule, in words an operator can check. A deny
 * wins over an allow; an allow of READ, WRITE, DELETE or ALTER also allows DESCRIBE, and ALTER_CONFIGS also
 * DESCRIBE_CONFIGS; {@code ALL} covers every operation; a prefixed pattern covers every name that starts with it; the
 * principal {@code User:*} covers every user. Hosts are not evaluated.
 */
final class KafkaAclAccess {

    static final String WILDCARD_PRINCIPAL = "User:*";

    static final List<String> OPERATIONS = List.of(
            "read",
            "write",
            "create",
            "delete",
            "alter",
            "describe",
            "cluster_action",
            "describe_configs",
            "alter_configs",
            "idempotent_write",
            "create_tokens",
            "describe_tokens");

    private static final Set<String> IMPLY_DESCRIBE = Set.of("read", "write", "delete", "alter");

    private KafkaAclAccess() {}

    /** The operations the principal may do to the resource. */
    static Set<String> allowed(List<Acl> acls, String principal, String type, String name) {
        Set<String> out = new TreeSet<>();
        for (String operation : OPERATIONS) {
            if (!denied(acls, principal, type, name, operation) && allows(acls, principal, type, name, operation)) {
                out.add(operation);
            }
        }
        return out;
    }

    static boolean denied(List<Acl> acls, String principal, String type, String name, String operation) {
        return acls.stream()
                .anyMatch(a -> "deny".equals(a.permission())
                        && applies(a, principal, type, name)
                        && ("all".equals(a.operation()) || a.operation().equals(operation)));
    }

    static boolean allows(List<Acl> acls, String principal, String type, String name, String operation) {
        return acls.stream()
                .anyMatch(a -> "allow".equals(a.permission())
                        && applies(a, principal, type, name)
                        && covers(a.operation(), operation));
    }

    private static boolean covers(String granted, String operation) {
        return "all".equals(granted)
                || granted.equals(operation)
                || ("describe".equals(operation) && IMPLY_DESCRIBE.contains(granted))
                || ("describe_configs".equals(operation) && "alter_configs".equals(granted));
    }

    private static boolean applies(Acl acl, String principal, String type, String name) {
        boolean who = acl.principal().equals(principal) || WILDCARD_PRINCIPAL.equals(acl.principal());
        boolean what = acl.resourceType().equals(type)
                && ("prefixed".equals(acl.pattern())
                        ? name.startsWith(acl.name())
                        : acl.name().equals(name) || "*".equals(acl.name()));
        return who && what;
    }

    /** The ACL with its enumerations lower-cased, refused when it is not a Kafka ACL. */
    static Acl normalised(Acl acl) {
        Acl out = new Acl(
                acl.principal() == null ? "" : acl.principal().trim(),
                acl.host() == null ? "" : acl.host().trim(),
                lower(acl.operation()),
                lower(acl.permission()),
                lower(acl.resourceType()),
                lower(acl.pattern()),
                acl.name() == null ? "" : acl.name());
        if (!out.principal().contains(":") || out.host().isEmpty() || out.name().isEmpty()) {
            throw new IllegalArgumentException(
                    "An ACL names a principal such as User:app, a host (* for any) and a resource name.");
        }
        if (!"all".equals(out.operation()) && !OPERATIONS.contains(out.operation())) {
            throw new IllegalArgumentException("Unknown operation '" + out.operation() + "'.");
        }
        if (!Set.of("allow", "deny").contains(out.permission())) {
            throw new IllegalArgumentException("A permission is allow or deny.");
        }
        if (!Set.of("literal", "prefixed").contains(out.pattern())) {
            throw new IllegalArgumentException("A pattern is literal or prefixed.");
        }
        if (!Set.of("topic", "group", "cluster", "transactional_id", "delegation_token", "user")
                .contains(out.resourceType())) {
            throw new IllegalArgumentException("Unknown resource type '" + out.resourceType() + "'.");
        }
        return out;
    }

    private static String lower(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
