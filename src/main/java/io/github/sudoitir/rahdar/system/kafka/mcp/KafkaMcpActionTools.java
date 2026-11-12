package io.github.sudoitir.rahdar.system.kafka.mcp;

import io.github.sudoitir.openbroker.model.ResourceId;
import io.github.sudoitir.rahdar.kernel.core.ConflictException;
import io.github.sudoitir.rahdar.kernel.core.StalePreviewException;
import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.system.kafka.KafkaHangingAborts;
import io.github.sudoitir.rahdar.system.kafka.KafkaHangingTransactions;
import io.github.sudoitir.rahdar.system.kafka.KafkaOffsetResets;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicConfigChanges;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicConfigs;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.ApplyRequest;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.CreateRequest;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicLifecycle.PartitionsRequest;
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
    private final KafkaHangingTransactions hanging;
    private final KafkaHangingAborts hangingAborts;
    private final KafkaTopicLifecycle lifecycle;

    /** The id of a hanging transaction that kafka_list_transactions returns. */
    private static final String HANGING_PREFIX = "kafka.hanging-transaction/";

    private static final String CLUSTER_ID = "clusterId";

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
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
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
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
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
                    "Abort a transaction or hanging transaction, fencing its producer. Previews by default; needs the"
                            + " etag.",
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
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required("transactionalId", transactionalId);
        boolean dry = McpArgs.flag(dryRun, true);
        if (!dry) {
            McpArgs.required("etag", etag);
            McpArgs.confirm(transactionalId, confirm);
        }
        if (transactionalId.startsWith(HANGING_PREFIX)) {
            // A hanging transaction found on a partition: its id names the partition and the producer.
            List<String> at = ResourceId.parse(transactionalId).path().segments();
            String topic = at.get(0);
            int partition = Integer.parseInt(at.get(1));
            long producer = Long.parseLong(at.get(2));
            return guard(() -> dry
                    ? hanging.abortPreview(id, topic, partition, producer)
                    : hangingAborts.abort(id, topic, partition, producer, etag));
        }
        return guard(
                () -> dry ? transactions.abortPreview(id, transactionalId) : aborts.abort(id, transactionalId, etag));
    }

    @McpTool(
            name = "kafka_create_topic",
            description = "Create a topic. Previews by default and creates nothing; a real run needs the etag.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaCreateTopic(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String topic,
            @McpToolParam(required = false) Integer partitions,
            @McpToolParam(required = false) Integer replicationFactor,
            @McpToolParam(required = false) String settings,
            @McpToolParam(required = false) String etag,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required("topic", topic);
        boolean dry = McpArgs.flag(dryRun, true);
        CreateRequest request =
                new CreateRequest(topic, partitions, replicationFactor, overrides(settings), dry ? null : etag);
        if (!dry) {
            McpArgs.required("etag", etag);
            McpArgs.confirm(topic, confirm);
        }
        return guard(() -> dry ? lifecycle.previewCreate(id, request) : lifecycle.create(id, request));
    }

    @McpTool(
            name = "kafka_delete_topic",
            description = "Delete a topic. Previews by default, naming groups and the record estimate.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaDeleteTopic(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String topic,
            @McpToolParam(required = false) String etag,
            @McpToolParam(required = false) String fingerprint,
            @McpToolParam(required = false) Boolean override,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required("topic", topic);
        boolean dry = McpArgs.flag(dryRun, true);
        if (!dry) {
            McpArgs.required("etag", etag);
            McpArgs.confirm(topic, confirm);
        }
        return guard(() -> dry
                ? lifecycle.previewDelete(id, topic)
                : lifecycle.deleteTopic(id, topic, new ApplyRequest(etag, fingerprint, McpArgs.flag(override, false))));
    }

    @McpTool(
            name = "kafka_increase_partitions",
            description = "Raise a topic's partition count. Previews by default; a lower count is refused.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaIncreasePartitions(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String topic,
            @McpToolParam(required = true) Integer partitions,
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
        return guard(() -> dry
                ? lifecycle.previewPartitions(id, topic, partitions)
                : lifecycle.increasePartitions(id, topic, new PartitionsRequest(partitions, etag)));
    }

    @McpTool(
            name = "kafka_delete_group",
            description = "Delete an empty group. A group with members is refused and its members stay.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaDeleteGroup(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String group,
            @McpToolParam(required = false) String etag,
            @McpToolParam(required = false) Boolean dryRun,
            @McpToolParam(required = false) String confirm) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required("group", group);
        boolean dry = McpArgs.flag(dryRun, true);
        if (!dry) {
            McpArgs.required("etag", etag);
            McpArgs.confirm(group, confirm);
        }
        return guard(() -> dry
                ? lifecycle.previewDeleteGroup(id, group)
                : lifecycle.deleteGroup(id, group, new ApplyRequest(etag, null, false)));
    }

    /** The settings a JSON object of names and values sets; empty when none. */
    private static Map<String, String> overrides(String set) {
        if (set == null || set.isBlank()) {
            return Map.of();
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
            } catch (StalePreviewException e) {
                throw new ConflictException("kafka-stale", e.getMessage());
            }
        });
    }
}
