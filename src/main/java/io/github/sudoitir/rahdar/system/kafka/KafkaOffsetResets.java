package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation.Seek;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.KafkaOffsets.Plan;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetResult;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resets a group's offsets (ADR-0216): against the preview's state key, through the approval gate, audited with the
 * offsets before and after. The key is checked when asked and again when the operation runs, so a reset approved after
 * the group moved is refused as stale.
 */
@Service
@RequiredArgsConstructor
public class KafkaOffsetResets {

    /** An audit row keeps every partition's offset up to this many, and a summary per topic beyond it. */
    static final int SUMMARISE_ABOVE = 200;

    /** The parameters of {@code subscription.seek}; the etag pins the state the operator confirmed. */
    public record OffsetResetParams(
            UUID clusterId, String group, List<String> topics, String to, String value, String etag) {}

    private final KafkaOffsets offsets;
    private final BrokerGateway gateway;
    private final OperationGate gate;
    private final AuditService audit;
    private final ActorResolver actors;

    public OffsetResult reset(UUID clusterId, String group, OffsetRequest request) {
        Plan plan = offsets.plan(clusterId, group, request);
        KafkaEtags.require(request.etag(), plan.view().etag());
        refuseIfBlocked(plan);
        return gated(new OffsetResetParams(
                clusterId, group, request.topics(), request.to(), request.value(), request.etag()));
    }

    /** What the gate pins: the state key now, and how many partitions the reset moves. */
    public Effect estimate(OffsetResetParams p) {
        Plan plan = offsets.plan(p.clusterId(), p.group(), request(p));
        long moving = plan.view().partitions().stream()
                .filter(r -> r.refused() == null)
                .count();
        return new Effect(moving, "partitions", plan.view().etag(), null);
    }

    @Gated("subscription.seek")
    private OffsetResult gated(OffsetResetParams p) {
        return gate.run(Operation.of(p), () -> run(p));
    }

    public void replay(OffsetResetParams p) {
        run(p);
    }

    private OffsetResult run(OffsetResetParams p) {
        Plan plan = offsets.plan(p.clusterId(), p.group(), request(p));
        KafkaEtags.require(p.etag(), plan.view().etag());
        refuseIfBlocked(plan);
        Map<String, Object> params = Map.of(
                "to", p.to(),
                "value", p.value() == null ? "" : p.value(),
                "before", moves(plan, true),
                "planned", moves(plan, false));
        AuditEvent event = audit.begin(
                actors.resolve(), "RESET_GROUP_OFFSETS", "SUBSCRIPTION", p.group(), p.clusterId(), null, params, false);
        try {
            ClusterConnection connection = gateway.cluster(p.clusterId());
            List<String> moved = new ArrayList<>();
            List<String> pending = new ArrayList<>(plan.positions().keySet());
            for (var topic : plan.positions().entrySet()) {
                try {
                    KafkaFailures.call(() -> connection
                            .operations()
                            .execute(
                                    plan.ids().get(topic.getKey()),
                                    new Seek(topic.getValue()),
                                    Scope.whole(),
                                    Precondition.ifMatch(plan.etags().get(topic.getKey())))
                            .orThrow());
                } catch (RuntimeException e) {
                    if (moved.isEmpty()) {
                        throw e;
                    }
                    return partial(event, plan, moved, pending, e);
                }
                moved.add(topic.getKey());
                pending.remove(topic.getKey());
            }
            long partitions = partitionsOf(plan, moved);
            audit.finish(
                    event, false, partitions, null, Map.of("before", moves(plan, true), "after", moves(plan, false)));
            return new OffsetResult(plan.view().partitions(), partitions, List.copyOf(moved), List.of(), null);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    /**
     * A reset that stopped after some topics moved: those stay moved, so the audit row records what moved and what did
     * not, as a failure, and the answer names both with the cause instead of hiding the topics that did move.
     */
    private OffsetResult partial(
            AuditEvent event, Plan plan, List<String> moved, List<String> pending, RuntimeException cause) {
        String why = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        long partitions = partitionsOf(plan, moved);
        audit.finish(
                event,
                true,
                partitions,
                why,
                Map.of(
                        "movedTopics",
                        List.copyOf(moved),
                        "notMovedTopics",
                        List.copyOf(pending),
                        "before",
                        moves(plan, true, moved),
                        "after",
                        moves(plan, false, moved)));
        return new OffsetResult(plan.view().partitions(), partitions, List.copyOf(moved), List.copyOf(pending), why);
    }

    private static long partitionsOf(Plan plan, List<String> topics) {
        return plan.view().partitions().stream()
                .filter(r -> r.refused() == null && topics.contains(r.topic()))
                .count();
    }

    private static void refuseIfBlocked(Plan plan) {
        if (plan.view().blocked() != null) {
            throw new ConflictException("not-empty", plan.view().blocked());
        }
        List<String> refused = plan.view().partitions().stream()
                .map(OffsetRow::refused)
                .filter(r -> r != null)
                .toList();
        if (!refused.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", refused));
        }
    }

    /** "topic/partition" to offset, as they are (before) or will be (after), for the audit row. */
    private static Object moves(Plan plan, boolean before) {
        return recorded(offsets(plan, before, null));
    }

    /** The same, for the named topics only. */
    private static Object moves(Plan plan, boolean before, List<String> topics) {
        return recorded(offsets(plan, before, topics));
    }

    /**
     * The offsets as the audit row keeps them: every partition up to {@link #SUMMARISE_ABOVE}, and above that a count and
     * the lowest and highest offset per topic, marked {@code summarised}, so a row stays small at thousands of
     * partitions. The row's {@code to} and {@code value} still say where the reset aimed.
     */
    static Object recorded(Map<String, Long> offsets) {
        if (offsets.size() <= SUMMARISE_ABOVE) {
            return offsets;
        }
        Map<String, long[]> stats = new TreeMap<>();
        offsets.forEach((key, offset) -> {
            long[] t = stats.computeIfAbsent(
                    key.substring(0, key.lastIndexOf('/')), topic -> new long[] {0, Long.MAX_VALUE, Long.MIN_VALUE});
            t[0]++;
            t[1] = Math.min(t[1], offset);
            t[2] = Math.max(t[2], offset);
        });
        Map<String, Object> perTopic = new TreeMap<>();
        stats.forEach((topic, t) -> perTopic.put(topic, Map.of("partitions", t[0], "min", t[1], "max", t[2])));
        return Map.of("summarised", true, "topics", perTopic);
    }

    private static Map<String, Long> offsets(Plan plan, boolean before, List<String> topics) {
        Map<String, Long> out = new TreeMap<>();
        for (OffsetRow r : plan.view().partitions()) {
            if (topics != null && !topics.contains(r.topic())) {
                continue;
            }
            Long value = before ? r.current() : r.next();
            if (value != null) {
                out.put(r.topic() + "/" + r.partition(), value);
            }
        }
        return out;
    }

    private static OffsetRequest request(OffsetResetParams p) {
        return new OffsetRequest(p.topics(), p.to(), p.value(), p.etag());
    }
}
