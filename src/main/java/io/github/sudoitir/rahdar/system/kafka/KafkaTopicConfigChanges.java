package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.kafka.admin.KafkaKinds;
import io.github.sudoitir.openbroker.model.Attributes;
import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.openbroker.model.ResourcePath;
import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation.Update;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.platform.broker.BrokerGateway;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigChangeRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigPreview;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Changes or removes a topic's overrides (ADR-0216): after the broker validated the preview, gated and audited. */
@Service
@RequiredArgsConstructor
public class KafkaTopicConfigChanges {

    /** The parameters of {@code destination.update-config}; the etag pins the settings the operator saw. */
    public record ConfigChangeParams(
            UUID clusterId, String topic, Map<String, String> set, List<String> remove, String etag) {}

    private final KafkaTopicConfigs configs;
    private final BrokerGateway gateway;
    private final OperationGate gate;
    private final AuditService audit;
    private final ActorResolver actors;

    public ConfigPreview apply(UUID clusterId, String topic, ConfigChangeRequest request) {
        ConfigPreview preview = configs.preview(clusterId, topic, request);
        KafkaEtags.require(request.etag(), preview.etag());
        refuseIfInvalid(preview);
        return gated(new ConfigChangeParams(clusterId, topic, request.set(), request.remove(), request.etag()));
    }

    public Effect estimate(ConfigChangeParams p) {
        ConfigPreview preview = configs.preview(p.clusterId(), p.topic(), request(p));
        return new Effect(preview.changes().size(), "settings", preview.etag(), null);
    }

    public void replay(ConfigChangeParams p) {
        run(p);
    }

    @Gated("destination.update-config")
    private ConfigPreview gated(ConfigChangeParams p) {
        return gate.run(Operation.of(p), () -> run(p));
    }

    private ConfigPreview run(ConfigChangeParams p) {
        ConfigPreview preview = configs.preview(p.clusterId(), p.topic(), request(p));
        KafkaEtags.require(p.etag(), preview.etag());
        refuseIfInvalid(preview);
        AuditEvent event = audit.begin(
                actors.resolve(),
                "UPDATE_TOPIC_CONFIG",
                "DESTINATION",
                p.topic(),
                p.clusterId(),
                null,
                Map.of("changes", preview.changes()),
                false);
        try {
            KafkaFailures.call(() -> gateway.cluster(p.clusterId())
                    .operations()
                    .execute(
                            new ResourceId(KafkaKinds.TOPIC, ResourcePath.of(p.topic())),
                            new Update(Attributes.of(KafkaTopicConfigs.patch(request(p)))),
                            Scope.whole())
                    .orThrow());
            audit.finish(event, false, preview.changes().size(), null, Map.of("changes", preview.changes()));
            return preview;
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    private static void refuseIfInvalid(ConfigPreview preview) {
        if (!preview.valid()) {
            throw new ConflictException("invalid-config", "The broker refused a setting; nothing was applied.");
        }
    }

    private static ConfigChangeRequest request(ConfigChangeParams p) {
        return new ConfigChangeRequest(p.set(), p.remove(), p.etag());
    }
}
