package io.github.sudoitir.rahdar.feature.resources;

import io.github.sudoitir.openbroker.BrokerException;
import io.github.sudoitir.openbroker.ErrorReasons;
import io.github.sudoitir.openbroker.Status;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.messaging.Position;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.ResourceClass;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.model.Shard;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.broker.BrokerFailures;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Cutting the oldest records off a destination's log (ADR-0216): the records before an offset in each named
 * partition, or before a time. A log is cut from its start, so a group that has not read up to the cut loses
 * records, and the effect names every such group with the count. A compacted topic is refused with the driver's
 * reason. The run is a gated operation, audited with the log start of each partition before and after.
 */
@Service
@RequiredArgsConstructor
public class TruncateService {

    /** The infix of an attribute change that names a group's unread records lost to the cut, followed by the group. */
    private static final Pattern UNREAD_LOST = Pattern.compile("^[a-z]+\\.unread_lost\\.(.+)$");

    /** The infix of an attribute change that names a partition's log start, followed by the partition. */
    private static final Pattern LOG_START = Pattern.compile("^[a-z]+\\.log_start_offset\\.(\\d+)$");

    private static final String AUDIT_ACTION = "TRUNCATE_DESTINATION";

    /** Where the cut falls: before an offset in each named partition, or before a time. Exactly one is given. */
    public record Cut(Map<Integer, Long> offsets, Instant time) {

        public Cut {
            offsets = offsets == null ? Map.of() : Map.copyOf(offsets);
            if (offsets.isEmpty() == (time == null)) {
                throw new IllegalArgumentException("A cut names offsets by partition, or a time, and not both.");
            }
        }
    }

    /** What a cut removes, or removed: the records, and every group that loses records it has not read. */
    public record TruncateEffect(Long records, List<GroupLoss> groups) {}

    /** The records of a group's that the cut removes before the group has read them. */
    public record GroupLoss(String group, long records) {}

    /** What the gate holds of a truncate. */
    public record TruncateParams(UUID clusterId, String kind, String path, Cut cut) {}

    private final ResourceTargets targets;
    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;
    private final AuditService audit;
    private final ActorResolver actors;
    private final OperationGate gate;

    /** What the cut would do, with no audit row of its own beyond the dry run's; nothing is cut. */
    public TruncateEffect preview(UUID clusterId, String kind, String path, Cut cut) {
        return run(clusterId, kind, path, cut, true);
    }

    /** Cuts the log, passing the approval gate as {@code destination.truncate}. */
    public TruncateEffect truncate(UUID clusterId, String kind, String path, Cut cut, boolean dryRun) {
        if (dryRun) {
            return run(clusterId, kind, path, cut, true);
        }
        ResourceId destination = destination(clusterId, kind, path);
        access.requireResource(
                clusterId, ResourceRef.destination(destination.name()), ResourcePermissions.DESTINATION_TRUNCATE);
        return gatedTruncate(new TruncateParams(clusterId, kind, path, cut));
    }

    @Gated("destination.truncate")
    private TruncateEffect gatedTruncate(TruncateParams p) {
        return gate.run(
                io.github.sudoitir.rahdar.kernel.gate.Operation.of(p),
                () -> run(p.clusterId(), p.kind(), p.path(), p.cut(), false));
    }

    /** The cut's reach for the gate's estimate: what a dry run reports, and the log starts as the state key. */
    public Reach reach(TruncateParams p) {
        ResourceId destination = destination(p.clusterId(), p.kind(), p.path());
        Preview preview = preview(p.clusterId(), destination, p.cut());
        TruncateEffect effect = effectOf(preview);
        return new Reach(effect.records() == null ? 0 : effect.records(), preview.etag());
    }

    /** How many records, and the state they were counted against. */
    public record Reach(long records, String stateKey) {}

    private TruncateEffect run(UUID clusterId, String kind, String path, Cut cut, boolean dryRun) {
        ResourceId destination = destination(clusterId, kind, path);
        access.requireResource(
                clusterId, ResourceRef.destination(destination.name()), ResourcePermissions.DESTINATION_TRUNCATE);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("destination", destination.format());
        params.put(
                "cut", cut.time() != null ? Map.of("time", cut.time().toString()) : Map.of("offsets", cut.offsets()));
        AuditEvent event = audit.begin(
                actors.resolve(), AUDIT_ACTION, "DESTINATION", destination.name(), clusterId, null, params, dryRun);
        try {
            Preview preview = preview(clusterId, destination, cut);
            TruncateEffect effect = effectOf(preview);
            if (dryRun) {
                audit.succeed(event, effect.records() == null ? 0 : effect.records());
                return effect;
            }
            List<Map<String, Object>> starts = logStarts(preview);
            OperationResult result = gateway.cluster(clusterId)
                    .operations()
                    .execute(destination, truncate(cut), Scope.whole(), Precondition.ifMatch(preview.etag()))
                    .orThrow();
            long removed = result.affected().orElse(effect.records() == null ? 0 : effect.records());
            audit.finish(event, false, removed, null, starts);
            return new TruncateEffect(removed, effect.groups());
        } catch (BrokerException e) {
            audit.fail(event, e.getMessage());
            throw refused(e);
        }
    }

    private Preview preview(UUID clusterId, ResourceId destination, Cut cut) {
        try {
            return gateway.cluster(clusterId).operations().preview(destination, truncate(cut), Scope.whole());
        } catch (BrokerException e) {
            throw refused(e);
        }
    }

    private static Operation truncate(Cut cut) {
        if (cut.time() != null) {
            return new Operation.Truncate(new Position.AtTime(cut.time()));
        }
        Map<Shard, Long> offsets = new LinkedHashMap<>();
        cut.offsets()
                .forEach((partition, offset) ->
                        offsets.put(Shard.partition(PartitionId.of(String.valueOf(partition))), offset));
        return new Operation.Truncate(new Position.AtOffsets(offsets));
    }

    /** A cut the driver cannot make (a compacted topic, an offset out of range) is a conflict with its reason. */
    private static RuntimeException refused(BrokerException e) {
        if (e.status() == Status.FAILED_PRECONDITION
                || e.status() == Status.OUT_OF_RANGE
                || ErrorReasons.PRECONDITION_FAILED.equals(e.reason())) {
            return new ConflictException("truncate-refused", e.getMessage());
        }
        return BrokerFailures.translate(e);
    }

    /** The preview as the effect an operator reads: the records removed, and each group's loss, worst first. */
    static TruncateEffect effectOf(Preview preview) {
        Long records =
                switch (preview.estimate().count()) {
                    case Count.Exact(var n) -> n;
                    case Count.Estimate(var n) -> n;
                    case Count.Unavailable(var _) -> null;
                };
        List<GroupLoss> groups = new ArrayList<>();
        preview.diff().forEach(change -> {
            Matcher lost = UNREAD_LOST.matcher(change.key());
            if (lost.matches()) {
                groups.add(new GroupLoss(
                        lost.group(1),
                        change.after()
                                .map(v -> Long.parseLong(String.valueOf(v)))
                                .orElse(0L)));
            }
        });
        groups.sort((a, b) -> Long.compare(b.records(), a.records()));
        return new TruncateEffect(records, groups);
    }

    /** The log start of each partition the cut moves, before and after, by partition. */
    private static List<Map<String, Object>> logStarts(Preview preview) {
        List<Map<String, Object>> starts = new ArrayList<>();
        preview.diff().forEach(change -> {
            Matcher start = LOG_START.matcher(change.key());
            if (start.matches()) {
                starts.add(Map.of(
                        "partition",
                        Integer.parseInt(start.group(1)),
                        "logStartBefore",
                        change.before()
                                .map(v -> Long.parseLong(String.valueOf(v)))
                                .orElse(0L),
                        "logStartAfter",
                        change.after()
                                .map(v -> Long.parseLong(String.valueOf(v)))
                                .orElse(0L)));
            }
        });
        return starts;
    }

    private ResourceId destination(UUID clusterId, String kind, String path) {
        return targets.require(clusterId, kind, path, ResourceClass.DESTINATION).id();
    }
}
