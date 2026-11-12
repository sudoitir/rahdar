package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.rahdar.feature.resources.LifecycleRequests.CreateDestinationRequest;
import io.github.sudoitir.rahdar.feature.resources.ReadAsOneResource;
import io.github.sudoitir.rahdar.kernel.settings.SettingsService;
import io.github.sudoitir.rahdar.platform.broker.Attempt;
import io.github.sudoitir.rahdar.platform.broker.BrokerSettings;
import io.github.sudoitir.rahdar.platform.broker.IdentityBasis;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeOutcome;
import io.github.sudoitir.rahdar.platform.clusters.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.rahdar.platform.clusters.ResourceTargets.Target;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.ApplyRequest;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.CreateRequest;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.PreviewView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The shared destination and subscription routes for a Kafka topic or group. They call the same lifecycle as the
 * Kafka routes, so a generic delete cannot apply under a fresh etag or remove a group's members.
 */
@Component
class KafkaReadAsOne implements ReadAsOneResource {

    private static final String STALE = "Preview again and send the etag from that preview.";

    private final KafkaTopicLifecycle topics;
    private final SettingsService settings;

    KafkaReadAsOne(KafkaTopicLifecycle topics, SettingsService settings) {
        this.topics = topics;
        this.settings = settings;
    }

    @Override
    public boolean supports(String kind) {
        return KafkaKinds.TOPIC.toString().equals(kind)
                || KafkaKinds.GROUP.toString().equals(kind);
    }

    @Override
    public Attempt<LifecycleOutcome> createDestination(
            UUID clusterId, CreateDestinationRequest request, boolean dryRun) {
        CreateRequest body = createRequest(request);
        if (dryRun) {
            return preview(topics.previewCreate(clusterId, body), IdentityBasis.CONFIGURATION);
        }
        requireEtag(body.etag());
        topics.create(clusterId, body);
        return applied(IdentityBasis.CONFIGURATION);
    }

    @Override
    public Attempt<LifecycleOutcome> deleteDestination(
            UUID clusterId, Target target, boolean dryRun, boolean override, String etag, String fingerprint) {
        if (dryRun) {
            return preview(topics.previewDelete(clusterId, target.name()), IdentityBasis.INCARNATION);
        }
        requireEtag(etag);
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new KafkaStaleException(STALE);
        }
        topics.deleteTopic(clusterId, target.name(), new ApplyRequest(etag, fingerprint, override));
        return applied(IdentityBasis.INCARNATION);
    }

    @Override
    public Attempt<LifecycleOutcome> deleteSubscription(UUID clusterId, Target target, boolean dryRun, String etag) {
        if (dryRun) {
            return preview(topics.previewDeleteGroup(clusterId, target.name()), IdentityBasis.INCARNATION);
        }
        requireEtag(etag);
        topics.deleteGroup(clusterId, target.name(), new ApplyRequest(etag, null, false));
        return applied(IdentityBasis.INCARNATION);
    }

    private Attempt<LifecycleOutcome> preview(PreviewView preview, IdentityBasis basis) {
        String groups = preview.groups().isEmpty()
                ? "No group has offsets on this topic."
                : "Groups that still have offsets: " + String.join(", ", preview.groups()) + ".";
        String estimate = preview.estimate() == null
                ? "The record count is unavailable."
                : "About " + preview.estimate() + " records (" + preview.accuracy() + ").";
        String words = preview.note() + " " + groups + " " + estimate;
        NodeOutcome node = new NodeOutcome(
                null, KafkaTopicLifecycle.WHOLE_CLUSTER, NodeStatus.WOULD_APPLY, preview.estimate(), words);
        return new Attempt.Ok<>(new LifecycleOutcome(true, preview.cap(), preview.overCap(), List.of(node), basis));
    }

    private Attempt<LifecycleOutcome> applied(IdentityBasis basis) {
        NodeOutcome node = new NodeOutcome(null, KafkaTopicLifecycle.WHOLE_CLUSTER, NodeStatus.APPLIED, null, null);
        return new Attempt.Ok<>(
                new LifecycleOutcome(false, settings.intValue(BrokerSettings.BULK_CAP), false, List.of(node), basis));
    }

    private static void requireEtag(String etag) {
        if (etag == null || etag.isBlank()) {
            throw new KafkaStaleException(STALE);
        }
    }

    private static CreateRequest createRequest(CreateDestinationRequest request) {
        Map<String, Object> attributes = request.attributes();
        if (attributes.containsKey(KafkaKinds.REPLICAS)) {
            throw new IllegalArgumentException("A topic create does not assign replicas.");
        }
        Map<String, String> settings = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            if (key.startsWith(KafkaKinds.CONFIG_PREFIX)) {
                settings.put(key.substring(KafkaKinds.CONFIG_PREFIX.length()), String.valueOf(value));
            } else if (KafkaTopicLifecycle.SETTINGS.contains(key) && value != null) {
                settings.put(key, String.valueOf(value));
            }
        });
        Object etag = attributes.get("etag");
        return new CreateRequest(
                request.name(),
                number(attributes.get(KafkaKinds.PARTITIONS)),
                number(attributes.get(KafkaKinds.REPLICATION_FACTOR)),
                settings,
                etag == null ? null : etag.toString());
    }

    private static Integer number(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
    }
}
