package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.Endpoint;
import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.Partial;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.kafka.admin.Topology;
import io.github.sudoitir.openbroker.model.Condition;
import io.github.sudoitir.openbroker.model.ConditionStatus;
import io.github.sudoitir.openbroker.model.ConditionTypes;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.rahdar.kernel.core.HeavyRequests;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.BrokerQuorum;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterBroker;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterController;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterProblems;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterTotals;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ClusterView;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.CoReplication;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.QuorumVoter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * One read of a Kafka cluster: the brokers, the controller-only nodes, the quorum, which brokers share replicas, and
 * the partitions that need attention (ADR-0233). The permit is held here, so the screen and the MCP tool pay the same
 * bound.
 */
@Service
@RequiredArgsConstructor
public class KafkaClusterView {

    /** Topic names in the partitions that need attention. The counts are not capped. */
    static final int TOP = 20;

    /** The driver's topic cap. Reaching it means the totals are a floor, including a cluster that has exactly this many. */
    static final int TOPIC_CAP = Topology.MAX_TOPICS;

    static final String TOPIC_CAP_REASON = "The cluster describes at most " + TOPIC_CAP
            + " topics at once, so these figures are a floor, not a total.";

    static final String TOPICS_WITHHELD = "Topic names need destination:read.";

    static final String UNKNOWN_QUORUM = "The cluster mode could not be read, so the quorum is not counted as healthy.";

    static final String PLACEMENT_UNREAD = "Placement was not read";

    /**
     * The driver's own words for a KRaft cluster older than 4.0. Taken from the version the node read already
     * reported, so the note is the same snapshot as the boxes.
     */
    static final String FENCE_GAP =
            "Kafka before 4.0 does not list fenced brokers, so a fenced broker is missing from the nodes instead of shown as fenced.";

    /** The words a ZooKeeper-mode cluster gets: the controller is not a quorum this view can read. */
    static final String ZOOKEEPER_QUORUM = "The controller is kept in ZooKeeper and cannot be read.";

    /** Leader, replicas and the in-sync set. Offsets and log dirs would wait on a broker that is down. */
    private static final Set<String> PLACEMENT =
            Set.of(KafkaKinds.LEADER, KafkaKinds.REPLICAS, KafkaKinds.IN_SYNC_REPLICAS);

    static final HeavyRequests.Endpoint CLUSTER =
            new HeavyRequests.Endpoint("cluster reads", 4, Duration.ofSeconds(30), Integer.MAX_VALUE);

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;
    private final ActorResolver actors;
    private final HeavyRequests heavy;

    public ClusterView read(UUID clusterId) {
        access.requireCluster(clusterId, Permissions.CLUSTER_READ);
        return heavy.run(CLUSTER, actors.resolve().displayName(), _ -> assemble(gateway.cluster(clusterId), clusterId));
    }

    private ClusterView assemble(ClusterConnection connection, UUID clusterId) {
        Partial<List<NodeDescription>> described =
                KafkaFailures.call(() -> connection.nodes().describe(Scope.whole()));
        Nodes nodes = nodes(described);
        List<String> notes = new ArrayList<>();
        String mode = viewMode(nodes.rawMode());
        BrokerQuorum quorum = quorum(mode, nodes.voters(), nodes.leader(), notes);
        fenceNote(mode, nodes.version()).ifPresent(notes::add);
        Placement placement = placement(connection, notes);
        boolean names = access.holds(clusterId, Permissions.DESTINATION_READ);
        Derived derived = derive(nodes, placement.partitions());
        boolean capped = placement.read() && derived.topics() >= TOPIC_CAP;
        if (capped) {
            notes.add(TOPIC_CAP_REASON);
        }
        String reason = !placement.read() ? placement.reason() : capped ? TOPIC_CAP_REASON : null;
        return new ClusterView(
                mode,
                placement.read() && !capped,
                reason,
                derived.brokers(),
                derived.controllers(),
                quorum,
                derived.edges(),
                totals(derived, placement.read(), capped),
                problems(derived, names),
                List.copyOf(notes));
    }

    /** kraft, zookeeper, or unknown. Absent is unknown: it is not ZooKeeper and it is not healthy. */
    static String viewMode(String raw) {
        if (raw == null) {
            return "Unknown";
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "kraft" -> "KRaft";
            case "zookeeper" -> "ZooKeeper";
            default -> "Unknown";
        };
    }

    private static BrokerQuorum quorum(String mode, List<QuorumVoter> voters, String leader, List<String> notes) {
        if ("ZooKeeper".equals(mode)) {
            notes.add(ZOOKEEPER_QUORUM);
            return quorum(false, List.of(), null);
        }
        if ("Unknown".equals(mode)) {
            notes.add(UNKNOWN_QUORUM);
            return new BrokerQuorum(false, UNKNOWN_QUORUM, null, List.copyOf(voters), false);
        }
        return quorum(true, voters, leader);
    }

    /** Voters that are caught up are a majority only when a leader was elected and they outnumber the rest. */
    static BrokerQuorum quorum(boolean kraft, List<QuorumVoter> voters, String leader) {
        if (voters.isEmpty()) {
            return new BrokerQuorum(
                    false,
                    kraft ? "The cluster did not report its controller quorum." : ZOOKEEPER_QUORUM,
                    null,
                    List.of(),
                    false);
        }
        long caughtUp = voters.stream().filter(QuorumVoter::caughtUp).count();
        return new BrokerQuorum(true, null, leader, voters, leader != null && caughtUp * 2 > voters.size());
    }

    /** A KRaft cluster whose reported release is before 4.0. An unreadable version stays unstated. */
    static Optional<String> fenceNote(String mode, String version) {
        if (!"KRaft".equals(mode)) {
            return Optional.empty();
        }
        Integer major = major(version);
        return major != null && major < 4 ? Optional.of(FENCE_GAP) : Optional.empty();
    }

    private static Integer major(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String digits = version.toLowerCase(Locale.ROOT);
        if (digits.startsWith("at least ")) {
            digits = digits.substring("at least ".length());
        } else if (digits.startsWith("before ")) {
            digits = digits.substring("before ".length());
        }
        int end = 0;
        while (end < digits.length() && Character.isDigit(digits.charAt(end))) {
            end++;
        }
        return end == 0 ? null : Integer.valueOf(digits.substring(0, end));
    }

    private static Placement placement(ClusterConnection connection, List<String> notes) {
        try {
            return new Placement(KafkaPartitionReads.allPartitions(connection, null, PLACEMENT), true, null);
        } catch (RuntimeException e) {
            String reason = PLACEMENT_UNREAD + ": " + e.getMessage();
            notes.add(reason);
            return new Placement(List.of(), false, reason);
        }
    }

    private static Derived derive(Nodes nodes, List<Resource> partitions) {
        Map<Integer, long[]> counts = new HashMap<>();
        for (Described broker : nodes.brokers()) {
            if (broker.id() != null) {
                counts.put(broker.id(), new long[4]);
            }
        }
        Map<Pair, long[]> pairs = new TreeMap<>();
        long under = 0;
        long offline = 0;
        long preferred = 0;
        Set<String> topics = new HashSet<>();
        Set<String> attention = new TreeSet<>();
        for (Resource partition : partitions) {
            Integer leader = KafkaPlacement.integer(
                    partition.attributes().raw(KafkaKinds.LEADER).orElse(null));
            List<Integer> replicas = KafkaPlacement.ints(
                    partition.attributes().raw(KafkaKinds.REPLICAS).orElse(null));
            List<Integer> inSync = KafkaPlacement.ints(
                    partition.attributes().raw(KafkaKinds.IN_SYNC_REPLICAS).orElse(null));
            List<Integer> offlineReplicas = KafkaPlacement.offline(leader, replicas, inSync);
            topics.add(KafkaPlacement.topic(partition));
            if (leader == null) {
                offline++;
            } else if (!offlineReplicas.isEmpty()) {
                under++;
            }
            if (leader != null && !KafkaPlacement.preferred(leader, replicas)) {
                preferred++;
            }
            if (KafkaPlacement.needsAttention(partition)) {
                attention.add(KafkaPlacement.topic(partition));
            }
            for (int replica : replicas) {
                long[] count = counts.get(replica);
                if (count == null) {
                    continue;
                }
                count[1]++;
                if (leader != null && leader == replica) {
                    count[0]++;
                }
                if (offlineReplicas.contains(replica)) {
                    count[2]++;
                }
                if (leader == null) {
                    count[3]++;
                }
            }
            List<Integer> ordered = replicas.stream().distinct().sorted().toList();
            for (int i = 0; i < ordered.size(); i++) {
                for (int j = i + 1; j < ordered.size(); j++) {
                    long[] pair = pairs.computeIfAbsent(new Pair(ordered.get(i), ordered.get(j)), _ -> new long[2]);
                    pair[0]++;
                    if (offlineReplicas.contains(ordered.get(i)) || offlineReplicas.contains(ordered.get(j))) {
                        pair[1]++;
                    }
                }
            }
        }
        List<ClusterBroker> brokers = new ArrayList<>();
        for (Described broker : nodes.brokers()) {
            long[] count = broker.id() == null ? null : counts.get(broker.id());
            brokers.add(new ClusterBroker(
                    broker.nativeId(),
                    broker.rack(),
                    broker.address(),
                    broker.reachable(),
                    broker.reason(),
                    count == null ? null : count[0],
                    count == null ? null : count[1],
                    count == null ? null : count[2],
                    count == null ? null : count[3],
                    broker.quorumLeader()));
        }
        List<CoReplication> edges = new ArrayList<>();
        pairs.forEach((pair, count) -> edges.add(
                new CoReplication(Integer.toString(pair.a()), Integer.toString(pair.b()), count[0], count[1])));
        return new Derived(
                brokers,
                nodes.controllers(),
                edges,
                topics.size(),
                partitions.size(),
                under,
                offline,
                preferred,
                attention);
    }

    private static ClusterTotals totals(Derived derived, boolean read, boolean capped) {
        long reached =
                derived.brokers().stream().filter(ClusterBroker::reachable).count();
        Long topics = read && !capped ? (long) derived.topics() : null;
        Long partitions = read && !capped ? (long) derived.partitions() : null;
        return new ClusterTotals(
                derived.brokers().size(),
                reached,
                derived.controllers().size(),
                topics,
                partitions,
                derived.under(),
                derived.offline(),
                derived.preferred(),
                capped);
    }

    private static ClusterProblems problems(Derived derived, boolean names) {
        List<String> topics = names ? derived.attention().stream().limit(TOP).toList() : List.of();
        long omitted = names
                ? derived.attention().size() - topics.size()
                : derived.attention().size();
        return new ClusterProblems(
                derived.offline(),
                derived.under(),
                derived.preferred(),
                topics,
                names ? null : TOPICS_WITHHELD,
                omitted);
    }

    private static Nodes nodes(Partial<List<NodeDescription>> described) {
        List<Described> brokers = new ArrayList<>();
        List<ClusterController> controllers = new ArrayList<>();
        List<QuorumVoter> voters = new ArrayList<>();
        String leader = null;
        String rawMode = null;
        String version = null;
        Set<NodeId> seen = new HashSet<>();
        for (NodeDescription node : described.value()) {
            seen.add(node.node());
            if (rawMode == null) {
                rawMode = node.attributes()
                        .raw("kafka.cluster_mode")
                        .map(String::valueOf)
                        .orElse(null);
            }
            if (version == null) {
                version = node.attributes()
                        .raw("kafka.cluster_version")
                        .map(String::valueOf)
                        .orElse(null);
            }
            if (isTrue(node, "kafka.quorum_voter")) {
                boolean behind = node.conditions().stream()
                        .anyMatch(
                                c -> c.type().equals(ConditionTypes.OUT_OF_SYNC) && c.status() == ConditionStatus.TRUE);
                voters.add(new QuorumVoter(node.nativeId(), lag(node), !behind));
                if (isTrue(node, "kafka.quorum_leader")) {
                    leader = node.nativeId();
                }
            }
            boolean broker = node.functions().contains(NodeFunction.BROKER);
            boolean controller = node.functions().contains(NodeFunction.CONTROLLER);
            if (broker) {
                brokers.add(described(node, described.failures().get(node.node())));
            } else if (controller) {
                boolean behind = node.conditions().stream()
                        .anyMatch(
                                c -> c.type().equals(ConditionTypes.OUT_OF_SYNC) && c.status() == ConditionStatus.TRUE);
                controllers.add(new ClusterController(
                        node.nativeId(), lag(node), !behind, isTrue(node, "kafka.quorum_leader")));
            }
        }
        for (Map.Entry<NodeId, BrokerException> failure : described.failures().entrySet()) {
            if (seen.contains(failure.getKey())) {
                continue;
            }
            brokers.add(new Described(
                    failure.getKey().value(),
                    null,
                    failure.getValue().info().metadata().get("kafka.endpoint"),
                    false,
                    failure.getValue().getMessage(),
                    false,
                    id(failure.getKey().value())));
        }
        return new Nodes(rawMode, version, brokers, controllers, voters, leader);
    }

    private static Described described(NodeDescription node, BrokerException failure) {
        boolean serving = node.conditions().stream()
                .noneMatch(c -> c.type().equals(ConditionTypes.SERVING) && c.status() == ConditionStatus.FALSE);
        String reason = failure != null
                ? failure.getMessage()
                : node.conditions().stream()
                        .filter(c -> c.type().equals(ConditionTypes.SERVING) && c.status() == ConditionStatus.FALSE)
                        .map(Condition::message)
                        .findFirst()
                        .orElse(null);
        return new Described(
                node.nativeId(),
                node.attributes().raw("kafka.rack").map(String::valueOf).orElse(null),
                node.endpoints().stream().map(Endpoint::address).findFirst().orElse(null),
                failure == null && serving,
                reason,
                isTrue(node, "kafka.quorum_leader"),
                id(node.nativeId()));
    }

    private static Long lag(NodeDescription node) {
        return node.attributes()
                .raw("kafka.quorum_lag")
                .map(v -> ((Number) v).longValue())
                .orElse(null);
    }

    private static boolean isTrue(NodeDescription node, String key) {
        return node.attributes()
                .raw(key)
                .map(v -> Boolean.parseBoolean(String.valueOf(v)))
                .orElse(false);
    }

    /** Null when the native id is not a broker number, so its partitions cannot be counted. */
    private static Integer id(String nativeId) {
        try {
            return Integer.valueOf(nativeId);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private record Nodes(
            String rawMode,
            String version,
            List<Described> brokers,
            List<ClusterController> controllers,
            List<QuorumVoter> voters,
            String leader) {}

    private record Described(
            String nativeId,
            String rack,
            String address,
            boolean reachable,
            String reason,
            boolean quorumLeader,
            Integer id) {}

    private record Placement(List<Resource> partitions, boolean read, String reason) {}

    private record Pair(int a, int b) implements Comparable<Pair> {
        @Override
        public int compareTo(Pair other) {
            int byA = Integer.compare(a, other.a);
            return byA != 0 ? byA : Integer.compare(b, other.b);
        }
    }

    private record Derived(
            List<ClusterBroker> brokers,
            List<ClusterController> controllers,
            List<CoReplication> edges,
            int topics,
            int partitions,
            long under,
            long offline,
            long preferred,
            Set<String> attention) {}
}
