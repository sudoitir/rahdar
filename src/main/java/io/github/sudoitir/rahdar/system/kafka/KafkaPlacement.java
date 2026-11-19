package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.NodeDescription;
import io.github.sudoitir.openbroker.model.NodeFunction;
import io.github.sudoitir.openbroker.model.Placement;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.PartitionRow;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReplicaOnBroker;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Placement facts that do not ask the broker: replica order is the cluster's order, the preferred leader is the first
 * replica, and a rack suggestion keeps every current replica (ADR-0230).
 */
final class KafkaPlacement {

    static final int DEFAULT_LIMIT = 100;

    static final int MAX_LIMIT = 500;

    /** The operation option a reassignment must carry, in bytes per second. Not a resource attribute. */
    static final String THROTTLE_OPTION = "replication.throttle.bytes_per_second";

    static final String THROTTLE_REMOVED = "this throttle is removed when the move finishes";

    static final String THROTTLE_RESTORED = "the existing throttle is put back";

    static final String SUGGESTION = "suggestion";

    static final String SUGGESTION_NOTE =
            "This is a suggestion. Only the replicas you confirm are sent, and nothing has been changed.";

    static final String MIN_IN_SYNC = "min.insync.replicas";

    /** Throttle settings a move writes and a finished move puts back. */
    static final List<String> THROTTLE_SETTINGS = List.of(
            "leader.replication.throttled.rate",
            "follower.replication.throttled.rate",
            "leader.replication.throttled.replicas",
            "follower.replication.throttled.replicas");

    private static final String RACK = "kafka.rack";

    private KafkaPlacement() {}

    /** A broker and its rack. A blank rack is a broker the suggestion does not place for. */
    record Rack(int broker, String rack) {}

    /** True only when the leader is the first replica. The replica list is not reordered to make this true. */
    static boolean preferred(Integer leader, List<Integer> replicas) {
        return leader != null && !replicas.isEmpty() && leader.equals(replicas.getFirst());
    }

    /**
     * Replicas that are in the replica list and not in the ISR. When the leader is absent every replica is offline,
     * even one the ISR still names.
     */
    static List<Integer> offline(Integer leader, List<Integer> replicas, List<Integer> inSync) {
        List<Integer> out = new ArrayList<>();
        for (int replica : replicas) {
            if (leader == null || !contains(inSync, replica)) {
                out.add(replica);
            }
        }
        return List.copyOf(out);
    }

    /** Under-replicated, an offline replica, or not led by the first replica. A healthy partition is none of these. */
    static boolean needsAttention(Integer leader, List<Integer> replicas, List<Integer> inSync) {
        return !preferred(leader, replicas)
                || !offline(leader, replicas, inSync).isEmpty();
    }

    /**
     * Keeps {@code current} in order and appends one broker for each rack no current replica already stands in. The
     * broker is the lowest id of that rack. Brokers without a rack are not a rack to fill.
     */
    static List<Integer> suggest(List<Integer> current, List<Rack> brokers) {
        LinkedHashSet<Integer> kept = new LinkedHashSet<>(current);
        Map<Integer, String> rackOf = new TreeMap<>();
        for (Rack broker : brokers) {
            if (broker.rack() != null && !broker.rack().isBlank()) {
                rackOf.putIfAbsent(broker.broker(), broker.rack());
            }
        }
        Set<String> represented = new LinkedHashSet<>();
        for (int id : current) {
            String rack = rackOf.get(id);
            if (rack != null) {
                represented.add(rack);
            }
        }
        Map<String, Integer> pick = new TreeMap<>();
        for (Rack broker : brokers) {
            if (broker.rack() == null
                    || broker.rack().isBlank()
                    || represented.contains(broker.rack())
                    || kept.contains(broker.broker())) {
                continue;
            }
            pick.merge(broker.rack(), broker.broker(), Math::min);
        }
        List<Integer> out = new ArrayList<>(kept);
        out.addAll(pick.values());
        return List.copyOf(out);
    }

    static List<Rack> racks(List<NodeDescription> nodes) {
        List<Rack> out = new ArrayList<>();
        for (NodeDescription node : nodes) {
            Rack rack = rack(node);
            if (rack != null) {
                out.add(rack);
            }
        }
        return out;
    }

    private static Rack rack(NodeDescription node) {
        if (!node.functions().contains(NodeFunction.BROKER)) {
            return null;
        }
        Integer id = brokerId(node.nativeId());
        if (id == null) {
            return null;
        }
        String rack = node.attributes()
                .raw(RACK)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .orElse(null);
        return new Rack(id, rack);
    }

    static PartitionRow row(Resource resource) {
        Integer leader = integer(resource.attributes().raw(KafkaKinds.LEADER).orElse(null));
        List<Integer> replicas =
                ints(resource.attributes().raw(KafkaKinds.REPLICAS).orElse(null));
        List<Integer> inSync =
                ints(resource.attributes().raw(KafkaKinds.IN_SYNC_REPLICAS).orElse(null));
        List<Integer> eligible =
                resource.attributes().raw(KafkaKinds.ELIGIBLE_LEADER_REPLICAS).isPresent()
                        ? ints(resource.attributes()
                                .raw(KafkaKinds.ELIGIBLE_LEADER_REPLICAS)
                                .get())
                        : null;
        return new PartitionRow(
                topic(resource),
                partition(resource),
                leader,
                replicas,
                inSync,
                offline(leader, replicas, inSync),
                longOrNull(
                        resource.attributes().raw(KafkaKinds.LOG_START_OFFSET).orElse(null)),
                longOrNull(resource.attributes().raw(KafkaKinds.LOG_END_OFFSET).orElse(null)),
                longOrNull(resource.attributes().raw(KafkaKinds.SIZE_BYTES).orElse(null)),
                onBroker(replicas, resource.placements()),
                preferred(leader, replicas),
                eligible,
                longOrNull(resource.attributes()
                        .raw(KafkaKinds.LOCAL_LOG_START_OFFSET)
                        .orElse(null)),
                longOrNull(resource.attributes().raw(KafkaKinds.TIERED_OFFSET).orElse(null)),
                text(resource, KafkaKinds.TIERED_OFFSET_REASON),
                text(resource, KafkaKinds.LOG_DIRS_UNAVAILABLE));
    }

    static boolean needsAttention(Resource resource) {
        Integer leader = integer(resource.attributes().raw(KafkaKinds.LEADER).orElse(null));
        return needsAttention(
                leader,
                ints(resource.attributes().raw(KafkaKinds.REPLICAS).orElse(null)),
                ints(resource.attributes().raw(KafkaKinds.IN_SYNC_REPLICAS).orElse(null)));
    }

    static String topic(Resource resource) {
        return resource.id().path().segments().getFirst();
    }

    static int partition(Resource resource) {
        return Integer.parseInt(resource.id().path().segments().get(1));
    }

    static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.clamp(limit, 1, MAX_LIMIT);
    }

    static int cursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor);
            if (offset < 0) {
                throw new NumberFormatException(cursor);
            }
            return offset;
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException("Not a cursor: " + cursor);
        }
    }

    static List<Integer> ints(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (Object item : list) {
            Integer value = integer(item);
            if (value != null) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    static Integer integer(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException _) {
                return null;
            }
        }
        return null;
    }

    /** A count the broker reported. Absent stays absent: an unknown size is not zero. */
    static Long longOrNull(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private static List<ReplicaOnBroker> onBroker(List<Integer> replicas, List<Placement> placements) {
        List<ReplicaOnBroker> out = new ArrayList<>();
        for (int broker : replicas) {
            Placement placement = placements.stream()
                    .filter(item -> brokerId(item.node().value()) != null
                            && brokerId(item.node().value()) == broker)
                    .findFirst()
                    .orElse(null);
            Long bytes = null;
            Long lag = null;
            boolean future = false;
            if (placement != null) {
                bytes = longOrNull(placement
                        .attributes()
                        .raw(KafkaKinds.REPLICA_SIZE_BYTES)
                        .orElse(null));
                lag = longOrNull(
                        placement.attributes().raw(KafkaKinds.OFFSET_LAG).orElse(null));
                future = future(placement);
            }
            out.add(new ReplicaOnBroker(broker, bytes, lag, future));
        }
        return List.copyOf(out);
    }

    private static String text(Resource resource, String key) {
        return resource.attributes()
                .raw(key)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .orElse(null);
    }

    private static boolean contains(List<Integer> values, int wanted) {
        for (int value : values) {
            if (value == wanted) {
                return true;
            }
        }
        return false;
    }

    private static boolean future(Placement placement) {
        Object raw = placement.attributes().raw(KafkaKinds.FUTURE_REPLICA).orElse(null);
        return Boolean.TRUE.equals(raw) || "true".equals(String.valueOf(raw));
    }

    private static Integer brokerId(String nativeId) {
        if (nativeId == null) {
            return null;
        }
        try {
            return Integer.valueOf(nativeId);
        } catch (NumberFormatException _) {
            return null;
        }
    }
}
