package io.github.sudoitir.rahdar.platform.mcp;

import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.security.PermissionResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerSystems;
import io.github.sudoitir.rahdar.platform.clusters.ClusterDirectory;
import io.github.sudoitir.rahdar.platform.clusters.RegisteredCluster;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Which clusters a tool that exists for one broker system applies to (ADR-0207). The catalogue offers
 * such a tool only to a caller who can read a cluster of that system, and a call that names a cluster of
 * another system is refused with the system it runs, as the REST namespace answers 404 for it.
 */
@Component
@RequiredArgsConstructor
class McpSystems {

    private final ClusterDirectory clusters;
    private final BrokerSystems systems;
    private final PermissionResolver perm;

    /** Whether the caller can read at least one cluster that runs this system. */
    boolean readsAny(String systemId) {
        return clusters.clusters().stream()
                .map(RegisteredCluster::getId)
                .anyMatch(id -> systemId.equals(systems.idOf(id)) && perm.canSeeCluster(id));
    }

    /**
     * The tool error for calling this tool on a cluster that runs another system, or {@code null} when the
     * tool applies. A cluster the caller cannot see is left to the tool, whose not-found says nothing about it.
     */
    String refusal(McpToolDef tool, UUID clusterId) {
        String system = tool.system();
        if (system == null || clusterId == null || !perm.canSeeCluster(clusterId)) {
            return null;
        }
        String running = systems.idOf(clusterId);
        if (system.equals(running)) {
            return null;
        }
        String name =
                clusters.cluster(clusterId).map(RegisteredCluster::getName).orElse(clusterId.toString());
        return tool.name() + " applies to " + Character.toUpperCase(system.charAt(0)) + system.substring(1)
                + " clusters; " + name + " runs "
                + systems.nameOf(running) + ".";
    }
}
