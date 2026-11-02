package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.model.Scope;
import io.github.sudoitir.openbroker.operation.Operation.Update;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
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

/**
 * Changes or removes the dynamic settings of a broker, or of the cluster-wide default (ADR-0216): after the driver
 * previewed it, gated and audited. A setting that only a restart changes, or that holds a secret, is refused by the
 * driver before anything is sent.
 */
@Service
@RequiredArgsConstructor
public class KafkaBrokerConfigChanges {

    /** The parameters of {@code broker.update-config}; {@code broker} is a broker number or {@code default}. */
    public record BrokerConfigParams(
            UUID clusterId, String broker, Map<String, String> set, List<String> remove, String etag) {}

    private final KafkaBrokerConfigs configs;
    private final BrokerGateway gateway;
    private final OperationGate gate;
    private final AuditService audit;
    private final ActorResolver actors;

    public ConfigPreview apply(UUID clusterId, String broker, ConfigChangeRequest request) {
        ConfigPreview preview = configs.preview(clusterId, broker, request);
        KafkaEtags.require(request.etag(), preview.etag());
        return gated(new BrokerConfigParams(clusterId, broker, request.set(), request.remove(), request.etag()));
    }

    public Effect estimate(BrokerConfigParams p) {
        ConfigPreview preview = configs.preview(p.clusterId(), p.broker(), request(p));
        return new Effect(preview.changes().size(), "settings", preview.etag(), null);
    }

    /** The settings an approver is shown: each name and its value. */
    String shown(BrokerConfigParams p) {
        return p.set() == null || p.set().isEmpty() ? "none" : p.set().toString();
    }

    public void replay(BrokerConfigParams p) {
        run(p);
    }

    @Gated("broker.update-config")
    private ConfigPreview gated(BrokerConfigParams p) {
        return gate.run(Operation.of(p), () -> run(p));
    }

    private ConfigPreview run(BrokerConfigParams p) {
        ConfigPreview preview = configs.preview(p.clusterId(), p.broker(), request(p));
        KafkaEtags.require(p.etag(), preview.etag());
        AuditEvent event = audit.begin(
                actors.resolve(),
                "UPDATE_BROKER_CONFIG",
                "NODE",
                p.broker(),
                p.clusterId(),
                null,
                Map.of("changes", preview.changes()),
                false);
        try {
            KafkaFailures.call(() -> gateway.cluster(p.clusterId())
                    .operations()
                    .execute(
                            KafkaBrokerConfigs.id(KafkaBrokerConfigs.driverName(p.broker())),
                            new Update(KafkaBrokerConfigs.patch(request(p))),
                            Scope.whole(),
                            Precondition.ifMatch(preview.etag()))
                    .orThrow());
            audit.finish(event, false, preview.changes().size(), null, Map.of("changes", preview.changes()));
            return preview;
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }

    private static ConfigChangeRequest request(BrokerConfigParams p) {
        return new ConfigChangeRequest(p.set(), p.remove(), p.etag());
    }
}
