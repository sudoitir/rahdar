package io.github.sudoitir.rahdar.system.rabbitmq;

import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.Plane;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.Listener;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.NodeView;
import io.github.sudoitir.rahdar.system.rabbitmq.web.RabbitMqViews.NodesView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** A RabbitMQ cluster's nodes with their alarms, partitions and listeners (ADR-0223), as the SDK describes them. */
@Service
@RequiredArgsConstructor
public class RabbitMqNodes {

    private static final String PARTITIONED_FROM = "partitioned from ";

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;

    public NodesView list(UUID clusterId) {
        access.requireCluster(clusterId, Permissions.CLUSTER_READ);
        List<NodeDescription> nodes = RabbitMqFailures.call(
                () -> gateway.cluster(clusterId).nodes().describe(Scope.whole()).orThrow());
        String clusterName = nodes.stream()
                .map(n -> n.membership().group())
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(null);
        return new NodesView(
                clusterName, nodes.stream().map(RabbitMqNodes::view).toList());
    }

    static NodeView view(NodeDescription node) {
        boolean running = holds(node, ConditionTypes.SERVING);
        return new NodeView(
                node.nativeId(),
                node.version().orElse(null),
                running,
                alarms(node),
                partitions(node),
                node.endpoints().stream()
                        .filter(e -> e.plane() == Plane.DATA)
                        .map(RabbitMqNodes::listener)
                        .toList());
    }

    private static boolean holds(NodeDescription node, String type) {
        return node.conditions().stream().anyMatch(c -> c.type().equals(type) && c.status() == ConditionStatus.TRUE);
    }

    /** The alarm a node raised: the full condition names it, memory or disk. */
    private static List<String> alarms(NodeDescription node) {
        List<String> out = new ArrayList<>();
        for (Condition c : node.conditions()) {
            if (c.type().equals(ConditionTypes.FULL) && c.status() == ConditionStatus.TRUE) {
                out.add(c.reason().startsWith("mem") ? "memory" : "disk");
            }
        }
        return out;
    }

    /** The nodes this one is partitioned from, which the out-of-sync condition names. */
    private static List<String> partitions(NodeDescription node) {
        for (Condition c : node.conditions()) {
            if (c.type().equals(ConditionTypes.OUT_OF_SYNC) && c.status() == ConditionStatus.TRUE) {
                int at = c.message().indexOf(PARTITIONED_FROM);
                String names = at < 0
                        ? ""
                        : c.message().substring(at + PARTITIONED_FROM.length()).replaceAll("\\.$", "");
                return names.isBlank()
                        ? List.of()
                        : Arrays.stream(names.split(",")).map(String::trim).toList();
            }
        }
        return List.of();
    }

    private static Listener listener(Endpoint endpoint) {
        String address = endpoint.address();
        int colon = address.lastIndexOf(':');
        int port = 0;
        try {
            port = colon < 0 ? 0 : Integer.parseInt(address.substring(colon + 1));
        } catch (NumberFormatException _) {
            // A listener whose address names no port is shown without one.
        }
        return new Listener(endpoint.protocol(), port);
    }
}
