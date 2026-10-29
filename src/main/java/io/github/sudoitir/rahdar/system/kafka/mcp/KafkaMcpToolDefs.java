package io.github.sudoitir.rahdar.system.kafka.mcp;

import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef.Param;
import io.github.sudoitir.rahdar.kernel.plugin.McpToolDef.Posture;
import io.github.sudoitir.rahdar.system.kafka.KafkaModule;
import java.util.List;

/**
 * The catalogue entries of the Kafka tools (ADR-0054, ADR-0207): what each does and the detail its schema leaves out,
 * such as the values {@code to} accepts. Each exists for Kafka clusters only.
 */
public final class KafkaMcpToolDefs {

    private static final String ETAG =
            "From the preview of this exact change. If the cluster changed since, the call is refused as stale:"
                    + " preview again.";

    private static final String CONFIRM = "confirm";

    private static final String CONFIRM_GROUP = "A real run needs confirm equal to the group's name.";

    public static final List<McpToolDef> ALL = List.of(
            read(
                    "kafka_list_groups",
                    "A page of a Kafka cluster's consumer and share groups with their state and lag.",
                    Param.values("type", List.of("consumer", "share"), "Omit for both."),
                    Param.note("state", "A group state, such as stable, empty or rebalancing."),
                    Param.note("q", "Part of the group's name."),
                    Param.note("cursor", "The next value of the previous page; absent for the first.")),
            read(
                    "kafka_describe_group",
                    "One group's members, assignments, topics and per-partition lag.",
                    Param.note("group", "The group's id, as kafka_list_groups returns it.")),
            read(
                    "kafka_preview_offset_reset",
                    "What moving a group's offsets would do, per partition, and the etag a reset needs.",
                    Param.values(
                            "to",
                            List.of("earliest", "latest", "time", "offset"),
                            "time takes an ISO-8601 instant in value, offset takes an offset."),
                    Param.note("topics", "Comma-separated topics; omit for every topic of the group."),
                    Param.note(
                            "blocked",
                            "A group with members, or one that is rebalancing, is refused: the answer says why.")),
            mutate(
                    "kafka_reset_offsets",
                    "Move an empty group's offsets to earliest, latest, a time or an offset.",
                    Param.note("etag", ETAG),
                    Param.note(CONFIRM, CONFIRM_GROUP + " Records the group skips are not deleted."),
                    Param.note(
                            "to", "As kafka_preview_offset_reset; send the same to, value and topics as the preview.")),
            read(
                    "kafka_topic_config",
                    "A topic's settings with their source and default, and whether they can be changed.",
                    Param.note("topic", "The topic's name.")),
            mutate(
                    "kafka_set_topic_config",
                    "Set or remove a topic's settings.",
                    Param.shape(
                            "set", "{ \"<setting>\": \"<value>\" }", "Overrides to set, by the broker's setting name."),
                    Param.note("remove", "Comma-separated overrides to remove; the setting falls back."),
                    Param.note(
                            "dryRun",
                            McpToolDef.DRY_RUN + " The preview carries the etag and the broker's validation."),
                    Param.note("etag", ETAG),
                    Param.note(CONFIRM, "A real run needs confirm equal to the topic's name.")),
            read(
                    "kafka_list_acls",
                    "A page of a Kafka cluster's ACLs, filtered by principal or resource.",
                    Param.note("principal", "Such as User:app."),
                    Param.note("resource", "Such as topic:orders.")),
            read(
                    "kafka_list_transactions",
                    "A Kafka cluster's open transactional ids, and with hangingOnly the hanging transactions on its"
                            + " partitions.",
                    Param.note(
                            "hangingOnly",
                            "The coordinator aborts an open transaction by itself within seconds of its timeout, so"
                                    + " an open one is information. This asks for the ones that are a problem: the open"
                                    + " ones it has missed, and the hanging transactions found on the partitions"
                                    + " (open there, unknown to the coordinator, holding readers back). Finding them"
                                    + " reads every partition in scope."),
                    Param.note("topic", "With hangingOnly, only this topic's partitions.")),
            mutate(
                    "kafka_abort_transaction",
                    "Abort a transaction or a hanging transaction, fencing its producer.",
                    Param.note(
                            "transactionalId",
                            "From kafka_list_transactions: a transactional id the coordinator has missed, or the id of"
                                    + " a hanging transaction, such as kafka.hanging-transaction/orders/0/4711."),
                    Param.note("dryRun", McpToolDef.DRY_RUN + " The preview names what the abort releases."),
                    Param.note("etag", ETAG),
                    Param.note(CONFIRM, "A real run needs confirm equal to the transactional id.")));

    private KafkaMcpToolDefs() {}

    private static McpToolDef read(String name, String summary, Param... params) {
        return new McpToolDef(name, Posture.READ, summary, List.of(params)).forSystem(KafkaModule.SYSTEM);
    }

    private static McpToolDef mutate(String name, String summary, Param... params) {
        return new McpToolDef(name, Posture.MUTATE, summary, List.of(params)).forSystem(KafkaModule.SYSTEM);
    }
}
