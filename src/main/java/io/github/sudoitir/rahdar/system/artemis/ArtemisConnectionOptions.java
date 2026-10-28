package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.openbroker.ClusterSpec;
import io.github.sudoitir.openbroker.CredentialSource;
import io.github.sudoitir.openbroker.Credentials;
import io.github.sudoitir.openbroker.NodeSpec;
import io.github.sudoitir.rahdar.platform.broker.BrokerConnectionSettings;
import io.github.sudoitir.rahdar.platform.broker.ConnectionOptions;
import io.github.sudoitir.rahdar.platform.broker.ConnectionSettingsSource;
import io.github.sudoitir.rahdar.platform.broker.CoreConnectionSettings;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** An Artemis cluster is its nodes' Jolokia and Core endpoints, with a management and a Core account. */
@Component
@RequiredArgsConstructor
class ArtemisConnectionOptions implements ConnectionOptions {

    private final ConnectionSettingsSource settings;

    @Override
    public String system() {
        return ArtemisModule.SYSTEM;
    }

    @Override
    public Entry entry() {
        return Entry.JOLOKIA_SEEDS;
    }

    @Override
    public ClusterSpec spec(UUID clusterId, List<NodeSpec> nodes) {
        return ClusterSpec.ofNodes(nodes);
    }

    @Override
    public CredentialSource credentials(UUID clusterId) {
        return plane -> switch (plane) {
            case MANAGEMENT -> {
                BrokerConnectionSettings s = settings.jolokiaSettings(clusterId);
                yield s.hasCredentials()
                        ? Credentials.usernamePassword(s.username(), s.password())
                        : new Credentials.None();
            }
            case DATA -> {
                CoreConnectionSettings s = settings.coreSettings(clusterId);
                yield s.hasCredentials()
                        ? Credentials.usernamePassword(s.username(), s.password())
                        : new Credentials.None();
            }
        };
    }

    @Override
    public Optional<TlsBundle> tls(UUID clusterId) {
        BrokerConnectionSettings s = settings.jolokiaSettings(clusterId);
        return s.hasTls() ? Optional.of(new TlsBundle(s.tlsBundle(), s.verifyHostname())) : Optional.empty();
    }
}
