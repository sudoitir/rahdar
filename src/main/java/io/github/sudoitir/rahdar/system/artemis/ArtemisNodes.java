package io.github.sudoitir.rahdar.system.artemis;

import io.github.sudoitir.openbroker.artemis.ArtemisManagement;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerTarget;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The Artemis management agent of a node: the one place Rahdar unwraps the broker SDK's Artemis extension. */
@Component
public class ArtemisNodes {

    private final BrokerGateway gateway;

    public ArtemisNodes(BrokerGateway gateway) {
        this.gateway = gateway;
    }

    public ArtemisNode on(BrokerTarget target) {
        return node(target);
    }

    /** The agent of a node reached through any connection, such as a probe's or a test's. */
    public static ArtemisNode node(BrokerTarget target) {
        ArtemisManagement management = target.connection()
                .unwrap(ArtemisManagement.class)
                .orElseThrow(() -> new IllegalStateException("This cluster is not an Artemis cluster."));
        return new ArtemisNode(management.jolokia(target.node()));
    }

    /** A registered node's agent. */
    public ArtemisNode on(UUID clusterId, UUID nodeId) {
        return on(gateway.node(clusterId, nodeId));
    }
}
