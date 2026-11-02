package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.CreatedView;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.QueueRequest;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.RabbitMqChangePreview;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Creates a classic queue, a quorum queue or a stream (ADR-0223); the kind of queue is the request's type. */
@Service
@RequiredArgsConstructor
public class RabbitMqQueues {

    private final ClusterAccessGuard access;
    private final RabbitMqOps ops;
    private final RabbitMqChanges changes;

    public RabbitMqChangePreview preview(UUID clusterId, QueueRequest request) {
        access.requireCluster(clusterId, ResourcePermissions.SUBSCRIPTION_CREATE);
        ResourceId id = id(request);
        return RabbitMqOps.view(
                ops.preview(clusterId, id, Verb.CREATE, attributes(request)),
                Verb.CREATE,
                "Create " + request.type() + " queue " + request.name() + " in virtual host " + request.vhost() + ".",
                List.of());
    }

    public CreatedView create(UUID clusterId, QueueRequest request) {
        access.requireCluster(clusterId, ResourcePermissions.SUBSCRIPTION_CREATE);
        return changes.create(
                clusterId, id(request), attributes(request), request.etag(), "CREATE_SUBSCRIPTION", "SUBSCRIPTION");
    }

    static ResourceId id(QueueRequest request) {
        if (request.vhost() == null
                || request.vhost().isBlank()
                || request.name() == null
                || request.name().isBlank()) {
            throw new IllegalArgumentException("A queue needs a virtual host and a name.");
        }
        return RabbitMqIds.id(RabbitMqIds.queueKind(request.type()), request.vhost(), request.name());
    }

    static Map<String, Object> attributes(QueueRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(RabbitMqIds.DURABLE, request.durable() == null || request.durable());
        out.put(RabbitMqIds.AUTO_DELETE, request.autoDelete() != null && request.autoDelete());
        arguments(out, request.arguments());
        return out;
    }

    /** The arguments by name, each under the driver's key for it. */
    static void arguments(Map<String, Object> out, Map<String, Object> arguments) {
        if (arguments != null) {
            arguments.forEach((name, value) -> out.put(RabbitMqIds.ARGUMENT_PREFIX + name, value));
        }
    }
}
