package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.rahdar.platform.broker.ClockOffsetRegistry.ClockOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The registered broker nodes, as the transport needs to see them. Implemented by the module
 * that owns node registration, so the transport never reads that module's tables.
 */
public interface NodeDirectory {

    /** Every registered node. */
    List<KnownNode> nodes();

    /** Persist the latest clock reading for each node that has one (ADR-0053). */
    void recordClockOffsets(Map<UUID, ClockOffset> byNode);

    /** The broker system a registered cluster runs, as the broker SDK names it. */
    String system(UUID clusterId);

    /** One registered node and the endpoints it is reached at; none on a plane Rahdar has no address for yet. */
    record KnownNode(UUID id, UUID clusterId, String name, List<Endpoint> endpoints) {

        /** Where the node is managed, or {@code null} while Rahdar cannot manage it. */
        public String managementUrl() {
            return endpoints.stream()
                    .filter(e -> e.plane() == Plane.MANAGEMENT)
                    .map(Endpoint::address)
                    .findFirst()
                    .orElse(null);
        }
    }
}
