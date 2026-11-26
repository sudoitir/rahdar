package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.SchemaPreview;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaSchemaBindingEntity;
import io.github.sudoitir.rahdar.system.kafka.internal.persistence.KafkaSchemaBindingRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Which serde decodes a topic's key or value. The binding is stored here and the name is passed to the driver on each
 * decode. A name is never a class name.
 */
@Service
@RequiredArgsConstructor
public class KafkaSchemaBindings {

    /** The serdes the driver registers. A class name is refused rather than loaded. */
    static final Set<String> SERDES = Set.of("avro", "protobuf", "json");

    private static final String KEY = "key";
    private static final String VALUE = "value";

    private final KafkaSchemaBindingRepository bindings;
    private final ClusterAccessGuard access;
    private final OperationGate gate;
    private final AuditService audit;
    private final ActorResolver actors;

    @Schema(name = "SchemaBinding", requiredMode = Schema.RequiredMode.REQUIRED)
    public record BindingView(String topic, String side, String serde) {}

    public record BindingParams(UUID clusterId, String topic, String side, String serde, String etag) {}

    public List<BindingView> list(UUID clusterId) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_READ);
        return bindings.findByClusterIdOrderByTopicAscSideAsc(clusterId).stream()
                .map(row -> new BindingView(row.getTopic(), row.getSide(), row.getSerde()))
                .toList();
    }

    public SchemaPreview preview(UUID clusterId, String topic, String side, String serde) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_READ);
        require(topic, side, serde);
        String current = current(clusterId, topic, side);
        return KafkaSchemaReviews.binding(topic, side, current, serde, etag(topic, side, current), null);
    }

    public BindingView apply(UUID clusterId, String topic, String side, String serde, String etag) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        return gated(new BindingParams(clusterId, topic, side, serde, etag));
    }

    public Effect estimate(BindingParams p) {
        SchemaPreview preview = preview(p.clusterId(), p.topic(), p.side(), p.serde());
        return new Effect(1, "binding", preview.etag(), preview.note());
    }

    public void replay(BindingParams p) {
        run(p);
    }

    @Gated("schema.binding")
    private BindingView gated(BindingParams params) {
        return gate.run(io.github.sudoitir.rahdar.kernel.gate.Operation.of(params), () -> run(params));
    }

    private BindingView run(BindingParams p) {
        require(p.topic(), p.side(), p.serde());
        String current = current(p.clusterId(), p.topic(), p.side());
        KafkaEtags.require(p.etag(), etag(p.topic(), p.side(), current));
        AuditEvent event = audit.begin(
                actors.resolve(),
                "SET_SCHEMA_BINDING",
                "SCHEMA",
                p.topic() + "-" + p.side(),
                p.clusterId(),
                null,
                Map.of("topic", p.topic(), "side", p.side(), "before", current == null ? "" : current),
                false);
        bindings.findByClusterIdAndTopicAndSide(p.clusterId(), p.topic(), p.side())
                .ifPresentOrElse(
                        row -> row.changeSerde(p.serde()),
                        () -> bindings.save(
                                new KafkaSchemaBindingEntity(p.clusterId(), p.topic(), p.side(), p.serde())));
        audit.finish(event, false, 1, null, Map.of("serde", p.serde(), "status", "APPLIED"));
        return new BindingView(p.topic(), p.side(), p.serde());
    }

    private String current(UUID clusterId, String topic, String side) {
        return bindings.findByClusterIdAndTopicAndSide(clusterId, topic, side)
                .map(KafkaSchemaBindingEntity::getSerde)
                .orElse(null);
    }

    static String etag(String topic, String side, String serde) {
        return KafkaEtags.of(List.of(topic, side, serde == null ? "" : serde));
    }

    static void require(String topic, String side, String serde) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic is required.");
        }
        if (!KEY.equals(side) && !VALUE.equals(side)) {
            throw new IllegalArgumentException("side must be key or value.");
        }
        if (serde == null || serde.isBlank() || serde.indexOf('.') >= 0 || serde.indexOf('/') >= 0) {
            throw new IllegalArgumentException("A serde is a name this installation runs, not a class name.");
        }
        if (!SERDES.contains(serde)) {
            throw new IllegalArgumentException("serde must be one of avro, protobuf, json.");
        }
    }
}
