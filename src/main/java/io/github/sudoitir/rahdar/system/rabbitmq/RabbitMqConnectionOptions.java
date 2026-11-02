package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.ClusterSpec;
import io.github.sudoitir.openbroker.CredentialSource;
import io.github.sudoitir.openbroker.Credentials;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.NodeSpec;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.ConnectionOptions;
import io.github.sudoitir.rahdar.platform.broker.ConnectionSettingsSource;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence.RabbitMqConnectionEntity;
import io.github.sudoitir.rahdar.system.rabbitmq.internal.persistence.RabbitMqConnectionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A RabbitMQ cluster is reached at its management API URLs and, for messages, its AMQP endpoints (ADR-0223). The one
 * account serves both planes: a user name and password, or a token held as the password.
 */
@Component
@RequiredArgsConstructor
class RabbitMqConnectionOptions implements ConnectionOptions {

    /** The driver's option for how often the cluster is read, as an ISO-8601 duration. */
    static final String WATCH_INTERVAL = "rabbitmq.watch_interval";

    /** The driver's option for the virtual host of the event exchange. */
    static final String EVENT_VHOST = "rabbitmq.event_vhost";

    /** How often the cluster is read when the operator did not say. */
    static final int DEFAULT_WATCH_SECONDS = 10;

    /** The virtual host of the event exchange when the operator did not say. */
    static final String DEFAULT_EVENT_VHOST = "/";

    /** A static token is offered as valid for this long from each connect. */
    private static final Duration TOKEN_VALIDITY = Duration.ofDays(1);

    private final RabbitMqConnectionRepository connections;
    private final ConnectionSettingsSource settings;

    @Override
    public String system() {
        return RabbitMqModule.SYSTEM;
    }

    @Override
    public Entry entry() {
        return Entry.MANAGEMENT_API;
    }

    /**
     * The stored nodes are laid in without their endpoints: their listener addresses are often wildcards and are for
     * display only. The cluster is reached at the URLs and endpoints it was registered with.
     */
    @Override
    @Transactional(readOnly = true)
    public ClusterSpec spec(UUID clusterId, List<NodeSpec> nodes) {
        RabbitMqConnectionEntity connection = connection(clusterId);
        return spec(
                nodes.stream().map(node -> new NodeSpec(node.id(), List.of())).toList(),
                Arrays.asList(connection.getManagementUrls().split(",")),
                connection.getAmqpEndpoints() == null
                        ? List.of()
                        : Arrays.asList(connection.getAmqpEndpoints().split(",")),
                connection.getWatchIntervalSeconds(),
                connection.getEventVhost());
    }

    /** The spec of normalised management URLs and AMQP endpoints, shared with the check that registers a cluster. */
    static ClusterSpec spec(
            List<NodeSpec> nodes, List<String> managementUrls, List<String> amqp, int watchSeconds, String eventVhost) {
        List<Endpoint> endpoints = new ArrayList<>();
        managementUrls.forEach(url -> endpoints.add(ManagementUrls.endpoint(url)));
        amqp.forEach(endpoint -> endpoints.add(AmqpEndpoints.endpoint(endpoint)));
        Map<String, String> options = new LinkedHashMap<>();
        options.put(WATCH_INTERVAL, Duration.ofSeconds(watchSeconds).toString());
        options.put(EVENT_VHOST, eventVhost);
        return new ClusterSpec(nodes, endpoints, options);
    }

    @Override
    public CredentialSource credentials(UUID clusterId) {
        return plane -> {
            CoreConnectionSettings account = settings.coreSettings(clusterId);
            if (RabbitMqConnectionEntity.TOKEN.equals(connection(clusterId).getAuth())) {
                return Credentials.bearerToken(account.password(), Instant.now().plus(TOKEN_VALIDITY));
            }
            return account.hasCredentials()
                    ? Credentials.usernamePassword(account.username(), account.password())
                    : new Credentials.None();
        };
    }

    @Override
    public Optional<TlsBundle> tls(UUID clusterId) {
        CoreConnectionSettings account = settings.coreSettings(clusterId);
        return account.hasTls()
                ? Optional.of(new TlsBundle(account.tlsBundle(), account.verifyHostname()))
                : Optional.empty();
    }

    private RabbitMqConnectionEntity connection(UUID clusterId) {
        return connections.findById(clusterId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
    }
}
