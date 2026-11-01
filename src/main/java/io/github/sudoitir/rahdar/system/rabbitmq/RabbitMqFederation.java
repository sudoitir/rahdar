package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourcePage;
import io.github.sudoitir.openbroker.model.StandardAttributes;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.FederationLink;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.FederationPage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The federation links of a RabbitMQ cluster, read only: policies and upstreams make them, and an upstream holds a credential. */
@Service
@RequiredArgsConstructor
public class RabbitMqFederation {

    static final String REMEDY = "Enable the rabbitmq_federation and rabbitmq_federation_management plugins on every"
            + " node (rabbitmq-plugins enable rabbitmq_federation rabbitmq_federation_management) to see links.";

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;

    public FederationPage list(UUID clusterId, String vhost, String cursor, Integer limit) {
        access.requireCluster(clusterId, Permissions.DESTINATION_READ);
        Map<String, String> selector =
                vhost == null || vhost.isBlank() ? Map.of() : Map.of(RabbitMqIds.VHOST_SELECTOR, vhost);
        try {
            ResourcePage page = RabbitMqReads.rawPage(
                    gateway.cluster(clusterId),
                    RabbitMqReads.of(RabbitMqIds.kind(RabbitMqIds.FEDERATION_LINK), selector),
                    cursor,
                    limit);
            return new FederationPage(
                    true,
                    null,
                    page.items().stream().map(RabbitMqFederation::link).toList(),
                    page.continueToken().orElse(null));
        } catch (BrokerException e) {
            if (RabbitMqShovels.pluginMissing(e)) {
                return new FederationPage(false, REMEDY, List.of(), null);
            }
            throw RabbitMqFailures.translate(e);
        }
    }

    /** The link: its id is the virtual host, the upstream, the type and the federated exchange or queue. */
    static FederationLink link(Resource resource) {
        List<String> segments = resource.id().path().segments();
        String status = resource.conditions().stream()
                .filter(c -> c.type().equals(ConditionTypes.CONNECTED))
                .findFirst()
                .map(c -> c.status() == ConditionStatus.TRUE ? "running" : c.reason())
                .orElse(null);
        return new FederationLink(
                segments.get(0),
                segments.get(1),
                segments.get(2),
                resource.id().name(),
                status,
                RabbitMqShovels.endpoint(resource, StandardAttributes.ROUTE_SOURCE.name()));
    }
}
