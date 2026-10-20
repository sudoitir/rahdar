package io.github.sudoitir.rahdar.platform.broker;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** A registered cluster's connection settings, as its {@link ConnectionSettingsSource} resolves them. */
@Component
@RequiredArgsConstructor
public class BrokerConnections {

    private final ConnectionSettingsSource settings;

    public BrokerConnectionSettings settingsFor(UUID clusterId) {
        return settings.jolokiaSettings(clusterId);
    }

    public CoreConnectionSettings coreSettingsFor(UUID clusterId) {
        return settings.coreSettings(clusterId);
    }
}
