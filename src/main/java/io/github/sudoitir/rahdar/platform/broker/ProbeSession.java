package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.ClusterSpec;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.NodeSpec;
import io.github.sudoitir.openbroker.Plane;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Management addresses that are not registered nodes yet (a seed, a derived URL, a node about to be
 * adopted), reached with one account for the length of one operation: a registration, a check or a
 * rediscovery. Each address is a node whose id is the address itself. Close it when the operation ends.
 */
public final class ProbeSession implements AutoCloseable {

    private final Function<ClusterSpec, ClusterConnection> connect;
    private final List<NodeSpec> nodes = new ArrayList<>();
    private ClusterConnection connection;

    ProbeSession(Function<ClusterSpec, ClusterConnection> connect) {
        this.connect = connect;
    }

    /** The node at this management address, added to the session on first use. */
    public synchronized BrokerTarget node(String managementUrl) {
        NodeId id = NodeId.of(managementUrl);
        if (nodes.stream().noneMatch(n -> n.id().equals(id))) {
            nodes.add(new NodeSpec(id, List.of(new Endpoint(Plane.MANAGEMENT, BrokerGateway.JOLOKIA, managementUrl))));
            ClusterSpec spec = ClusterSpec.ofNodes(nodes);
            if (connection == null) {
                connection = connect.apply(spec);
            } else {
                connection.reconfigure(spec);
            }
        }
        return new BrokerTarget(connection, id);
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            connection.close();
        }
    }
}
