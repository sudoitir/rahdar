package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.system.rabbitmq.RabbitMqOps.Verb;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ChangePreview;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.CreatedView;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.ExchangeRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Creates an exchange: a destination other queues are bound to (ADR-0223). */
@Service
@RequiredArgsConstructor
public class RabbitMqExchanges {

    private final ClusterAccessGuard access;
    private final RabbitMqOps ops;
    private final RabbitMqChanges changes;

    public ChangePreview preview(UUID clusterId, ExchangeRequest request) {
        access.requireCluster(clusterId, ResourcePermissions.DESTINATION_CREATE);
        return RabbitMqOps.view(
                ops.preview(clusterId, id(request), Verb.CREATE, attributes(request)),
                Verb.CREATE,
                "Create " + request.type() + " exchange " + request.name() + " in virtual host " + request.vhost()
                        + ".",
                List.of());
    }

    public CreatedView create(UUID clusterId, ExchangeRequest request) {
        access.requireCluster(clusterId, ResourcePermissions.DESTINATION_CREATE);
        return changes.create(
                clusterId, id(request), attributes(request), request.etag(), "CREATE_DESTINATION", "DESTINATION");
    }

    static ResourceId id(ExchangeRequest request) {
        if (request.vhost() == null
                || request.vhost().isBlank()
                || request.name() == null
                || request.name().isBlank()) {
            throw new IllegalArgumentException("An exchange needs a virtual host and a name.");
        }
        return RabbitMqIds.id(RabbitMqIds.EXCHANGE, request.vhost(), request.name());
    }

    static Map<String, Object> attributes(ExchangeRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (request.type() == null || request.type().isBlank()) {
            throw new IllegalArgumentException("An exchange is direct, fanout, topic or headers.");
        }
        out.put(RabbitMqIds.EXCHANGE_TYPE, request.type());
        out.put(RabbitMqIds.DURABLE, request.durable() == null || request.durable());
        out.put(RabbitMqIds.AUTO_DELETE, request.autoDelete() != null && request.autoDelete());
        out.put(RabbitMqIds.INTERNAL, request.internal() != null && request.internal());
        RabbitMqQueues.arguments(out, request.arguments());
        return out;
    }
}
