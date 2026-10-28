package io.github.sudoitir.rahdar.system.kafka.mcp;

import io.github.sudoitir.rahdar.platform.mcp.McpArgs;
import io.github.sudoitir.rahdar.platform.mcp.McpErrors;
import io.github.sudoitir.rahdar.system.kafka.KafkaAcls;
import io.github.sudoitir.rahdar.system.kafka.KafkaGroups;
import io.github.sudoitir.rahdar.system.kafka.KafkaOffsets;
import io.github.sudoitir.rahdar.system.kafka.KafkaTopicConfigs;
import io.github.sudoitir.rahdar.system.kafka.KafkaTransactions;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRequest;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.TransactionPage;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.TransactionRow;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
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

    private final KafkaGroups groups;
    private final KafkaOffsets offsets;
    private final KafkaTopicConfigs configs;
    private final KafkaAcls acls;
    private final KafkaTransactions transactions;

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
        UUID id = McpArgs.uuid("clusterId", clusterId);
        return McpErrors.guard(() -> groups.list(id, type, state, q, cursor, limit));
    }

    @McpTool(
            name = "kafka_describe_group",
            description = "One group's members, assignments, topics and per-partition lag.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaDescribeGroup(
            @McpToolParam(required = true) String clusterId, @McpToolParam(required = true) String group) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        McpArgs.required("group", group);
        return McpErrors.guard(() -> groups.detail(id, group));
    }

    @McpTool(
            name = "kafka_preview_offset_reset",
            description = "What moving a group's offsets would do, per partition, and the etag a reset needs.",
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
            @McpToolParam(required = false) String topics) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        McpArgs.required("group", group);
        McpArgs.required("to", to);
        return McpErrors.guard(() -> offsets.preview(id, group, new OffsetRequest(topicList(topics), to, value, null)));
    }

    @McpTool(
            name = "kafka_topic_config",
            description = "A topic's settings with their source and default, and whether they can be changed.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaTopicConfig(
            @McpToolParam(required = true) String clusterId, @McpToolParam(required = true) String topic) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        McpArgs.required("topic", topic);
        return McpErrors.guard(() -> configs.read(id, topic));
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
        UUID id = McpArgs.uuid("clusterId", clusterId);
        return McpErrors.guard(() -> acls.list(id, principal, resource, cursor, limit));
    }

    @McpTool(
            name = "kafka_list_transactions",
            description =
                    "A page of a Kafka cluster's transactional ids: state, timeout, whether they hang, what they block.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult kafkaListTransactions(
            @McpToolParam(required = true) String clusterId,
            @McpToolParam(required = false) Boolean hangingOnly,
            @McpToolParam(required = false) String cursor,
            @McpToolParam(required = false) Integer limit) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        boolean hanging = McpArgs.flag(hangingOnly, false);
        return McpErrors.guard(() -> {
            if (!hanging) {
                return transactions.list(id, cursor, limit);
            }
            // The hanging ones are few among many: read pages until enough are found, within a bound.
            int wanted = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
            List<TransactionRow> found = new ArrayList<>();
            String next = cursor;
            for (int page = 0; page < HANGING_SCAN_PAGES && found.size() < wanted; page++) {
                TransactionPage read = transactions.list(id, next, 500);
                read.items().stream().filter(TransactionRow::hanging).forEach(found::add);
                next = read.next();
                if (next == null) {
                    break;
                }
            }
            return new TransactionPage(found.size() > wanted ? found.subList(0, wanted) : found, next);
        });
    }

    /** The most pages of transactions a hanging-only listing reads before it hands back a cursor to go on from. */
    private static final int HANGING_SCAN_PAGES = 5;

    /** The topics a comma-separated argument names; null when none, which means every topic of the group. */
    static List<String> topicList(String topics) {
        if (topics == null || topics.isBlank()) {
            return null;
        }
        return Arrays.stream(topics.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
    }
}
