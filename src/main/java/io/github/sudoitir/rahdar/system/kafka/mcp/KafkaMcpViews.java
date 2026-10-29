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
        int size = pageSize(limit);
        List<TopicLag> topics = new ArrayList<>();
        for (GroupTopic t : detail.topics()) {
            if (topic == null || topic.equals(t.topic())) {
                topics.add(topicLag(t, topic, from, size));
            }
        }
        List<MemberAssignment> members =
                detail.members().stream().map(KafkaMcpViews::member).toList();
        boolean truncated = topics.stream().anyMatch(TopicLag::truncated)
                || members.stream().anyMatch(m -> m.assigned() > m.first().size());
        return new GroupSummary(
                detail.id(),
                detail.type(),
                detail.state(),
                detail.readOnly(),
                topics,
                members,
                detail.sampledAt(),
                truncated,
                groupNote(truncated, topic, size));
    }

    private static TopicLag topicLag(GroupTopic t, String topic, int from, int size) {
        List<GroupPartitionLag> all = t.partitions();
        long lag = all.stream()
                .filter(p -> p.lag() != null)
                .mapToLong(GroupPartitionLag::lag)
                .sum();
        int unknown = (int) all.stream().filter(p -> p.lag() == null).count();
        int start = topic == null ? 0 : Math.min(from, all.size());
        int end = topic == null ? Math.min(TOP, all.size()) : Math.min(from + size, all.size());
        return new TopicLag(
                t.topic(),
                t.deleted(),
                all.size(),
                lag,
                unknown,
                all.subList(start, end),
                end < all.size(),
                nextCursor(topic, end, all.size()));
    }

    private static String groupNote(boolean truncated, String topic, int size) {
        if (!truncated) {
            return null;
        }
        if (topic == null) {
            return "Partitions are shown for the " + TOP + " slowest of each topic; pass topic to page through"
                    + " one topic's partitions. Members list their first " + TOP + " assignments.";
        }
        return "Pass cursor (nextCursor) for the next " + size + " partitions of the topic.";
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
        int size = pageSize(limit);
        Map<String, List<OffsetRow>> byTopic = preview.partitions().stream()
                .collect(Collectors.groupingBy(OffsetRow::topic, LinkedHashMap::new, Collectors.toList()));
        List<TopicMoves> topics = new ArrayList<>();
        byTopic.forEach((name, rows) -> {
            if (topic == null || topic.equals(name)) {
                topics.add(topicMoves(name, rows, topic, from, size));
            }
        });
        boolean truncated = topics.stream().anyMatch(TopicMoves::truncated);
        return new ResetSummary(
                topics,
                preview.members(),
                preview.blocked(),
                preview.etag(),
                truncated,
                resetNote(truncated, topic, size));
    }

    private static TopicMoves topicMoves(String name, List<OffsetRow> rows, String topic, int from, int size) {
        List<OffsetRow> ordered = rows.stream()
                .sorted(Comparator.comparingLong((OffsetRow r) -> r.skipped() + r.reread())
                        .reversed())
                .toList();
        int start = topic == null ? 0 : Math.min(from, ordered.size());
        int end = topic == null ? Math.min(TOP, ordered.size()) : Math.min(from + size, ordered.size());
        return new TopicMoves(
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
                nextCursor(topic, end, ordered.size()));
    }

    private static String resetNote(boolean truncated, String topic, int size) {
        if (!truncated) {
            return null;
        }
        if (topic == null) {
            return "Partitions are shown for the " + TOP + " that move most in each topic; pass topic to page"
                    + " through one topic's partitions.";
        }
        return "Pass cursor (nextCursor) for the next " + size + " partitions of the topic.";
    }

    record ConfigSummary(
            boolean internal, String readOnlyReason, List<ConfigEntry> entries, int omitted, String note) {}

    /** The settings that are overridden on the topic (the default), or the named ones, or all. */
    static ConfigSummary config(TopicConfig config, Boolean onlyOverridden, String keys) {
        Set<String> wanted = wanted(keys);
        boolean overridden = wanted.isEmpty() && (onlyOverridden == null || onlyOverridden);
        List<ConfigEntry> shown = config.entries().stream()
                .filter(e -> wanted.isEmpty() || wanted.contains(e.name()))
                .filter(e -> !overridden || e.source() == ConfigSource.TOPIC)
                .toList();
        int omitted = config.entries().size() - shown.size();
        return new ConfigSummary(
                config.internal(), config.readOnlyReason(), shown, omitted, configNote(omitted, overridden));
    }

    private static Set<String> wanted(String keys) {
        if (keys == null || keys.isBlank()) {
            return Set.of();
        }
        return Set.of(keys.split("\\s*+,\\s*+"));
    }

    private static String configNote(int omitted, boolean overridden) {
        if (omitted == 0) {
            return null;
        }
        if (overridden) {
            return omitted + " settings that follow the broker default are left out; pass onlyOverridden=false"
                    + " for all of them, or keys for named ones.";
        }
        return omitted + " settings that were not named are left out.";
    }

    private static int pageSize(Integer limit) {
        return limit == null ? PAGE : Math.clamp(limit, 1, PAGE);
    }

    private static String nextCursor(String topic, int end, int total) {
        if (topic != null && end < total) {
            return String.valueOf(end);
        }
        return null;
    }

    private static int cursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException("The cursor is the nextCursor of a previous answer.");
        }
    }
}
