package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.BrokerDrivers;
import io.github.sudoitir.openbroker.BrokerSystem;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** The broker systems this Rahdar has a driver for, and which of them a cluster runs. */
@Component
public class BrokerSystems {

    private final BrokerDrivers drivers;
    private final NodeDirectory directory;

    public BrokerSystems(BrokerDrivers drivers, NodeDirectory directory) {
        this.drivers = drivers;
        this.directory = directory;
    }

    /** The ids of the installed drivers' systems. */
    public Set<String> ids() {
        return drivers.systems().stream().map(BrokerSystem::id).collect(Collectors.toUnmodifiableSet());
    }

    /** The id of the system the cluster runs. */
    public String idOf(UUID clusterId) {
        return directory.system(clusterId);
    }

    /** The system the cluster runs, as its driver describes it; empty when no installed driver serves it. */
    public Optional<BrokerSystem> of(UUID clusterId) {
        String id = idOf(clusterId);
        return drivers.systems().stream().filter(s -> s.id().equals(id)).findFirst();
    }
}
