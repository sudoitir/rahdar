package io.github.sudoitir.rahdar.system.kafka.mcp;

import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.system.kafka.KafkaAcls;
import io.github.sudoitir.rahdar.system.kafka.KafkaGroups;
import io.github.sudoitir.rahdar.system.kafka.KafkaHangingTransactions;
import io.github.sudoitir.rahdar.system.kafka.KafkaOffsets;
import io.github.sudoitir.rahdar.system.kafka.KafkaPartitionReads;
import io.github.sudoitir.rahdar.system.kafka.KafkaReplication;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicConfigs;
import io.github.sudoitir.rahdar.system.kafka.KafkaTransactions;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.PartitionSelection;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaReplicationViews.ReplicaMoveRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.HangingPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.TransactionPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.TransactionRow;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The read tools of a Kafka cluster (ADR-0216): the services the REST layer calls, so the permission checks are
 * theirs. They are kept apart from the mutating tools so that a read never carries a destructive hint (ADR-0045).
 */
@Component
@RequiredArgsConstructor
public class KafkaMcpReadTools {

    private static final String CLUSTER_ID = "clusterId";

    private static final String TOPIC = "topic";

    private final KafkaGroups groups;
    private final KafkaOffsets offsets;
    private final KafkaTopicConfigs configs;
    private final KafkaAcls acls;
    private final KafkaTransactions transactions;
    private final KafkaHangingTransactions hanging;
    private final KafkaPartitionReads partitions;
    private final KafkaReplication replication;

    @McpTool(
            name = "kafka_list_groups",
            description = "A page of a Kafka cluster's consumer and share groups with their state and lag.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaListGroups(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String type,
            @McpToolParam(required = false) String state,
            @McpToolParam(required = false) String q,
            @McpToolParam(required = false) String cursor,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        return McpErrors.guard(() -> groups.list(id, type, state, q, cursor, limit));
    }

    @McpTool(
            name = "kafka_describe_group",
            description =
                    "One group's members and, per topic, its total lag and slowest partitions; topic pages one topic.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaDescribeGroup(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String group,
            @McpToolParam(required = false) String topic,
            @McpToolParam(required = false) String cursor) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required("group", group);
        return McpErrors.guard(() -> KafkaMcpViews.group(groups.detail(id, group), topic, cursor, null));
    }

    @McpTool(
            name = "kafka_preview_offset_reset",
            description =
                    "What moving a group's offsets would do, per topic, and the etag a reset needs; topic pages one.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaPreviewOffsetReset(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String group,
            @McpToolParam(required = true) String to,
            @McpToolParam(required = false) String value,
            @McpToolParam(required = false) String topics,
            @McpToolParam(required = false) String topic,
            @McpToolParam(required = false) String cursor) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required("group", group);
        McpArgs.required("to", to);
        return McpErrors.guard(() -> KafkaMcpViews.reset(
                offsets.preview(id, group, new OffsetRequest(topicList(topics), to, value, null)),
                topic,
                cursor,
                null));
    }

    @McpTool(
            name = "kafka_topic_config",
            description = "A topic's overridden settings (all with onlyOverridden=false, or named in keys).",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaTopicConfig(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String topic,
            @McpToolParam(required = false) Boolean onlyOverridden,
            @McpToolParam(required = false) String keys) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required("topic", topic);
        return McpErrors.guard(() -> KafkaMcpViews.config(configs.read(id, topic), onlyOverridden, keys));
    }

    @McpTool(
            name = "kafka_list_acls",
            description = "A page of a Kafka cluster's ACLs, filtered by principal or resource.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaListAcls(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String principal,
            @McpToolParam(required = false) String resource,
            @McpToolParam(required = false) String cursor,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        return McpErrors.guard(() -> acls.list(id, principal, resource, cursor, limit));
    }

    @McpTool(
            name = "kafka_list_transactions",
            description = "Open transactional ids of a Kafka cluster; with hangingOnly, its hanging transactions.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaListTransactions(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) Boolean hangingOnly,
            @McpToolParam(required = false) String topic,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        boolean find = McpArgs.flag(hangingOnly, false);
        return McpErrors.guard(() -> {
            TransactionPage open = transactions.list(id, null, find ? Integer.valueOf(500) : limit);
            if (!find) {
                return new TransactionListing(
                        open,
                        null,
                        "These are the open transactions the coordinator knows. Set hangingOnly to find the hanging"
                                + " ones: it reads every partition, so it is asked for.");
            }
            List<TransactionRow> missed =
                    open.items().stream().filter(TransactionRow::hanging).toList();
            return new TransactionListing(
                    new TransactionPage(missed, null),
                    hanging.find(id, topic, null, limit),
                    "open: transactions the coordinator has missed (still open past their timeout and cleanup);"
                            + " hanging: open on a partition and unknown to the coordinator. Abort either with"
                            + " kafka_abort_transaction.");
        });
    }

    @McpTool(
            name = "kafka_list_partitions",
            description = "One page of a topic's partitions: leader, replica order, sync and bytes.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaListPartitions(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String topic,
            @McpToolParam(required = false) String cursor,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required(TOPIC, topic);
        return McpErrors.guard(() -> partitions.partitions(id, topic, cursor, limit));
    }

    @McpTool(
            name = "kafka_list_replication",
            description = "Partitions that are under-replicated, offline, or not on the preferred leader.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaListReplication(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) String topic,
            @McpToolParam(required = false) String cursor,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        return McpErrors.guard(() -> partitions.replication(id, topic, cursor, limit));
    }

    @McpTool(
            name = "kafka_suggest_replicas",
            description = "A rack suggestion that keeps the current replicas. It moves nothing.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaSuggestReplicas(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String topic,
            @McpToolParam(required = true) Integer partition) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required(TOPIC, topic);
        if (partition == null) {
            throw McpErrors.invalidParams("partition is required.");
        }
        return McpErrors.guard(() -> partitions.suggest(id, topic, partition));
    }

    @McpTool(
            name = "kafka_preview_preferred_election",
            description = "What a preferred-leader election would change. Sends nothing.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaPreviewPreferredElection(
            @McpToolParam(required = true) String clusterId, @McpToolParam(required = true) String partitions) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        return KafkaMcpActionTools.guard(() -> replication.previewElection(
                id, new PartitionSelection(KafkaMcpActionTools.selection(partitions), null)));
    }

    @McpTool(
            name = "kafka_preview_reassign",
            description = "What a throttled replica move would copy. Sends nothing without a positive throttle.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaPreviewReassign(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = true) String topic,
            @McpToolParam(required = true) Integer partition,
            @McpToolParam(required = true) String replicas,
            @McpToolParam(required = true) Long throttleBytesPerSecond) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        McpArgs.required(TOPIC, topic);
        if (partition == null) {
            throw McpErrors.invalidParams("partition is required.");
        }
        return KafkaMcpActionTools.guard(() -> replication.previewReassign(
                id,
                topic,
                partition,
                new ReplicaMoveRequest(KafkaMcpActionTools.brokers(replicas), throttleBytesPerSecond, null)));
    }

    @McpTool(
            name = "kafka_list_reassignments",
            description = "In-progress replica moves, and which of them this product started.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaListReassignments(@McpToolParam(required = true) String clusterId) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        return McpErrors.guard(() -> replication.reassignments(id));
    }

    @McpTool(
            name = "kafka_preview_cancel_reassignment",
            description = "The replicas a cancel would leave. Sends nothing.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaPreviewCancelReassignment(
            @McpToolParam(required = true) String clusterId, @McpToolParam(required = true) String partitions) {
        UUID id = McpArgs.uuid(CLUSTER_ID, clusterId);
        return KafkaMcpActionTools.guard(() ->
                replication.previewCancel(id, new PartitionSelection(KafkaMcpActionTools.selection(partitions), null)));
    }

    /** What the transactions tool answers: the coordinator's open transactions, and the hanging ones when asked for. */
    public record TransactionListing(TransactionPage open, HangingPage hanging, String note) {}

    /**
     * The topics a comma-separated argument names. An empty list is every topic of a group, and no setting to remove.
     */
    static List<String> topicList(String topics) {
        if (topics == null || topics.isBlank()) {
            return List.of();
        }
        return Arrays.stream(topics.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
    }
}
