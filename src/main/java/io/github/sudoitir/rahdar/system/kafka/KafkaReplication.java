package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.NodeId;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.PartitionId;
import io.github.sudoitir.openbroker.model.Resource;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.ResourceQuery;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Election;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.OperationResult;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.feature.resources.ResourcePermissions;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.kernel.security.Permissions;
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaReassignmentEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaReassignmentRepository;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.CancelOutcome;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.CancelPartition;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.CancelPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ElectionOutcome;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ElectionPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.PartitionSelection;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReassignOutcome;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReassignPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReassignmentList;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReassignmentRow;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReplicaMoveRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.SelectedPartition;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Preferred leader election and a throttled replica move (ADR-0230). A preview changes nothing. Apply, including one
 * an approval releases later, checks the preview's etag again and sends the driver's operation with that etag. Unclean
 * election is not a request this service can make.
 */
@Service
@RequiredArgsConstructor
public class KafkaReplication {

    private static final String DESTINATION = "DESTINATION";

    private static final String ALREADY = "Already the preferred leader";

    private static final String DURATION_UNAVAILABLE = "The duration is unavailable";

    private final BrokerGateway gateway;
    private final KafkaPartitionReads reads;
    private final KafkaReassignmentRepository reassignments;
    private final ClusterAccessGuard access;
    private final OperationGate gate;
    private final AuditService audit;
    private final ActorResolver actors;
    private final Clock clock;

    /** One partition an election or a cancel names, in a stable order. */
    public record NamedPartition(String topic, int partition) {}

    public record ElectParams(UUID clusterId, List<NamedPartition> partitions, String etag) {}

    public record ReassignParams(
            UUID clusterId,
            String topic,
            int partition,
            List<Integer> replicas,
            long throttleBytesPerSecond,
            String etag) {}

    public record CancelParams(UUID clusterId, List<NamedPartition> partitions, String etag) {}

    public ElectionPreview previewElection(UUID clusterId, PartitionSelection request) {
        List<NamedPartition> named = named(request == null ? null : request.partitions());
        named.forEach(partition -> requireUpdate(clusterId, partition.topic()));
        return electionPlan(clusterId, named).view();
    }

    public ElectionOutcome elect(UUID clusterId, PartitionSelection request) {
        List<NamedPartition> named = named(request == null ? null : request.partitions());
        named.forEach(partition -> requireUpdate(clusterId, partition.topic()));
        ElectionPreview preview = electionPlan(clusterId, named).view();
        KafkaEtags.require(request.etag(), preview.etag());
        return gatedElect(new ElectParams(clusterId, named, request.etag()));
    }

    public ReassignPreview previewReassign(UUID clusterId, String topic, int partition, ReplicaMoveRequest request) {
        requireUpdate(clusterId, topic);
        List<Integer> replicas = replicas(request);
        long throttle = throttle(request == null ? null : request.throttleBytesPerSecond());
        int minInSync = minInSync(clusterId, topic);
        if (replicas.size() < minInSync) {
            throw new IllegalArgumentException(
                    "Replication factor " + replicas.size() + " is below min.insync.replicas " + minInSync + ".");
        }
        return reassignPlan(clusterId, topic, partition, replicas, throttle, minInSync);
    }

    public ReassignOutcome reassign(UUID clusterId, String topic, int partition, ReplicaMoveRequest request) {
        ReassignPreview preview = previewReassign(clusterId, topic, partition, request);
        KafkaEtags.require(request.etag(), preview.etag());
        return gatedReassign(new ReassignParams(
                clusterId, topic, partition, replicas(request), request.throttleBytesPerSecond(), request.etag()));
    }

    public CancelPreview previewCancel(UUID clusterId, PartitionSelection request) {
        List<NamedPartition> named = named(request == null ? null : request.partitions(), EMPTY_CANCEL);
        named.forEach(partition -> requireUpdate(clusterId, partition.topic()));
        return cancelPlan(clusterId, named).view();
    }

    public CancelOutcome cancel(UUID clusterId, PartitionSelection request) {
        List<NamedPartition> named = named(request == null ? null : request.partitions(), EMPTY_CANCEL);
        named.forEach(partition -> requireUpdate(clusterId, partition.topic()));
        CancelPreview preview = cancelPlan(clusterId, named).view();
        KafkaEtags.require(request.etag(), preview.etag());
        return gatedCancel(new CancelParams(clusterId, named, request.etag()));
    }

    public ReassignmentList reassignments(UUID clusterId) {
        access.requireCluster(clusterId, Permissions.DESTINATION_READ);
        Map<String, KafkaReassignmentEntity> stored = new LinkedHashMap<>();
        reassignments
                .findByClusterId(clusterId)
                .forEach(row -> stored.put(key(row.getTopic(), row.getPartition()), row));
        Map<String, ReassignmentRow> rows = new LinkedHashMap<>();
        for (Resource resource : moving(clusterId)) {
            String topic = KafkaPlacement.topic(resource);
            int partition = KafkaPlacement.partition(resource);
            String id = key(topic, partition);
            rows.put(
                    id,
                    new ReassignmentRow(
                            topic,
                            partition,
                            resource.attributes()
                                    .raw(KafkaKinds.REASSIGNMENT)
                                    .map(String::valueOf)
                                    .orElse(null),
                            stored.containsKey(id)));
        }
        stored.forEach(
                (id, row) -> rows.putIfAbsent(id, new ReassignmentRow(row.getTopic(), row.getPartition(), null, true)));
        return new ReassignmentList(List.copyOf(rows.values()));
    }

    public Effect estimateElect(ElectParams p) {
        ElectionPreview preview = electionPlan(p.clusterId(), p.partitions()).view();
        return new Effect(preview.wouldChange(), "partitions", preview.etag(), preview.note());
    }

    public Effect estimateReassign(ReassignParams p) {
        ReassignPreview preview = previewReassign(
                p.clusterId(),
                p.topic(),
                p.partition(),
                new ReplicaMoveRequest(p.replicas(), p.throttleBytesPerSecond(), p.etag()));
        long count = preview.bytes() == null ? 1 : preview.bytes();
        return new Effect(
                count, preview.bytes() == null ? "partition" : "bytes", preview.etag(), preview.throttleOutcome());
    }

    public void replayElect(ElectParams p) {
        runElect(p);
    }

    public void replayReassign(ReassignParams p) {
        runReassign(p);
    }

    public Effect estimateCancel(CancelParams p) {
        CancelPreview preview = cancelPlan(p.clusterId(), p.partitions()).view();
        return new Effect(preview.partitions().size(), "partitions", preview.etag(), preview.note());
    }

    public void replayCancel(CancelParams p) {
        runCancel(p);
    }

    @Gated("kafka.partition.elect")
    private ElectionOutcome gatedElect(ElectParams p) {
        return gate.run(io.github.sudoitir.rahdar.kernel.gate.Operation.of(p), () -> runElect(p));
    }

    @Gated("kafka.partition.reassign")
    private ReassignOutcome gatedReassign(ReassignParams p) {
        return gate.run(io.github.sudoitir.rahdar.kernel.gate.Operation.of(p), () -> runReassign(p));
    }

    @Gated("kafka.partition.cancel-reassignment")
    private CancelOutcome gatedCancel(CancelParams p) {
        return gate.run(io.github.sudoitir.rahdar.kernel.gate.Operation.of(p), () -> runCancel(p));
    }

    private ElectionOutcome runElect(ElectParams p) {
        ElectionPlan plan = electionPlan(p.clusterId(), p.partitions());
        KafkaEtags.require(p.etag(), plan.view().etag());
        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        plan.parts().forEach(part -> {
            before.put(label(part), part.leaderBefore());
            after.put(label(part), part.leaderAfter());
        });
        AuditEvent event = audit.begin(
                actors.resolve(),
                "ELECT_PREFERRED_LEADERS",
                DESTINATION,
                plan.parts().isEmpty() ? "" : plan.parts().getFirst().topic(),
                p.clusterId(),
                null,
                Map.of("leaders", before),
                false);
        try {
            int elected = 0;
            int already = 0;
            for (ElectionPart part : plan.parts()) {
                OperationResult result = KafkaFailures.call(() -> gateway.cluster(p.clusterId())
                        .operations()
                        .execute(
                                partitionId(part.topic(), part.partition()),
                                new Operation.ElectLeader(Election.PREFERRED),
                                Scope.whole(),
                                Precondition.ifMatch(part.driverEtag()))
                        .orThrow());
                if (result.alreadyInState() || part.already()) {
                    already++;
                } else {
                    elected++;
                }
            }
            audit.finish(
                    event, false, elected, null, Map.of("leaders", after, "scope", KafkaTopicLifecycle.WHOLE_CLUSTER));
            return new ElectionOutcome(KafkaTopicLifecycle.WHOLE_CLUSTER, "APPLIED", elected, already);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    private ReassignOutcome runReassign(ReassignParams p) {
        int minInSync = minInSync(p.clusterId(), p.topic());
        if (p.replicas().size() < minInSync) {
            throw new IllegalArgumentException(
                    "Replication factor " + p.replicas().size() + " is below min.insync.replicas " + minInSync + ".");
        }
        ReassignPreview preview = reassignPlan(
                p.clusterId(), p.topic(), p.partition(), p.replicas(), p.throttleBytesPerSecond(), minInSync);
        KafkaEtags.require(p.etag(), preview.etag());
        Map<String, Object> before =
                Map.of("replicas", preview.currentReplicas(), "throttle", p.throttleBytesPerSecond());
        AuditEvent event = audit.begin(
                actors.resolve(),
                "REASSIGN_REPLICAS",
                DESTINATION,
                p.topic() + "-" + p.partition(),
                p.clusterId(),
                null,
                before,
                false);
        try {
            OperationResult result = KafkaFailures.call(() -> gateway.cluster(p.clusterId())
                    .operations()
                    .execute(
                            partitionId(p.topic(), p.partition()),
                            reassign(p.partition(), p.replicas(), p.throttleBytesPerSecond()),
                            Scope.whole(),
                            Precondition.ifMatch(preview.etag()))
                    .orThrow());
            if (!result.alreadyInState()) {
                remember(p.clusterId(), p.topic(), p.partition(), event.getId(), previousOf(result), addedOf(result));
            }
            audit.finish(
                    event,
                    false,
                    1,
                    null,
                    Map.of(
                            "replicas",
                            preview.replicas(),
                            "throttleOutcome",
                            preview.throttleOutcome(),
                            "scope",
                            KafkaTopicLifecycle.WHOLE_CLUSTER));
            return new ReassignOutcome(KafkaTopicLifecycle.WHOLE_CLUSTER, "APPLIED", preview.throttleOutcome());
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    private ElectionPlan electionPlan(UUID clusterId, List<NamedPartition> partitions) {
        List<ElectionPart> parts = new ArrayList<>();
        for (NamedPartition partition : partitions) {
            Resource resource = reads.find(clusterId, partition.topic(), partition.partition());
            Integer leader = KafkaPlacement.integer(
                    resource.attributes().raw(KafkaKinds.LEADER).orElse(null));
            List<Integer> replicas = KafkaPlacement.ints(
                    resource.attributes().raw(KafkaKinds.REPLICAS).orElse(null));
            Preview preview = KafkaFailures.call(() -> gateway.cluster(clusterId)
                    .operations()
                    .preview(
                            partitionId(partition.topic(), partition.partition()),
                            new Operation.ElectLeader(Election.PREFERRED),
                            Scope.whole()));
            String note = note(preview);
            boolean already = note.startsWith(ALREADY);
            String after = already || replicas.isEmpty() ? text(leader) : Integer.toString(replicas.getFirst());
            parts.add(new ElectionPart(
                    partition.topic(), partition.partition(), preview.etag(), text(leader), after, already, note));
        }
        int wouldChange = (int) parts.stream().filter(part -> !part.already()).count();
        int already = parts.size() - wouldChange;
        List<String> etagParts = parts.stream()
                .map(part -> part.topic() + "\0" + part.partition() + "\0" + part.driverEtag())
                .toList();
        String note = parts.size() == 1
                ? parts.getFirst().note()
                : wouldChange + " partitions would move to their first replica. " + already + " are already preferred.";
        return new ElectionPlan(new ElectionPreview(wouldChange, already, KafkaEtags.of(etagParts), note), parts);
    }

    private ReassignPreview reassignPlan(
            UUID clusterId, String topic, int partition, List<Integer> replicas, long throttle, int minInSync) {
        Resource resource = reads.find(clusterId, topic, partition);
        List<Integer> current = KafkaPlacement.ints(
                resource.attributes().raw(KafkaKinds.REPLICAS).orElse(null));
        Preview preview = KafkaFailures.call(() -> gateway.cluster(clusterId)
                .operations()
                .preview(partitionId(topic, partition), reassign(partition, replicas, throttle), Scope.whole()));
        Count count = preview.estimate().count();
        Long bytes =
                switch (count) {
                    case Count.Exact(var value) -> value;
                    case Count.Estimate(var value) -> value;
                    case Count.Unavailable(var ignored) -> null;
                };
        String bytesUnavailable = count instanceof Count.Unavailable(var reason) ? reason : null;
        String rawNote = note(preview);
        String duration = null;
        String durationUnavailable = null;
        if (rawNote.startsWith(DURATION_UNAVAILABLE)) {
            durationUnavailable =
                    rawNote.substring(DURATION_UNAVAILABLE.length()).replaceFirst("^:\\s*", "");
        } else if (!rawNote.isBlank()) {
            duration = rawNote;
        }
        String throttleOutcome = existingThrottle(clusterId, topic, replicas)
                ? KafkaPlacement.THROTTLE_RESTORED
                : KafkaPlacement.THROTTLE_REMOVED;
        return new ReassignPreview(
                current,
                List.copyOf(replicas),
                current.size(),
                replicas.size(),
                minInSync,
                bytes,
                bytesUnavailable,
                duration,
                durationUnavailable,
                throttleOutcome,
                throttle,
                preview.etag());
    }

    private void remember(
            UUID clusterId,
            String topic,
            int partition,
            Long operationId,
            Map<String, String> previous,
            List<String> added) {
        long auditId = operationId == null ? 0L : operationId;
        Optional<KafkaReassignmentEntity> found =
                reassignments.findByClusterIdAndTopicAndPartition(clusterId, topic, partition);
        if (found.isEmpty()) {
            reassignments.save(new KafkaReassignmentEntity(
                    clusterId, topic, partition, auditId, previous, String.join(",", added), clock.instant()));
            return;
        }
        KafkaReassignmentEntity row = found.get();
        Map<String, String> merged = new LinkedHashMap<>(row.getPreviousThrottle());
        LinkedHashSet<String> keys = new LinkedHashSet<>(split(row.getAddedKeys()));
        previous.forEach((key, value) -> {
            if (!merged.containsKey(key) && !keys.contains(key)) {
                merged.put(key, value);
            }
        });
        keys.addAll(added);
        row.replace(auditId, merged, String.join(",", keys));
        reassignments.save(row);
    }

    private boolean existingThrottle(UUID clusterId, String topic, List<Integer> replicas) {
        if (throttled(topicConfig(clusterId, topic))) {
            return true;
        }
        for (int broker : replicas) {
            if (throttled(brokerConfig(clusterId, broker))) {
                return true;
            }
        }
        return false;
    }

    private Resource topicConfig(UUID clusterId, String topic) {
        return KafkaGroups.read(
                        gateway.cluster(clusterId),
                        ResourceQuery.of(KafkaKinds.TOPIC)
                                .named(KafkaGroups.escape(topic))
                                .project(throttleProjection()))
                .items()
                .stream()
                .filter(resource -> resource.id().name().equals(topic))
                .findFirst()
                .orElse(null);
    }

    private Resource brokerConfig(UUID clusterId, int broker) {
        String name = Integer.toString(broker);
        return KafkaGroups.read(
                        gateway.cluster(clusterId),
                        ResourceQuery.of(KafkaKinds.BROKER_CONFIG).named(name).project(throttleProjection()))
                .items()
                .stream()
                .filter(resource -> resource.id().name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static boolean throttled(Resource resource) {
        if (resource == null) {
            return false;
        }
        for (String setting : KafkaPlacement.THROTTLE_SETTINGS) {
            Object raw = resource.attributes()
                    .raw(KafkaKinds.CONFIG_PREFIX + setting)
                    .orElse(null);
            if (raw != null && !String.valueOf(raw).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> throttleProjection() {
        List<String> keys = new ArrayList<>();
        keys.add(KafkaKinds.CONFIG_PREFIX + KafkaPlacement.MIN_IN_SYNC);
        KafkaPlacement.THROTTLE_SETTINGS.forEach(setting -> keys.add(KafkaKinds.CONFIG_PREFIX + setting));
        return Set.copyOf(keys);
    }

    private int minInSync(UUID clusterId, String topic) {
        Resource resource = topicConfig(clusterId, topic);
        if (resource == null) {
            return 1;
        }
        Object raw = resource.attributes()
                .raw(KafkaKinds.CONFIG_PREFIX + KafkaPlacement.MIN_IN_SYNC)
                .orElse(null);
        Integer value = KafkaPlacement.integer(raw);
        return value == null || value < 1 ? 1 : value;
    }

    private List<Resource> moving(UUID clusterId) {
        List<Resource> out = new ArrayList<>();
        Optional<String> cursor = Optional.empty();
        ResourceQuery query = ResourceQuery.of(KafkaKinds.PARTITION).project(Set.of(KafkaKinds.REASSIGNMENT));
        do {
            var page = KafkaGroups.read(
                    gateway.cluster(clusterId), cursor.map(query::continueFrom).orElse(query));
            page.items().stream()
                    .filter(resource -> resource.attributes()
                            .raw(KafkaKinds.REASSIGNMENT)
                            .map(String::valueOf)
                            .filter(value -> !value.isBlank())
                            .isPresent())
                    .forEach(out::add);
            cursor = page.continueToken();
        } while (cursor.isPresent());
        return out;
    }

    private void requireUpdate(UUID clusterId, String topic) {
        access.requireResource(clusterId, ResourceRef.destination(topic), ResourcePermissions.DESTINATION_UPDATE);
    }

    private static Operation.Reassign reassign(int partition, List<Integer> replicas, long throttle) {
        List<NodeId> nodes =
                replicas.stream().map(id -> NodeId.of(Integer.toString(id))).toList();
        Attributes options = Attributes.builder()
                .putRaw(KafkaPlacement.THROTTLE_OPTION, throttle)
                .build();
        return new Operation.Reassign(Map.of(PartitionId.of(Integer.toString(partition)), nodes), options);
    }

    private static ResourceId partitionId(String topic, int partition) {
        return new ResourceId(KafkaKinds.PARTITION, ResourcePath.of(topic, Integer.toString(partition)));
    }

    private static final String EMPTY_CANCEL =
            "A cancel names one partition. An empty set is refused, and nothing was sent.";

    private CancelPlan cancelPlan(UUID clusterId, List<NamedPartition> partitions) {
        List<CancelPart> parts = new ArrayList<>();
        for (NamedPartition partition : partitions) {
            Preview preview = KafkaFailures.call(() -> gateway.cluster(clusterId)
                    .operations()
                    .preview(
                            partitionId(partition.topic(), partition.partition()),
                            cancel(partition.partition()),
                            Scope.whole()));
            parts.add(new CancelPart(
                    partition.topic(),
                    partition.partition(),
                    preview.etag(),
                    replicas(preview, false),
                    replicas(preview, true)));
        }
        List<String> etagParts = parts.stream()
                .map(part -> part.topic() + "\0" + part.partition() + "\0" + part.driverEtag())
                .toList();
        List<CancelPartition> shown = parts.stream()
                .map(part -> new CancelPartition(part.topic(), part.partition(), part.before(), part.after()))
                .toList();
        return new CancelPlan(
                new CancelPreview(shown, KafkaEtags.of(etagParts), "Cancels the in-progress reassignment."), parts);
    }

    private CancelOutcome runCancel(CancelParams p) {
        CancelPlan plan = cancelPlan(p.clusterId(), p.partitions());
        KafkaEtags.require(p.etag(), plan.view().etag());
        Map<String, List<Integer>> before = new LinkedHashMap<>();
        Map<String, List<Integer>> after = new LinkedHashMap<>();
        plan.parts().forEach(part -> {
            before.put(part.topic() + "-" + part.partition(), part.before());
            after.put(part.topic() + "-" + part.partition(), part.after());
        });
        AuditEvent event = audit.begin(
                actors.resolve(),
                "CANCEL_REASSIGNMENT",
                DESTINATION,
                plan.parts().isEmpty() ? "" : plan.parts().getFirst().topic(),
                p.clusterId(),
                null,
                Map.of("replicas", before),
                false);
        try {
            for (CancelPart part : plan.parts()) {
                KafkaFailures.call(() -> gateway.cluster(p.clusterId())
                        .operations()
                        .execute(
                                partitionId(part.topic(), part.partition()),
                                cancel(part.partition()),
                                Scope.whole(),
                                Precondition.ifMatch(part.driverEtag()))
                        .orThrow());
            }
            audit.finish(
                    event,
                    false,
                    plan.parts().size(),
                    null,
                    Map.of("replicas", after, "scope", KafkaTopicLifecycle.WHOLE_CLUSTER));
            return new CancelOutcome(
                    KafkaTopicLifecycle.WHOLE_CLUSTER, "APPLIED", plan.view().partitions());
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    private static Operation.Reassign cancel(int partition) {
        return new Operation.Reassign(Map.of(PartitionId.of(Integer.toString(partition)), List.of()));
    }

    private static List<Integer> replicas(Preview preview, boolean after) {
        for (AttributeChange change : preview.diff()) {
            if (KafkaKinds.REPLICAS.equals(change.key())) {
                return KafkaPlacement.ints((after ? change.after() : change.before()).orElse(null));
            }
        }
        return List.of();
    }

    private static List<NamedPartition> named(List<SelectedPartition> partitions) {
        return named(
                partitions,
                "A preferred election names one partition. An empty set is refused, and electing every partition is not sent.");
    }

    private static List<NamedPartition> named(List<SelectedPartition> partitions, String empty) {
        if (partitions == null || partitions.isEmpty()) {
            throw new IllegalArgumentException(empty);
        }
        Map<String, NamedPartition> unique = new TreeMap<>();
        for (SelectedPartition partition : partitions) {
            if (partition == null
                    || partition.topic() == null
                    || partition.topic().isBlank()
                    || partition.partition() == null) {
                throw new IllegalArgumentException("A preferred election names a topic and a partition.");
            }
            unique.putIfAbsent(
                    partition.topic() + "\0" + partition.partition(),
                    new NamedPartition(partition.topic(), partition.partition()));
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(NamedPartition::topic).thenComparingInt(NamedPartition::partition))
                .toList();
    }

    private static List<Integer> replicas(ReplicaMoveRequest request) {
        List<Integer> replicas = request == null || request.replicas() == null ? List.of() : request.replicas();
        if (replicas.isEmpty()) {
            throw new IllegalArgumentException(
                    "Name the replicas. Cancelling a move is a different request, and nothing was changed.");
        }
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (Integer replica : replicas) {
            if (replica == null || !seen.add(replica)) {
                throw new IllegalArgumentException(
                        replica == null ? "A replica is a broker number." : "Broker " + replica + " is named twice.");
            }
        }
        return List.copyOf(replicas);
    }

    private static long throttle(Long throttle) {
        if (throttle == null || throttle <= 0) {
            throw new IllegalArgumentException(
                    "A reassignment needs a positive number of bytes per second, so it cannot be confirmed.");
        }
        return throttle;
    }

    private static String note(Preview preview) {
        for (AttributeChange change : preview.diff()) {
            if (KafkaKinds.NOTE.equals(change.key())) {
                return change.after().map(String::valueOf).orElse("");
            }
        }
        return "";
    }

    private static Map<String, String> previousOf(OperationResult result) {
        if (result.resource().isEmpty()) {
            return Map.of();
        }
        Object raw = result.resource()
                .get()
                .attributes()
                .raw(KafkaKinds.THROTTLE_PREVIOUS)
                .orElse(null);
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> out = new TreeMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null) {
                out.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return out;
    }

    private static List<String> addedOf(OperationResult result) {
        if (result.resource().isEmpty()) {
            return List.of();
        }
        return strings(result.resource()
                .get()
                .attributes()
                .raw(KafkaKinds.THROTTLE_ADDED)
                .orElse(null));
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                out.add(String.valueOf(item));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> split(String addedKeys) {
        if (addedKeys == null || addedKeys.isBlank()) {
            return List.of();
        }
        return List.of(addedKeys.split(","));
    }

    private static String text(Integer leader) {
        return leader == null ? "none" : Integer.toString(leader);
    }

    private static String label(ElectionPart part) {
        return part.topic() + "-" + part.partition();
    }

    private static String key(String topic, int partition) {
        return topic + "\0" + partition;
    }

    private record ElectionPart(
            String topic,
            int partition,
            String driverEtag,
            String leaderBefore,
            String leaderAfter,
            boolean already,
            String note) {}

    private record ElectionPlan(ElectionPreview view, List<ElectionPart> parts) {}

    private record CancelPart(
            String topic, int partition, String driverEtag, List<Integer> before, List<Integer> after) {}

    private record CancelPlan(CancelPreview view, List<CancelPart> parts) {}
}
