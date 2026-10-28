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
            for (var topic : plan.positions().entrySet()) {
                KafkaFailures.call(() -> connection
                        .operations()
                        .execute(
                                plan.ids().get(topic.getKey()),
                                new Seek(topic.getValue()),
                                Scope.whole(),
                                Precondition.ifMatch(plan.etags().get(topic.getKey())))
                        .orThrow());
            }
            long moved = plan.view().partitions().stream()
                    .filter(r -> r.refused() == null)
                    .count();
            audit.finish(event, false, moved, null, Map.of("before", moves(plan, true), "after", moves(plan, false)));
            return new OffsetResult(plan.view().partitions(), moved);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
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

    /** "topic/partition" to offset, as they are (before) or will be (after). */
    private static Map<String, Long> moves(Plan plan, boolean before) {
        Map<String, Long> out = new TreeMap<>();
        for (OffsetRow r : plan.view().partitions()) {
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
