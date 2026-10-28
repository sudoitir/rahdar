package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.BrokerRow;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Brokers;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Quorum;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.Voter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The brokers with the reach, leadership and replication each has, and the controller quorum (ADR-0216). */
@Service
@RequiredArgsConstructor
public class KafkaBrokers {

    static final String ZOOKEEPER_QUORUM = "The controller is kept in ZooKeeper and cannot be read.";

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;

    public Brokers read(UUID clusterId) {
        access.requireCluster(clusterId, Permissions.CLUSTER_READ);
        var connection = gateway.cluster(clusterId);
        Partial<List<NodeDescription>> described =
                KafkaFailures.call(() -> connection.nodes().describe(Scope.whole()));
        List<Resource> partitions = KafkaGroups.read(
                        connection,
                        ResourceQuery.of(KafkaKinds.PARTITION)
                                .project(Set.of(KafkaKinds.LEADER, KafkaKinds.REPLICAS, KafkaKinds.IN_SYNC_REPLICAS)))
                .items();
        List<BrokerRow> brokers = new ArrayList<>();
        List<Voter> voters = new ArrayList<>();
        String leader = null;
        String mode = null;
        Set<NodeId> seen = new HashSet<>();
        for (NodeDescription node : described.value()) {
            seen.add(node.node());
            if (mode == null) {
                mode = node.attributes()
                        .raw("kafka.cluster_mode")
                        .map(String::valueOf)
                        .orElse(null);
            }
            if (isTrue(node, "kafka.quorum_voter")) {
                boolean behind = node.conditions().stream()
                        .anyMatch(
                                c -> c.type().equals(ConditionTypes.OUT_OF_SYNC) && c.status() == ConditionStatus.TRUE);
                voters.add(new Voter(
                        node.nativeId(),
                        node.attributes()
                                .raw("kafka.quorum_lag")
                                .map(v -> ((Number) v).longValue())
                                .orElse(null),
                        !behind));
                if (isTrue(node, "kafka.quorum_leader")) {
                    leader = node.nativeId();
                }
            }
            if (node.functions().contains(NodeFunction.BROKER)) {
                brokers.add(broker(node, described.failures().get(node.node()), partitions));
            }
        }
        described.failures().forEach((id, failure) -> {
            if (!seen.contains(id)) {
                brokers.add(new BrokerRow(
                        id.value(),
                        null,
                        failure.info().metadata().get("kafka.endpoint"),
                        false,
                        failure.getMessage(),
                        0,
                        0,
                        0));
            }
        });
        boolean kraft = mode != null ? "kraft".equalsIgnoreCase(mode) : !voters.isEmpty();
        return new Brokers(kraft ? "KRaft" : "ZooKeeper", brokers, quorum(kraft, voters, leader));
    }

    private static BrokerRow broker(NodeDescription node, BrokerException failure, List<Resource> partitions) {
        int id = Integer.parseInt(node.nativeId());
        long leaders = 0;
        long under = 0;
        long offline = 0;
        for (Resource p : partitions) {
            List<?> replicas = list(p, KafkaKinds.REPLICAS);
            if (!replicas.contains(id) && !replicas.contains((long) id)) {
                continue;
            }
            boolean led = p.attributes().raw(KafkaKinds.LEADER).isPresent();
            if (!led) {
                offline++;
            } else if (((Number) p.attributes().raw(KafkaKinds.LEADER).get()).intValue() == id) {
                leaders++;
            }
            List<?> inSync = list(p, KafkaKinds.IN_SYNC_REPLICAS);
            if (!inSync.contains(id) && !inSync.contains((long) id)) {
                under++;
            }
        }
        String reason = failure != null
                ? failure.getMessage()
                : node.conditions().stream()
                        .filter(c -> c.type().equals(ConditionTypes.SERVING) && c.status() == ConditionStatus.FALSE)
                        .map(Condition::message)
                        .findFirst()
                        .orElse(null);
        return new BrokerRow(
                node.nativeId(),
                node.attributes().raw("kafka.rack").map(String::valueOf).orElse(null),
                node.endpoints().stream().map(Endpoint::address).findFirst().orElse(null),
                failure == null,
                reason,
                leaders,
                under,
                offline);
    }

    private static Quorum quorum(boolean kraft, List<Voter> voters, String leader) {
        if (voters.isEmpty()) {
            return new Quorum(
                    false,
                    kraft ? "The cluster did not report its controller quorum." : ZOOKEEPER_QUORUM,
                    null,
                    List.of(),
                    false);
        }
        long caughtUp = voters.stream().filter(Voter::caughtUp).count();
        return new Quorum(true, null, leader, voters, leader != null && caughtUp * 2 > voters.size());
    }

    private static List<?> list(Resource p, String key) {
        return p.attributes()
                .raw(key)
                .filter(List.class::isInstance)
                .map(v -> (List<?>) v)
                .orElse(List.of());
    }

    private static boolean isTrue(NodeDescription node, String key) {
        return node.attributes()
                .raw(key)
                .map(v -> Boolean.parseBoolean(String.valueOf(v)))
                .orElse(false);
    }
}
