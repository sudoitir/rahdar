package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.kafka.schema.CompatibilityLevel;
import io.github.sudoitir.openbroker.kafka.schema.SchemaType;
import io.github.sudoitir.openbroker.kafka.schema.SubjectVersion;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.OperationGate;
import io.github.sudoitir.rahdar.kernel.security.ActorResolver;
import io.github.sudoitir.rahdar.kernel.security.ClusterAccessGuard;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Preview and apply for a schema registration, a compatibility change and a subject delete. A preview writes nothing.
 * Apply checks that preview's etag again, including when an approval is released.
 */
@Service
@RequiredArgsConstructor
public class KafkaSchemaReviews {

    /** The sentence every preview carries, so the copy cannot drift. */
    public static final String NOTHING_WRITTEN = "Nothing was written.";

    private static final String ALREADY = "Already registered";
    private static final String CHECK_ON_APPLY = "The compatibility check happens on apply.";
    private static final String CANNOT_RESTORE = "The schemas cannot be restored.";
    private static final String SCHEMA = "SCHEMA";
    private static final String APPLIED = "APPLIED";

    private final KafkaSchemaRegistry registries;
    private final ClusterAccessGuard access;
    private final OperationGate gate;
    private final AuditService audit;
    private final ActorResolver actors;

    @Schema(name = "SchemaPreview", requiredMode = Schema.RequiredMode.REQUIRED)
    public record SchemaPreview(
            String etag,
            @Schema(nullable = true) String subject,
            String action,
            List<String> before,
            List<String> after,
            @Schema(nullable = true) String compatibilityOutcome,
            boolean checkOnApply,
            boolean alreadyRegistered,
            @Schema(nullable = true) Integer existingVersionId,
            @Schema(nullable = true) String note,
            String statement,
            boolean writesNothing) {

        public SchemaPreview {
            statement = NOTHING_WRITTEN;
            writesNothing = true;
        }
    }

    @Schema(name = "SchemaVersionView", requiredMode = Schema.RequiredMode.REQUIRED)
    public record VersionView(int id, int version, String type, String schema, boolean deleted) {}

    /** One registry write. The action says which. No credential is a field. */
    public record SchemaWriteParams(
            UUID clusterId,
            String action,
            String subject,
            String schemaType,
            String schema,
            String level,
            Boolean permanent,
            String etag) {}

    public SchemaPreview previewRegister(UUID clusterId, String subject, SchemaType type, String schema) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        Preview preview = registries.call(clusterId, catalog -> catalog.previewRegister(subject, type, schema));
        return of(subject, "register", preview);
    }

    public VersionView register(UUID clusterId, String subject, SchemaType type, String schema, String etag) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        return gated(
                new SchemaWriteParams(clusterId, "register", subject, type.name(), schema, null, null, etag),
                () -> runRegister(clusterId, subject, type, schema, etag));
    }

    public SchemaPreview previewCompatibility(UUID clusterId, String subject, CompatibilityLevel level) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        Preview preview = registries.call(clusterId, catalog -> catalog.previewCompatibility(subject, level));
        return of(subject, "compatibility", preview);
    }

    public CompatibilityLevel setCompatibility(UUID clusterId, String subject, CompatibilityLevel level, String etag) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        return gated(
                new SchemaWriteParams(clusterId, "compatibility", subject, null, null, level.name(), null, etag),
                () -> runCompatibility(clusterId, subject, level, etag));
    }

    public SchemaPreview previewDefault(UUID clusterId, CompatibilityLevel level) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        Preview preview = registries.call(clusterId, catalog -> catalog.previewCompatibilityDefault(level));
        return of(null, "default-compatibility", preview);
    }

    public CompatibilityLevel setDefault(UUID clusterId, CompatibilityLevel level, String etag) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        return gated(
                new SchemaWriteParams(clusterId, "default-compatibility", null, null, null, level.name(), null, etag),
                () -> runDefault(clusterId, level, etag));
    }

    public SchemaPreview previewDelete(UUID clusterId, String subject, boolean permanent) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        Preview preview = registries.call(clusterId, catalog -> catalog.previewDelete(subject, permanent));
        SchemaPreview view = of(subject, permanent ? "delete-permanent" : "delete", preview);
        if (permanent && (view.note() == null || !view.note().contains(CANNOT_RESTORE))) {
            return withNote(view, CANNOT_RESTORE);
        }
        return view;
    }

    public void delete(UUID clusterId, String subject, boolean permanent, String etag) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        gated(new SchemaWriteParams(clusterId, "delete", subject, null, null, null, permanent, etag), () -> {
            runDelete(clusterId, subject, permanent, etag);
            return null;
        });
    }

    public Effect estimate(SchemaWriteParams p) {
        SchemaPreview preview = previewOf(p);
        return new Effect(preview.alreadyRegistered() ? 0 : 1, "schema", preview.etag(), preview.note());
    }

    public void replay(SchemaWriteParams p) {
        switch (p.action()) {
            case "register" ->
                runRegister(p.clusterId(), p.subject(), SchemaType.valueOf(p.schemaType()), p.schema(), p.etag());
            case "compatibility" ->
                runCompatibility(p.clusterId(), p.subject(), CompatibilityLevel.valueOf(p.level()), p.etag());
            case "default-compatibility" -> runDefault(p.clusterId(), CompatibilityLevel.valueOf(p.level()), p.etag());
            case "delete" -> runDelete(p.clusterId(), p.subject(), Boolean.TRUE.equals(p.permanent()), p.etag());
            default -> throw new IllegalArgumentException("Unknown schema action.");
        }
    }

    @Gated("schema.write")
    private <T> T gated(SchemaWriteParams params, Supplier<T> run) {
        return gate.run(io.github.sudoitir.rahdar.kernel.gate.Operation.of(params), run);
    }

    private SchemaPreview previewOf(SchemaWriteParams p) {
        return switch (p.action()) {
            case "register" ->
                previewRegister(p.clusterId(), p.subject(), SchemaType.valueOf(p.schemaType()), p.schema());
            case "compatibility" ->
                previewCompatibility(p.clusterId(), p.subject(), CompatibilityLevel.valueOf(p.level()));
            case "default-compatibility" -> previewDefault(p.clusterId(), CompatibilityLevel.valueOf(p.level()));
            case "delete" -> previewDelete(p.clusterId(), p.subject(), Boolean.TRUE.equals(p.permanent()));
            default -> throw new IllegalArgumentException("Unknown schema action.");
        };
    }

    private VersionView runRegister(UUID clusterId, String subject, SchemaType type, String schema, String etag) {
        SchemaPreview preview = previewRegister(clusterId, subject, type, schema);
        KafkaEtags.require(etag, preview.etag());
        Map<String, Object> before = before(subject, preview);
        AuditEvent event =
                audit.begin(actors.resolve(), "REGISTER_SCHEMA", SCHEMA, subject, clusterId, null, before, false);
        SubjectVersion version = registries.call(
                clusterId, catalog -> catalog.register(subject, type, schema, Precondition.ifMatch(etag)));
        audit.finish(event, false, preview.alreadyRegistered() ? 0 : 1, null, after(version));
        return new VersionView(
                version.id(), version.version(), version.type().name(), version.schema(), version.deleted());
    }

    private CompatibilityLevel runCompatibility(UUID clusterId, String subject, CompatibilityLevel level, String etag) {
        SchemaPreview preview = previewCompatibility(clusterId, subject, level);
        KafkaEtags.require(etag, preview.etag());
        AuditEvent event = audit.begin(
                actors.resolve(),
                "SET_SCHEMA_COMPATIBILITY",
                SCHEMA,
                subject,
                clusterId,
                null,
                before(subject, preview),
                false);
        CompatibilityLevel applied = registries.call(
                clusterId, catalog -> catalog.setCompatibility(subject, level, Precondition.ifMatch(etag)));
        audit.finish(event, false, 1, null, Map.of("compatibility", applied.name(), "status", APPLIED));
        return applied;
    }

    private CompatibilityLevel runDefault(UUID clusterId, CompatibilityLevel level, String etag) {
        SchemaPreview preview = previewDefault(clusterId, level);
        KafkaEtags.require(etag, preview.etag());
        AuditEvent event = audit.begin(
                actors.resolve(),
                "SET_SCHEMA_COMPATIBILITY_DEFAULT",
                SCHEMA,
                "default",
                clusterId,
                null,
                before("default", preview),
                false);
        CompatibilityLevel applied = registries.call(
                clusterId, catalog -> catalog.setCompatibilityDefault(level, Precondition.ifMatch(etag)));
        audit.finish(event, false, 1, null, Map.of("compatibility", applied.name(), "status", APPLIED));
        return applied;
    }

    private void runDelete(UUID clusterId, String subject, boolean permanent, String etag) {
        SchemaPreview preview = previewDelete(clusterId, subject, permanent);
        KafkaEtags.require(etag, preview.etag());
        AuditEvent event = audit.begin(
                actors.resolve(),
                permanent ? "DELETE_SCHEMA_PERMANENT" : "DELETE_SCHEMA",
                SCHEMA,
                subject,
                clusterId,
                null,
                before(subject, preview),
                false);
        registries.call(clusterId, catalog -> {
            catalog.delete(subject, permanent, Precondition.ifMatch(etag));
            return null;
        });
        audit.finish(event, false, 1, null, Map.of("permanent", permanent, "status", APPLIED));
    }

    private static Map<String, Object> before(String subject, SchemaPreview preview) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("subject", subject);
        before.put("before", preview.before());
        before.put("note", preview.note());
        return before;
    }

    private static Map<String, Object> after(SubjectVersion version) {
        return Map.of("id", version.id(), "version", version.version(), "status", APPLIED);
    }

    static SchemaPreview of(String subject, String action, Preview preview) {
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        String note = null;
        Integer existing = null;
        String outcome = null;
        for (AttributeChange change : preview.diff()) {
            String was = change.before().map(Object::toString).orElse(null);
            String now = change.after().map(Object::toString).orElse(null);
            if ("note".equals(change.key())) {
                note = now;
            } else if ("schema.id".equals(change.key()) && now != null) {
                existing = Integer.valueOf(now);
            } else if ("compatibility".equals(change.key())) {
                outcome = now;
                add(before, "compatibility", was);
                add(after, "compatibility", now);
            } else {
                add(before, change.key(), was);
                add(after, change.key(), now);
            }
        }
        boolean already = note != null && note.startsWith(ALREADY);
        boolean checkOnApply = note != null && note.contains(CHECK_ON_APPLY);
        if (outcome == null && note != null && (note.contains("compatible") || note.contains("incompatible"))) {
            outcome = note;
        }
        return new SchemaPreview(
                preview.etag(),
                subject,
                action,
                before,
                after,
                outcome,
                checkOnApply,
                already,
                existing,
                note,
                NOTHING_WRITTEN,
                true);
    }

    private static SchemaPreview withNote(SchemaPreview view, String note) {
        return new SchemaPreview(
                view.etag(),
                view.subject(),
                view.action(),
                view.before(),
                view.after(),
                view.compatibilityOutcome(),
                view.checkOnApply(),
                view.alreadyRegistered(),
                view.existingVersionId(),
                note,
                NOTHING_WRITTEN,
                true);
    }

    private static void add(List<String> lines, String key, String value) {
        if (value != null) {
            lines.add(key + "=" + value);
        }
    }

    /** Exposed for the binding preview, which uses the same shape. */
    static SchemaPreview binding(String topic, String side, String before, String after, String etag, String note) {
        return new SchemaPreview(
                etag,
                topic + "-" + side,
                "binding",
                before == null ? List.of() : List.of("serde=" + before),
                List.of("serde=" + after),
                null,
                false,
                false,
                null,
                note,
                NOTHING_WRITTEN,
                true);
    }
}
