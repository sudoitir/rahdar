package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.ClusterSpec;
import io.github.sudoitir.openbroker.CredentialSource;
import io.github.sudoitir.openbroker.Credentials;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.NodeSpec;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.kafka.KafkaOptions;
import io.github.sudoitir.rahdar.kernel.core.NotFoundException;
import io.github.sudoitir.rahdar.platform.broker.ConnectionOptions;
import io.github.sudoitir.rahdar.platform.broker.ConnectionSettingsSource;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaConnectionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Kafka cluster is reached at its bootstrap servers, with the security protocol and SASL mechanism it was
 * registered with. The one account is the cluster's DATA credential: a user name and password, or for
 * OAUTHBEARER a token held as the password.
 */
@Component
@RequiredArgsConstructor
class KafkaConnectionOptions implements ConnectionOptions {

    /** A static token is offered as valid for this long from each connect. */
    private static final Duration TOKEN_VALIDITY = Duration.ofDays(1);

    private final KafkaConnectionRepository connections;
    private final ConnectionSettingsSource settings;

    @Override
    public String system() {
        return KafkaModule.SYSTEM;
    }

    @Override
    public Entry entry() {
        return Entry.BOOTSTRAP;
    }

    @Override
    @Transactional(readOnly = true)
    public ClusterSpec spec(UUID clusterId, List<NodeSpec> nodes) {
        KafkaConnectionEntity connection = connection(clusterId);
        List<Endpoint> bootstrap = Arrays.stream(
                        connection.getBootstrapServers().split(","))
                .map(server -> new Endpoint(Plane.DATA, KafkaOptions.PROTOCOL, server))
                .toList();
        Map<String, String> options = new HashMap<>();
        options.put(KafkaOptions.SECURITY_PROTOCOL, connection.getSecurityProtocol());
        Optional.ofNullable(connection.getSaslMechanism()).ifPresent(m -> options.put(KafkaOptions.SASL_MECHANISM, m));
        Optional.ofNullable(connection.getClientId()).ifPresent(c -> options.put(KafkaOptions.CLIENT_ID, c));
        return new ClusterSpec(nodes, bootstrap, options);
    }

    @Override
    public CredentialSource credentials(UUID clusterId) {
        return plane -> {
            CoreConnectionSettings account = settings.coreSettings(clusterId);
            if (KafkaOptions.OAUTHBEARER.equals(connection(clusterId).getSaslMechanism())) {
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

    private KafkaConnectionEntity connection(UUID clusterId) {
        return connections.findById(clusterId).orElseThrow(() -> new NotFoundException("cluster", clusterId));
    }
}
