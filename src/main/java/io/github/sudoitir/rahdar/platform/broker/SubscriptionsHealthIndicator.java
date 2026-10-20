package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.rahdar.kernel.core.RahdarHealth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The {@code subscriptions} contributor: for every serving node Rahdar subscribes to, whether
 * its broker-notification subscription is established, and why when it is not. Degraded
 * while any is not established.
 */
@Component
@RequiredArgsConstructor
class SubscriptionsHealthIndicator extends AbstractHealthIndicator {

    private final BrokerNotifications subscriptions;
    private final NodeDirectory nodes;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Map<UUID, String> names = nodes.nodes().stream()
                .collect(Collectors.toMap(NodeDirectory.KnownNode::id, NodeDirectory.KnownNode::name, (a, b) -> a));
        Map<String, Object> perNode = new LinkedHashMap<>();
        boolean lost = false;
        for (Map.Entry<UUID, SubscriptionVerdict.NodeState> e :
                subscriptions.nodeStates().entrySet()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("name", names.getOrDefault(e.getKey(), e.getKey().toString()));
            switch (e.getValue()) {
                case SubscriptionVerdict.NodeState.Connected(var since) -> {
                    detail.put("established", true);
                    detail.put("since", since);
                }
                case SubscriptionVerdict.NodeState.Failed(var kind, var reason, var at) -> {
                    lost = true;
                    detail.put("established", false);
                    detail.put("reason", kind + ": " + reason);
                    detail.put("at", at);
                }
            }
            perNode.put(e.getKey().toString(), detail);
        }
        builder.status(lost ? RahdarHealth.DEGRADED : Status.UP).withDetail("nodes", perNode);
    }
}
