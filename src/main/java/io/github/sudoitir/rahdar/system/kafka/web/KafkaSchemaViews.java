package io.github.sudoitir.rahdar.system.kafka.web;

import io.github.sudoitir.openbroker.kafka.schema.CompatibilityLevel;
import io.github.sudoitir.openbroker.kafka.schema.SchemaType;
import io.github.sudoitir.openbroker.kafka.schema.Subject;
import io.github.sudoitir.openbroker.kafka.schema.SubjectVersion;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings.BindingView;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews.VersionView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The schema registry's request and read shapes. A credential is never one of them. */
public final class KafkaSchemaViews {

    private KafkaSchemaViews() {}

    @Schema(name = "SchemaSubjectPage", requiredMode = Schema.RequiredMode.REQUIRED)
    public record SubjectPage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<SubjectRow> items,

            @Schema(nullable = true, description = "The cursor of the next page; absent on the last.")
            String next) {}

    @Schema(name = "SchemaSubjectRow", requiredMode = Schema.RequiredMode.REQUIRED)
    public record SubjectRow(
            String name,
            @Schema(nullable = true) String compatibility,
            @Schema(nullable = true) String compatibilityNote,
            int versions) {}

    @Schema(name = "SchemaSubject", requiredMode = Schema.RequiredMode.REQUIRED)
    public record SubjectView(
            String name,
            @Schema(nullable = true) String type,
            List<VersionView> versions,
            @Schema(nullable = true) String compatibility,
            @Schema(nullable = true) String compatibilityNote,
            @Schema(nullable = true) String schemaText) {}

    @Schema(name = "SchemaRegisterRequest")
    public record RegisterRequest(
            String subject,
            String type,
            String schema,
            @Schema(nullable = true) String etag) {}

    @Schema(name = "SchemaCompatibilityRequest")
    public record CompatibilityRequest(
            String level, @Schema(nullable = true) String etag) {}

    @Schema(name = "SchemaDeleteRequest")
    public record DeleteRequest(Boolean permanent) {}

    @Schema(name = "SchemaBindingPage", requiredMode = Schema.RequiredMode.REQUIRED)
    public record BindingPage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<BindingView> items) {}

    @Schema(name = "SchemaBindingRequest")
    public record BindingRequest(
            String topic,
            String side,
            String serde,
            @Schema(nullable = true) String etag) {}

    public static SubjectRow row(Subject subject) {
        return new SubjectRow(
                subject.name(),
                subject.compatibility().map(Enum::name).orElse(null),
                subject.compatibilityNote().orElse(null),
                subject.versions().size());
    }

    public static SubjectView subject(Subject subject) {
        SubjectVersion latest =
                subject.versions().isEmpty() ? null : subject.versions().getLast();
        return new SubjectView(
                subject.name(),
                latest == null ? null : latest.type().name(),
                subject.versions().stream()
                        .map(version -> new VersionView(
                                version.id(),
                                version.version(),
                                version.type().name(),
                                version.schema(),
                                version.deleted()))
                        .toList(),
                subject.compatibility().map(Enum::name).orElse(null),
                subject.compatibilityNote().orElse(null),
                latest == null ? null : latest.schema());
    }

    public static SchemaType type(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("type must be one of AVRO, PROTOBUF, JSON.");
        }
        try {
            return SchemaType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type must be one of AVRO, PROTOBUF, JSON.");
        }
    }

    public static CompatibilityLevel level(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("level is required.");
        }
        try {
            return CompatibilityLevel.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "level must be one of BACKWARD, BACKWARD_TRANSITIVE, FORWARD, FORWARD_TRANSITIVE, FULL, FULL_TRANSITIVE, NONE.");
        }
    }

    public static String etag(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.strip();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
