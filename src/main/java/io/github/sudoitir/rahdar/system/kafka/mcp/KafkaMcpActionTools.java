package io.github.sudoitir.rahdar.system.kafka.mcp;

import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.system.kafka.KafkaOffsetResets;
import io.github.sudoitir.rahdar.system.kafka.KafkaStaleException;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicConfigChanges;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicConfigs;
import io.github.sudoitir.rahdar.system.kafka.KafkaTransactionAborts;
import io.github.sudoitir.rahdar.system.kafka.KafkaTransactions;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigChangeRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRequest;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The mutating tools of a Kafka cluster (ADR-0216). Each calls the service the REST layer calls, so its permission
 * check, its approval gate and its audit row are the existing ones; a change the gate holds is answered as held. This
 * class adds the model-facing gate: a change carries the {@code etag} of the preview it was made from, and
 * {@code confirm} equal to the subject's name.
 */
@Component
@RequiredArgsConstructor
public class KafkaMcpActionTools {

    private final KafkaOffsetResets resets;
    private final KafkaTopicConfigs configs;
    private final KafkaTopicConfigChanges configChanges;
    private final KafkaTransactions transactions;
    private final KafkaTransactionAborts aborts;

    @McpTool(
            name = "kafka_reset_offsets",
            description = "Move an empty group's offsets, from a kafka_preview_offset_reset preview's etag.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            // Skipping records is not undone by the group reading on.
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaResetOffsets(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String group,
            @McpToolParam(required = true) String to,
            @McpToolParam(required = false) String value,
            @McpToolParam(required = false) String topics,
            @McpToolParam(required = true) String etag,
            @McpToolParam(required = true) String confirm) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        McpArgs.required("group", group);
        McpArgs.required("to", to);
        McpArgs.required("etag", etag);
        McpArgs.confirm(group, confirm);
        return guard(
                () -> resets.reset(id, group, new OffsetRequest(KafkaMcpReadTools.topicList(topics), to, value, etag)));
    }

    @McpTool(
            name = "kafka_set_topic_config",
            description = "Set or remove a topic's settings. Previews by default; a real run needs the etag.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            // A shorter retention deletes records the broker already holds.
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaSetTopicConfig(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String topic,
            @McpToolParam(required = false) String set,
            @McpToolParam(required = false) String remove,
            @McpToolParam(required = false) String etag,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        McpArgs.required("topic", topic);
        boolean dry = McpArgs.flag(dryRun, true);
        Map<String, String> overrides = overrides(set);
        List<String> removals = KafkaMcpReadTools.topicList(remove);
        ConfigChangeRequest request = new ConfigChangeRequest(overrides, removals, dry ? null : etag);
        if (!dry) {
            McpArgs.required("etag", etag);
            McpArgs.confirm(topic, confirm);
        }
        return guard(() -> dry ? configs.preview(id, topic, request) : configChanges.apply(id, topic, request));
    }

    @McpTool(
            name = "kafka_abort_transaction",
            description =
                    "Abort a hanging transaction, fencing its producer. Previews by default; a real run needs the etag.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaAbortTransaction(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String transactionalId,
            @McpToolParam(required = false) String etag,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        McpArgs.required("transactionalId", transactionalId);
        boolean dry = McpArgs.flag(dryRun, true);
        if (!dry) {
            McpArgs.required("etag", etag);
            McpArgs.confirm(transactionalId, confirm);
        }
        return guard(
                () -> dry ? transactions.abortPreview(id, transactionalId) : aborts.abort(id, transactionalId, etag));
    }

    /** The settings a JSON object of names and values sets; null when none. */
    private static Map<String, String> overrides(String set) {
        if (set == null || set.isBlank()) {
            return null;
        }
        Map<String, Object> parsed = McpErrors.parse("set", set, Map.class);
        return parsed.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> String.valueOf(e.getValue())));
    }

    /** A preview that went stale is a conflict the model can act on: preview again. */
    private static McpSchema.CallToolResult guard(Supplier<Object> body) {
        return McpErrors.guard(() -> {
            try {
                return body.get();
            } catch (KafkaStaleException e) {
                throw new ConflictException("kafka-stale", e.getMessage());
            }
        });
    }
}
