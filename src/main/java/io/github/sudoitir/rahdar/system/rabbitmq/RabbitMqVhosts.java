package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.MetricKeys;
import io.github.sudoitir.openbroker.model.MetricValue;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceCreate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceDelete;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqChanges.NamespaceUpdate;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.RabbitMqChangePreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.RabbitMqChangeResult;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.Vhost;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.VhostPage;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.VhostRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** A RabbitMQ cluster's virtual hosts: the namespace its queues, exchanges, policies and permissions live in. */
@Service
@RequiredArgsConstructor
public class RabbitMqVhosts {

    /** The most queues a deletion's preview names. */
    private static final int NAMED = 20;

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;
    private final RabbitMqOps ops;
    private final RabbitMqChanges changes;

    public VhostPage list(UUID clusterId, String cursor, Integer limit) {
        access.requireCluster(clusterId, RabbitMqModule.NAMESPACE_READ);
        ClusterConnection connection = gateway.cluster(clusterId);
        ResourcePage page =
                RabbitMqReads.page(connection, ResourceQuery.of(RabbitMqIds.kind(RabbitMqIds.VHOST)), cursor, limit);
        RabbitMqReads.Found queues = RabbitMqReads.all(
                connection, ResourceQuery.of(ResourceClass.SUBSCRIPTION).project(Set.of(MetricKeys.BACKLOG.name())));
        Map<String, long[]> counts = new HashMap<>();
        for (Resource queue : queues.items()) {
            long[] count = counts.computeIfAbsent(queue.id().path().segments().getFirst(), v -> new long[2]);
            count[0]++;
            count[1] += queue.attributes()
                    .get(MetricKeys.BACKLOG)
                    .map(MetricValue::asLong)
                    .orElse(0L);
        }
        List<Vhost> items = page.items().stream()
                .map(v -> vhost(v, counts.getOrDefault(v.id().name(), new long[2])))
                .toList();
        return new VhostPage(items, !queues.truncated(), page.continueToken().orElse(null));
    }

    private static Vhost vhost(Resource vhost, long[] count) {
        String type = RabbitMqReads.text(vhost, RabbitMqIds.DEFAULT_QUEUE_TYPE);
        return new Vhost(
                vhost.id().name(),
                RabbitMqReads.text(vhost, RabbitMqIds.DESCRIPTION),
                tags(RabbitMqReads.text(vhost, RabbitMqIds.TAGS)),
                type.isEmpty() ? null : type,
                count[0],
                count[1]);
    }

    static List<String> tags(String text) {
        return text.isBlank()
                ? List.of()
                : Arrays.stream(text.split(","))
                        .map(String::trim)
                        .filter(t -> !t.isEmpty())
                        .toList();
    }

    public RabbitMqChangePreview previewCreate(UUID clusterId, VhostRequest request) {
        access.requireCluster(clusterId, RabbitMqModule.NAMESPACE_WRITE);
        ResourceId id = id(request.name());
        Map<String, Object> attributes = attributes(request);
        Preview preview = ops.preview(clusterId, id, Verb.CREATE, attributes);
        return RabbitMqOps.view(preview, Verb.CREATE, "Create virtual host " + id.name() + ".", List.of());
    }

    public RabbitMqChangeResult create(UUID clusterId, VhostRequest request) {
        access.requireCluster(clusterId, RabbitMqModule.NAMESPACE_WRITE);
        return changes.apply(
                new NamespaceCreate(clusterId, id(request.name()).format(), attributes(request), request.etag()));
    }

    public RabbitMqChangePreview previewUpdate(UUID clusterId, String name, VhostRequest request) {
        access.requireCluster(clusterId, RabbitMqModule.NAMESPACE_WRITE);
        ResourceId id = id(name);
        Preview preview = ops.preview(clusterId, id, Verb.UPDATE, attributes(request));
        return RabbitMqOps.view(preview, Verb.UPDATE, "Change virtual host " + name + ".", List.of());
    }

    public RabbitMqChangeResult update(UUID clusterId, String name, VhostRequest request) {
        access.requireCluster(clusterId, RabbitMqModule.NAMESPACE_WRITE);
        return changes.apply(new NamespaceUpdate(clusterId, id(name).format(), attributes(request), request.etag()));
    }

    /** The effect names every queue the virtual host holds and the messages that go with them. */
    public RabbitMqChangePreview previewDelete(UUID clusterId, String name) {
        access.requireCluster(clusterId, RabbitMqModule.NAMESPACE_DELETE);
        ResourceId id = id(name);
        Preview preview = ops.preview(clusterId, id, Verb.DELETE, Map.of());
        RabbitMqReads.Found queues = RabbitMqReads.all(
                gateway.cluster(clusterId),
                ResourceQuery.of(ResourceClass.SUBSCRIPTION)
                        .where(Map.of(RabbitMqIds.VHOST_SELECTOR, name))
                        .project(Set.of(MetricKeys.BACKLOG.name())));
        return RabbitMqOps.view(
                preview, Verb.DELETE, "Delete virtual host " + name + " and everything in it.", lines(queues));
    }

    public RabbitMqChangeResult delete(UUID clusterId, String name, String etag) {
        access.requireCluster(clusterId, RabbitMqModule.NAMESPACE_DELETE);
        return changes.apply(new NamespaceDelete(clusterId, id(name).format(), Map.of(), etag));
    }

    static List<String> lines(RabbitMqReads.Found queues) {
        List<String> out = new ArrayList<>();
        if (queues.items().isEmpty()) {
            out.add("It holds no queue.");
        } else {
            String more = queues.truncated() ? " or more" : "";
            out.add("It holds " + queues.items().size() + more + " queues, and they go with it.");
        }
        queues.items().stream()
                .limit(NAMED)
                .forEach(q -> out.add("Queue " + q.id().name() + ": "
                        + q.attributes()
                                .get(MetricKeys.BACKLOG)
                                .map(b -> b.asLong() + " messages ready")
                                .orElse("messages unknown")));
        if (queues.items().size() > NAMED) {
            out.add("and " + (queues.items().size() - NAMED) + " more.");
        }
        return out;
    }

    private static ResourceId id(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A virtual host needs a name.");
        }
        return RabbitMqIds.id(RabbitMqIds.VHOST, name);
    }

    private static Map<String, Object> attributes(VhostRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (request.description() != null) {
            out.put(RabbitMqIds.DESCRIPTION, request.description());
        }
        if (request.tags() != null) {
            out.put(RabbitMqIds.TAGS, String.join(",", request.tags()));
        }
        if (request.defaultQueueType() != null && !request.defaultQueueType().isBlank()) {
            out.put(RabbitMqIds.DEFAULT_QUEUE_TYPE, request.defaultQueueType());
        }
        return out;
    }
}
