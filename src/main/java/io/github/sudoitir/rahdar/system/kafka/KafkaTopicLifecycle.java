package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.ClusterConnection;
import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.messaging.Count;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Operation;
import io.github.sudoitir.openbroker.operation.Operations;
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
import io.github.sudoitir.rahdar.kernel.security.ResourceRef;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.BulkCapExceededException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Topic create, delete and partition increase, and deletion of an empty group (ADR-0225). The driver's preview is the
 * only preview. Apply checks that preview's etag again, including when an approval is released, and writes one
 * outcome for the cluster.
 */
@Service
@RequiredArgsConstructor
public class KafkaTopicLifecycle {

    /** The one outcome of a Kafka command. There is no per-broker fan-out. */
    static final String WHOLE_CLUSTER = "whole cluster";

    private static final String PARTITIONS = "partitions";

    private static final String DESTINATION = "DESTINATION";

    private static final String STATUS = "status";

    private static final String SCOPE = "scope";

    private static final String APPLIED = "APPLIED";

    /** The topic settings a create may name. Any other key is refused before the broker is asked. */
    static final Set<String> SETTINGS =
            Set.of("cleanup.policy", "retention.ms", "retention.bytes", "max.message.bytes", "min.insync.replicas");

    private final BrokerGateway gateway;
    private final ClusterAccessGuard access;
    private final OperationGate gate;
    private final AuditService audit;
    private final ActorResolver actors;
    private final SettingsService settingsService;

    @Schema(name = "KafkaTopicCreateRequest")
    public record CreateRequest(
            String name, Integer partitions, Integer replicationFactor, Map<String, String> settings, String etag) {}

    /** Not {@code ApplyRequest}: that name is the broker-configuration apply body. */
    @Schema(name = "KafkaTopicApplyRequest")
    public record TopicApplyRequest(String etag, String fingerprint, Boolean override) {}

    @Schema(name = "KafkaPartitionsRequest")
    public record PartitionsRequest(Integer partitions, String etag) {}

    @Schema(name = "KafkaTopicPreview", requiredMode = Schema.RequiredMode.REQUIRED)
    public record PreviewView(
            String name,
            String etag,
            String fingerprint,
            String note,
            List<String> groups,
            Long estimate,
            String accuracy,
            Integer partitions,
            Integer replicationFactor,
            Integer currentPartitions,
            long cap,
            boolean overCap) {}

    @Schema(name = "KafkaClusterOutcome", requiredMode = Schema.RequiredMode.REQUIRED)
    public record ClusterOutcome(String scope, String status) {}

    public record CreateParams(
            UUID clusterId,
            String name,
            Integer partitions,
            Integer replicationFactor,
            Map<String, String> settings,
            String etag) {}

    public record DeleteParams(UUID clusterId, String topic, String etag, String fingerprint, boolean override) {}

    public record PartitionsParams(UUID clusterId, String topic, int partitions, String etag) {}

    public record GroupDeleteParams(UUID clusterId, String group, String etag) {}

    public PreviewView previewCreate(UUID clusterId, CreateRequest request) {
        access.requireCreate(
                clusterId, ResourceRef.destination(request.name()), ResourcePermissions.DESTINATION_CREATE);
        refuseUnknownSettings(request.settings());
        Preview preview = KafkaFailures.call(() -> operations(clusterId)
                .preview(topic(request.name()), new Operation.Create(attributes(request)), Scope.whole()));
        return view(request.name(), preview, request.partitions(), request.replicationFactor());
    }

    public ClusterOutcome create(UUID clusterId, CreateRequest request) {
        PreviewView preview = previewCreate(clusterId, request);
        KafkaEtags.require(request.etag(), preview.etag());
        return gatedCreate(new CreateParams(
                clusterId,
                request.name(),
                request.partitions(),
                request.replicationFactor(),
                request.settings(),
                request.etag()));
    }

    public PreviewView previewDelete(UUID clusterId, String topic) {
        access.requireResource(clusterId, ResourceRef.destination(topic), ResourcePermissions.DESTINATION_DELETE);
        return deleteView(clusterId, topic);
    }

    public ClusterOutcome deleteTopic(UUID clusterId, String topic, TopicApplyRequest request) {
        access.requireResource(clusterId, ResourceRef.destination(topic), ResourcePermissions.DESTINATION_DELETE);
        return gatedDelete(new DeleteParams(
                clusterId, topic, request.etag(), request.fingerprint(), Boolean.TRUE.equals(request.override())));
    }

    public PreviewView previewPartitions(UUID clusterId, String topic, int partitions) {
        access.requireResource(clusterId, ResourceRef.destination(topic), ResourcePermissions.DESTINATION_UPDATE);
        Preview preview = KafkaFailures.call(() -> operations(clusterId)
                .preview(topic(topic), new Operation.Update(partitionPatch(partitions)), Scope.whole()));
        return view(topic, preview, partitions, null);
    }

    public ClusterOutcome increasePartitions(UUID clusterId, String topic, PartitionsRequest request) {
        access.requireResource(clusterId, ResourceRef.destination(topic), ResourcePermissions.DESTINATION_UPDATE);
        return gatedPartitions(new PartitionsParams(clusterId, topic, request.partitions(), request.etag()));
    }

    public PreviewView previewDeleteGroup(UUID clusterId, String group) {
        access.requireResource(clusterId, ResourceRef.subscription(group), ResourcePermissions.SUBSCRIPTION_DELETE);
        Preview preview = KafkaFailures.call(
                () -> operations(clusterId).preview(group(group), Operation.delete(), Scope.whole()));
        return view(group, preview, null, null);
    }

    public ClusterOutcome deleteGroup(UUID clusterId, String group, TopicApplyRequest request) {
        access.requireResource(clusterId, ResourceRef.subscription(group), ResourcePermissions.SUBSCRIPTION_DELETE);
        return gatedGroup(new GroupDeleteParams(clusterId, group, request.etag()));
    }

    public Effect estimateCreate(CreateParams p) {
        PreviewView preview = previewCreate(
                p.clusterId(), new CreateRequest(p.name(), p.partitions(), p.replicationFactor(), p.settings(), null));
        return new Effect(1, "topic", preview.etag(), preview.note());
    }

    public Effect estimateDelete(DeleteParams p) {
        PreviewView preview = deleteView(p.clusterId(), p.topic());
        return new Effect(
                preview.estimate() == null ? 0 : preview.estimate(), "records", preview.etag(), preview.note());
    }

    public Effect estimatePartitions(PartitionsParams p) {
        PreviewView preview = previewPartitions(p.clusterId(), p.topic(), p.partitions());
        return new Effect(p.partitions(), PARTITIONS, preview.etag(), preview.note());
    }

    public Effect estimateGroup(GroupDeleteParams p) {
        PreviewView preview = previewDeleteGroup(p.clusterId(), p.group());
        return new Effect(1, "group", preview.etag(), preview.note());
    }

    public void replayCreate(CreateParams p) {
        runCreate(p);
    }

    public void replayDelete(DeleteParams p) {
        runDelete(p);
    }

    public void replayPartitions(PartitionsParams p) {
        runPartitions(p);
    }

    public void replayGroup(GroupDeleteParams p) {
        runGroup(p);
    }

    @Gated("destination.create")
    private ClusterOutcome gatedCreate(CreateParams p) {
        return gate.run(io.github.sudoitir.rahdar.kernel.gate.Operation.of(p), () -> runCreate(p));
    }

    @Gated("kafka.topic.delete")
    private ClusterOutcome gatedDelete(DeleteParams p) {
        return gate.run(io.github.sudoitir.rahdar.kernel.gate.Operation.of(p), () -> runDelete(p));
    }

    @Gated("destination.increase-partitions")
    private ClusterOutcome gatedPartitions(PartitionsParams p) {
        return gate.run(io.github.sudoitir.rahdar.kernel.gate.Operation.of(p), () -> runPartitions(p));
    }

    @Gated("kafka.group.delete")
    private ClusterOutcome gatedGroup(GroupDeleteParams p) {
        return gate.run(io.github.sudoitir.rahdar.kernel.gate.Operation.of(p), () -> runGroup(p));
    }

    private ClusterOutcome runCreate(CreateParams p) {
        CreateRequest request =
                new CreateRequest(p.name(), p.partitions(), p.replicationFactor(), p.settings(), p.etag());
        PreviewView preview = previewCreate(p.clusterId(), request);
        KafkaEtags.require(p.etag(), preview.etag());
        Map<String, Object> before = Map.of(
                "name",
                p.name(),
                PARTITIONS,
                String.valueOf(p.partitions()),
                "replicationFactor",
                String.valueOf(p.replicationFactor()));
        AuditEvent event = audit.begin(
                actors.resolve(), "CREATE_TOPIC", DESTINATION, p.name(), p.clusterId(), null, before, false);
        KafkaFailures.call(() -> operations(p.clusterId())
                .execute(
                        topic(p.name()),
                        new Operation.Create(attributes(request)),
                        Scope.whole(),
                        Precondition.ifMatch(p.etag()))
                .orThrow());
        audit.finish(event, false, 1, null, Map.of(STATUS, APPLIED, SCOPE, WHOLE_CLUSTER));
        return applied();
    }

    private ClusterOutcome runDelete(DeleteParams p) {
        PreviewView preview = deleteView(p.clusterId(), p.topic());
        KafkaEtags.require(p.etag(), preview.etag());
        if (p.fingerprint() == null || !p.fingerprint().equals(preview.fingerprint())) {
            throw new KafkaStaleException(
                    "The groups or the record estimate changed since the preview. Preview again.");
        }
        long estimate = preview.estimate() == null ? 0 : preview.estimate();
        long cap = settingsService.intValue(BrokerSettings.BULK_CAP);
        if (estimate > cap && !p.override()) {
            throw new BulkCapExceededException(estimate, cap);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("groups", preview.groups());
        params.put("estimate", estimate);
        params.put("accuracy", preview.accuracy());
        params.put(PARTITIONS, preview.currentPartitions());
        params.put("override", p.override());
        AuditEvent event = audit.begin(
                actors.resolve(), "DELETE_TOPIC", DESTINATION, p.topic(), p.clusterId(), null, params, false);
        KafkaFailures.call(() -> operations(p.clusterId())
                .execute(topic(p.topic()), Operation.delete(), Scope.whole(), Precondition.ifMatch(p.etag()))
                .orThrow());
        audit.finish(
                event,
                false,
                estimate,
                null,
                Map.of(STATUS, APPLIED, SCOPE, WHOLE_CLUSTER, "groups", preview.groups()));
        return applied();
    }

    private ClusterOutcome runPartitions(PartitionsParams p) {
        PreviewView preview = previewPartitions(p.clusterId(), p.topic(), p.partitions());
        KafkaEtags.require(p.etag(), preview.etag());
        Map<String, Object> params =
                Map.of("from", String.valueOf(preview.currentPartitions()), "to", String.valueOf(p.partitions()));
        AuditEvent event = audit.begin(
                actors.resolve(), "INCREASE_PARTITIONS", DESTINATION, p.topic(), p.clusterId(), null, params, false);
        KafkaFailures.call(() -> operations(p.clusterId())
                .execute(
                        topic(p.topic()),
                        new Operation.Update(partitionPatch(p.partitions())),
                        Scope.whole(),
                        Precondition.ifMatch(p.etag()))
                .orThrow());
        audit.finish(
                event,
                false,
                p.partitions(),
                null,
                Map.of("from", params.get("from"), "to", params.get("to"), SCOPE, WHOLE_CLUSTER));
        return applied();
    }

    private ClusterOutcome runGroup(GroupDeleteParams p) {
        PreviewView preview = previewDeleteGroup(p.clusterId(), p.group());
        KafkaEtags.require(p.etag(), preview.etag());
        AuditEvent event = audit.begin(
                actors.resolve(),
                "DELETE_GROUP",
                "SUBSCRIPTION",
                p.group(),
                p.clusterId(),
                null,
                Map.of("state", preview.note() == null ? "" : preview.note()),
                false);
        KafkaFailures.call(() -> operations(p.clusterId())
                .execute(group(p.group()), Operation.delete(), Scope.whole(), Precondition.ifMatch(p.etag()))
                .orThrow());
        audit.finish(event, false, 1, null, Map.of(STATUS, APPLIED, SCOPE, WHOLE_CLUSTER));
        return applied();
    }

    private PreviewView deleteView(UUID clusterId, String topic) {
        Preview preview = KafkaFailures.call(
                () -> operations(clusterId).preview(topic(topic), Operation.delete(), Scope.whole()));
        return view(topic, preview, null, null);
    }

    private PreviewView view(String name, Preview preview, Integer partitions, Integer replicationFactor) {
        List<String> groups = new ArrayList<>();
        String note = "";
        Long current = null;
        for (AttributeChange change : preview.diff()) {
            if (change.key().startsWith(KafkaKinds.OFFSETS_KEPT)) {
                groups.add(change.key().substring(KafkaKinds.OFFSETS_KEPT.length()));
            } else if (KafkaKinds.NOTE.equals(change.key())) {
                note = String.valueOf(change.after().orElse(""));
            } else if (KafkaKinds.PARTITIONS.equals(change.key())
                    && change.before().isPresent()) {
                current = Long.parseLong(String.valueOf(change.before().get()));
            }
        }
        groups.sort(String::compareTo);
        Long estimate = count(preview);
        String accuracy =
                switch (preview.estimate().count()) {
                    case Count.Exact(var _) -> "exact";
                    case Count.Estimate(var _) -> "estimate";
                    case Count.Unavailable(var _) -> "unavailable";
                };
        long cap = settingsService.intValue(BrokerSettings.BULK_CAP);
        boolean over = estimate != null && estimate > cap;
        return new PreviewView(
                name,
                preview.etag(),
                KafkaEtags.of(fingerprintParts(groups, estimate)),
                note,
                List.copyOf(groups),
                estimate,
                accuracy,
                partitions,
                replicationFactor,
                current == null ? null : current.intValue(),
                cap,
                over);
    }

    private static List<String> fingerprintParts(List<String> groups, Long estimate) {
        List<String> parts = new ArrayList<>(new TreeSet<>(groups));
        parts.add(estimate == null ? "" : Long.toString(estimate));
        return parts;
    }

    private static Long count(Preview preview) {
        return switch (preview.estimate().count()) {
            case Count.Exact(var n) -> n;
            case Count.Estimate(var n) -> n;
            case Count.Unavailable(var _) -> null;
        };
    }

    private static void refuseUnknownSettings(Map<String, String> settings) {
        if (settings == null) {
            return;
        }
        for (String key : settings.keySet()) {
            if (!SETTINGS.contains(key)) {
                throw new IllegalArgumentException("A topic has no setting " + key + ".");
            }
        }
    }

    private static Attributes attributes(CreateRequest request) {
        Attributes.Builder builder = Attributes.builder();
        if (request.partitions() != null) {
            builder.putRaw(KafkaKinds.PARTITIONS, request.partitions().longValue());
        }
        if (request.replicationFactor() != null) {
            builder.putRaw(
                    KafkaKinds.REPLICATION_FACTOR, request.replicationFactor().longValue());
        }
        if (request.settings() != null) {
            request.settings().forEach((key, value) -> builder.putRaw(KafkaKinds.CONFIG_PREFIX + key, value));
        }
        return builder.build();
    }

    private static Attributes partitionPatch(int partitions) {
        return Attributes.builder()
                .putRaw(KafkaKinds.PARTITIONS, (long) partitions)
                .build();
    }

    private Operations operations(UUID clusterId) {
        ClusterConnection connection = gateway.cluster(clusterId);
        return connection.operations();
    }

    private static ResourceId topic(String name) {
        return new ResourceId(KafkaKinds.TOPIC, ResourcePath.of(name));
    }

    private static ResourceId group(String name) {
        return new ResourceId(KafkaKinds.GROUP, ResourcePath.of(name));
    }

    private static ClusterOutcome applied() {
        return new ClusterOutcome(WHOLE_CLUSTER, APPLIED);
    }
}
