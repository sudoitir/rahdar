package io.github.sudoitir.rahdar.platform.broker;

import io.github.sudoitir.rahdar.platform.broker.ClockOffsetRegistry.ClockOffset;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Turns per-node clock readings into a verdict an operator can act on (ADR-0053).
 *
 * <p>Four clocks can be wrong and only three of them can be measured directly. A
 * broker's is read from its Jolokia responses; a producer's and a consumer's
 * arrive as message timestamps. Rahdar's own cannot be measured against itself —
 * so it is inferred by corroboration: if <em>every</em> reachable node in the
 * estate reports an offset of the same sign beyond tolerance, the single common
 * cause is this host, not every broker simultaneously. Some nodes off and others
 * in agreement is the opposite conclusion, and names the nodes.
 */
@Service
@Slf4j
public class ClockOffsetService {

    /** How often node readings are turned into a verdict and persisted. */
    public static final Duration REFRESH_INTERVAL = Duration.ofSeconds(30);

    /** Corroboration needs more than one witness; one skewed node is a skewed node. */
    private static final int MIN_CORROBORATING_NODES = 2;

    public enum Verdict {
        /** Nothing measured yet, or every reading is inside its own error bar. */
        UNKNOWN,
        /** Everything measured agrees with Rahdar, within uncertainty. */
        IN_AGREEMENT,
        /** Some nodes disagree and others do not — the named nodes are wrong. */
        BROKER_SKEWED,
        /** Every node disagrees the same way — the common factor is Rahdar's own host. */
        RAHDAR_SUSPECT
    }

    /** One node's measured disagreement with Rahdar. */
    public record NodeSkew(UUID nodeId, String nodeName, ClockOffset offset) {}

    /**
     * What Rahdar currently believes about the clocks in one cluster.
     *
     * @param skewed the nodes whose offset exceeds both its own uncertainty and the
     *     configured tolerance; empty for {@link Verdict#IN_AGREEMENT}
     */
    public record Assessment(Verdict verdict, List<NodeSkew> skewed, List<NodeSkew> measured, Instant at) {

        public static final Assessment UNKNOWN = new Assessment(Verdict.UNKNOWN, List.of(), List.of(), null);

        /** The largest disagreement, which is the one worth putting in a banner. */
        public Optional<NodeSkew> worst() {
            return skewed.stream()
                    .max((a, b) -> Long.compare(
                            Math.abs(a.offset().offsetMs()), Math.abs(b.offset().offsetMs())));
        }
    }

    private final NodeDirectory nodes;
    private final ClockOffsetRegistry registry;
    private final Clock clock;
    private final long toleranceMs;

    /** Published per refresh so the hot request-reply path never touches the database. */
    private volatile Map<UUID, ClockOffset> byNode = Map.of();

    private volatile Map<UUID, Assessment> byCluster = Map.of();

    private final BrokerTime brokerTime;

    public ClockOffsetService(
            NodeDirectory nodes, ClockOffsetRegistry registry, Clock clock, BrokerProperties properties) {
        this.nodes = nodes;
        this.registry = registry;
        this.clock = clock;
        this.toleranceMs = properties.clockSkewToleranceMs();
        this.brokerTime = new BrokerTime(this::offsetFor);
    }

    /** The normaliser every foreign timestamp passes through. */
    public BrokerTime brokerTime() {
        return brokerTime;
    }

    /**
     * The offset for a node, if one has been measured.
     *
     * <p>The null check is not defensive padding: an observation from the
     * notification path carries no node, and {@code Map.of()} throws on a null key
     * rather than answering "not present".
     */
    public Optional<ClockOffset> offsetFor(UUID nodeId) {
        return nodeId == null ? Optional.empty() : Optional.ofNullable(byNode.get(nodeId));
    }

    public Assessment assessmentFor(UUID clusterId) {
        return byCluster.getOrDefault(clusterId, Assessment.UNKNOWN);
    }

    /** How far apart two clocks must be before Rahdar is willing to call it skew. */
    public long toleranceMs() {
        return toleranceMs;
    }

    /**
     * Join the readings to the nodes that produced them, persist them, and decide.
     *
     * <p>Registered on its own trigger rather than riding the scrape: the readings
     * accumulate as a side effect of every Jolokia call, so this makes no broker
     * request at all and its cadence is unrelated to any node's.
     */
    public void refresh() {
        Map<String, ClockOffset> readings = registry.all();
        if (readings.isEmpty()) {
            byNode = Map.of();
            byCluster = Map.of();
            return;
        }

        Map<UUID, ClockOffset> nextByNode = new HashMap<>();
        Map<UUID, List<NodeSkew>> perCluster = new HashMap<>();

        for (NodeDirectory.KnownNode node : nodes.nodes()) {
            ClockOffset offset = readings.get(node.managementUrl());
            if (offset == null) {
                continue;
            }
            nextByNode.put(node.id(), offset);
            perCluster
                    .computeIfAbsent(node.clusterId(), k -> new ArrayList<>())
                    .add(new NodeSkew(node.id(), node.name(), offset));
        }
        nodes.recordClockOffsets(nextByNode);

        byNode = Map.copyOf(nextByNode);
        byCluster = decide(perCluster);
    }

    /**
     * Rahdar's wall clock stepped, so every reading was taken against a clock that
     * no longer exists. Keeping them would drag the estimate towards a value that
     * was true a moment ago and is not now.
     */
    public void onRahdarClockStepped() {
        registry.invalidate();
        byNode = Map.of();
        byCluster = Map.of();
    }

    private Map<UUID, Assessment> decide(Map<UUID, List<NodeSkew>> perCluster) {
        // Corroboration is estate-wide on purpose: a Rahdar whose clock is wrong is
        // wrong for every cluster it manages, and a single-node cluster could never
        // reach the conclusion on its own evidence.
        List<NodeSkew> everything =
                perCluster.values().stream().flatMap(List::stream).toList();
        boolean rahdarSuspect = rahdarIsTheCommonFactor(everything);

        Map<UUID, Assessment> out = new HashMap<>();
        Instant at = clock.instant();
        perCluster.forEach((clusterId, measured) -> {
            List<NodeSkew> skewed = measured.stream().filter(this::isSkewed).toList();
            out.put(clusterId, new Assessment(verdictFor(skewed, rahdarSuspect), skewed, measured, at));
        });
        return Map.copyOf(out);
    }

    private static Verdict verdictFor(List<NodeSkew> skewed, boolean rahdarSuspect) {
        if (skewed.isEmpty()) {
            return Verdict.IN_AGREEMENT;
        }
        return rahdarSuspect ? Verdict.RAHDAR_SUSPECT : Verdict.BROKER_SKEWED;
    }

    private boolean rahdarIsTheCommonFactor(List<NodeSkew> measured) {
        if (measured.size() < MIN_CORROBORATING_NODES) {
            return false;
        }
        List<NodeSkew> skewed = measured.stream().filter(this::isSkewed).toList();
        if (skewed.size() != measured.size()) {
            return false; // some node agrees with Rahdar, so Rahdar is not the odd one out
        }
        long first = skewed.getFirst().offset().offsetMs();
        return skewed.stream().allMatch(s -> Long.signum(s.offset().offsetMs()) == Long.signum(first));
    }

    private boolean isSkewed(NodeSkew skew) {
        ClockOffset offset = skew.offset();
        return offset.isMeaningful() && Math.abs(offset.offsetMs()) > toleranceMs;
    }
}
