package io.github.sudoitir.rahdar.system.kafka.mcp;

import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigEntry;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.ConfigSource;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupDetail;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupMember;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupPartitionLag;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.GroupTopic;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetPreview;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.OffsetRow;
import io.github.sudoitir.rahdar.system.kafka.web.KafkaViews.TopicConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the MCP read tools of a Kafka cluster answer where the REST API answers every partition or every setting: totals
 * per topic and the few entries that matter, with {@code truncated} and a note when something is left out, and one
 * topic's entries in pages when asked for by name. The REST views are unchanged; a screen pages through them.
 */
final class KafkaMcpViews {

    /** The partitions or members listed beside each total. */
    static final int TOP = 20;

    /** The most entries of one topic in a page. */
    static final int PAGE = 100;

    private KafkaMcpViews() {}

    record TopicLag(
            String topic,
            boolean deleted,
            int partitions,
            long lag,
            int lagUnknown,
            List<GroupPartitionLag> slowest,
            boolean truncated,
            String nextCursor) {}

    record MemberAssignment(String id, String clientId, String host, int assigned, List<String> first) {}

    record GroupSummary(
            String id,
            String type,
            String state,
            String readOnly,
            List<TopicLag> topics,
            List<MemberAssignment> members,
            Instant sampledAt,
            boolean truncated,
            String note) {}

    /** The group with a total per topic and its slowest partitions, or, for {@code topic}, that topic's page. */
    static GroupSummary group(GroupDetail detail, String topic, String cursor, Integer limit) {
        int from = cursor(cursor);
        int size = limit == null ? PAGE : Math.max(1, Math.min(limit, PAGE));
        List<TopicLag> topics = new ArrayList<>();
        for (GroupTopic t : detail.topics()) {
            if (topic != null && !topic.equals(t.topic())) {
                continue;
            }
            List<GroupPartitionLag> all = t.partitions();
            long lag = all.stream()
                    .filter(p -> p.lag() != null)
                    .mapToLong(GroupPartitionLag::lag)
                    .sum();
            int unknown = (int) all.stream().filter(p -> p.lag() == null).count();
            int end = topic == null ? Math.min(TOP, all.size()) : Math.min(from + size, all.size());
            int start = topic == null ? 0 : Math.min(from, all.size());
            topics.add(new TopicLag(
                    t.topic(),
                    t.deleted(),
                    all.size(),
                    lag,
                    unknown,
                    all.subList(start, end),
                    end < all.size(),
                    topic != null && end < all.size() ? String.valueOf(end) : null));
        }
        List<MemberAssignment> members =
                detail.members().stream().map(KafkaMcpViews::member).toList();
        boolean truncated = topics.stream().anyMatch(TopicLag::truncated)
                || members.stream().anyMatch(m -> m.assigned() > m.first().size());
        String note = !truncated
                ? null
                : topic == null
                        ? "Partitions are shown for the " + TOP + " slowest of each topic; pass topic to page through"
                                + " one topic's partitions. Members list their first " + TOP + " assignments."
                        : "Pass cursor (nextCursor) for the next " + size + " partitions of the topic.";
        return new GroupSummary(
                detail.id(),
                detail.type(),
                detail.state(),
                detail.readOnly(),
                topics,
                members,
                detail.sampledAt(),
                truncated,
                note);
    }

    private static MemberAssignment member(GroupMember m) {
        List<String> shown = m.assignments().size() > TOP ? m.assignments().subList(0, TOP) : m.assignments();
        return new MemberAssignment(
                m.id(), m.clientId(), m.host(), m.assignments().size(), shown);
    }

    record TopicMoves(
            String topic,
            int partitions,
            int moving,
            int refused,
            long skipped,
            long reread,
            List<OffsetRow> top,
            boolean truncated,
            String nextCursor) {}

    record ResetSummary(
            List<TopicMoves> topics,
            List<String> members,
            String blocked,
            String etag,
            boolean truncated,
            String note) {}

    /** The reset preview with totals per topic and the partitions that move most, or, for {@code topic}, its page. */
    static ResetSummary reset(OffsetPreview preview, String topic, String cursor, Integer limit) {
        int from = cursor(cursor);
        int size = limit == null ? PAGE : Math.max(1, Math.min(limit, PAGE));
        Map<String, List<OffsetRow>> byTopic = preview.partitions().stream()
                .collect(Collectors.groupingBy(OffsetRow::topic, LinkedHashMap::new, Collectors.toList()));
        List<TopicMoves> topics = new ArrayList<>();
        byTopic.forEach((name, rows) -> {
            if (topic != null && !topic.equals(name)) {
                return;
            }
            List<OffsetRow> ordered = rows.stream()
                    .sorted(Comparator.comparingLong((OffsetRow r) -> r.skipped() + r.reread())
                            .reversed())
                    .toList();
            int start = topic == null ? 0 : Math.min(from, ordered.size());
            int end = topic == null ? Math.min(TOP, ordered.size()) : Math.min(from + size, ordered.size());
            topics.add(new TopicMoves(
                    name,
                    rows.size(),
                    (int) rows.stream()
                            .filter(r -> r.refused() == null && r.next() != null)
                            .count(),
                    (int) rows.stream().filter(r -> r.refused() != null).count(),
                    rows.stream().mapToLong(OffsetRow::skipped).sum(),
                    rows.stream().mapToLong(OffsetRow::reread).sum(),
                    ordered.subList(start, end),
                    end < ordered.size(),
                    topic != null && end < ordered.size() ? String.valueOf(end) : null));
        });
        boolean truncated = topics.stream().anyMatch(TopicMoves::truncated);
        String note = !truncated
                ? null
                : topic == null
                        ? "Partitions are shown for the " + TOP + " that move most in each topic; pass topic to page"
                                + " through one topic's partitions."
                        : "Pass cursor (nextCursor) for the next " + size + " partitions of the topic.";
        return new ResetSummary(topics, preview.members(), preview.blocked(), preview.etag(), truncated, note);
    }

    record ConfigSummary(
            boolean internal, String readOnlyReason, List<ConfigEntry> entries, int omitted, String note) {}

    /** The settings that are overridden on the topic (the default), or the named ones, or all. */
    static ConfigSummary config(TopicConfig config, Boolean onlyOverridden, String keys) {
        Set<String> wanted = keys == null || keys.isBlank() ? Set.of() : Set.of(keys.split("\\s*,\\s*"));
        boolean overridden = wanted.isEmpty() && (onlyOverridden == null || onlyOverridden);
        List<ConfigEntry> shown = config.entries().stream()
                .filter(e -> wanted.isEmpty() || wanted.contains(e.name()))
                .filter(e -> !overridden || e.source() == ConfigSource.TOPIC)
                .toList();
        int omitted = config.entries().size() - shown.size();
        String note = omitted == 0
                ? null
                : overridden
                        ? omitted + " settings that follow the broker default are left out; pass onlyOverridden=false"
                                + " for all of them, or keys for named ones."
                        : omitted + " settings that were not named are left out.";
        return new ConfigSummary(config.internal(), config.readOnlyReason(), shown, omitted, note);
    }

    private static int cursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The cursor is the nextCursor of a previous answer.");
        }
    }
}
