package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.rahdar.kernel.core.RahdarHealth;
import io.github.sudoitir.rahdar.platform.broker.NodeCallHealth.Calls;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * The {@code brokers} contributor: for every registered node, when a management call last
 * succeeded and failed and how long calls last waited on the per-node rate limit; for every
 * cluster, whether Rahdar holds a broker connection for it. Degraded while any node's latest call failed.
 * Built entirely from what earlier calls recorded — a health read never calls a broker.
 */
@Component
@RequiredArgsConstructor
class BrokersHealthIndicator extends AbstractHealthIndicator {

    private final NodeDirectory nodes;
    private final NodeCallHealth calls;
    private final NodeCallLimiter limiter;
    private final BrokerGateway gateway;

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Map<String, Object> perNode = new LinkedHashMap<>();
        Set<UUID> clusters = new LinkedHashSet<>();
        boolean failing = false;
        for (NodeDirectory.KnownNode node : nodes.nodes()) {
            clusters.add(node.clusterId());
            Optional<Calls> latest =
                    node.managementUrl() == null ? Optional.empty() : calls.calls(node.managementUrl());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("name", node.name());
            detail.put("clusterId", node.clusterId());
            detail.put("lastSuccess", latest.map(Calls::lastSuccess).orElse(null));
            detail.put("lastFailure", latest.map(Calls::lastFailure).orElse(null));
            detail.put("lastError", latest.map(Calls::lastError).orElse(null));
            detail.put(
                    "rateLimitWaitMs",
                    node.managementUrl() == null
                            ? 0L
                            : limiter.lastWait(node.managementUrl()).toMillis());
            failing |= latest.map(Calls::failing).orElse(false);
            perNode.put(node.id().toString(), detail);
        }
        Set<UUID> connected = gateway.clusterIds();
        Map<String, Boolean> connections = new LinkedHashMap<>();
        clusters.forEach(id -> connections.put(id.toString(), connected.contains(id)));
        builder.status(failing ? RahdarHealth.DEGRADED : Status.UP)
                .withDetail("nodes", perNode)
                .withDetail("connections", connections);
    }
}
