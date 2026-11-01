package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.AccessRuleClear;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.AccessRuleSet;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.Permission;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PermissionPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.PermissionRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.RabbitMqChangePreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.RabbitMqChangeResult;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.TopicPermission;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.TopicPermissionRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.User;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.UserPage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Who may do what on a RabbitMQ cluster: its users (never a password or its hash), their permissions per virtual host
 * and their topic permissions per exchange (ADR-0223). A change shows the user's rights before and after.
 */
@Service
@RequiredArgsConstructor
public class RabbitMqPermissions {

    private static final String NONE = "none";

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;
    private final RabbitMqOps ops;
    private final RabbitMqChanges changes;

    public UserPage users(UUID clusterId, String cursor, Integer limit) {
        access.requireCluster(clusterId, ResourcePermissions.ACCESS_RULE_READ);
        ResourcePage page = RabbitMqReads.page(
                gateway.cluster(clusterId),
                RabbitMqReads.of(RabbitMqIds.kind(RabbitMqIds.USER), Map.of()),
                cursor,
                limit);
        return new UserPage(
                page.items().stream()
                        .map(u -> new User(u.id().name(), RabbitMqVhosts.tags(RabbitMqReads.text(u, RabbitMqIds.TAGS))))
                        .toList(),
                page.continueToken().orElse(null));
    }

    public PermissionPage list(UUID clusterId, String vhost, String user, String cursor, Integer limit) {
        access.requireCluster(clusterId, ResourcePermissions.ACCESS_RULE_READ);
        ClusterConnection connection = gateway.cluster(clusterId);
        Map<String, String> selector = new LinkedHashMap<>();
        if (vhost != null && !vhost.isBlank()) {
            selector.put(RabbitMqIds.VHOST_SELECTOR, vhost);
        }
        if (user != null && !user.isBlank()) {
            selector.put(RabbitMqIds.USER_SELECTOR, user);
        }
        ResourcePage page = RabbitMqReads.page(
                connection, RabbitMqReads.of(RabbitMqIds.kind(RabbitMqIds.PERMISSION), selector), cursor, limit);
        Map<String, List<TopicPermission>> topics = new HashMap<>();
        RabbitMqReads.all(connection, RabbitMqReads.of(RabbitMqIds.kind(RabbitMqIds.TOPIC_PERMISSION), selector))
                .items()
                .forEach(t -> topics.computeIfAbsent(key(t), k -> new ArrayList<>())
                        .add(new TopicPermission(
                                t.id().name(),
                                RabbitMqReads.text(t, RabbitMqIds.WRITE),
                                RabbitMqReads.text(t, RabbitMqIds.READ))));
        return new PermissionPage(
                page.items().stream()
                        .map(p -> new Permission(
                                p.id().path().segments().get(0),
                                p.id().path().segments().get(1),
                                RabbitMqReads.text(p, RabbitMqIds.CONFIGURE),
                                RabbitMqReads.text(p, RabbitMqIds.WRITE),
                                RabbitMqReads.text(p, RabbitMqIds.READ),
                                topics.getOrDefault(key(p), List.of())))
                        .toList(),
                page.continueToken().orElse(null));
    }

    private static String key(Resource resource) {
        return resource.id().path().segments().get(0) + "\u0000"
                + resource.id().path().segments().get(1);
    }

    // ---- a user's permission in a virtual host ------------------------------------------------------------------

    public RabbitMqChangePreview previewSet(UUID clusterId, String vhost, String user, PermissionRequest request) {
        access.requireCluster(clusterId, ResourcePermissions.ACCESS_RULE_WRITE);
        ResourceId id = permission(vhost, user);
        Map<String, Object> attributes = attributes(request);
        return preview(
                clusterId,
                id,
                Verb.CREATE,
                attributes,
                "Set the permission of user " + user + " in virtual host " + vhost + ".");
    }

    public RabbitMqChangeResult set(UUID clusterId, String vhost, String user, PermissionRequest request) {
        access.requireCluster(clusterId, ResourcePermissions.ACCESS_RULE_WRITE);
        return changes.apply(
                new AccessRuleSet(clusterId, permission(vhost, user).format(), attributes(request), request.etag()));
    }

    public RabbitMqChangePreview previewClear(UUID clusterId, String vhost, String user) {
        access.requireCluster(clusterId, ResourcePermissions.ACCESS_RULE_WRITE);
        return preview(
                clusterId,
                permission(vhost, user),
                Verb.DELETE,
                Map.of(),
                "Remove the permission of user " + user + " in virtual host " + vhost + ".");
    }

    public RabbitMqChangeResult clear(UUID clusterId, String vhost, String user, String etag) {
        access.requireCluster(clusterId, ResourcePermissions.ACCESS_RULE_WRITE);
        return changes.apply(
                new AccessRuleClear(clusterId, permission(vhost, user).format(), Map.of(), etag));
    }

    // ---- a user's topic permission on an exchange ---------------------------------------------------------------

    public RabbitMqChangePreview previewSetTopic(
            UUID clusterId, String vhost, String user, String exchange, TopicPermissionRequest request) {
        access.requireCluster(clusterId, ResourcePermissions.ACCESS_RULE_WRITE);
        return preview(
                clusterId,
                topic(vhost, user, exchange),
                Verb.CREATE,
                attributes(request),
                "Set the topic permission of user " + user + " on exchange " + exchange + ".");
    }

    public RabbitMqChangeResult setTopic(
            UUID clusterId, String vhost, String user, String exchange, TopicPermissionRequest request) {
        access.requireCluster(clusterId, ResourcePermissions.ACCESS_RULE_WRITE);
        return changes.apply(new AccessRuleSet(
                clusterId, topic(vhost, user, exchange).format(), attributes(request), request.etag()));
    }

    public RabbitMqChangePreview previewClearTopic(UUID clusterId, String vhost, String user, String exchange) {
        access.requireCluster(clusterId, ResourcePermissions.ACCESS_RULE_WRITE);
        return preview(
                clusterId,
                topic(vhost, user, exchange),
                Verb.DELETE,
                Map.of(),
                "Remove the topic permission of user " + user + " on exchange " + exchange + ".");
    }

    public RabbitMqChangeResult clearTopic(UUID clusterId, String vhost, String user, String exchange, String etag) {
        access.requireCluster(clusterId, ResourcePermissions.ACCESS_RULE_WRITE);
        return changes.apply(
                new AccessRuleClear(clusterId, topic(vhost, user, exchange).format(), Map.of(), etag));
    }

    private RabbitMqChangePreview preview(
            UUID clusterId, ResourceId id, Verb verb, Map<String, Object> attributes, String summary) {
        Preview preview = ops.preview(clusterId, id, verb, attributes);
        String before = current(gateway.cluster(clusterId), id)
                .map(RabbitMqPermissions::rights)
                .orElse(NONE);
        String after = verb == Verb.DELETE ? NONE : rights(attributes);
        return RabbitMqOps.view(preview, verb, summary, List.of("Before: " + before, "After: " + after));
    }

    private static Optional<Resource> current(ClusterConnection connection, ResourceId id) {
        List<String> segments = id.path().segments();
        Map<String, String> selector =
                Map.of(RabbitMqIds.VHOST_SELECTOR, segments.get(0), RabbitMqIds.USER_SELECTOR, segments.get(1));
        return RabbitMqReads.all(
                        connection, RabbitMqReads.of(RabbitMqIds.kind(id.kind().value()), selector))
                .items()
                .stream()
                .filter(r -> r.id().equals(id))
                .findFirst();
    }

    private static String rights(Resource resource) {
        return rights(resource.attributes().asMap());
    }

    private static String rights(Map<String, ?> attributes) {
        List<String> parts = new ArrayList<>();
        for (String key : List.of(RabbitMqIds.CONFIGURE, RabbitMqIds.WRITE, RabbitMqIds.READ)) {
            if (attributes.containsKey(key)) {
                parts.add(key.substring(key.lastIndexOf('.') + 1) + " '" + attributes.get(key) + "'");
            }
        }
        return String.join(", ", parts);
    }

    private static ResourceId permission(String vhost, String user) {
        require(vhost, user);
        return RabbitMqIds.id(RabbitMqIds.PERMISSION, vhost, user);
    }

    private static ResourceId topic(String vhost, String user, String exchange) {
        require(vhost, user);
        require(exchange, "exchange");
        return RabbitMqIds.id(RabbitMqIds.TOPIC_PERMISSION, vhost, user, exchange);
    }

    private static void require(String... parts) {
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException(
                        "A permission names a virtual host, a user and, for a topic, an exchange.");
            }
        }
    }

    private static Map<String, Object> attributes(PermissionRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(RabbitMqIds.CONFIGURE, pattern(request.configure()));
        out.put(RabbitMqIds.WRITE, pattern(request.write()));
        out.put(RabbitMqIds.READ, pattern(request.read()));
        return out;
    }

    private static Map<String, Object> attributes(TopicPermissionRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(RabbitMqIds.WRITE, pattern(request.write()));
        out.put(RabbitMqIds.READ, pattern(request.read()));
        return out;
    }

    /** A pattern over names; the empty one grants nothing. It must be a regular expression. */
    private static String pattern(String text) {
        String pattern = text == null ? "" : text;
        PolicyMatches.compile(pattern);
        return pattern;
    }
}
