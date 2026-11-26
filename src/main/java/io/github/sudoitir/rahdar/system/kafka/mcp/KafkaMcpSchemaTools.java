package io.github.sudoitir.rahdar.system.kafka.mcp;

import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaBindings;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaRegistry;
import io.github.sudoitir.rahdar.system.kafka.KafkaSchemaReviews;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaSchemaViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Schema registry tools. They call the same services as the REST layer. A change previews by default; a real run needs
 * the preview's etag and is held by the approval gate.
 */
@Component
@RequiredArgsConstructor
public class KafkaMcpSchemaTools {

    private static final String CLUSTER_ID = "clusterId";

    private final KafkaSchemaRegistry registry;
    private final KafkaSchemaReviews reviews;
    private final KafkaSchemaBindings bindings;

    @McpTool(
            name = "kafka_schema_subjects",
            description = "A page of a Kafka cluster's schema subjects.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaSchemaSubjects(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String cursor,
            @McpToolParam(required = false) String q) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        return McpErrors.guard(() -> {
            var page = registry.subjects(id, cursor, q);
            return new KafkaSchemaViews.SubjectPage(
                    page.items().stream().map(KafkaSchemaViews::row).toList(),
                    page.next().map(c -> c.token()).orElse(null));
        });
    }

    @McpTool(
            name = "kafka_schema_subject",
            description = "One subject's versions and compatibility. A soft-deleted version stays listed.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaSchemaSubject(
            @McpToolParam(required = true) String clusterId, @McpToolParam(required = true) String subject) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required("subject", subject);
        return McpErrors.guard(() -> KafkaSchemaViews.subject(registry.subject(id, subject)));
    }

    @McpTool(
            name = "kafka_register_schema",
            description = "Register a schema under a subject. Previews by default; a real run needs the etag.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaRegisterSchema(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String subject,
            @McpToolParam(required = true) String type,
            @McpToolParam(required = true) String schema,
            @McpToolParam(required = false) String etag,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required("subject", subject);
        boolean dry = McpArgs.flag(dryRun, true);
        if (!dry) {
            McpArgs.required("etag", etag);
            McpArgs.confirm(subject, confirm);
        }
        return KafkaMcpActionTools.guard(() -> dry
                ? reviews.previewRegister(id, subject, KafkaSchemaViews.type(type), schema)
                : reviews.register(id, subject, KafkaSchemaViews.type(type), schema, etag));
    }

    @McpTool(
            name = "kafka_set_schema_compatibility",
            description = "Set a subject's compatibility, or the registry default when subject is omitted.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaSetSchemaCompatibility(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String subject,
            @McpToolParam(required = true) String level,
            @McpToolParam(required = false) String etag,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        boolean dry = McpArgs.flag(dryRun, true);
        boolean registryDefault = subject == null || subject.isBlank();
        if (!dry) {
            McpArgs.required("etag", etag);
            McpArgs.confirm(registryDefault ? "default" : subject, confirm);
        }
        var compatibility = KafkaSchemaViews.level(level);
        return KafkaMcpActionTools.guard(() -> {
            if (registryDefault) {
                return dry ? reviews.previewDefault(id, compatibility) : reviews.setDefault(id, compatibility, etag);
            }
            return dry
                    ? reviews.previewCompatibility(id, subject, compatibility)
                    : reviews.setCompatibility(id, subject, compatibility, etag);
        });
    }

    @McpTool(
            name = "kafka_delete_subject",
            description =
                    "Delete a subject. permanent soft-deletes and then deletes the schema, which cannot be restored.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaDeleteSubject(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String subject,
            @McpToolParam(required = false) Boolean permanent,
            @McpToolParam(required = false) String etag,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required("subject", subject);
        boolean dry = McpArgs.flag(dryRun, true);
        boolean hard = Boolean.TRUE.equals(permanent);
        if (!dry) {
            McpArgs.required("etag", etag);
            McpArgs.confirm(subject, confirm);
        }
        return KafkaMcpActionTools.guard(() -> {
            if (dry) {
                return reviews.previewDelete(id, subject, hard);
            }
            reviews.delete(id, subject, hard, etag);
            return "Deleted " + subject + (hard ? " permanently." : ".");
        });
    }

    @McpTool(
            name = "kafka_set_schema_binding",
            description = "Bind a topic side to a serde this installation runs. A class name is refused.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaSetSchemaBinding(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String topic,
            @McpToolParam(required = true) String side,
            @McpToolParam(required = true) String serde,
            @McpToolParam(required = false) String etag,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required("topic", topic);
        boolean dry = McpArgs.flag(dryRun, true);
        if (!dry) {
            McpArgs.required("etag", etag);
            McpArgs.confirm(topic, confirm);
        }
        return KafkaMcpActionTools.guard(
                () -> dry ? bindings.preview(id, topic, side, serde) : bindings.apply(id, topic, side, serde, etag));
    }
}
