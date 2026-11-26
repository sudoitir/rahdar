package io.github.sudoitir.rahdar.system.kafka;

import io.github.sudoitir.openbroker.kafka.schema.CompatibilityLevel;
import io.github.sudoitir.openbroker.kafka.schema.SchemaType;
import io.github.sudoitir.openbroker.kafka.schema.SubjectVersion;
import io.github.sudoitir.openbroker.operation.AttributeChange;
import io.github.sudoitir.openbroker.operation.Precondition;
import io.github.sudoitir.openbroker.operation.Preview;
import io.github.sudoitir.rahdar.kernel.audit.AuditEvent;
import io.github.sudoitir.rahdar.kernel.audit.AuditService;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.SecretRedactor;
import io.github.sudoitir.rahdar.kernel.gate.Effect;
import io.github.sudoitir.rahdar.kernel.gate.Gated;
import io.github.sudoitir.rahdar.kernel.gate.Operation;
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
    public static final String NOTHING_WRITTEN = "Nothing has been written.";

    /** A permanent delete is one action: soft-delete, then delete the schema. */
    static final String PERMANENT_DELETE =
            "This soft-deletes the subject and then deletes the schema. The schemas cannot be restored. "
                    + NOTHING_WRITTEN;

    /** The driver's sentence, after "Nothing has been written. ". Match by containment, not prefix. */
    static final String ALREADY = "Already registered; applying creates no second version.";

    private static final String CHECK_ON_APPLY = "The compatibility check happens on apply.";
    private static final String SCHEMA = "SCHEMA";
    private static final String APPLIED = "APPLIED";
    private static final String REGISTER = "register";
    private static final String COMPATIBILITY = "compatibility";
    private static final String DEFAULT_COMPATIBILITY = "default-compatibility";
    private static final String DELETE = "delete";
    private static final String STATUS = "status";

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
        return loadRegister(clusterId, subject, type, schema).view();
    }

    public VersionView register(UUID clusterId, String subject, SchemaType type, String schema, String etag) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        return gated(
                new SchemaWriteParams(clusterId, REGISTER, subject, type.name(), schema, null, null, etag),
                () -> runRegister(clusterId, subject, type, schema, etag));
    }

    public SchemaPreview previewCompatibility(UUID clusterId, String subject, CompatibilityLevel level) {
        return loadCompatibility(clusterId, subject, level).view();
    }

    public CompatibilityLevel setCompatibility(UUID clusterId, String subject, CompatibilityLevel level, String etag) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        return gated(
                new SchemaWriteParams(clusterId, COMPATIBILITY, subject, null, null, level.name(), null, etag),
                () -> runCompatibility(clusterId, subject, level, etag));
    }

    public SchemaPreview previewDefault(UUID clusterId, CompatibilityLevel level) {
        return loadDefault(clusterId, level).view();
    }

    public CompatibilityLevel setDefault(UUID clusterId, CompatibilityLevel level, String etag) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        return gated(
                new SchemaWriteParams(clusterId, DEFAULT_COMPATIBILITY, null, null, null, level.name(), null, etag),
                () -> runDefault(clusterId, level, etag));
    }

    public SchemaPreview previewDelete(UUID clusterId, String subject, boolean permanent) {
        return loadDelete(clusterId, subject, permanent).view();
    }

    public void delete(UUID clusterId, String subject, boolean permanent, String etag) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        gated(new SchemaWriteParams(clusterId, DELETE, subject, null, null, null, permanent, etag), () -> {
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
            case REGISTER ->
                runRegister(p.clusterId(), p.subject(), SchemaType.valueOf(p.schemaType()), p.schema(), p.etag());
            case COMPATIBILITY ->
                runCompatibility(p.clusterId(), p.subject(), CompatibilityLevel.valueOf(p.level()), p.etag());
            case DEFAULT_COMPATIBILITY -> runDefault(p.clusterId(), CompatibilityLevel.valueOf(p.level()), p.etag());
            case DELETE -> runDelete(p.clusterId(), p.subject(), Boolean.TRUE.equals(p.permanent()), p.etag());
            default -> throw new IllegalArgumentException("Unknown schema action.");
        }
    }

    @Gated("schema.write")
    private <T> T gated(SchemaWriteParams params, Supplier<T> run) {
        return gate.run(Operation.of(params), run);
    }

    private SchemaPreview previewOf(SchemaWriteParams p) {
        return switch (p.action()) {
            case REGISTER ->
                previewRegister(p.clusterId(), p.subject(), SchemaType.valueOf(p.schemaType()), p.schema());
            case COMPATIBILITY ->
                previewCompatibility(p.clusterId(), p.subject(), CompatibilityLevel.valueOf(p.level()));
            case DEFAULT_COMPATIBILITY -> previewDefault(p.clusterId(), CompatibilityLevel.valueOf(p.level()));
            case DELETE -> previewDelete(p.clusterId(), p.subject(), Boolean.TRUE.equals(p.permanent()));
            default -> throw new IllegalArgumentException("Unknown schema action.");
        };
    }

    private VersionView runRegister(UUID clusterId, String subject, SchemaType type, String schema, String etag) {
        Issued issued = loadRegister(clusterId, subject, type, schema);
        KafkaEtags.require(etag, issued.view().etag());
        AuditEvent event = audit.begin(
                actors.resolve(),
                "REGISTER_SCHEMA",
                SCHEMA,
                subject,
                clusterId,
                null,
                before(subject, issued.view()),
                false);
        SubjectVersion version = attempt(
                event,
                () -> registries.call(
                        clusterId,
                        catalog -> catalog.register(subject, type, schema, Precondition.ifMatch(issued.driverEtag()))));
        audit.finish(event, false, issued.view().alreadyRegistered() ? 0 : 1, null, after(version));
        return new VersionView(
                version.id(), version.version(), version.type().name(), version.schema(), version.deleted());
    }

    private CompatibilityLevel runCompatibility(UUID clusterId, String subject, CompatibilityLevel level, String etag) {
        Issued issued = loadCompatibility(clusterId, subject, level);
        KafkaEtags.require(etag, issued.view().etag());
        AuditEvent event = audit.begin(
                actors.resolve(),
                "SET_SCHEMA_COMPATIBILITY",
                SCHEMA,
                subject,
                clusterId,
                null,
                before(subject, issued.view()),
                false);
        CompatibilityLevel applied = attempt(
                event,
                () -> registries.call(
                        clusterId,
                        catalog ->
                                catalog.setCompatibility(subject, level, Precondition.ifMatch(issued.driverEtag()))));
        audit.finish(event, false, 1, null, Map.of(COMPATIBILITY, applied.name(), STATUS, APPLIED));
        return applied;
    }

    private CompatibilityLevel runDefault(UUID clusterId, CompatibilityLevel level, String etag) {
        Issued issued = loadDefault(clusterId, level);
        KafkaEtags.require(etag, issued.view().etag());
        AuditEvent event = audit.begin(
                actors.resolve(),
                "SET_SCHEMA_COMPATIBILITY_DEFAULT",
                SCHEMA,
                "default",
                clusterId,
                null,
                before("default", issued.view()),
                false);
        CompatibilityLevel applied = attempt(
                event,
                () -> registries.call(
                        clusterId,
                        catalog -> catalog.setCompatibilityDefault(level, Precondition.ifMatch(issued.driverEtag()))));
        audit.finish(event, false, 1, null, Map.of(COMPATIBILITY, applied.name(), STATUS, APPLIED));
        return applied;
    }

    private void runDelete(UUID clusterId, String subject, boolean permanent, String etag) {
        Issued issued = loadDelete(clusterId, subject, permanent);
        KafkaEtags.require(etag, issued.view().etag());
        AuditEvent event = audit.begin(
                actors.resolve(),
                permanent ? "DELETE_SCHEMA_PERMANENT" : "DELETE_SCHEMA",
                SCHEMA,
                subject,
                clusterId,
                null,
                before(subject, issued.view()),
                false);
        attempt(
                event,
                () -> registries.call(clusterId, catalog -> {
                    catalog.delete(subject, permanent, Precondition.ifMatch(issued.driverEtag()));
                    return null;
                }));
        audit.finish(event, false, 1, null, Map.of("permanent", permanent, STATUS, APPLIED));
    }

    private Issued loadRegister(UUID clusterId, String subject, SchemaType type, String schema) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        Preview driver = registries.call(clusterId, catalog -> catalog.previewRegister(subject, type, schema));
        return issued(subject, REGISTER, REGISTER, driver);
    }

    private Issued loadCompatibility(UUID clusterId, String subject, CompatibilityLevel level) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        Preview driver = registries.call(clusterId, catalog -> catalog.previewCompatibility(subject, level));
        return issued(subject, COMPATIBILITY, COMPATIBILITY, driver);
    }

    private Issued loadDefault(UUID clusterId, CompatibilityLevel level) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        Preview driver = registries.call(clusterId, catalog -> catalog.previewCompatibilityDefault(level));
        return issued(null, DEFAULT_COMPATIBILITY, DEFAULT_COMPATIBILITY, driver);
    }

    private Issued loadDelete(UUID clusterId, String subject, boolean permanent) {
        access.requireCluster(clusterId, KafkaModule.SCHEMA_WRITE);
        Preview driver = registries.call(clusterId, catalog -> catalog.previewDelete(subject, permanent));
        Issued issued = issued(subject, DELETE, permanent ? "permanent" : "soft", driver);
        SchemaPreview view = issued.view();
        if (permanent) {
            view = withNote(view, PERMANENT_DELETE);
        } else if (view.note() == null || !view.note().contains(NOTHING_WRITTEN)) {
            view = withNote(view, NOTHING_WRITTEN);
        }
        return new Issued(view, driver.etag());
    }

    /** The review etag binds the driver's state to the intent, so one preview cannot authorise another. */
    private static Issued issued(String subject, String action, String intent, Preview driver) {
        return new Issued(of(subject, action, driver, intentEtag(driver.etag(), intent)), driver.etag());
    }

    private static String intentEtag(String driverEtag, String intent) {
        return KafkaEtags.of(List.of(driverEtag, intent));
    }

    private <T> T attempt(AuditEvent event, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            close(audit, event, e);
            throw e;
        }
    }

    static void close(AuditService audit, AuditEvent event, RuntimeException failure) {
        String detail = SecretRedactor.redact(failure.getMessage());
        if (failure instanceof ConflictException conflict
                && KafkaSchemaRegistry.DELETE_IN_DOUBT.equals(conflict.slug())) {
            audit.inDoubt(event, detail);
        } else {
            audit.fail(event, detail);
        }
    }

    private record Issued(SchemaPreview view, String driverEtag) {}

    private static Map<String, Object> before(String subject, SchemaPreview preview) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("subject", subject);
        before.put("before", preview.before());
        before.put("note", preview.note());
        return before;
    }

    private static Map<String, Object> after(SubjectVersion version) {
        return Map.of("id", version.id(), "version", version.version(), STATUS, APPLIED);
    }

    static SchemaPreview of(String subject, String action, Preview preview, String reviewEtag) {
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
            } else if (COMPATIBILITY.equals(change.key())) {
                outcome = now;
                add(before, COMPATIBILITY, was);
                add(after, COMPATIBILITY, now);
            } else {
                add(before, change.key(), was);
                add(after, change.key(), now);
            }
        }
        boolean already = note != null && note.contains(ALREADY);
        boolean checkOnApply = note != null && note.contains(CHECK_ON_APPLY);
        if (outcome == null && note != null && (note.contains("compatible") || note.contains("incompatible"))) {
            outcome = note;
        }
        return new SchemaPreview(
                reviewEtag,
                subject,
                action,
                before,
                after,
                outcome,
                checkOnApply,
                already,
                existing,
                SecretRedactor.redact(note),
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
